package org.jaagruk.safety

import android.os.Bundle
import android.content.res.Configuration
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import org.jaagruk.safety.ui.navigation.AppNavigation
import org.jaagruk.safety.ui.theme.JaagrukTheme
import org.jaagruk.safety.viewmodel.ThemeViewModel

class MainActivity : ComponentActivity() {
    private val themeViewModel: ThemeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Allow content to draw behind system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("jaagruk_locale", MODE_PRIVATE)
        setContent {
            var language by remember { mutableStateOf(preferences.getString("language", "en") ?: "en") }
            val deviceConfiguration = LocalConfiguration.current
            val configuration = remember(language, deviceConfiguration) { Configuration(deviceConfiguration).apply {
                setLocale(Locale.forLanguageTag(language))
            } }
            val localized = remember(configuration) {
                val translatedResources = createConfigurationContext(configuration).resources
                // Keep the Activity in the wrapper chain for permission and document launchers.
                object : ContextWrapper(this@MainActivity) {
                    override fun getResources() = translatedResources
                }
            }
            val isDarkTheme by themeViewModel.isDarkTheme.collectAsState()
            SideEffect {
                // Apply after decor attachment so launch-theme flags cannot overwrite the choice.
                window.decorView.post {
                    val dark = themeViewModel.isDarkTheme.value
                    val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                JaagrukTheme(darkTheme = isDarkTheme) {
                    AppNavigation(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = themeViewModel::toggleTheme,
                        onLanguage = { tag ->
                            if (tag in listOf("en", "hi", "sat", "ta")) {
                                preferences.edit().putString("language", tag).apply()
                                language = tag
                            }
                        },
                    )
                }
            }
        }
    }
}
