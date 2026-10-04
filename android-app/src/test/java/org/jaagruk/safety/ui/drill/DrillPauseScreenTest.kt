package org.jaagruk.safety.ui.drill

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.jaagruk.safety.R
import org.jaagruk.safety.ui.components.UiMessage
import org.jaagruk.safety.ui.theme.JaagrukTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class DrillPauseScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrationOffersSkipAudioInsteadOfAnIneffectiveResume() {
        var skipped = 0
        var resumed = 0
        compose.setContent {
            JaagrukTheme {
                PauseOverlay(UiMessage.info(R.string.drill_paused_narration),
                    onResume = { resumed++ }, onSkipAudio = { skipped++ }, onAbandon = {})
            }
        }
        compose.onNodeWithText("Continue without audio").assertIsDisplayed().performClick()
        compose.onNodeWithText("Continue").assertDoesNotExist()
        assertThat(skipped).isEqualTo(1)
        assertThat(resumed).isEqualTo(0)
    }

    @Test fun trackingPauseDoesNotOfferABypass() {
        var abandoned = 0
        compose.setContent {
            JaagrukTheme {
                PauseOverlay(UiMessage.warning(R.string.drill_paused_tracking),
                    onResume = {}, onSkipAudio = {}, onAbandon = { abandoned++ })
            }
        }
        compose.onNodeWithText("Continue without audio").assertDoesNotExist()
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.onNodeWithText("Paused: the camera lost track of the scene.").assertIsDisplayed()
        compose.onNodeWithText("Stop drill").assertIsDisplayed().performClick()
        assertThat(abandoned).isEqualTo(1)
    }
}
