package org.jaagruk.safety.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jaagruk.core.assessment.AnswerMatcher
import org.jaagruk.core.assessment.StepKind
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.R

fun trainingText(context: Context, key: String): String {
    val id = context.resources.getIdentifier(key, "string", context.packageName)
    return if (id != 0) context.getString(id) else key
}

private fun moduleIcon(id: String): ImageVector = when (id) {
    ModuleCatalog.ID_FIRE -> Icons.Default.LocalFireDepartment
    ModuleCatalog.ID_GAS -> Icons.Default.Air
    ModuleCatalog.ID_MACHINERY -> Icons.Default.PrecisionManufacturing
    ModuleCatalog.ID_PPE_HEIGHT -> Icons.Default.Engineering
    else -> Icons.Default.ElectricalServices
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingHomeScreen(bottomPadding: Dp, onPractice: (String) -> Unit,
    onAsk: () -> Unit, onTools: () -> Unit, onGeneralChat: () -> Unit, onLanguage: (String) -> Unit) {
    val context = LocalContext.current
    var languages by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomPadding).testTag("training-home"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                        Icon(Icons.Default.HealthAndSafety, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(9.dp).size(24.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { languages = true }, modifier = Modifier.testTag("training-language")) {
                            Icon(Icons.Default.Language, stringResource(R.string.train_language))
                        }
                        DropdownMenu(languages, onDismissRequest = { languages = false }) {
                            appLanguages.forEach { (tag, label) ->
                                DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("language-$tag"),
                                    onClick = { languages = false; onLanguage(tag) })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(stringResource(R.string.train_title), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.train_subtitle), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 360.dp))
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.OfflineBolt, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.train_offline), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.scene_count), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.scene_catalog), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 20.dp, bottom = 2.dp))
            }
            items(ModuleCatalog.all, key = { it.moduleId }) { module ->
                OutlinedCard(onClick = { onPractice(module.moduleId) }, shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
                            Icon(moduleIcon(module.moduleId), null, Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(trainingText(context, module.titleKey), style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.train_meta, module.estimatedMinutes, module.fullScenario.steps.size),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp),
                            shadowElevation = 0.dp) {
                            Icon(Icons.Default.ViewInAr, stringResource(R.string.scene_explore), Modifier.padding(10.dp).size(22.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                TextButton(onClick = onAsk, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Default.QuestionAnswer, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.train_ask))
                }
                TextButton(onClick = onTools, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Default.Description, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.train_documents))
                }
                TextButton(onClick = onGeneralChat, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Default.ChatBubbleOutline, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.train_general_chat))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPracticeScreen(moduleId: String, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val scenario = remember(moduleId) { ModuleCatalog.byId(moduleId)?.fullScenario }
    var index by rememberSaveable(moduleId) { mutableIntStateOf(0) }
    var answers by rememberSaveable(moduleId) { mutableStateOf(arrayListOf<String>()) }
    var checked by rememberSaveable(moduleId) { mutableStateOf(false) }
    var correct by rememberSaveable(moduleId) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    LaunchedEffect(index) { list.scrollToItem(0) }
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text(stringResource(R.string.train_practice)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
    }) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("training-practice"), state = list,
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.train_notice), style = MaterialTheme.typography.bodySmall) }
            val step = scenario?.steps?.getOrNull(index)
            if (scenario == null) {
                item { Text(stringResource(R.string.train_invalid)) }
            } else if (step == null) {
                item {
                    Icon(Icons.Default.TaskAlt, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.train_complete), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.train_result, correct, scenario.steps.size))
                }
                item { Button(onClick = { index = 0; answers = arrayListOf(); checked = false; correct = 0 }) {
                    Text(stringResource(R.string.train_again))
                } }
            } else {
                item {
                    Text(trainingText(context, scenario.titleKey), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.train_step, index + 1, scenario.steps.size), style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(progress = { index.toFloat() / scenario.steps.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
                    Text(trainingText(context, step.promptKey), style = MaterialTheme.typography.titleLarge)
                    if (step.kind == StepKind.SEQUENCE || step.kind == StepKind.MULTI_SELECT) {
                        Text(stringResource(if (step.kind == StepKind.SEQUENCE) R.string.train_order else R.string.train_multi))
                    }
                }
                items(step.options, key = { it.optionId }) { option ->
                    val selected = option.optionId in answers
                    val multiple = step.kind == StepKind.MULTI_SELECT || step.kind == StepKind.SEQUENCE
                    Surface(shape = RoundedCornerShape(8.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(option.optionId)
                            .toggleable(selected, enabled = !checked, role = if (multiple) Role.Checkbox else Role.RadioButton) {
                                answers = if (multiple) ArrayList(answers.toMutableList().apply {
                                    if (!remove(option.optionId)) add(option.optionId)
                                }) else arrayListOf(option.optionId)
                            }.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (step.kind == StepKind.SEQUENCE && selected) {
                                Text((answers.indexOf(option.optionId) + 1).toString(), fontWeight = FontWeight.Bold)
                            } else if (multiple) Checkbox(selected, onCheckedChange = null, enabled = !checked)
                            else RadioButton(selected, onClick = null, enabled = !checked)
                            Text(trainingText(context, option.labelKey), modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (checked) item {
                    Text(stringResource(if (AnswerMatcher.matches(step, answers)) R.string.train_correct else R.string.train_review),
                        style = MaterialTheme.typography.titleMedium)
                    step.remediationKey?.let { Text(trainingText(context, it)) }
                    step.correctOptionIds.forEachIndexed { position, id ->
                        step.option(id)?.let { Text("${position + 1}. ${trainingText(context, it.labelKey)}") }
                    }
                }
                item { Button(onClick = {
                    if (checked) { index++; answers = arrayListOf(); checked = false }
                    else { if (AnswerMatcher.matches(step, answers)) correct++; checked = true }
                }, enabled = answers.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                    Text(stringResource(if (checked) R.string.train_next else R.string.train_check))
                } }
            }
        }
    }
}
