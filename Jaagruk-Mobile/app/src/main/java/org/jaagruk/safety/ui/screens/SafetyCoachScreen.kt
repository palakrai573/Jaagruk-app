package org.jaagruk.safety.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jaagruk.ai.AiOutcome
import org.jaagruk.core.ai.AiCapability
import org.jaagruk.core.ai.AiTask
import org.jaagruk.core.ai.AiLanguage
import org.jaagruk.core.ai.CorpusPassage
import org.jaagruk.core.ai.RetrievalResult
import org.jaagruk.core.ai.SafetyCorpus
import org.jaagruk.safety.R
import org.jaagruk.safety.ai.repository.AIRepository

class SafetyCoachViewModel(application: Application) : AndroidViewModel(application) {
    data class State(val question: String = "", val working: Boolean = false,
        val outcome: AiOutcome? = null, val requestId: Long = 0,
        val sources: List<CorpusPassage> = emptyList())
    private val repository = AIRepository.getInstance(application)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var request: Job? = null

    fun edit(value: String) {
        stop()
        mutable.update { it.copy(question = value.take(AiTask.SafetyQuestion.MAX_QUESTION_CHARS), outcome = null, sources = emptyList()) }
    }

    fun ask(language: String) {
        val current = mutable.value
        if (current.working || current.question.isBlank()) return
        val id = current.requestId + 1
        mutable.update { it.copy(working = true, outcome = null, requestId = id, sources = emptyList()) }
        request = viewModelScope.launch(Dispatchers.IO) {
            try {
                val supported = AiLanguage.fromTagOrNull(language)
                val task = supported?.let { AiTask.SafetyQuestion(it, current.question.trim()) }
                val sources = task?.let { SafetyCorpus.retriever.retrieveForTask(it) as? RetrievalResult.Grounded }
                    ?.passages?.map { it.passage }.orEmpty()
                mutable.update { if (it.requestId == id) it.copy(sources = sources) else it }
                val outcome = repository.askSafety(current.question.trim(), language)
                mutable.update { if (it.requestId == id) it.copy(working = false, outcome = outcome) else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { if (it.requestId == id) it.copy(working = false, outcome = AiOutcome.Failed("request failed")) else it }
            }
        }
    }

    fun stop() {
        mutable.update { it.copy(working = false, requestId = it.requestId + 1) }
        if (request?.isActive == true) { repository.stop(); request?.cancel() }
        request = null
    }

    override fun onCleared() { stop(); super.onCleared() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyCoachScreen(onBack: () -> Unit, bottomPadding: Dp = 0.dp, vm: SafetyCoachViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.stop() }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.train_ask)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
    }) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(bottom = bottomPadding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.VerifiedUser, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(stringResource(R.string.safety_notice), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            OutlinedTextField(state.question, vm::edit, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.safety_question)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                minLines = 4, maxLines = 7, shape = RoundedCornerShape(8.dp))
            if (state.working) {
                OutlinedCard(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ManageSearch, null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.safety_working), style = MaterialTheme.typography.titleSmall)
                        }
                        OutlinedButton(onClick = vm::stop, modifier = Modifier.align(Alignment.End)) {
                            Icon(Icons.Default.Stop, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.cd_stop))
                        }
                    }
                }
            } else Button(onClick = { vm.ask(language) },
                enabled = state.question.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Default.PsychologyAlt, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.safety_ask))
            }
            when (val outcome = state.outcome) {
                is AiOutcome.Answer -> {
                    OutlinedCard(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.safety_quote), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if (outcome.truncated) Text(stringResource(R.string.safety_truncated), color = MaterialTheme.colorScheme.error)
                            Text(outcome.text, style = MaterialTheme.typography.bodyLarge)
                            HorizontalDivider()
                            Text(stringResource(R.string.safety_sources), style = MaterialTheme.typography.titleSmall)
                            outcome.citations.forEach { Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                is AiOutcome.Unavailable -> SafetyMessage(Icons.Default.CloudOff, stringResource(if (outcome.capability == AiCapability.LANGUAGE_UNSUPPORTED)
                    R.string.safety_unsupported else R.string.safety_model_missing))
                is AiOutcome.NoGrounding, AiOutcome.ModelDeclined -> SafetyMessage(Icons.Default.ReportProblem, stringResource(R.string.safety_refused))
                is AiOutcome.Failed, is AiOutcome.Filtered -> SafetyMessage(Icons.Default.ErrorOutline, stringResource(R.string.safety_failed))
                null -> Unit
            }
            if (state.sources.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.safety_library), style = MaterialTheme.typography.titleMedium)
                state.sources.forEach { passage ->
                    OutlinedCard(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(passage.title, style = MaterialTheme.typography.titleSmall)
                            Text(passage.body, style = MaterialTheme.typography.bodyMedium)
                            Text(passage.sourceLabel, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
