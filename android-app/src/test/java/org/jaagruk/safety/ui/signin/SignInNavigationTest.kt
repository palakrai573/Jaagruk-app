package org.jaagruk.safety.ui.signin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.jaagruk.safety.ui.theme.JaagrukTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w360dp-h800dp")
class SignInNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `worker can return to training without choosing an account`() {
        val vm = mockk<SignInViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(SignInViewModel.State())
        var exits = 0
        var signIns = 0
        compose.setContent {
            JaagrukTheme {
                SignInScreen(onWorkerSignedIn = { signIns++ }, onSupervisorTools = {},
                    onVerify = {}, viewModel = vm, onBack = { exits++ })
            }
        }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        assertThat(exits).isEqualTo(1)
        assertThat(signIns).isEqualTo(0)
        verify(exactly = 0) { vm.selectWorker(any()) }
    }
}
