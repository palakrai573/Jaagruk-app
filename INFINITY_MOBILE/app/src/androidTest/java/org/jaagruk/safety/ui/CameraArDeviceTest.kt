package org.jaagruk.safety.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.sceneview.ar.ARSceneView
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.MainActivity
import org.jaagruk.safety.R
import org.jaagruk.safety.ai.repository.AIRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Requires a real ARCore device with CAMERA permission granted before instrumentation. */
@RunWith(Parameterized::class)
class CameraArDeviceTest(private val moduleId: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "camera-{0}")
        fun cases() = ModuleCatalog.all.map { arrayOf(it.moduleId) }
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun findAr(view: View): ARSceneView? {
        if (view is ARSceneView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findAr(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun cameraFramesAdvanceAndLeavingReleasesAiInterlock() {
        val activity = compose.activity
        val guard = AIRepository.getInstance(activity).sessionGuard
        val title = activity.getString(activity.resources.getIdentifier(
            ModuleCatalog.byId(moduleId)!!.titleKey, "string", activity.packageName))
        compose.onNodeWithTag("training-home").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithTag("open-camera-ar").performScrollTo().performClick()
        compose.onNodeWithTag("camera-ar-screen").assertIsDisplayed()
        var firstTimestamp = 0L
        compose.waitUntil(30_000) {
            compose.runOnIdle { firstTimestamp = findAr(activity.window.decorView)?.frame?.timestamp ?: 0L }
            firstTimestamp > 0
        }
        assertTrue("AR must reserve memory before creating its camera session", guard.isInDrill)
        var laterTimestamp = firstTimestamp
        compose.waitUntil(5_000) {
            compose.runOnIdle { laterTimestamp = findAr(activity.window.decorView)?.frame?.timestamp ?: 0L }
            laterTimestamp > firstTimestamp
        }
        compose.runOnIdle {
            val ar = requireNotNull(findAr(activity.window.decorView))
            assertNotNull(ar.session)
            assertNotNull(ar.cameraStream)
            assertTrue(ar.width > 0 && ar.height > 0)
        }
        compose.onNodeWithContentDescription(activity.getString(R.string.cd_back)).performClick()
        compose.onNodeWithTag("simulation-screen").assertIsDisplayed()
        compose.waitUntil(5_000) { !guard.isInDrill }
        // Surface detection and anchored movement are separate physical interaction checks.
    }
}
