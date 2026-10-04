package org.jaagruk.safety.ui.home

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.ui.components.CatalogStrings
import org.jaagruk.safety.ui.theme.JaagrukTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w360dp-h800dp")
class PracticeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `fire rehearsal includes selection sequence feedback completion and restart`() =
        completeModule(ModuleCatalog.ID_FIRE)

    @Test fun `gas rehearsal completes without a worker`() = completeModule(ModuleCatalog.ID_GAS)
    @Test fun `machinery rehearsal completes without a worker`() = completeModule(ModuleCatalog.ID_MACHINERY)
    @Test fun `height rehearsal completes without a worker`() = completeModule(ModuleCatalog.ID_PPE_HEIGHT)
    @Test fun `electrical rehearsal completes without a worker`() = completeModule(ModuleCatalog.ID_ELECTRICAL)

    private fun completeModule(moduleId: String) {
        val scenario = ModuleCatalog.byId(moduleId)!!.fullScenario
        compose.setContent { JaagrukTheme { PracticeScreen(scenario.scenarioId, {}, {}) } }
        scenario.steps.forEach { step ->
            step.correctOptionIds.forEach { optionId ->
                val label = CatalogStrings.resolve(context, step.option(optionId)!!.labelKey)
                compose.onNodeWithTag("practice-list").performScrollToNode(hasContentDescription(label))
                compose.onNodeWithContentDescription(label).performClick()
            }
            compose.onNodeWithTag("practice-list").performScrollToNode(hasText("Check answer"))
            compose.onNodeWithText("Check answer").performClick()
            compose.onNodeWithTag("practice-list").performScrollToNode(hasText("Correct"))
            compose.onNodeWithText("Correct").assertIsDisplayed()
            compose.onNodeWithTag("practice-list").performScrollToNode(hasText("Continue"))
            compose.onNodeWithText("Continue").performClick()
        }
        compose.onNodeWithText("Practice complete").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${scenario.steps.size} of ${scenario.steps.size} decisions correct.").assertExists()
        compose.onNodeWithText("Practise again").performScrollTo().performClick()
        compose.onNodeWithText("Decision 1 of ${scenario.steps.size}").performScrollTo().assertIsDisplayed()
    }
}
