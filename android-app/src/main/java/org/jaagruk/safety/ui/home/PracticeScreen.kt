package org.jaagruk.safety.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jaagruk.core.assessment.AnswerMatcher
import org.jaagruk.core.assessment.StepKind
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.safety.R
import org.jaagruk.safety.ui.components.*

/** Untimed, local rehearsal of the same catalog, deliberately outside the assessment repository. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(scenarioId: String, onBack: () -> Unit, onSignIn: () -> Unit) {
    val scenario = remember(scenarioId) { ModuleCatalog.scenario(scenarioId) }
    var index by rememberSaveable(scenarioId) { mutableIntStateOf(0) }
    var selection by rememberSaveable(scenarioId) { mutableStateOf(arrayListOf<String>()) }
    var checked by rememberSaveable(scenarioId) { mutableStateOf(false) }
    var correctCount by rememberSaveable(scenarioId) { mutableIntStateOf(0) }
    val step = scenario?.steps?.getOrNull(index)
    val listState = rememberLazyListState()
    LaunchedEffect(index) { listState.scrollToItem(0) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.action_practice)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
        })
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("practice-list"), state = listState, contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(stringResource(R.string.practice_notice), style = MaterialTheme.typography.bodyMedium) }
            if (scenario == null || scenario.requiresBuddy) {
                item { Text(stringResource(R.string.drill_unknown_scenario)) }
            } else if (step == null) {
                item {
                    Text(stringResource(R.string.practice_complete), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.practice_result, correctCount, scenario.steps.size))
                }
                item { GloveButton(stringResource(R.string.practice_again), onClick = {
                    index = 0; correctCount = 0; checked = false; selection = arrayListOf()
                }, modifier = Modifier.fillMaxWidth()) }
                item { GloveOutlinedButton(stringResource(R.string.explore_sign_in), onSignIn, Modifier.fillMaxWidth()) }
            } else {
                item {
                    Text(catalogString(scenario.titleKey), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.practice_step, index + 1, scenario.steps.size), style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(progress = { index.toFloat() / scenario.steps.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Text(catalogString(step.promptKey), style = MaterialTheme.typography.titleLarge)
                    if (step.kind == StepKind.SEQUENCE || step.kind == StepKind.MULTI_SELECT) {
                        Text(stringResource(if (step.kind == StepKind.SEQUENCE) R.string.practice_order else R.string.practice_multi),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(step.options, key = { it.optionId }) { option ->
                    OptionCard(
                        label = catalogString(option.labelKey), pictogram = option.pictogram,
                        selected = option.optionId in selection, enabled = !checked,
                        ordinal = if (step.kind == StepKind.SEQUENCE) selection.indexOf(option.optionId).takeIf { it >= 0 }?.plus(1) else null,
                        modifier = Modifier.fillMaxWidth(), onClick = {
                            selection = if (step.kind == StepKind.SEQUENCE || step.kind == StepKind.MULTI_SELECT) {
                                ArrayList(selection.toMutableList().apply {
                                    if (!remove(option.optionId)) add(option.optionId)
                                })
                            } else arrayListOf(option.optionId)
                        },
                    )
                }
                if (checked) {
                    item {
                        Text(stringResource(if (AnswerMatcher.matches(step, selection)) R.string.practice_correct else R.string.practice_review),
                            style = MaterialTheme.typography.titleMedium)
                        step.remediationKey?.let { Text(catalogString(it), modifier = Modifier.padding(top = 8.dp)) }
                        step.correctOptionIds.forEachIndexed { position, id ->
                            step.option(id)?.let { option ->
                                Text("${position + 1}. ${catalogString(option.labelKey)}", modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
                item {
                    GloveButton(stringResource(if (checked) R.string.practice_next else R.string.practice_check),
                        enabled = selection.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                            if (checked) { index++; selection = arrayListOf(); checked = false }
                            else { if (AnswerMatcher.matches(step, selection)) correctCount++; checked = true }
                        })
                }
            }
        }
    }
}
