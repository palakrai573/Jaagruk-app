package org.jaagruk.safety

import android.content.res.Configuration
import android.graphics.Paint
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.ui.components.CatalogStrings
import org.jaagruk.safety.ui.home.PracticeScreen
import org.jaagruk.safety.ui.theme.JaagrukTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

/** Real phone Compose input/rendering for every module in each supported UI locale. */
@RunWith(Parameterized::class)
class DevicePracticeAcceptanceTest(private val language: String, private val moduleId: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<String>> = listOf("en", "hi", "sat").flatMap { language ->
            ModuleCatalog.all.map { arrayOf(language, it.moduleId) }
        }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun completeEveryDecisionAndRestart() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val context = base.createConfigurationContext(configuration)
        if (language == "sat") {
            assertTrue("Ol Chiki font missing", Paint().hasGlyph("\u1c61"))
            assertTrue("Santali app name fell back", context.getString(R.string.app_name).any { it in '\u1c50'..'\u1c7f' })
        }
        val scenario = ModuleCatalog.byId(moduleId)!!.fullScenario
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                JaagrukTheme { PracticeScreen(scenario.scenarioId, {}, {}) }
            }
        }
        fun clickResource(id: Int) {
            val text = context.getString(id)
            compose.onNodeWithTag("practice-list").performScrollToNode(hasText(text))
            compose.onNodeWithText(text).performClick()
        }
        scenario.steps.forEach { step ->
            step.correctOptionIds.forEach { id ->
                val text = CatalogStrings.resolve(context, step.option(id)!!.labelKey)
                if (language == "sat") assertTrue("Untranslated catalog option: $id", text.any { it in '\u1c50'..'\u1c7f' })
                compose.onNodeWithTag("practice-list").performScrollToNode(hasContentDescription(text))
                compose.onNodeWithContentDescription(text).performClick()
            }
            clickResource(R.string.practice_check)
            compose.onNodeWithTag("practice-list").performScrollToNode(hasText(context.getString(R.string.practice_correct)))
            compose.onNodeWithText(context.getString(R.string.practice_correct)).assertIsDisplayed()
            clickResource(R.string.practice_next)
        }
        compose.onNodeWithText(context.getString(R.string.practice_complete)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.practice_result, scenario.steps.size, scenario.steps.size)).assertExists()
        clickResource(R.string.practice_again)
        compose.onNodeWithText(context.getString(R.string.practice_step, 1, scenario.steps.size)).performScrollTo().assertIsDisplayed()
    }
}
