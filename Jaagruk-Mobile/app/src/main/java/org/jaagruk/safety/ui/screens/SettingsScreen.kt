package org.jaagruk.safety.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jaagruk.safety.BuildConfig
import org.jaagruk.safety.R
import org.jaagruk.safety.ai.repository.AIRepository

val appLanguages = listOf("en" to "English", "hi" to "हिन्दी", "sat" to "ᱥᱟᱱᱛᱟᱲᱤ", "ta" to "தமிழ்")

@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    bottomPadding: Dp,
    onToggleTheme: () -> Unit,
    onOpenGallery: () -> Unit = {},
    onLanguage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val repository = remember { AIRepository.getInstance(context) }
    val modelOnDisk = repository.isModelOnDisk()
    var languagePicker by remember { mutableStateOf(false) }
    if (languagePicker) AlertDialog(
        onDismissRequest = { languagePicker = false },
        title = { Text(stringResource(R.string.train_language)) },
        text = { Column {
            appLanguages.forEach { (tag, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("settings-language-$tag")
                    .toggleable(locale.language == tag, role = Role.RadioButton) {
                        onLanguage(tag)
                        languagePicker = false
                    }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(locale.language == tag, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }
        } },
        confirmButton = {},
    )
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomPadding)
            .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.settings_preferences), style = MaterialTheme.typography.titleSmall)
            Surface(onClick = { languagePicker = true }, modifier = Modifier.fillMaxWidth().testTag("settings-language")) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.train_language)) },
                    supportingContent = { Text(appLanguages.firstOrNull { it.first == locale.language }?.second ?: locale.displayName) },
                    leadingContent = { Icon(Icons.Default.Language, null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                )
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .toggleable(isDarkTheme, role = Role.Switch) { onToggleTheme() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.DarkMode, null)
                Text(stringResource(R.string.settings_dark), Modifier.weight(1f))
                Switch(isDarkTheme, onCheckedChange = null)
            }
            HorizontalDivider()
            Text(stringResource(R.string.settings_offline_ai), style = MaterialTheme.typography.titleSmall)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_engine_value)) },
                supportingContent = { Text(stringResource(if (modelOnDisk) R.string.settings_model_installed else R.string.model_missing_title)) },
                leadingContent = { Icon(Icons.Default.Memory, null) },
            )
            Text(stringResource(R.string.settings_ai_languages), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelLarge)
            if (BuildConfig.DEBUG) TextButton(onClick = onOpenGallery) {
                Icon(Icons.Default.Palette, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_design))
            }
        }
    }
}
