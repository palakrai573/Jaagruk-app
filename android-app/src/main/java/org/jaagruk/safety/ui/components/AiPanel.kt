package org.jaagruk.safety.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jaagruk.ai.AiOutcome
import org.jaagruk.core.ai.AiCapability
import org.jaagruk.core.ai.InsufficientReason
import org.jaagruk.safety.R

/**
 * What the assistance panel is showing.
 *
 * Six states, mirroring [AiOutcome], because each one is a different sentence a worker can act on.
 * Collapsing them into "answer or sorry" would hide the two that matter most: *the site's documents do
 * not cover this* is a useful answer that tells a worker to ask a supervisor, and *the assistant is not
 * installed on this phone* is a thing a supervisor can fix.
 */
sealed interface AiPanelState {

    /** Nothing asked yet. */
    data object Idle : AiPanelState

    /**
     * Working, with a word count.
     *
     * Deliberately a count and not the text being produced. Partial output has not been through the
     * output guard, and showing an invented figure for two seconds before replacing it would defeat the
     * point of having one.
     */
    class Working(val words: Int = 0) : AiPanelState

    /** Checked and safe to read, with the sources it came from. */
    class Ready(
        val text: String,
        val citations: List<String>,
        val truncated: Boolean,
    ) : AiPanelState

    /** No answer available, and the reason is not the worker's problem to solve. */
    class NoAnswer(val message: UiMessage) : AiPanelState

    /** Cannot run here, with the reason stated. */
    class Unavailable(val message: UiMessage) : AiPanelState

    /** Something went wrong. */
    class Failed(val message: UiMessage) : AiPanelState
}

/**
 * Maps an engine outcome to a panel state.
 *
 * One place, so every feature says the same thing about the same situation. A [AiOutcome.Filtered] is
 * reported as a plain failure on purpose: the rejection code is a fact about the model's behaviour that
 * a maintainer needs from the log, and a worker cannot act on "the output failed the numeric grounding
 * check".
 */
fun AiOutcome.toPanelState(): AiPanelState = when (this) {
    is AiOutcome.Answer -> AiPanelState.Ready(text, citations, truncated)

    is AiOutcome.NoGrounding -> AiPanelState.NoAnswer(
        UiMessage.info(
            when (reason) {
                InsufficientReason.NO_QUERY_TERMS -> R.string.ai_no_question
                else -> R.string.ai_not_in_documents
            },
        ),
    )

    AiOutcome.ModelDeclined -> AiPanelState.NoAnswer(
        UiMessage.info(R.string.ai_not_in_documents),
    )

    is AiOutcome.Filtered -> AiPanelState.Failed(UiMessage.warning(R.string.ai_check_failed))

    is AiOutcome.Unavailable -> AiPanelState.Unavailable(
        UiMessage.info(
            when (capability) {
                AiCapability.MODEL_MISSING -> R.string.ai_unavailable_not_installed
                AiCapability.UNSUPPORTED_DEVICE -> R.string.ai_unavailable_device
                AiCapability.LANGUAGE_UNSUPPORTED -> R.string.ai_unavailable_language
                AiCapability.BUSY_IN_DRILL -> R.string.ai_unavailable_drill
                AiCapability.DISABLED_BY_POLICY -> R.string.ai_unavailable_policy
                AiCapability.READY -> R.string.ai_check_failed
            },
        ),
    )

    is AiOutcome.Failed -> AiPanelState.Failed(UiMessage.warning(R.string.ai_failed))
}

/**
 * The assistance panel.
 *
 * Used by all four features so the shape, the disclaimer and the citation line are identical wherever
 * generated text appears. A worker should be able to recognise "this came from the assistant" without
 * reading a label.
 *
 * @param disclaimerRes the one-line statement of what this text is and is not. Required rather than
 *   defaulted: every site of use has a different thing it must not be mistaken for, and a shared
 *   default would end up wrong somewhere.
 */
@Composable
fun AiPanel(
    state: AiPanelState,
    titleRes: Int,
    disclaimerRes: Int,
    actionRes: Int,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
    onStop: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    SectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(disclaimerRes),
            style = MaterialTheme.typography.labelMedium,
            fontStyle = FontStyle.Italic,
        )
        Spacer(Modifier.height(10.dp))

        when (state) {
            AiPanelState.Idle -> {
                GloveButton(
                    text = stringResource(actionRes),
                    onClick = onAsk,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is AiPanelState.Working -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (state.words > 0) {
                            stringResource(R.string.ai_working_progress, state.words)
                        } else {
                            stringResource(R.string.ai_working)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onStop != null) {
                    Spacer(Modifier.height(10.dp))
                    GloveOutlinedButton(
                        text = stringResource(R.string.ai_stop),
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            is AiPanelState.Ready -> {
                Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (state.truncated) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.ai_shortened),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                // The citation is not decoration. It is what lets a worker or an inspector check the
                // claim against the document it came from, which is the difference between a grounded
                // answer and an assertion.
                if (state.citations.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (citation in state.citations) {
                            Text(
                                text = stringResource(R.string.ai_source, citation),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                GloveOutlinedButton(
                    text = stringResource(actionRes),
                    onClick = onAsk,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is AiPanelState.NoAnswer -> {
                MessageBanner(state.message, stringResource(R.string.cd_info))
                Spacer(Modifier.height(10.dp))
                GloveOutlinedButton(
                    text = stringResource(actionRes),
                    onClick = onAsk,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is AiPanelState.Unavailable -> {
                MessageBanner(state.message, stringResource(R.string.cd_info))
                Spacer(Modifier.height(10.dp))
                GloveOutlinedButton(stringResource(actionRes), onAsk,
                    enabled = enabled, modifier = Modifier.fillMaxWidth())
            }

            is AiPanelState.Failed -> {
                MessageBanner(state.message, stringResource(R.string.cd_warning))
                Spacer(Modifier.height(10.dp))
                GloveOutlinedButton(
                    text = stringResource(actionRes),
                    onClick = onAsk,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
