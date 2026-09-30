package org.jaagruk.core.ai

/**
 * What a passage is about, which decides how it can be retrieved.
 *
 * Scope is a retrieval filter, not a ranking hint. When a worker asks about a specific step of a
 * specific module, searching that module's passages first is the difference between a useful answer
 * and a confidently wrong one about a different hazard.
 */
enum class PassageScope {
    /** Explains one authored drill step directly. The narrowest and most useful scope. */
    STEP,

    /** Applies to a whole safety domain. */
    MODULE,

    /** A statutory rule: Mines Act, Factories Act, Mines Rules, DGMS circular. */
    STATUTE,

    /** Cross-cutting practice that belongs to no single module. */
    GENERAL,
}

/**
 * One retrievable, citable unit of safety text.
 *
 * A passage is the only thing a generated answer is ever allowed to be built from. That is the
 * whole design: the model supplies phrasing, the corpus supplies facts. Small models are not
 * reliable sources of numbers — a hallucinated methane threshold is a fatality, not a typo — and
 * published benchmarking on sub-1B models shows accuracy collapsing without retrieval and
 * recovering sharply with it.
 *
 * @param passageId stable identifier, cited in the UI and asserted unique by [Corpus]
 * @param title short heading, weighted higher than the body during retrieval
 * @param body the text the model is shown; must contain any figure the answer may quote
 * @param language passages are indexed per language; cross-language BM25 is meaningless
 * @param moduleId owning module, or null when the passage applies everywhere
 * @param stepIds authored step ids this passage explains; empty when not step-specific
 * @param statutoryReference the legal hook, when there is one
 * @param sourceLabel what the worker sees as the citation. Never blank: an answer with no visible
 *   source is an assertion, and the point of grounding is that the worker can check it.
 */
class CorpusPassage(
    val passageId: String,
    val title: String,
    val body: String,
    val language: AiLanguage,
    val scope: PassageScope,
    val sourceLabel: String,
    val moduleId: String? = null,
    val stepIds: Set<String> = emptySet(),
    val statutoryReference: String? = null,
) {
    init {
        require(passageId.isNotBlank()) { "passageId must not be blank" }
        require(title.isNotBlank()) { "title must not be blank for $passageId" }
        require(body.isNotBlank()) { "body must not be blank for $passageId" }
        require(sourceLabel.isNotBlank()) {
            "sourceLabel must not be blank for $passageId: an ungrounded citation is not a citation"
        }
        require(body.length <= MAX_BODY_CHARS) {
            "body for $passageId is ${body.length} chars, over the $MAX_BODY_CHARS cap; " +
                "split it so retrieval can return the relevant half instead of both"
        }
        require(scope != PassageScope.STEP || stepIds.isNotEmpty()) {
            "$passageId is scoped STEP but names no stepIds"
        }
        require(scope != PassageScope.MODULE || moduleId != null) {
            "$passageId is scoped MODULE but names no moduleId"
        }
        require(stepIds.none { it.isBlank() }) { "$passageId has a blank stepId" }
    }

    /** The text handed to retrieval. The title is repeated so a title hit outranks a body hit. */
    val indexedText: String get() = "$title $title $body"

    override fun toString(): String =
        "CorpusPassage($passageId, ${language.tag}, $scope, ${body.length} chars)"

    companion object {
        /**
         * Passage length cap.
         *
         * Chosen against the context budget rather than for tidiness: four passages plus the
         * instruction block plus the question has to fit a 2048-token window with room for the
         * answer, and an overlong passage would silently push an earlier one out of context — the
         * failure mode being an answer grounded in text the caller believes it was not given.
         */
        const val MAX_BODY_CHARS: Int = 1_200
    }
}

/**
 * The bundled body of safety text, validated once at construction.
 *
 * Compiled into `:core` for the same reason [org.jaagruk.core.catalog.ModuleCatalog] is: it has to
 * work on a handset that has never had signal, and it has to be reviewable in a diff by somebody
 * who signs off on safety content.
 */
class Corpus(passages: List<CorpusPassage>) {

    val passages: List<CorpusPassage> = passages.toList()

    private val byId: Map<String, CorpusPassage>
    private val byLanguage: Map<AiLanguage, List<CorpusPassage>>

    init {
        require(this.passages.isNotEmpty()) { "corpus must not be empty" }
        val ids = this.passages.map { it.passageId }
        require(ids.distinct().size == ids.size) {
            "duplicate passageIds: " + ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        }
        byId = this.passages.associateBy { it.passageId }
        byLanguage = this.passages.groupBy { it.language }
    }

    fun byId(passageId: String): CorpusPassage? = byId[passageId]

    fun forLanguage(language: AiLanguage): List<CorpusPassage> = byLanguage[language].orEmpty()

    fun languages(): Set<AiLanguage> = byLanguage.keys

    /** Every step id any passage claims to explain. Asserted against the catalog by a test. */
    fun coveredStepIds(): Set<String> = passages.flatMapTo(mutableSetOf()) { it.stepIds }

    /** Every module id any passage is attributed to. */
    fun coveredModuleIds(): Set<String> = passages.mapNotNullTo(mutableSetOf()) { it.moduleId }

    fun size(): Int = passages.size

    override fun toString(): String =
        "Corpus(${passages.size} passages, languages=${byLanguage.keys.map { it.tag }})"
}

/**
 * Which passages a query is allowed to see.
 *
 * Narrowing is a correctness measure. A question asked from inside the confined-space module must
 * not be answered out of the fire module's text, and a coaching explanation for one step must not
 * quote a different step's rule.
 */
class PassageFilter(
    val language: AiLanguage,
    val moduleId: String? = null,
    val stepId: String? = null,
    val scopes: Set<PassageScope> = PassageScope.entries.toSet(),
    /**
     * When true, a module-scoped query still sees STATUTE and GENERAL passages.
     *
     * On by default: statutory rules and cross-cutting practice are usually exactly what a
     * "why" question needs, and excluding them makes answers narrower than the truth.
     */
    val includeUnscoped: Boolean = true,
) {
    init {
        require(scopes.isNotEmpty()) { "at least one PassageScope must be allowed" }
    }

    fun allows(passage: CorpusPassage): Boolean {
        if (passage.language != language) return false
        if (passage.scope !in scopes) return false

        if (stepId != null && passage.scope == PassageScope.STEP) {
            return stepId in passage.stepIds
        }
        if (moduleId != null && passage.moduleId != null && passage.moduleId != moduleId) {
            return false
        }
        if (moduleId != null && passage.moduleId == null && !includeUnscoped) {
            return false
        }
        // A step-scoped passage for a different step is never relevant, even inside the module.
        if (stepId == null && passage.scope == PassageScope.STEP && moduleId != null) {
            return passage.moduleId == moduleId
        }
        return true
    }

    override fun toString(): String =
        "PassageFilter(${language.tag}, module=$moduleId, step=$stepId, scopes=${scopes.size})"
}
