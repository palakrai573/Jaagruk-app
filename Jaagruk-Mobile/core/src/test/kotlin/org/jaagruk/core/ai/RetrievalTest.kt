package org.jaagruk.core.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RetrievalTest {

    private fun passage(
        id: String,
        title: String,
        body: String,
        scope: PassageScope = PassageScope.GENERAL,
        moduleId: String? = null,
        steps: Set<String> = emptySet(),
        language: AiLanguage = AiLanguage.ENGLISH,
    ) = CorpusPassage(
        passageId = id,
        title = title,
        body = body,
        language = language,
        scope = scope,
        sourceLabel = "test source $id",
        moduleId = moduleId,
        stepIds = steps,
    )

    private val methane = passage(
        id = "methane",
        title = "Methane action levels",
        body = "Withdraw persons at 1.25 percent methane. Nobody is permitted at 2 percent.",
        scope = PassageScope.STEP,
        moduleId = "gas-confined-space",
        steps = setOf("gas_recognise_zone"),
    )
    private val oxygen = passage(
        id = "oxygen",
        title = "Oxygen deficiency",
        body = "Below 19.5 percent oxygen the atmosphere is immediately dangerous to life.",
        scope = PassageScope.MODULE,
        moduleId = "gas-confined-space",
    )
    private val extinguisher = passage(
        id = "extinguisher",
        title = "Extinguisher selection",
        body = "Carbon dioxide suits electrical fires. Water must never go on an electrical fire.",
        scope = PassageScope.STEP,
        moduleId = "fire-evacuation",
        steps = setOf("fire_pick_extinguisher"),
    )
    private val ladder = passage(
        id = "ladder",
        title = "Ladder pitch",
        body = "A portable ladder stands at 75 degrees, one unit out for every 4 units of height.",
        scope = PassageScope.GENERAL,
    )

    private val corpus = Corpus(listOf(methane, oxygen, extinguisher, ladder))
    private val retriever = Retriever(corpus)

    private fun filter(
        moduleId: String? = null,
        stepId: String? = null,
        language: AiLanguage = AiLanguage.ENGLISH,
    ) = PassageFilter(language = language, moduleId = moduleId, stepId = stepId)

    // -----------------------------------------------------------------------
    // Ranking
    // -----------------------------------------------------------------------

    @Test
    fun `the passage about the subject ranks first`() {
        val result = retriever.retrieve("what is the methane withdrawal level", filter())
        assertThat(result).isInstanceOf(RetrievalResult.Grounded::class.java)
        assertThat((result as RetrievalResult.Grounded).passageIds.first()).isEqualTo("methane")
    }

    @Test
    fun `a title match outranks a body-only match`() {
        // "oxygen" is in the oxygen passage title and the methane passage does not mention it.
        val result = retriever.retrieve("oxygen", filter()) as RetrievalResult.Grounded
        assertThat(result.passageIds.first()).isEqualTo("oxygen")
    }

    @Test
    fun `ranking is deterministic across repeated calls`() {
        val first = retriever.retrieve("electrical fire water", filter()) as RetrievalResult.Grounded
        repeat(5) {
            val again = retriever.retrieve("electrical fire water", filter()) as RetrievalResult.Grounded
            assertThat(again.passageIds).isEqualTo(first.passageIds)
        }
    }

    @Test
    fun `a numeric query finds the passage carrying that figure`() {
        val result = retriever.retrieve("1.25", filter()) as RetrievalResult.Grounded
        assertThat(result.passageIds).contains("methane")
    }

    // -----------------------------------------------------------------------
    // The relevance floor — the safety-critical behaviour
    // -----------------------------------------------------------------------

    @Test
    fun `a question the corpus does not cover is refused rather than answered`() {
        val result = retriever.retrieve("how do I claim my provident fund", filter())
        assertThat(result).isInstanceOf(RetrievalResult.Insufficient::class.java)
    }

    @Test
    fun `a query of only stopwords is refused`() {
        val result = retriever.retrieve("is it the and for", filter())
        assertThat((result as RetrievalResult.Insufficient).reason)
            .isEqualTo(InsufficientReason.NO_QUERY_TERMS)
    }

    @Test
    fun `a query sharing no term with any passage reports no match`() {
        val result = retriever.retrieve("provident fund pension arrears", filter())
        assertThat((result as RetrievalResult.Insufficient).reason)
            .isEqualTo(InsufficientReason.NO_MATCH)
    }

    @Test
    fun `a query that brushes one term of a long question falls below the floor`() {
        // One matching term ("methane") out of eight distinct terms is a ratio well under 0.34.
        val result = retriever.retrieve(
            "methane bicycle registration paperwork canteen subsidy transfer allowance housing",
            filter(),
        )
        val insufficient = result as RetrievalResult.Insufficient
        assertThat(insufficient.reason).isEqualTo(InsufficientReason.BELOW_RELEVANCE_FLOOR)
        assertThat(insufficient.bestRatio).isLessThan(0.34)
    }

    @Test
    fun `a weak passage riding along on a strong one is dropped`() {
        val relaxed = Retriever(corpus, RetrievalConfig(maxPassages = 4, minMatchedTermRatio = 0.5))
        val result = relaxed.retrieve("methane withdrawal 1.25", filter()) as RetrievalResult.Grounded
        assertThat(result.passages.all { it.matchedTermRatio >= 0.5 }).isTrue()
    }

    @Test
    fun `the floor is configurable and rejecting everything is possible`() {
        val strict = Retriever(corpus, RetrievalConfig(minMatchedTermRatio = 1.0))
        val result = strict.retrieve("methane oxygen ladder extinguisher", filter())
        assertThat(result).isInstanceOf(RetrievalResult.Insufficient::class.java)
    }

    // -----------------------------------------------------------------------
    // Scoping
    // -----------------------------------------------------------------------

    @Test
    fun `a module-scoped query never returns another module's step passage`() {
        val result = retriever.retrieve(
            "fire extinguisher water electrical",
            filter(moduleId = "gas-confined-space"),
        )
        if (result is RetrievalResult.Grounded) {
            assertThat(result.passageIds).doesNotContain("extinguisher")
        }
    }

    @Test
    fun `a module-scoped query still sees unscoped statute and general passages`() {
        val result = retriever.retrieve(
            "ladder 75 degrees pitch",
            filter(moduleId = "gas-confined-space"),
        ) as RetrievalResult.Grounded
        assertThat(result.passageIds).contains("ladder")
    }

    @Test
    fun `includeUnscoped false hides general passages from a module query`() {
        val narrow = PassageFilter(
            language = AiLanguage.ENGLISH,
            moduleId = "gas-confined-space",
            includeUnscoped = false,
        )
        val result = retriever.retrieve("ladder 75 degrees pitch", narrow)
        assertThat(result).isInstanceOf(RetrievalResult.Insufficient::class.java)
    }

    @Test
    fun `a step-scoped filter only admits that step's passage`() {
        val result = retriever.retrieve(
            "methane 1.25 withdraw",
            filter(stepId = "gas_recognise_zone"),
        ) as RetrievalResult.Grounded
        assertThat(result.passageIds).contains("methane")

        val other = retriever.retrieve(
            "methane 1.25 withdraw",
            filter(stepId = "fire_pick_extinguisher"),
        )
        if (other is RetrievalResult.Grounded) {
            assertThat(other.passageIds).doesNotContain("methane")
        }
    }

    @Test
    fun `a language with no passages is reported as out of scope`() {
        val result = retriever.retrieve("मेथेन", filter(language = AiLanguage.HINDI))
        assertThat((result as RetrievalResult.Insufficient).reason)
            .isEqualTo(InsufficientReason.NO_PASSAGES_IN_SCOPE)
    }

    @Test
    fun `retrieval never crosses languages`() {
        val bilingual = Corpus(
            listOf(
                methane,
                passage(
                    id = "methane-hi",
                    title = "मेथेन के स्तर",
                    body = "1.25 प्रतिशत पर लोगों को बाहर निकाला जाता है।",
                    language = AiLanguage.HINDI,
                ),
            ),
        )
        val both = Retriever(bilingual)
        val hindi = both.retrieve("मेथेन स्तर", filter(language = AiLanguage.HINDI))
            as RetrievalResult.Grounded
        assertThat(hindi.passages.all { it.passage.language == AiLanguage.HINDI }).isTrue()

        val english = both.retrieve("methane level", filter()) as RetrievalResult.Grounded
        assertThat(english.passages.all { it.passage.language == AiLanguage.ENGLISH }).isTrue()
    }

    // -----------------------------------------------------------------------
    // Coaching path
    // -----------------------------------------------------------------------

    private fun coaching(stepId: String, moduleId: String) = AiTask.StepCoaching(
        language = AiLanguage.ENGLISH,
        moduleId = moduleId,
        stepId = stepId,
        focus = CoachingFocus.WRONG_ANSWER,
        promptText = "Which extinguisher for a motor control panel?",
        chosenLabels = listOf("Water extinguisher"),
        correctLabels = listOf("Carbon dioxide extinguisher"),
        latencyMs = 4_000,
        expertMs = 2_000,
        critical = true,
    )

    @Test
    fun `coaching always leads with the passage authored for that step`() {
        val result = retriever.retrieveForCoaching(
            coaching("fire_pick_extinguisher", "fire-evacuation"),
        ) as RetrievalResult.Grounded
        assertThat(result.passageIds.first()).isEqualTo("extinguisher")
    }

    @Test
    fun `a step passage is admitted even when the wording shares few terms`() {
        // Identity beats term overlap: the step passage is selected by id, so the floor cannot
        // exclude it however the step happens to be worded.
        val task = AiTask.StepCoaching(
            language = AiLanguage.ENGLISH,
            moduleId = "fire-evacuation",
            stepId = "fire_pick_extinguisher",
            focus = CoachingFocus.WRONG_ANSWER,
            promptText = "Pick one",
            chosenLabels = listOf("that one"),
            correctLabels = listOf("the other"),
            latencyMs = 1_000,
            expertMs = 1_000,
            critical = false,
        )
        val result = retriever.retrieveForCoaching(task) as RetrievalResult.Grounded
        assertThat(result.passageIds).containsExactly("extinguisher")
    }

    @Test
    fun `coaching a step with no authored passage falls back to term matching`() {
        val result = retriever.retrieveForCoaching(
            coaching("gas_ppe_select", "gas-confined-space"),
        )
        // No passage names gas_ppe_select, and the wording is about extinguishers, which is another
        // module. Refusing is correct.
        assertThat(result).isInstanceOf(RetrievalResult.Insufficient::class.java)
    }

    @Test
    fun `coaching never exceeds the passage budget`() {
        val capped = Retriever(corpus, RetrievalConfig(maxPassages = 1))
        val result = capped.retrieveForCoaching(
            coaching("fire_pick_extinguisher", "fire-evacuation"),
        ) as RetrievalResult.Grounded
        assertThat(result.passages).hasSize(1)
    }

    // -----------------------------------------------------------------------
    // Index and configuration invariants
    // -----------------------------------------------------------------------

    @Test
    fun `document frequency is counted only over passages the filter admits`() {
        // With the scope narrowed to one module, a term common outside it must not have its IDF
        // depressed by documents the query was never allowed to see.
        val index = Bm25Index(corpus.forLanguage(AiLanguage.ENGLISH))
        val wide = index.search("percent", 4)
        val narrow = index.search("percent", 4) { it.moduleId == "gas-confined-space" }
        assertThat(narrow.size).isAtMost(wide.size)
        assertThat(narrow.all { it.passage.moduleId == "gas-confined-space" }).isTrue()
    }

    @Test
    fun `an index reports its size`() {
        val index = Bm25Index(corpus.forLanguage(AiLanguage.ENGLISH))
        assertThat(index.documentCount).isEqualTo(4)
        assertThat(index.termCount).isGreaterThan(20)
    }

    @Test
    fun `search rejects a non positive limit`() {
        val index = Bm25Index(corpus.forLanguage(AiLanguage.ENGLISH))
        assertThrows<IllegalArgumentException> { index.search("methane", 0) }
    }

    @Test
    fun `bm25 parameters are validated`() {
        assertThrows<IllegalArgumentException> { Bm25Config(k1 = 0.0) }
        assertThrows<IllegalArgumentException> { Bm25Config(b = 1.5) }
    }

    @Test
    fun `retrieval config is validated`() {
        assertThrows<IllegalArgumentException> { RetrievalConfig(maxPassages = 0) }
        assertThrows<IllegalArgumentException> { RetrievalConfig(minMatchedTermRatio = 1.5) }
    }

    @Test
    fun `matched term ratio is zero when there are no query terms`() {
        val scored = ScoredPassage(methane, 1.0, emptySet(), 0)
        assertThat(scored.matchedTermRatio).isEqualTo(0.0)
    }

    @Test
    fun `grounded results expose citations without duplicates`() {
        val result = retriever.retrieve("methane 1.25 withdraw percent", filter())
            as RetrievalResult.Grounded
        assertThat(result.citations).containsNoDuplicates()
        assertThat(result.citations).isNotEmpty()
    }

    // -----------------------------------------------------------------------
    // Corpus validation
    // -----------------------------------------------------------------------

    @Test
    fun `a corpus rejects duplicate passage ids`() {
        assertThrows<IllegalArgumentException> { Corpus(listOf(methane, methane)) }
    }

    @Test
    fun `a corpus rejects being empty`() {
        assertThrows<IllegalArgumentException> { Corpus(emptyList()) }
    }

    @Test
    fun `a passage rejects a blank source label`() {
        assertThrows<IllegalArgumentException> {
            CorpusPassage(
                passageId = "x",
                title = "t",
                body = "b",
                language = AiLanguage.ENGLISH,
                scope = PassageScope.GENERAL,
                sourceLabel = "  ",
            )
        }
    }

    @Test
    fun `a step scoped passage must name its steps`() {
        assertThrows<IllegalArgumentException> {
            CorpusPassage(
                passageId = "x",
                title = "t",
                body = "b",
                language = AiLanguage.ENGLISH,
                scope = PassageScope.STEP,
                sourceLabel = "s",
            )
        }
    }

    @Test
    fun `a module scoped passage must name its module`() {
        assertThrows<IllegalArgumentException> {
            CorpusPassage(
                passageId = "x",
                title = "t",
                body = "b",
                language = AiLanguage.ENGLISH,
                scope = PassageScope.MODULE,
                sourceLabel = "s",
            )
        }
    }

    @Test
    fun `a passage rejects a body over the context budget`() {
        assertThrows<IllegalArgumentException> {
            CorpusPassage(
                passageId = "x",
                title = "t",
                body = "b".repeat(CorpusPassage.MAX_BODY_CHARS + 1),
                language = AiLanguage.ENGLISH,
                scope = PassageScope.GENERAL,
                sourceLabel = "s",
            )
        }
    }
}
