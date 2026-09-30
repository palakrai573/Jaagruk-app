package org.jaagruk.safety.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.jaagruk.safety.MainActivity
import org.junit.Rule
import org.junit.Test

class TrainingNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeOpensWithoutSignInAndAskUsesSafetyCoach() {
        compose.onNodeWithText("Safety training").assertIsDisplayed()
        compose.onNodeWithText("Ask").performClick()
        compose.onNodeWithText("Safety coach").assertIsDisplayed()
        compose.onNodeWithText("Your safety question").assertIsDisplayed()
    }
}
