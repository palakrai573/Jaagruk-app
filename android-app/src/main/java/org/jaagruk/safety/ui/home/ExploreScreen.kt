package org.jaagruk.safety.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.R
import org.jaagruk.safety.ui.LocaleManager
import org.jaagruk.safety.ui.components.*

/** Public entry point. Browsing and rehearsal never create a worker or a certificate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onPractice: (String) -> Unit,
    onSignIn: () -> Unit,
    onAsk: () -> Unit,
    onVerify: () -> Unit,
    onSupervisor: () -> Unit,
) {
    var languagesOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    Box {
                        IconButton(onClick = { languagesOpen = true }) {
                            Icon(Icons.Outlined.Language, stringResource(R.string.explore_language))
                        }
                        DropdownMenu(languagesOpen, onDismissRequest = { languagesOpen = false }) {
                            LocaleManager.supported.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text(LocaleManager.endonym(tag)) },
                                    onClick = { languagesOpen = false; LocaleManager.apply(tag) },
                                    trailingIcon = {
                                        if (LocaleManager.current() == tag) Icon(Icons.Outlined.Check, null)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onSignIn) {
                        Icon(Icons.AutoMirrored.Outlined.Login, stringResource(R.string.explore_sign_in))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(true, onClick = {}, icon = { Icon(Icons.Outlined.School, null) },
                    label = { Text(stringResource(R.string.explore_train)) })
                NavigationBarItem(false, onClick = onAsk, icon = { Icon(Icons.Outlined.QuestionAnswer, null) },
                    label = { Text(stringResource(R.string.explore_coach)) })
                NavigationBarItem(false, onClick = onVerify, icon = { Icon(Icons.Outlined.QrCodeScanner, null) },
                    label = { Text(stringResource(R.string.explore_verify)) })
                NavigationBarItem(false, onClick = onSupervisor, icon = { Icon(Icons.Outlined.Engineering, null) },
                    label = { Text(stringResource(R.string.explore_site)) })
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.OfflinePin, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.explore_offline), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.explore_title), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.explore_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.explore_modules), style = MaterialTheme.typography.titleLarge)
            }
            items(ModuleCatalog.all, key = { it.moduleId }) { module ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PictogramIcon(module.pictogram, "", size = 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(catalogString(module.titleKey), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.explore_duration, module.estimatedMinutes, module.fullScenario.steps.size),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(catalogString(module.descriptionKey), style = MaterialTheme.typography.bodyMedium)
                    GloveButton(stringResource(R.string.action_practice),
                        onClick = { onPractice(module.fullScenario.scenarioId) }, modifier = Modifier.fillMaxWidth())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item {
                Text(stringResource(R.string.explore_certify_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.explore_certify_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                GloveOutlinedButton(stringResource(R.string.explore_sign_in), onSignIn, Modifier.fillMaxWidth())
            }
        }
    }
}
