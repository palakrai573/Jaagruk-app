package org.jaagruk.safety.circle

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.util.DisplayMetrics
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.*

/**
 * FloatingBubbleView
 *
 * Draggable floating bubble attached to WindowManager.
 * Calls [onTap] when tapped (< 8dp drag distance).
 * Remembers last X/Y position across drags.
 *
 * All touch logic, WindowManager params, show/hide/isShowing, and onTap
 * are identical to before. Only the visual layer is replaced.
 */
class FloatingBubbleView(
    private val context: Context,
    private val onTap: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val root = FrameLayout(context)
    private lateinit var params: WindowManager.LayoutParams

    private var lastX = 0; private var lastY = 0
    private var downRawX = 0f; private var downRawY = 0f
    private var added = false

    // Compose state exposed to the bubble canvas
    private val bubbleState = mutableStateOf(BubbleState.IDLE)
    private val isDragging  = mutableStateOf(false)

    fun show() {
        if (added) return
        buildView()
        wm.addView(root, params)
        added = true
    }

    fun hide() {
        if (!added) return
        runCatching { wm.removeView(root) }
        added = false
    }

    fun isShowing() = added

    /** Update the visual state of the bubble — UI only, no logic effect. */
    fun setState(state: BubbleState) { bubbleState.value = state }

    private fun buildView() {
        // ── Layout params ──────────────────────────────────────────────────────
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(dm)

        params = WindowManager.LayoutParams(
            dpToPx(64), dpToPx(64),
            lastX.takeIf { it != 0 } ?: (dm.widthPixels - dpToPx(80)),
            lastY.takeIf { it != 0 } ?: (dm.heightPixels / 3),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        // ── Compose visual ─────────────────────────────────────────────────────
        val composeView = BubbleComposeView(context, bubbleState, isDragging)
        // Set tree owners on the WindowManager root BEFORE addView — Compose
        // walks to the window root (FrameLayout) to find LifecycleOwner.
        composeView.installTreeOwners(root)
        root.addView(composeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // ── Touch handler: drag + tap (unchanged) ─────────────────────────────
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    lastX = params.x;     lastY = params.y
                    isDragging.value = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!isDragging.value &&
                        sqrt((dx * dx + dy * dy).toDouble()) > dpToPx(4)) {
                        isDragging.value = true
                    }
                    params.x = lastX + dx
                    params.y = lastY + dy
                    if (added) runCatching { wm.updateViewLayout(root, params) }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    val moved = sqrt((dx * dx + dy * dy).toDouble())
                    isDragging.value = false
                    if (moved < dpToPx(8)) {
                        onTap()
                    } else {
                        // Magnetic edge snap
                        snapToEdge(dm)
                    }
                    lastX = params.x; lastY = params.y
                }
            }
            true
        }
    }

    /** Animate the bubble to the nearest screen edge after a drag. */
    private fun snapToEdge(dm: DisplayMetrics) {
        if (!added) return
        val midX = dm.widthPixels / 2
        val targetX = if (params.x + dpToPx(32) < midX) dpToPx(12)
                      else dm.widthPixels - dpToPx(76)
        // Clamp Y within visible screen
        params.y = params.y.coerceIn(dpToPx(40), dm.heightPixels - dpToPx(120))
        params.x = targetX
        runCatching { wm.updateViewLayout(root, params) }
        lastX = params.x; lastY = params.y
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}

// ── Bubble state ───────────────────────────────────────────────────────────────

enum class BubbleState { IDLE, THINKING, SCANNING, GENERATING, DONE }

// ── Compose view host (lifecycle owner, no Activity needed) ───────────────────

private class BubbleComposeView(
    context: Context,
    private val stateSource: State<BubbleState>,
    private val dragging: State<Boolean>
) : AbstractComposeView(context),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val vmStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = vmStore

    private val ssrc = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssrc.savedStateRegistry

    init {
        ssrc.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /**
     * Must be called with the WindowManager root view BEFORE wm.addView().
     * Compose walks to the window root to find tree owners — they must be
     * set on the root FrameLayout, not on the AbstractComposeView itself.
     */
    fun installTreeOwners(root: android.view.View) {
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeViewModelStoreOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
    }

    @Composable
    override fun Content() {
        BubbleCanvas(
            state    = stateSource.value,
            dragging = dragging.value
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        vmStore.clear()
    }
}

// ── Premium bubble canvas ─────────────────────────────────────────────────────

@Composable
private fun BubbleCanvas(state: BubbleState, dragging: Boolean) {
    // ── Idle breathing ────────────────────────────────────────────────────────
    val breathingAnim = rememberInfiniteTransition(label = "breath")
    val breathScale by breathingAnim.animateFloat(
        initialValue    = 1.00f,
        targetValue     = 1.03f,
        animationSpec   = infiniteRepeatable(
            animation  = tween(5000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathScale"
    )

    // ── Drag scale ────────────────────────────────────────────────────────────
    val dragScale by animateFloatAsState(
        targetValue   = if (dragging) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label         = "dragScale"
    )

    // ── Lemniscate path phase (used for THINKING / GENERATING trace) ────────────
    val traceAnim = rememberInfiniteTransition(label = "trace")
    val tracePhase by traceAnim.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(
                durationMillis = if (state == BubbleState.GENERATING) 3000 else 1800,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "tracePhase"
    )

    // ── Scan ring rotation (OCR state) ────────────────────────────────────────
    val scanRotation by traceAnim.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanRot"
    )

    // ── Done pulse ────────────────────────────────────────────────────────────
    val donePulse by animateFloatAsState(
        targetValue   = if (state == BubbleState.DONE) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label         = "donePulse"
    )

    val totalScale = when {
        dragging            -> dragScale
        state == BubbleState.DONE -> donePulse
        state == BubbleState.IDLE -> breathScale
        else                -> 1f
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = totalScale
                scaleY = totalScale
            }
    ) {
        val cx   = size.width  / 2f
        val cy   = size.height / 2f
        val r    = (size.minDimension / 2f) - 4.dp.toPx()

        // ── Glass disc background ─────────────────────────────────────────────
        drawCircle(
            brush  = Brush.radialGradient(
                colors  = listOf(
                    Color(0xCCFFFFFF),  // frosted white centre
                    Color(0x99F8F8F8),
                    Color(0x66EFEFEF)   // soft translucent edge
                ),
                center = Offset(cx - r * 0.15f, cy - r * 0.15f),
                radius = r * 1.3f
            ),
            radius = r,
            center = Offset(cx, cy)
        )

        // ── Subtle silver border ──────────────────────────────────────────────
        drawCircle(
            color  = Color(0x30C0C0C0),
            radius = r,
            center = Offset(cx, cy),
            style  = Stroke(width = 1.2.dp.toPx())
        )

        // ── Soft drop shadow (simulated with semi-transparent ring) ───────────
        drawCircle(
            color  = Color(0x18000000),
            radius = r + 3.dp.toPx(),
            center = Offset(cx, cy + 1.dp.toPx()),
            style  = Stroke(width = 4.dp.toPx())
        )

        // ── Specular highlight ────────────────────────────────────────────────
        drawCircle(
            brush  = Brush.radialGradient(
                colors  = listOf(Color(0x60FFFFFF), Color(0x00FFFFFF)),
                center  = Offset(cx - r * 0.3f, cy - r * 0.35f),
                radius  = r * 0.55f
            ),
            radius = r * 0.55f,
            center = Offset(cx - r * 0.3f, cy - r * 0.35f)
        )

        // ── Scanning ring (OCR state) ─────────────────────────────────────────
        if (state == BubbleState.SCANNING) {
            rotate(scanRotation, pivot = Offset(cx, cy)) {
                drawArc(
                    color      = Color(0x60888888),
                    startAngle = 0f,
                    sweepAngle = 240f,
                    useCenter  = false,
                    topLeft    = Offset(cx - r - 5.dp.toPx(), cy - r - 5.dp.toPx()),
                    size       = Size((r + 5.dp.toPx()) * 2, (r + 5.dp.toPx()) * 2),
                    style      = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // ── Placeholder mark, replaced in phase 3 ───────────────────────────────────────────────────
        when (state) {
            BubbleState.IDLE, BubbleState.DONE ->
                drawLemniscateStatic(cx, cy, r * 0.40f)

            BubbleState.THINKING ->
                drawLemniscateTrace(cx, cy, r * 0.40f, tracePhase, strokeAlpha = 0.75f)

            BubbleState.SCANNING ->
                drawLemniscateStatic(cx, cy, r * 0.40f, alpha = 0.55f)

            BubbleState.GENERATING ->
                drawLemniscateTrace(cx, cy, r * 0.40f, tracePhase, strokeAlpha = 0.85f)
        }
    }
}

// ── Static lemniscate glyph ─────────────────────────────────────────────────────

private fun DrawScope.drawLemniscateStatic(
    cx: Float, cy: Float, halfWidth: Float,
    alpha: Float = 0.82f
) {
    val path = lemniscatePath(cx, cy, halfWidth)
    drawPath(
        path  = path,
        color = Color(0xCC303030).copy(alpha = alpha),
        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

// ── Animated lemniscate trace ───────────────────────────────────────────────────

private fun DrawScope.drawLemniscateTrace(
    cx: Float, cy: Float, halfWidth: Float,
    phase: Float, strokeAlpha: Float
) {
    val path = lemniscatePath(cx, cy, halfWidth)

    // Ghost full path (very faint)
    drawPath(
        path  = path,
        color = Color(0x22303030),
        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Animated leading segment
    val measure = android.graphics.PathMeasure(path.asAndroidPath(), false)
    val total   = measure.length
    val head    = phase
    val tail    = (phase - 0.35f).coerceAtLeast(0f)
    val dst     = android.graphics.Path()
    measure.getSegment(tail * total, head * total, dst, true)
    drawPath(
        path  = dst.asComposePath(),
        color = Color(0xFF303030).copy(alpha = strokeAlpha),
        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

// ── Lemniscate path builder ─────────────────────────────────────────
// Parametric lemniscate of Bernoulli approximated with cubic bezier curves.
// Two mirrored lobes forming the ∞ symbol, centred at (cx, cy).

private fun lemniscatePath(cx: Float, cy: Float, hw: Float): Path {
    // hw = half-width of the symbol (distance from centre to loop tip)
    val hh = hw * 0.48f   // half-height of each lobe
    val cp = hw * 0.60f   // bezier control point offset

    return Path().apply {
        // Start at centre
        moveTo(cx, cy)

        // Right lobe — upper arc
        cubicTo(
            cx + cp * 0.5f, cy - hh,
            cx + hw,        cy - hh,
            cx + hw,        cy
        )
        // Right lobe — lower arc
        cubicTo(
            cx + hw,        cy + hh,
            cx + cp * 0.5f, cy + hh,
            cx,             cy
        )

        // Left lobe — lower arc
        cubicTo(
            cx - cp * 0.5f, cy + hh,
            cx - hw,        cy + hh,
            cx - hw,        cy
        )
        // Left lobe — upper arc
        cubicTo(
            cx - hw,        cy - hh,
            cx - cp * 0.5f, cy - hh,
            cx,             cy
        )
        close()
    }
}
