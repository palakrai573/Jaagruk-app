package org.jaagruk.safety.ui

import android.content.res.Configuration
import android.graphics.Paint
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.R
import org.jaagruk.safety.ui.screens.TrainingPracticeScreen
import org.jaagruk.safety.ui.screens.trainingText
import org.jaagruk.safety.ui.theme.JaagrukTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

@RunWith(Parameterized::class)
class TrainingPracticeTest(private val language: String, private val moduleId: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<String>> = listOf("en", "hi", "sat", "ta").flatMap { language ->
            ModuleCatalog.all.map { arrayOf(language, it.moduleId) }
        }
    }
    @get:Rule val compose = createComposeRule()

    @Test fun completePracticeAndRestart() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val context = base.createConfigurationContext(configuration)
        if (language == "sat") assertTrue("Ol Chiki glyph unavailable", Paint().hasGlyph("\u1c61"))
        val scenario = ModuleCatalog.byId(moduleId)!!.fullScenario
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                JaagrukTheme { TrainingPracticeScreen(moduleId, onBack = {}) }
            }
        }
        fun click(id: Int) {
            val text = context.getString(id)
            compose.onNodeWithTag("training-practice").performScrollToNode(hasText(text))
            compose.onNodeWithText(text).performClick()
        }
        scenario.steps.forEach { step ->
            step.correctOptionIds.forEach { id ->
                val option = step.option(id)!!
                if (language == "sat") assertTrue(trainingText(context, option.labelKey).any { it in '\u1c50'..'\u1c7f' })
                if (language == "ta") assertTrue(trainingText(context, option.labelKey).any { it in '\u0b80'..'\u0bff' })
                compose.onNodeWithTag("training-practice").performScrollToNode(hasTestTag(id))
                compose.onNodeWithTag(id).performClick()
            }
            click(R.string.train_check)
            compose.onNodeWithTag("training-practice").performScrollToNode(hasText(context.getString(R.string.train_correct)))
            compose.onNodeWithText(context.getString(R.string.train_correct)).assertIsDisplayed()
            click(R.string.train_next)
        }
        compose.onNodeWithText(context.getString(R.string.train_complete)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.train_result, scenario.steps.size, scenario.steps.size)).assertExists()
        click(R.string.train_again)
        compose.onNodeWithText(context.getString(R.string.train_step, 1, scenario.steps.size)).performScrollTo().assertIsDisplayed()
    }
}
