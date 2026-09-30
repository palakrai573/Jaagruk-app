package org.jaagruk.safety.ui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.MainActivity
import org.jaagruk.safety.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
class SimulationDeviceTest(private val moduleId: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases() = ModuleCatalog.all.map { arrayOf(it.moduleId) }
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var value = ""
        web.post { web.evaluateJavascript(script) { value = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return value
    }
    private fun debugState(web: WebView): String = evaluate(web, """
        JSON.stringify({
          url: location.href,
          title: document.title,
          ready: document.body && document.body.dataset.sceneReady || '',
          error: document.body && document.body.dataset.sceneError || '',
          action: document.body && document.body.dataset.sceneAction || '',
          canvas: document.querySelectorAll('canvas').length,
          status: document.getElementById('status') && document.getElementById('status').textContent,
          body: document.body && document.body.innerText.slice(0, 160)
        })
    """.trimIndent())
    @Test fun sceneRendersRespondsAndOpensPractice() {
        val context = compose.activity
        val module = ModuleCatalog.byId(moduleId)!!
        val title = context.getString(context.resources.getIdentifier(module.titleKey, "string", context.packageName))
        compose.onNodeWithTag("training-home").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithTag("simulation-screen").assertIsDisplayed()
        var web: WebView? = null
        compose.runOnIdle { web = findWeb(context.window.decorView) }
        val renderer = requireNotNull(web)
        val ready = runCatching {
            compose.waitUntil(30_000) { evaluate(renderer, "document.body.dataset.sceneReady") == "\"true\"" }
            true
        }.getOrDefault(false)
        assertTrue("3D scene did not become ready: ${debugState(renderer)}", ready)
        compose.waitForIdle()
        Thread.sleep(1_500)
        assertEquals("1", evaluate(renderer, "document.querySelectorAll('canvas').length"))
        val bounds = IntArray(2)
        var width = 0
        var height = 0
        compose.runOnIdle { renderer.getLocationOnScreen(bounds); width = renderer.width; height = renderer.height }
        val shot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val colors = mutableSetOf<Int>()
        for (x in width / 5 until width * 4 / 5 step 8) for (y in height / 5 until height * 4 / 5 step 8)
            colors += shot.getPixel(bounds[0] + x, bounds[1] + y)
        File(context.filesDir, "scene-$moduleId.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("The 3D viewport must contain rendered geometry, not a blank background; colors=${colors.size}; ${debugState(renderer)}", colors.size > 100)
        compose.onNodeWithText(context.getString(R.string.scene_alarm)).performClick()
        compose.onNodeWithText(context.getString(R.string.scene_alarm)).assertIsSelected()
        compose.waitUntil(5_000) { evaluate(renderer, "document.body.dataset.sceneAction") == "\"ALERT\"" }
        compose.onNodeWithTag("simulation-canvas").performTouchInput { swipeLeft() }
        val changed = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        var differences = 0
        for (x in width / 5 until width * 4 / 5 step 12) for (y in height / 5 until height * 4 / 5 step 12) {
            if (shot.getPixel(bounds[0] + x, bounds[1] + y) != changed.getPixel(bounds[0] + x, bounds[1] + y)) differences++
        }
        shot.recycle()
        changed.recycle()
        assertTrue("Scene pixels must respond to the action and orbit gesture", differences > 25)
        compose.onNodeWithContentDescription(context.getString(R.string.scene_pause)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.scene_resume)).assertIsDisplayed()
        compose.waitUntil(5_000) { evaluate(renderer, "document.body.dataset.scenePaused") == "\"true\"" }
        compose.onNodeWithContentDescription(context.getString(R.string.scene_reset)).performClick()
        compose.onNodeWithText(context.getString(R.string.train_practice), substring = false).performClick()
        compose.onNodeWithTag("training-practice").assertIsDisplayed()
    }
}
