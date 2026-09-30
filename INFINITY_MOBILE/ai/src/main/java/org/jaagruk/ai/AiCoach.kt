package org.jaagruk.ai

import android.util.Log
import org.jaagruk.ai.runtime.StopReason
import org.jaagruk.core.ai.AiCapability
import org.jaagruk.core.ai.AiLanguage
import org.jaagruk.core.ai.AiTaskKind
import org.jaagruk.core.ai.AiTask
import org.jaagruk.core.ai.AnswerGuard
import org.jaagruk.core.ai.GuardRejection
import org.jaagruk.core.ai.GuardVerdict
import org.jaagruk.core.ai.InsufficientReason
import org.jaagruk.core.ai.PromptBudget
import org.jaagruk.core.ai.PromptBuilder
import org.jaagruk.core.ai.RetrievalResult
import org.jaagruk.core.ai.Retriever
import org.jaagruk.core.ai.SafetyCorpus

/**
 * What came back, in the six shapes the UI has to be able to say out loud.
 *
 * Six rather than success-or-failure, for the same reason certificate verification reports seven
 * verdicts rather than valid-or-invalid: collapsing them either hides a real problem or cries wolf.
 * "The site's safety documents do not cover this" and "the model produced something I would not show
 * you" are different sentences, and a worker deserves the accurate one.
 */
sealed interface AiOutcome {

    /** Checked, grounded, safe to display. */
    class Answer(
        val text: String,
        val citations: List<String>,
        val passageIds: List<String>,
        val truncated: Boolean,
        val tokenCount: Int,
        val elapsedMs: Long,
    ) : AiOutcome {
        val tokensPerSecond: Double
            get() = if (elapsedMs <= 0L) 0.0 else tokenCount * 1000.0 / elapsedMs
    }

    /**
     * Retrieval found nothing relevant, so nothing was generated.
     *
     * The most important outcome after [Answer]. No model ran, so nothing could be invented, and the
     * honest thing to tell the worker is that the site's documents do not cover it and to ask a
     * supervisor.
     */
    class NoGrounding(val reason: InsufficientReason) : AiOutcome

    /** The sources were supplied and the model reported that they do not answer the question. */
    data object ModelDeclined : AiOutcome

    /** Something was generated and the guard threw it away. Shown as a failure, never as an answer. */
    class Filtered(val rejection: GuardRejection, val detail: String) : AiOutcome

    /** No engine, no model, wrong language, or a drill is running. */
    class Unavailable(val capability: AiCapability) : AiOutcome

    /** The engine failed. Distinct from [Filtered]: nothing was produced to reject. */
    class Failed(val message: String) : AiOutcome
}

/**
 * The one entry point the app uses for on-device assistance.
 *
 * The whole pipeline, in order, with a refusal available at every step:
 *
 * ```
 *   capability check   -> Unavailable      (no engine, no model, sat, mid-drill)
 *   retrieval          -> NoGrounding      (nothing relevant; no model runs)
 *   prompt building    -> NoGrounding      (grounding could not be laid out)
 *   generation         -> Failed
 *   AnswerGuard        -> Filtered / ModelDeclined
 *   otherwise                              Answer
 * ```
 *
 * Nothing reaches a worker without passing every stage. The model is the only non-deterministic part,
 * and it sits between two deterministic gates that are unit tested in `:core` on a plain JVM.
 */
class AiCoach(
    private val engine: LlmEngine,
    private val retriever: Retriever = SafetyCorpus.retriever,
    private val budget: PromptBudget = PromptBudget(),
) {

    private companion object {
        const val TAG = "JaagrukLlm"

        /**
         * Output budget per task, in tokens.
         *
         * Sized from the sentence limits in [PromptBuilder] with headroom, and doubled for Hindi
         * because Devanagari costs more tokens per character. Generous rather than tight: hitting the
         * limit truncates mid-sentence, which the guard reports but cannot repair.
         */
        fun outputTokens(kind: AiTaskKind, language: AiLanguage): Int {
            val base = when (kind) {
                AiTaskKind.HAZARD_SUMMARY -> 80
                AiTaskKind.STEP_COACHING -> 200
                AiTaskKind.SAFETY_QUESTION -> 260
                AiTaskKind.SHIFT_BRIEFING -> 300
            }
            return if (language == AiLanguage.HINDI) base * 2 else base
        }
    }

    /** Whether assistance can run for a given app locale. */
    fun capability(localeTag: String): AiCapability {
        val language = AiLanguage.fromTagOrNull(localeTag)
            ?: return AiCapability.LANGUAGE_UNSUPPORTED
        return engine.capability(language)
    }

    val engineState get() = engine.state

    /**
     * Runs one task end to end.
     *
     * [onTokenCount] reports progress without exposing partial text, which is deliberate: unvalidated
     * output has not been through the guard, and showing an invented figure for two seconds before
     * replacing it would defeat the point of having a guard.
     *
     * There is no retry. Decoding is greedy, so a second attempt at the same prompt produces the same
     * tokens; retrying would double the wait on a slow handset and change nothing. A rejection is
     * reported rather than papered over.
     */
    suspend fun run(task: AiTask, onTokenCount: (Int) -> Unit = {}): AiOutcome {
        val capability = engine.capability(task.language)
        if (!capability.canGenerate) {
            return AiOutcome.Unavailable(capability)
        }

        val grounded = when (val retrieval = retriever.retrieveForTask(task)) {
            is RetrievalResult.Insufficient -> {
                Log.i(TAG, "refusing ${task.kind}: ${retrieval.reason}")
                return AiOutcome.NoGrounding(retrieval.reason)
            }
            is RetrievalResult.Grounded -> retrieval
        }

        val prompt = PromptBuilder.build(task, grounded.passages.map { it.passage }, budget)
            ?: return AiOutcome.NoGrounding(InsufficientReason.NO_PASSAGES_IN_SCOPE)

        val generation = engine.generate(
            prompt = prompt.text,
            maxTokens = outputTokens(task.kind, task.language),
            params = SamplingParams.GREEDY,
            onTokenCount = onTokenCount,
        ).getOrElse { error ->
            return AiOutcome.Failed(error.message ?: "generation failed")
        }

        if (generation.reason == StopReason.CANCELLED) return AiOutcome.Failed("generation cancelled")
        return when (val verdict = AnswerGuard.check(generation.text, prompt)) {
            is GuardVerdict.Accepted -> AiOutcome.Answer(
                text = verdict.text,
                citations = verdict.citations,
                passageIds = verdict.passageIds,
                truncated = verdict.truncated || generation.reason == StopReason.TOKEN_LIMIT,
                tokenCount = generation.tokenCount,
                elapsedMs = generation.elapsedMs,
            )
            GuardVerdict.Refused -> AiOutcome.ModelDeclined
            is GuardVerdict.Rejected -> {
                // Logged rather than shown. A rejection is a fact about the model's behaviour that a
                // maintainer needs and a worker cannot act on.
                Log.w(TAG, "guard rejected ${task.kind}: ${verdict.rejection} — ${verdict.detail}")
                AiOutcome.Filtered(verdict.rejection, verdict.detail)
            }
        }
    }

    /** Releases the model. Called when a drill starts and when the process is trimmed. */
    suspend fun release() = engine.unload()

    fun stop() = engine.stop()
}
