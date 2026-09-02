package org.jaagruk.safety.input

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.SystemMonotonicTimeSource

/**
 * Hand gestures a gloved worker can actually make.
 *
 * The touch problem this solves is real and unglamorous: a capacitive screen does not reliably register a
 * thick leather or nitrile glove, and taking gloves off next to a live conveyor to answer a safety
 * question is precisely the behaviour the training is meant to prevent. Voice covers part of it, but a
 * haulage road is loud.
 *
 * So the gesture set is chosen for what survives a glove and a headlamp beam — whole-hand shapes, no
 * finger precision, no pinches — and deliberately kept to five.
 */
enum class GloveGesture(val mediaPipeName: String) {
    /** Open palm: confirm, or "yes". */
    OPEN_PALM("Open_Palm"),

    /** Closed fist: cancel, or "no". */
    CLOSED_FIST("Closed_Fist"),

    /** Thumb up: next. */
    THUMB_UP("Thumb_Up"),

    /** Thumb down: back. */
    THUMB_DOWN("Thumb_Down"),

    /** Index pointing: select whatever is under the reticle. */
    POINTING_UP("Pointing_Up"),
    ;

    companion object {
        private val byName = entries.associateBy { it.mediaPipeName }

        fun fromMediaPipe(name: String): GloveGesture? = byName[name]
    }
}

/**
 * MediaPipe hand-gesture recognition, with honest degradation.
 *
 * **The model is not committed.** `gesture_recognizer.task` is roughly 8 MB of third-party model weights;
 * putting it in the repository would be both a licensing question and a poor use of a git history. When it
 * is absent this reports [Availability.MODEL_MISSING], the UI hides the gesture affordance, and the drill
 * runs on touch and voice. Nothing degrades silently and nothing pretends to work.
 *
 * `README.md` documents where to drop the file. That is the honest arrangement: the integration is real
 * and complete, and the asset is a deployment step.
 *
 * Two behaviours that matter more than the recognition itself:
 *
 *  * **A gesture must be held.** A single frame of "open palm" is a hand passing through the view. Requiring
 *    the same gesture across several consecutive frames is what stops a worker adjusting their helmet from
 *    submitting an answer.
 *  * **A cooldown after every accepted gesture.** Without it, holding a palm up submits the next step too,
 *    at a latency of a few milliseconds — which would then be flagged as a suspiciously fast answer and
 *    could void the run.
 */
class GestureRecognizerSource(
    private val context: Context,
    private val monotonic: MonotonicTimeSource = SystemMonotonicTimeSource,
) {

    enum class Availability {
        /** Ready to recognise. */
        READY,

        /** The `.task` model file is not in assets. Gesture input is hidden. */
        MODEL_MISSING,

        /** MediaPipe failed to initialise on this device. */
        UNSUPPORTED,

        /** Not started yet. */
        IDLE,
    }

    private val _availability = MutableStateFlow(Availability.IDLE)
    val availability: StateFlow<Availability> = _availability.asStateFlow()

    private val _gestures = MutableSharedFlow<GloveGesture>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val gestures: SharedFlow<GloveGesture> = _gestures.asSharedFlow()

    /** Live candidate, for the on-screen hand indicator. Null when no hand is in view. */
    private val _candidate = MutableStateFlow<GloveGesture?>(null)
    val candidate: StateFlow<GloveGesture?> = _candidate.asStateFlow()

    private var recognizer: GestureRecognizer? = null
    private var pendingGesture: GloveGesture? = null
    private var pendingFrames: Int = 0
    private var lastAcceptedAtMs: Long = 0L

    /**
     * Loads the model.
     *
     * CPU delegate rather than GPU. The GPU delegate is faster on paper and fails to initialise on a
     * meaningful share of the low-end Mali and Adreno parts this app targets, which turns a working
     * fallback into a crash on exactly the handsets that most need it.
     */
    fun start(): Availability {
        if (recognizer != null) return _availability.value

        if (!modelPresent()) {
            Log.i(TAG, "$MODEL_ASSET is not bundled; gesture input will stay hidden")
            _availability.value = Availability.MODEL_MISSING
            return Availability.MODEL_MISSING
        }

        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU)
                .build()

            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                // One hand. A worker holding a phone has exactly one free hand, and tracking two costs
                // frame time for no benefit.
                .setNumHands(1)
                .setMinHandDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                .setMinHandPresenceConfidence(MIN_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .setResultListener { result, _ -> onResult(result) }
                .setErrorListener { error -> Log.w(TAG, "gesture recognition error", error) }
                .build()

            recognizer = GestureRecognizer.createFromOptions(context, options)
            _availability.value = Availability.READY
            Availability.READY
        } catch (e: Exception) {
            // Missing native libraries, an unsupported ABI, or a corrupt model file. All device facts.
            Log.w(TAG, "MediaPipe gesture recognition unavailable on this device", e)
            _availability.value = Availability.UNSUPPORTED
            Availability.UNSUPPORTED
        }
    }

    fun stop() {
        runCatching { recognizer?.close() }
        recognizer = null
        pendingGesture = null
        pendingFrames = 0
        _candidate.value = null
        _availability.value = Availability.IDLE
    }

    /**
     * Feeds one camera frame.
     *
     * A `Bitmap`, deliberately. Both frame sources — ARCore's `acquireCameraImage` and CameraX's
     * `ImageAnalysis` — hand over YUV buffers in device-specific layouts, and converting once at the call
     * site keeps that mess out of here. Frames are dropped rather than queued when recognition is behind:
     * a stale gesture is worse than a missed one.
     */
    fun feed(bitmap: Bitmap, timestampMs: Long) {
        val active = recognizer ?: return
        try {
            val image: MPImage = BitmapImageBuilder(bitmap).build()
            active.recognizeAsync(image, timestampMs)
        } catch (e: Exception) {
            Log.d(TAG, "dropped a frame: ${e.message}")
        }
    }

    private fun onResult(result: GestureRecognizerResult) {
        val top = result.gestures().firstOrNull()?.firstOrNull()
        val gesture = top?.categoryName()?.let(GloveGesture::fromMediaPipe)
        val score = top?.score() ?: 0f

        if (gesture == null || score < MIN_GESTURE_SCORE) {
            pendingGesture = null
            pendingFrames = 0
            _candidate.value = null
            return
        }

        _candidate.value = gesture

        if (gesture != pendingGesture) {
            pendingGesture = gesture
            pendingFrames = 1
            return
        }

        pendingFrames++
        if (pendingFrames < REQUIRED_STABLE_FRAMES) return

        val now = monotonic.elapsedMillis()
        if (now - lastAcceptedAtMs < COOLDOWN_MS) return

        lastAcceptedAtMs = now
        pendingFrames = 0
        pendingGesture = null
        _gestures.tryEmit(gesture)
    }

    private fun modelPresent(): Boolean = try {
        context.assets.open(MODEL_ASSET).use { true }
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val TAG = "GestureRecognizer"

        /** Drop `gesture_recognizer.task` here. See README.md. */
        const val MODEL_ASSET = "models/gesture_recognizer.task"

        /**
         * Confidence floors, set low on purpose.
         *
         * A gloved hand is a poorer match for the model's training distribution than a bare one, so high
         * thresholds simply stop recognising the users this feature exists for. The stability requirement
         * below does the filtering that a high threshold would otherwise do, and it filters the right
         * thing: transient detections rather than unusual-looking hands.
         */
        const val MIN_DETECTION_CONFIDENCE = 0.4f
        const val MIN_PRESENCE_CONFIDENCE = 0.4f
        const val MIN_TRACKING_CONFIDENCE = 0.4f
        const val MIN_GESTURE_SCORE = 0.55f

        /** Consecutive frames the same gesture must hold. About a third of a second at 15 fps. */
        const val REQUIRED_STABLE_FRAMES = 5

        /**
         * Minimum gap between accepted gestures.
         *
         * Long enough that a held palm cannot answer the following step. Without it the next answer lands
         * a few milliseconds after the step opens, which the scoring engine correctly treats as a guess and
         * which could void an otherwise valid run.
         */
        const val COOLDOWN_MS = 1_200L
    }
}
