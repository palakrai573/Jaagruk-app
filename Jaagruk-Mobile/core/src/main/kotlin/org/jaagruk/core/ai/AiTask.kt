package org.jaagruk.core.ai

import org.jaagruk.core.assessment.AssessmentResult
import org.jaagruk.core.assessment.OutcomeClass
import org.jaagruk.core.assessment.StepResult
import org.jaagruk.core.catalog.ModuleCatalog

/**
 * Resolves a catalog string key to text in one language.
 *
 * `:core` stores keys, never prose — the same contract [org.jaagruk.core.catalog.ModuleCatalog]
 * already works to. Generation needs real words, so the host resolves them from `strings_catalog`
 * and passes them in. Keeping it an interface means the whole task-building path is testable
 * without Android resources.
 */
fun interface StringResolver {
    fun resolve(key: String): String
}

/** What kind of help is being asked for. Each maps to one prompt shape and one guard profile. */
enum class AiTaskKind {
    STEP_COACHING,
    SAFETY_QUESTION,
    SHIFT_BRIEFING,
    HAZARD_SUMMARY,
}

/**
 * Why a step is being coached.
 *
 * Three separate cases because they need three different explanations, and collapsing them is how
 * a coach ends up telling a worker who answered correctly that they were wrong. `HESITATED` is the
 * one no conventional quiz can produce, and it is the reason this platform exists.
 */
enum class CoachingFocus {
    /** Chose a wrong option. Explain why the right one is right. */
    WRONG_ANSWER,

    /** Right answer, past the hesitation threshold. Explain how to recognise it faster. */
    HESITATED,

    /** No answer inside the window. */
    TIMED_OUT,
    ;

    companion object {
        fun of(outcome: OutcomeClass): CoachingFocus? = when (outcome) {
            OutcomeClass.INCORRECT -> WRONG_ANSWER
            OutcomeClass.CORRECT_SLOW -> HESITATED
            OutcomeClass.TIMEOUT -> TIMED_OUT
            OutcomeClass.CORRECT_FAST, OutcomeClass.SKIPPED -> null
        }
    }
}

/**
 * A unit of work for the on-device model.
 *
 * Every field is either a number this app already computed, a label the host resolved from its own
 * resources, or text a human typed. Nothing a model produced can ever appear in a task, so a bad
 * generation cannot influence the next one.
 */
sealed interface AiTask {
    val kind: AiTaskKind
    val language: AiLanguage

    /** The retrieval query this task should be grounded with. */
    fun retrievalQuery(): String

    /** Which passages this task is allowed to be grounded from. */
    fun passageFilter(): PassageFilter

    /**
     * Explains one step of a finished drill.
     *
     * Deliberately carries the outcome and the timings but **not** the score, and there is no field
     * for one. The model is never told whether the worker passed, so it cannot restate a verdict as
     * if it were its own — the verdict is signed into a certificate by deterministic code and is
     * not a thing to be paraphrased.
     */
    class StepCoaching(
        override val language: AiLanguage,
        val moduleId: String,
        val stepId: String,
        val focus: CoachingFocus,
        val promptText: String,
        val chosenLabels: List<String>,
        val correctLabels: List<String>,
        val latencyMs: Long,
        val expertMs: Long,
        val critical: Boolean,
        /**
         * The step's authored wrong answers, used when what the worker actually chose is not
         * recoverable.
         *
         * A finished run is stored as the same payload that syncs to the server, and that payload
         * carries the outcome of each step but not which option was selected — the server has no use
         * for it, and its schema rejects fields it does not expect. Rather than widen the sync contract
         * for a coaching feature, coaching from a stored run explains why the correct action is correct
         * and why the authored distractors are dangerous. The model is never told the worker picked a
         * specific wrong answer when that is not known.
         */
        val distractorLabels: List<String> = emptyList(),
    ) : AiTask {
        override val kind: AiTaskKind get() = AiTaskKind.STEP_COACHING

        init {
            require(moduleId.isNotBlank()) { "moduleId must not be blank" }
            require(stepId.isNotBlank()) { "stepId must not be blank" }
            require(promptText.isNotBlank()) { "promptText must not be blank for $stepId" }
            require(correctLabels.isNotEmpty()) { "correctLabels must not be empty for $stepId" }
            require(latencyMs >= 0) { "latencyMs must be >= 0, got $latencyMs" }
            require(expertMs > 0) { "expertMs must be positive, got $expertMs" }
        }

        /** How far past the expert baseline, as a multiple. Shown to the model for HESITATED. */
        val paceMultiple: Double get() = latencyMs.toDouble() / expertMs.toDouble()

        override fun retrievalQuery(): String =
            (listOf(promptText) + correctLabels).joinToString(" ")

        override fun passageFilter(): PassageFilter =
            PassageFilter(language = language, moduleId = moduleId, stepId = null)
    }

    /** A worker's own question, answered only from the bundled safety text. */
    class SafetyQuestion(
        override val language: AiLanguage,
        val question: String,
        val moduleId: String? = null,
    ) : AiTask {
        override val kind: AiTaskKind get() = AiTaskKind.SAFETY_QUESTION

        init {
            require(question.isNotBlank()) { "question must not be blank" }
            require(question.length <= MAX_QUESTION_CHARS) {
                "question is ${question.length} chars, over the $MAX_QUESTION_CHARS cap"
            }
        }

        override fun retrievalQuery(): String = question

        override fun passageFilter(): PassageFilter =
            PassageFilter(language = language, moduleId = moduleId)

        companion object {
            const val MAX_QUESTION_CHARS: Int = 400
        }
    }

    /**
     * A shift-start toolbox talk, from numbers the app already holds.
     *
     * Low risk by construction: a supervisor reads the output before saying it, so a human is
     * always between the model and the workforce.
     */
    class ShiftBriefing(
        override val language: AiLanguage,
        val facts: SiteBriefingFacts,
    ) : AiTask {
        override val kind: AiTaskKind get() = AiTaskKind.SHIFT_BRIEFING

        override fun retrievalQuery(): String = facts.retrievalQuery()

        override fun passageFilter(): PassageFilter = PassageFilter(language = language)
    }

    /** A one-line supervisor-facing summary of a hazard a worker reported. */
    class HazardSummary(
        override val language: AiLanguage,
        val categoryLabel: String,
        val severityLabel: String,
        val note: String,
        val zoneLabel: String? = null,
    ) : AiTask {
        override val kind: AiTaskKind get() = AiTaskKind.HAZARD_SUMMARY

        init {
            require(categoryLabel.isNotBlank()) { "categoryLabel must not be blank" }
            require(severityLabel.isNotBlank()) { "severityLabel must not be blank" }
            require(note.isNotBlank()) { "note must not be blank" }
        }

        override fun retrievalQuery(): String =
            listOfNotNull(categoryLabel, zoneLabel, note).joinToString(" ")

        override fun passageFilter(): PassageFilter = PassageFilter(language = language)
    }
}

/**
 * The site facts a briefing is built from. Counts and labels only.
 *
 * `statutorilyValidButStale` is here for the same reason it is on the dashboard: it is the cohort a
 * blended compliance number hides, and it is the one a supervisor most needs named out loud at
 * shift start.
 */
class SiteBriefingFacts(
    val siteLabel: String,
    val workersTotal: Int,
    val readyCount: Int,
    val dueCount: Int,
    val staleCount: Int,
    val expiredCount: Int,
    val statutorilyValidButStaleCount: Int,
    val hesitationRiskCount: Int,
    val openHazardCount: Int,
    val openHazardZones: List<String> = emptyList(),
    val mostMissedTopicLabels: List<String> = emptyList(),
) {
    init {
        require(siteLabel.isNotBlank()) { "siteLabel must not be blank" }
        require(workersTotal >= 0) { "workersTotal must be >= 0" }
        listOf(
            "readyCount" to readyCount,
            "dueCount" to dueCount,
            "staleCount" to staleCount,
            "expiredCount" to expiredCount,
            "statutorilyValidButStaleCount" to statutorilyValidButStaleCount,
            "hesitationRiskCount" to hesitationRiskCount,
            "openHazardCount" to openHazardCount,
        ).forEach { (name, value) -> require(value >= 0) { "$name must be >= 0, got $value" } }
    }

    fun retrievalQuery(): String =
        (mostMissedTopicLabels + openHazardZones).joinToString(" ").ifBlank { siteLabel }
}

/**
 * Builds tasks from data the app already computed.
 *
 * Separated from the task classes so the mapping from an [AssessmentResult] to a coaching request
 * is a pure function with its own tests. It is also the only place that decides a step is worth
 * coaching, which keeps that rule in one place rather than in every screen.
 */
object AiTaskFactory {

    /**
     * Coaching for one step of a finished run, or null when the step needs none.
     *
     * Returns null for a step that was answered correctly and quickly, and for a skipped step. A
     * worker who got it right does not need a paragraph explaining that they got it right.
     */
    fun stepCoaching(
        result: AssessmentResult,
        stepId: String,
        language: AiLanguage,
        resolver: StringResolver,
    ): AiTask.StepCoaching? {
        val step = result.steps.firstOrNull { it.stepId == stepId } ?: return null
        return stepCoaching(result.moduleId, step, language, resolver)
    }

    fun stepCoaching(
        moduleId: String,
        step: StepResult,
        language: AiLanguage,
        resolver: StringResolver,
    ): AiTask.StepCoaching? {
        val focus = CoachingFocus.of(step.outcome) ?: return null
        val promptText = resolver.resolve("step_${step.stepId}_prompt")
        if (promptText.isBlank()) return null
        val correct = step.correctOptionIds.map { resolver.resolve("opt_$it") }.filter { it.isNotBlank() }
        if (correct.isEmpty()) return null
        return AiTask.StepCoaching(
            language = language,
            moduleId = moduleId,
            stepId = step.stepId,
            focus = focus,
            promptText = promptText,
            chosenLabels = step.answeredOptionIds
                .map { resolver.resolve("opt_$it") }
                .filter { it.isNotBlank() },
            correctLabels = correct,
            latencyMs = step.latencyMs,
            expertMs = step.expertMs,
            critical = step.critical,
        )
    }

    /**
     * Coaching for a step of a run that was reloaded from storage.
     *
     * The live path above has the full [StepResult] and knows which option the worker picked. This one
     * is for the result screen opened later, where the stored payload carries the outcome but not the
     * selection. The correct action and the authored distractors come from the catalog instead, so the
     * explanation is still specific to the step without asserting a choice that was not recorded.
     *
     * @return null when the outcome needs no coaching or the step is not in the catalog.
     */
    fun stepCoachingFromCatalog(
        moduleId: String,
        stepId: String,
        outcome: OutcomeClass,
        latencyMs: Long,
        expertMs: Long,
        critical: Boolean,
        language: AiLanguage,
        resolver: StringResolver,
    ): AiTask.StepCoaching? {
        val focus = CoachingFocus.of(outcome) ?: return null
        val spec = ModuleCatalog.allSteps().firstOrNull { it.stepId == stepId } ?: return null
        val promptText = resolver.resolve(spec.promptKey)
        if (promptText.isBlank() || promptText == spec.promptKey) return null

        val correct = spec.correctOptionIds
            .mapNotNull { id -> spec.option(id)?.labelKey }
            .map { resolver.resolve(it) }
            .filter { it.isNotBlank() }
        if (correct.isEmpty()) return null

        val distractors = spec.options
            .filter { it.isDistractor }
            .map { resolver.resolve(it.labelKey) }
            .filter { it.isNotBlank() }

        return AiTask.StepCoaching(
            language = language,
            moduleId = moduleId,
            stepId = stepId,
            focus = focus,
            promptText = promptText,
            chosenLabels = emptyList(),
            correctLabels = correct,
            latencyMs = latencyMs.coerceAtLeast(0L),
            expertMs = expertMs.coerceAtLeast(1L),
            critical = critical,
            distractorLabels = distractors,
        )
    }

    /** Every step of a run worth coaching, in the order they were presented. */
    fun coachableSteps(
        result: AssessmentResult,
        language: AiLanguage,
        resolver: StringResolver,
    ): List<AiTask.StepCoaching> =
        result.steps
            .sortedBy { it.stepIndex }
            .mapNotNull { stepCoaching(result.moduleId, it, language, resolver) }
}
