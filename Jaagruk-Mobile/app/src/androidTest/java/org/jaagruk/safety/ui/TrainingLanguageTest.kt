package org.jaagruk.safety.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.MainActivity
import org.jaagruk.safety.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TrainingLanguageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsLanguagePickerChangesVisibleLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("jaagruk_locale", Context.MODE_PRIVATE)
        val original = preferences.getString("language", "en") ?: "en"
        try {
            compose.onNodeWithTag("training-language").performClick()
            compose.onNodeWithTag("language-en").performClick()
            compose.onNodeWithText("Settings").performClick()
            for (tag in listOf("hi", "ta", "sat", "en")) {
                compose.onNodeWithTag("settings-language").performClick()
                compose.onNodeWithTag("settings-language-$tag").performClick()
                compose.waitForIdle()
                assertEquals(tag, preferences.getString("language", null))
                val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
                val localized = context.createConfigurationContext(config)
                compose.onNodeWithText(localized.getString(R.string.settings_dark)).assertIsDisplayed()
            }
        } finally {
            preferences.edit().putString("language", original).commit()
            compose.activityRule.scenario.recreate()
        }
    }

    @Test fun languageSelectionSurvivesRecreationAndCatalogRenders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("jaagruk_locale", Context.MODE_PRIVATE)
        val original = preferences.getString("language", "en") ?: "en"
        try {
            for (tag in listOf("en", "hi", "sat", "ta")) {
                compose.onNodeWithTag("training-language").performClick()
                compose.onNodeWithTag("language-$tag").performClick()
                compose.waitForIdle()
                assertEquals(tag, preferences.getString("language", null))
                compose.activityRule.scenario.recreate()
                compose.waitForIdle()

                val configuration = Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                }
                val localized = context.createConfigurationContext(configuration)
                val key = ModuleCatalog.all.first().titleKey
                val title = localized.getString(localized.resources.getIdentifier(key, "string", context.packageName))
                compose.onNodeWithText(title).assertIsDisplayed()
                if (tag == "sat") assertTrue(title.any { it.code in 0x1C50..0x1C7F })
                if (tag == "ta") assertTrue(title.any { it.code in 0x0B80..0x0BFF })
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try {
                    File(context.filesDir, "validation-home-$tag.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
        } finally {
            preferences.edit().putString("language", original).commit()
            compose.activityRule.scenario.recreate()
        }
    }
}
