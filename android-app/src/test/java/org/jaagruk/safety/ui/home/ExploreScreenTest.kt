package org.jaagruk.safety.ui.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.ui.theme.JaagrukTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w360dp-h800dp")
class ExploreScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `public home opens practice without worker authentication`() {
        var scenario: String? = null
        var signIns = 0
        compose.setContent {
            JaagrukTheme {
                ExploreScreen(onPractice = { scenario = it }, onSignIn = { signIns++ },
                    onAsk = {}, onVerify = {}, onSupervisor = {})
            }
        }
        compose.onNodeWithText("Ready for a safer shift.").assertIsDisplayed()
        compose.onAllNodesWithText("Practice (does not certify)")[0].performScrollTo().performClick()
        assertThat(scenario).isEqualTo(ModuleCatalog.all.first().fullScenario.scenarioId)
        assertThat(signIns).isEqualTo(0)
    }

    @Test fun `coach and verification are available before sign in`() {
        var questions = 0
        var verifications = 0
        compose.setContent {
            JaagrukTheme { ExploreScreen({}, {}, { questions++ }, { verifications++ }, {}) }
        }
        compose.onNodeWithText("Coach").performClick()
        compose.onNodeWithText("Verify").performClick()
        assertThat(questions).isEqualTo(1)
        assertThat(verifications).isEqualTo(1)
    }
}
