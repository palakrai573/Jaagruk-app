package org.jaagruk.ai

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The interlock that keeps the model out of a drill.
 *
 * ## The constraint
 *
 * A drill runs an ARCore session, a GLES3 surface and a camera pipeline, and on ARCore-less handsets
 * a CameraX preview plus the rotation-vector sensors. Gemma 3 1B at Q4_K_M is 769 MiB of weights, so
 * with its KV cache it sits around 900 MiB resident. On the 4 GB handsets this platform targets,
 * holding both means sustained thermal throttling and a real chance the allocator kills one of them.
 *
 * Throttling is the part that matters. Decision latency measured on a throttled frame loop measures
 * the phone, not the worker, and that measurement is signed into a certificate. So the model is not
 * merely discouraged during a drill; it is released.
 *
 * ## Why an interlock rather than a rule
 *
 * "Do not generate during a drill" as a convention survives exactly until someone adds a fifth
 * feature. As a latch that the engine subscribes to, adding a feature cannot get it wrong: any
 * generation attempted during a drill fails with [org.jaagruk.core.ai.AiCapability.BUSY_IN_DRILL],
 * and the model is already gone from memory.
 *
 * Reference counted rather than a boolean, because a buddy drill has a drill screen and a peer
 * session that can overlap, and a naive boolean would let the first one to finish re-admit the model
 * while the second was still running.
 */
class LlmSessionGuard {

    private companion object {
        const val TAG = "JaagrukLlm"
    }

    /** Called when the model must be released. Suspending, because unloading crosses to IO. */
    fun interface Listener {
        suspend fun onMustRelease()
    }

    private val activeDrills = AtomicInteger(0)
    private val listeners = CopyOnWriteArrayList<Listener>()

    val isInDrill: Boolean get() = activeDrills.get() > 0

    val activeDrillCount: Int get() = activeDrills.get()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /**
     * Claims the device for a drill and releases the model.
     *
     * Suspends until every listener has released, so a caller can await this before starting an AR
     * session and be sure the memory is actually back. Awaiting it is the point: starting the session
     * first and unloading afterwards is the window this class exists to close.
     */
    suspend fun enterDrill() {
        val depth = activeDrills.incrementAndGet()
        Log.i(TAG, "drill started, depth $depth")
        if (depth == 1) {
            for (listener in listeners) {
                runCatching { listener.onMustRelease() }
                    .onFailure { Log.w(TAG, "a listener failed to release", it) }
            }
        }
    }

    /**
     * Gives the device back.
     *
     * Does not reload anything. The next feature that needs the model loads it on demand, which keeps
     * a worker who finishes a drill and puts the phone away from paying for a load nobody wanted.
     */
    fun exitDrill() {
        val depth = activeDrills.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        Log.i(TAG, "drill ended, depth $depth")
    }

    /**
     * Runs [block] with the device claimed, releasing on any exit path.
     *
     * The form to prefer at call sites: a drill that throws, is cancelled, or is abandoned when the
     * worker backgrounds the app still gives the device back.
     */
    suspend fun <T> withDrill(block: suspend () -> T): T {
        enterDrill()
        return try {
            block()
        } finally {
            exitDrill()
        }
    }

    /** Clears the count. For process-level recovery only; never part of a normal flow. */
    fun reset() {
        activeDrills.set(0)
    }
}
