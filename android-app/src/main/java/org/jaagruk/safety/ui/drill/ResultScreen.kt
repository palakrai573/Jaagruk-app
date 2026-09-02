package org.jaagruk.safety.ui.drill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import org.jaagruk.core.assessment.Completion
import org.jaagruk.core.assessment.OutcomeClass
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.core.catalog.Pictogram
import org.jaagruk.safety.R
import org.jaagruk.safety.data.repo.AssessmentRepository
import org.jaagruk.safety.data.repo.CertificateRepository
import org.jaagruk.safety.sync.api.StepResultUpload
import org.jaagruk.safety.ui.components.BannerTone
import org.jaagruk.safety.ui.components.GloveButton
import org.jaagruk.safety.ui.components.GloveOutlinedButton
import org.jaagruk.safety.ui.components.PictogramIcon
import org.jaagruk.safety.ui.components.SectionCard
import org.jaagruk.safety.ui.components.StatTile
import org.jaagruk.safety.ui.components.StatusBanner
import org.jaagruk.safety.ui.components.catalogString
import org.jaagruk.safety.ui.theme.ReadinessColors
import javax.inject.Inject

/**
 * What happened, and what it does and does not mean.
 *
 * The screen is written to be honest about three things that a simpler pass/fail card would blur:
 *
 *  * **A pass with a hesitation flag is still a pass** — and still worth telling the worker about, because
 *    freezing on a critical step is the thing that gets people hurt even when they know the answer.
 *  * **A certificate may be pending.** If no site signing key is enrolled on this handset, the pass is stored
 *    and the certificate is minted later. The worker is told that, rather than left thinking they failed.
 *  * **An aborted run is reported as aborted.** It keeps its partial score and it cannot certify, and saying
 *    so beats a bare "incomplete".
 */
@Composable
fun ResultScreen(
    runId: String,
    onDone: (String?) -> Unit,
    onViewCertificates: (String) -> Unit,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(runId) { viewModel.load(runId) }

    if (state.loading) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        return
    }

    val run = state.run
    if (run == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            StatusBanner(
                text = stringResource(R.string.result_not_found),
                tone = BannerTone.ERROR,
                pictogramDescription = stringResource(R.string.cd_stop),
            )
            Spacer(Modifier.height(12.dp))
            GloveButton(
                text = stringResource(R.string.action_back),
                onClick = { onDone(null) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PictogramIcon(
                    pictogram = if (run.passed) Pictogram.ANSWER_YES else Pictogram.ANSWER_NO,
                    contentDescription = stringResource(
                        if (run.passed) R.string.result_passed else R.string.result_not_passed,
                    ),
                    size = 56.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(
                            if (run.passed) R.string.result_passed else R.string.result_not_passed,
                        ),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    state.moduleTitleKey?.let {
                        Text(catalogString(it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = "${run.scorePermille / 10}%",
                    label = stringResource(R.string.result_score),
                    accent = if (run.passed) ReadinessColors.ready else ReadinessColors.expired,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = "${run.medianLatencyMs} ms",
                    label = stringResource(R.string.result_median_latency),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Reported whether or not the run passed. A worker who knows the answers but freezes on the step
        // that matters is the specific failure mode this platform exists to surface.
        if (run.hesitationFlag) {
            item {
                StatusBanner(
                    text = stringResource(
                        R.string.result_hesitation,
                        (run.hesitationRatio * 100).toInt(),
                    ),
                    tone = BannerTone.WARNING,
                    pictogramDescription = stringResource(R.string.cd_warning),
                )
            }
        }

        run.voidReason?.let { reason ->
            item {
                StatusBanner(
                    text = stringResource(voidLabel(reason)),
                    tone = BannerTone.ERROR,
                    pictogramDescription = stringResource(R.string.cd_stop),
                )
            }
        }

        run.abortReason?.let { reason ->
            item {
                StatusBanner(
                    text = stringResource(R.string.result_aborted, reason.lowercase()),
                    tone = BannerTone.WARNING,
                    pictogramDescription = stringResource(R.string.cd_warning),
                )
            }
        }

        if (run.completion == Completion.COMPLETED.name && run.passed) {
            item {
                StatusBanner(
                    text = if (state.certificateSeq != null) {
                        stringResource(R.string.result_certificate_issued, state.certificateSeq!!)
                    } else {
                        stringResource(R.string.result_certificate_pending)
                    },
                    tone = if (state.certificateSeq != null) BannerTone.SUCCESS else BannerTone.INFO,
                    pictogramDescription = stringResource(R.string.cd_info),
                )
            }
        }

        if (state.remediationSteps.isNotEmpty()) {
            item {
                SectionCard {
                    Text(
                        text = stringResource(R.string.result_review_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.result_review_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        items(state.remediationSteps, key = { it.stepId }) { step ->
            StepReviewCard(step)
        }

        item {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GloveButton(
                    text = stringResource(R.string.action_done),
                    onClick = { onDone(run.workerId) },
                    modifier = Modifier.weight(1f),
                )
                if (state.certificateSeq != null) {
                    GloveOutlinedButton(
                        text = stringResource(R.string.action_my_certificates),
                        onClick = { onViewCertificates(run.workerId) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepReviewCard(step: StepResultUpload) {
    SectionCard {
        Text(
            text = catalogString("step_${step.stepId}_prompt"),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PictogramIcon(
                pictogram = if (step.correct) Pictogram.ANSWER_YES else Pictogram.ANSWER_NO,
                contentDescription = stringResource(
                    if (step.correct) R.string.result_step_correct else R.string.result_step_wrong,
                ),
                size = 28.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    R.string.result_step_timing,
                    step.latencyMs,
                    step.expertMs,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // The remediation string is the one piece of coaching a worker takes away, so it is shown for every
        // step worth reviewing rather than only for outright wrong answers.
        val remedyKey = "step_${step.stepId}_remedy"
        Spacer(Modifier.height(6.dp))
        Text(
            text = catalogString(remedyKey),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun voidLabel(reason: String): Int = when (reason) {
    "GUESS_PATTERN" -> R.string.result_void_guessing
    "BUDDY_REQUIRED_BUT_SOLO" -> R.string.result_void_solo_buddy
    else -> R.string.result_void_generic
}

@HiltViewModel
class ResultViewModel @Inject constructor(
    private val assessments: AssessmentRepository,
    private val certificates: CertificateRepository,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val run: org.jaagruk.safety.data.db.AssessmentRunEntity? = null,
        val moduleTitleKey: String? = null,
        val certificateSeq: Long? = null,
        val remediationSteps: List<StepResultUpload> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(runId: String) {
        viewModelScope.launch {
            val run = assessments.find(runId)
            if (run == null) {
                _state.value = State(loading = false)
                return@launch
            }

            val steps = assessments.decodeSteps(run.stepsJson)
            _state.value = State(
                loading = false,
                run = run,
                moduleTitleKey = ModuleCatalog.byId(run.moduleId)?.titleKey,
                certificateSeq = certificates.findByRunId(runId)?.seq,
                // Wrong, timed out, or notably slow. A worker reviewing five correct answers learns nothing;
                // reviewing the one they hesitated on is the whole value of the debrief.
                remediationSteps = steps.filter { step ->
                    !step.correct ||
                        step.outcome.equals(OutcomeClass.CORRECT_SLOW.name, ignoreCase = true)
                },
            )
        }
    }
}
