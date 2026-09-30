package org.jaagruk.safety.circle

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * OverlayComposeHost
 *
 * Hosts Compose UI on a WindowManager overlay without an Activity.
 *
 * Uses [AbstractComposeView] (which manages its own Recomposer) instead of
 * [ComposeView] — avoids the removed `compositionContext` setter.
 *
 * Call order:
 *   1. windowManager.addView(host.view, params)
 *   2. host.start()
 *   3. host.stop()
 *   4. windowManager.removeView(host.view)
 */
class OverlayComposeHost(
    context: Context,
    private val content: @Composable () -> Unit
) : LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // ── ViewModelStore ─────────────────────────────────────────────────────────
    private val vmStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = vmStore

    // ── SavedState ─────────────────────────────────────────────────────────────
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    // ── View (AbstractComposeView subclass handles Recomposer internally) ──────
    val view: AbstractComposeView = object : AbstractComposeView(context) {
        @Composable
        override fun Content() = content()
    }.also { v ->
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeViewModelStoreOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
    }

    init {
        savedStateController.performRestore(null)
    }

    /** Call AFTER windowManager.addView(host.view, params). */
    fun start() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** Call BEFORE windowManager.removeView(host.view). */
    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        vmStore.clear()
    }
}
