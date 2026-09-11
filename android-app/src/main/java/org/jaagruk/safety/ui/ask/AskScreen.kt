package org.jaagruk.safety.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jaagruk.ai.AiCoach
import org.jaagruk.core.ai.AiTask
import org.jaagruk.safety.R
import org.jaagruk.safety.ai.currentAiLanguage
import org.jaagruk.safety.ui.components.AiPanel
import org.jaagruk.safety.ui.components.AiPanelState
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.UiMessage
import org.jaagruk.safety.ui.components.toPanelState
import javax.inject.Inject

/**
 * A worker asks a safety question and gets an answer with no signal.
 *
 * The feature that most directly attacks the number in the problem statement. Retention after a
 * classroom session drops sharply inside a week, and a large part of why is that the question a worker
 * actually has arrives later, at the face, with nobody to ask. A drill cannot answer that. A searchable
 * body of the site's own safety text, read back in the worker's language, can.
 *
 * Two properties make it defensible rather than a novelty:
 *
 *  * **It refuses.** If retrieval finds nothing relevant in the bundled corpus, no model runs and the
 *    worker is told to ask a supervisor. A confident answer about a hazard nobody wrote down is the
 *    single worst thing this screen could produce.
 *  * **It cites.** Every answer names the document it came from, so the worker or an inspector can
 *    check it.
 *
 * The examples are not decoration either: a blank box is intimidating to somebody who has never used a
 * search field, and tapping a worked example is how the shape of a useful question gets learned.
 */
@Composable
fun AskScreen(
    onBack: () -> Unit,
    viewModel: AskViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.ask_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ask_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            OutlinedTextField(
                value = state.question,
                onValueChange = viewModel::onQuestionChanged,
                label = { Text(stringResource(R.string.ask_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                singleLine = false,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
            )
        }

        item {
            AiPanel(
                state = state.panel,
                titleRes = R.string.ask_action,
                disclaimerRes = R.string.ask_disclaimer,
                actionRes = R.string.ask_action,
                onAsk = viewModel::ask,
                onStop = viewModel::stop,
                enabled = state.question.isNotBlank(),
            )
        }

        // Shown until the worker has had one answer. After that the box is understood and the examples
        // are clutter.
        if (state.panel is AiPanelState.Idle || state.panel is AiPanelState.NoAnswer) {
            item {
                SectionCard {
                    Text(
                        text = stringResource(R.string.ask_examples_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (example in AskViewModel.EXAMPLE_RES_IDS) {
                            val text = stringResource(example)
                            GloveOutlinedButton(
                                text = text,
                                onClick = { viewModel.askExample(text) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            GloveOutlinedButton(
                text = stringResource(R.string.action_back),
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@HiltViewModel
class AskViewModel @Inject constructor(
    private val aiCoach: AiCoach,
) : ViewModel() {

    companion object {
        val EXAMPLE_RES_IDS = listOf(
            R.string.ask_example_one,
            R.string.ask_example_two,
            R.string.ask_example_three,
        )
    }

    data class State(
        val question: String = "",
        val panel: AiPanelState = AiPanelState.Idle,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun onQuestionChanged(value: String) {
        // Capped at the length the task type accepts, so typing cannot reach a state that throws.
        val trimmed = value.take(AiTask.SafetyQuestion.MAX_QUESTION_CHARS)
        _state.value = _state.value.copy(question = trimmed)
    }

    fun askExample(text: String) {
        _state.value = _state.value.copy(question = text)
        ask()
    }

    fun ask() {
        val question = _state.value.question.trim()
        if (question.isBlank()) {
            _state.value = _state.value.copy(
                panel = AiPanelState.NoAnswer(UiMessage.info(R.string.ai_no_question)),
            )
            return
        }
        val language = currentAiLanguage()
        if (language == null) {
            _state.value = _state.value.copy(
                panel = AiPanelState.Unavailable(UiMessage.info(R.string.ai_unavailable_language)),
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(panel = AiPanelState.Working())
            val outcome = aiCoach.run(AiTask.SafetyQuestion(language, question)) { words ->
                _state.value = _state.value.copy(panel = AiPanelState.Working(words))
            }
            _state.value = _state.value.copy(panel = outcome.toPanelState())
        }
    }

    fun stop() {
        aiCoach.stop()
    }
}
