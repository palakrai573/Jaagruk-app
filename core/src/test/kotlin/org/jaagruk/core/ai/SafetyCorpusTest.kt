package org.jaagruk.core.ai

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jaagruk.core.catalog.ModuleCatalog
import org.junit.jupiter.api.Test

/**
 * Guards the bundled safety content.
 *
 * The corpus is the half of this feature that decides whether answers are trustworthy, and unlike
 * the code it cannot be checked by reading a type signature. These tests are what stop a passage
 * pointing at a step that does not exist, existing in only one language, or spelling a figure out in
 * words so that [AnswerGuard] rejects every answer quoting it.
 */
class SafetyCorpusTest {

    private val corpus = SafetyCorpus.corpus

    // -----------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------

    @Test
    fun `the corpus loads and validates`() {
        assertThat(corpus.size()).isGreaterThan(50)
        assertThat(corpus.languages()).containsExactly(AiLanguage.ENGLISH, AiLanguage.HINDI)
    }

    @Test
    fun `every passage exists in both languages`() {
        val english = corpus.forLanguage(AiLanguage.ENGLISH)
            .map { it.passageId.removeSuffix("-en") }
            .toSet()
        val hindi = corpus.forLanguage(AiLanguage.HINDI)
            .map { it.passageId.removeSuffix("-hi") }
            .toSet()
        assertThat(hindi).containsExactlyElementsIn(english)
    }

    @Test
    fun `the two languages carry the same number of passages`() {
        assertThat(corpus.forLanguage(AiLanguage.HINDI))
            .hasSize(corpus.forLanguage(AiLanguage.ENGLISH).size)
    }

    @Test
    fun `passage ids all carry a language suffix`() {
        assertThat(corpus.passages.all { it.passageId.endsWith("-en") || it.passageId.endsWith("-hi") })
            .isTrue()
    }

    // -----------------------------------------------------------------------
    // Agreement with the catalog
    // -----------------------------------------------------------------------

    @Test
    fun `every step a passage claims to explain actually exists in the catalog`() {
        // A typo here is invisible at runtime: the passage simply never gets retrieved for the step
        // it was written for, and the worker gets a generic answer instead of the authored one.
        val catalogSteps = ModuleCatalog.allSteps().map { it.stepId }.toSet()
        val unknown = corpus.coveredStepIds() - catalogSteps
        assertThat(unknown).isEmpty()
    }

    @Test
    fun `every module a passage is attributed to actually exists`() {
        val moduleIds = ModuleCatalog.all.map { it.moduleId }.toSet()
        assertThat(corpus.coveredModuleIds() - moduleIds).isEmpty()
    }

    @Test
    fun `every module has passages in both languages`() {
        for (module in ModuleCatalog.all) {
            for (language in AiLanguage.entries) {
                val forModule = corpus.forLanguage(language).filter { it.moduleId == module.moduleId }
                assertThat(forModule).isNotEmpty()
            }
        }
    }

    @Test
    fun `every module has a statute passage so an answer can cite the law`() {
        for (module in ModuleCatalog.all) {
            val statutes = corpus.forLanguage(AiLanguage.ENGLISH)
                .filter { it.moduleId == module.moduleId && it.scope == PassageScope.STATUTE }
            assertThat(statutes).isNotEmpty()
        }
    }

    @Test
    fun `the statutory reference on a passage matches the module it belongs to`() {
        for (passage in corpus.passages) {
            val moduleId = passage.moduleId ?: continue
            val reference = passage.statutoryReference ?: continue
            val module = ModuleCatalog.byId(moduleId)!!
            assertThat(reference).isEqualTo(module.statutoryReference)
        }
    }

    @Test
    fun `at least two thirds of catalog steps have an authored explanation`() {
        val catalogSteps = ModuleCatalog.allSteps().map { it.stepId }.toSet()
        val covered = corpus.coveredStepIds().intersect(catalogSteps)
        val ratio = covered.size.toDouble() / catalogSteps.size
        // Not 100 %: some steps are variations sharing one rule, and a passage per step would mean
        // near-duplicate sources competing for the same retrieval slot. Coverage below two thirds
        // would mean the coaching feature is mostly falling back to generic module text.
        assertThat(ratio).isAtLeast(0.66)
    }

    @Test
    fun `the highest weighted rule in the catalog has its own passage`() {
        // "Never enter to rescue without breathing apparatus" carries the heaviest weight in the
        // catalog. If anything is authored, it is this.
        assertThat(corpus.coveredStepIds()).contains("gas_rescue_decision")
        val passages = SafetyCorpus.retriever.passagesForStep("gas_rescue_decision", AiLanguage.HINDI)
        assertThat(passages).isNotEmpty()
    }

    // -----------------------------------------------------------------------
    // Authoring rules that AnswerGuard depends on
    // -----------------------------------------------------------------------

    @Test
    fun `an english passage and its hindi pair state the same figures`() {
        // AnswerGuard compares numeric tokens against the prompt. If a figure appears as digits in
        // one language and as words in the other, an answer correct in one language is rejected in
        // the other. Devanagari digits fold to ASCII, so both forms are comparable here.
        val byBase = corpus.passages.groupBy { it.passageId.substringBeforeLast('-') }
        for ((base, pair) in byBase) {
            if (pair.size != 2) continue
            val english = pair.first { it.language == AiLanguage.ENGLISH }
            val hindi = pair.first { it.language == AiLanguage.HINDI }
            val englishNumbers = AnswerGuard.numbersIn("${english.title} ${english.body}")
            val hindiNumbers = AnswerGuard.numbersIn("${hindi.title} ${hindi.body}")
            assertWithMessage("figures must agree between the language pair of $base")
                .that(hindiNumbers)
                .containsExactlyElementsIn(englishNumbers)
        }
    }

    @Test
    fun `the methane action levels are the DGMS figures`() {
        val passage = corpus.byId("gas-methane-levels-en")!!
        val numbers = AnswerGuard.numbersIn(passage.body)
        assertThat(numbers).contains("1.25")
        assertThat(numbers).contains("2")
    }

    @Test
    fun `the oxygen deficiency figure is present in both languages`() {
        for (id in listOf("gas-oxygen-deficiency-en", "gas-oxygen-deficiency-hi")) {
            assertThat(AnswerGuard.numbersIn(corpus.byId(id)!!.body)).contains("19.5")
        }
    }

    @Test
    fun `the burn cooling time is present in both languages`() {
        for (id in listOf("burn-first-aid-en", "burn-first-aid-hi")) {
            assertThat(AnswerGuard.numbersIn(corpus.byId(id)!!.body)).contains("20")
        }
    }

    @Test
    fun `no passage body still contains an unflattened newline`() {
        // Bodies are flattened at construction so a source block cannot introduce line structure the
        // model reads as a new instruction.
        assertThat(corpus.passages.none { it.body.contains('\n') }).isTrue()
    }

    @Test
    fun `no passage contains a chat control token`() {
        assertThat(
            corpus.passages.none {
                it.body.contains("<start_of_turn>") || it.body.contains("<end_of_turn>")
            },
        ).isTrue()
    }

    @Test
    fun `no passage contains ol chiki`() {
        assertThat(corpus.passages.none { p -> p.body.any { it in '\u1C50'..'\u1C7F' } }).isTrue()
    }

    @Test
    fun `every passage cites a source a worker could look up`() {
        assertThat(corpus.passages.all { it.sourceLabel.length >= 10 }).isTrue()
    }

    // -----------------------------------------------------------------------
    // Retrieval actually works against real content
    // -----------------------------------------------------------------------

    @Test
    fun `a real english question about methane retrieves the methane passage`() {
        val result = SafetyCorpus.retriever.retrieve(
            "what methane level means we must leave the area",
            PassageFilter(AiLanguage.ENGLISH),
        )
        val grounded = result as RetrievalResult.Grounded
        assertThat(grounded.passageIds).contains("gas-methane-levels-en")
    }

    @Test
    fun `a real hindi question about methane retrieves the hindi methane passage`() {
        val result = SafetyCorpus.retriever.retrieve(
            "मेथेन कितने प्रतिशत पर बाहर निकलना है",
            PassageFilter(AiLanguage.HINDI),
        )
        val grounded = result as RetrievalResult.Grounded
        assertThat(grounded.passageIds).contains("gas-methane-levels-hi")
    }

    @Test
    fun `a real question about rescuing a collapsed colleague retrieves the do-not-enter rule`() {
        val result = SafetyCorpus.retriever.retrieve(
            "my buddy collapsed inside the tank should I go in and pull him out",
            PassageFilter(AiLanguage.ENGLISH),
        )
        val grounded = result as RetrievalResult.Grounded
        assertThat(grounded.passageIds).contains("gas-never-enter-to-rescue-en")
    }

    @Test
    fun `a real question about extinguishers retrieves the extinguisher passage`() {
        val result = SafetyCorpus.retriever.retrieve(
            "can I use water on a burning electrical panel",
            PassageFilter(AiLanguage.ENGLISH),
        )
        val grounded = result as RetrievalResult.Grounded
        assertThat(grounded.passageIds).contains("fire-extinguisher-class-en")
    }

    @Test
    fun `a real question about ladders retrieves the ladder passage`() {
        val result = SafetyCorpus.retriever.retrieve(
            "what angle should a ladder be set at",
            PassageFilter(AiLanguage.ENGLISH),
        )
        val grounded = result as RetrievalResult.Grounded
        assertThat(grounded.passageIds).contains("ladder-angle-en")
    }

    @Test
    fun `an off-topic question is refused against the real corpus`() {
        // The corpus is about industrial safety. Anything else must be refused rather than answered
        // from whatever happens to share a word.
        val result = SafetyCorpus.retriever.retrieve(
            "how many days of casual leave am I entitled to this year",
            PassageFilter(AiLanguage.ENGLISH),
        )
        assertThat(result).isInstanceOf(RetrievalResult.Insufficient::class.java)
    }

    @Test
    fun `a prompt can be built for every module in both languages`() {
        for (module in ModuleCatalog.all) {
            for (language in AiLanguage.entries) {
                val statute = corpus.forLanguage(language)
                    .first { it.moduleId == module.moduleId }
                val built = PromptBuilder.build(
                    AiTask.SafetyQuestion(language, "what does the law require here"),
                    listOf(statute),
                )
                assertThat(built).isNotNull()
                assertThat(built!!.text).contains(statute.title)
            }
        }
    }

    @Test
    fun `every passage fits the prompt budget on its own in its own language`() {
        val budget = PromptBudget()
        for (passage in corpus.passages) {
            val built = PromptBuilder.build(
                AiTask.SafetyQuestion(passage.language, "question"),
                listOf(passage),
                budget,
            )!!
            assertThat(built.droppedPassageIds).isEmpty()
            assertThat(built.text.length).isAtMost(budget.maxPromptChars(passage.language) + 2_000)
        }
    }
}

/**
 * Task dispatch in [Retriever.retrieveForTask].
 *
 * Separate from [SafetyCorpusTest] only for readability; both run against the real bundled corpus,
 * because the behaviour worth pinning here is "does grounding actually turn up for the four real
 * tasks", and a synthetic corpus would not answer that.
 */
class RetrieveForTaskTest {

    private val retriever = SafetyCorpus.retriever

    private fun facts(zones: List<String> = emptyList(), missed: List<String> = emptyList()) =
        SiteBriefingFacts(
            siteLabel = "JH-DHN-001",
            workersTotal = 80,
            readyCount = 41,
            dueCount = 17,
            staleCount = 14,
            expiredCount = 8,
            statutorilyValidButStaleCount = 22,
            hesitationRiskCount = 9,
            openHazardCount = 5,
            openHazardZones = zones,
            mostMissedTopicLabels = missed,
        )

    // -----------------------------------------------------------------------
    // A question is the one case that refuses
    // -----------------------------------------------------------------------

    @Test
    fun `a question outside the corpus is refused`() {
        val result = retriever.retrieveForTask(
            AiTask.SafetyQuestion(AiLanguage.ENGLISH, "how do I claim my provident fund"),
        )
        assertThat(result).isInstanceOf(RetrievalResult.Insufficient::class.java)
    }

    @Test
    fun `a question inside the corpus is grounded`() {
        val result = retriever.retrieveForTask(
            AiTask.SafetyQuestion(AiLanguage.ENGLISH, "what methane level means we must leave"),
        )
        assertThat(result).isInstanceOf(RetrievalResult.Grounded::class.java)
    }

    // -----------------------------------------------------------------------
    // A briefing always has something legitimate to say
    // -----------------------------------------------------------------------

    @Test
    fun `a briefing with no recognisable topics still grounds on general practice`() {
        // The site label and a zone name are not safety topics, so nothing matches by term. Refusing
        // to write a briefing on that basis would be wrong: the supervisor's own numbers are the
        // content, and general practice is what the reminder references.
        val result = retriever.retrieveForTask(
            AiTask.ShiftBriefing(AiLanguage.ENGLISH, facts(zones = listOf("Crusher house"))),
        )
        val grounded = result as RetrievalResult.Grounded
        assertThat(grounded.passages.all { it.passage.scope == PassageScope.GENERAL }).isTrue()
    }

    @Test
    fun `a briefing naming a real topic grounds on that topic`() {
        val result = retriever.retrieveForTask(
            AiTask.ShiftBriefing(
                AiLanguage.ENGLISH,
                facts(missed = listOf("confined space rescue breathing apparatus")),
            ),
        )
        val grounded = result as RetrievalResult.Grounded
        assertThat(grounded.passageIds).contains("gas-never-enter-to-rescue-en")
    }

    @Test
    fun `a hindi briefing falls back to hindi general passages`() {
        val result = retriever.retrieveForTask(
            AiTask.ShiftBriefing(AiLanguage.HINDI, facts(zones = listOf("Crusher house"))),
        )
        val grounded = result as RetrievalResult.Grounded
        assertThat(grounded.passages.all { it.passage.language == AiLanguage.HINDI }).isTrue()
    }

    // -----------------------------------------------------------------------
    // A hazard summary is never refused
    // -----------------------------------------------------------------------

    @Test
    fun `a hazard summary about a recognisable hazard grounds on it`() {
        val result = retriever.retrieveForTask(
            AiTask.HazardSummary(
                language = AiLanguage.ENGLISH,
                categoryLabel = "Damaged cable",
                severityLabel = "High",
                note = "Cable insulation is split and the conductor is exposed.",
                zoneLabel = "Crusher house",
            ),
        )
        val grounded = result as RetrievalResult.Grounded
        assertThat(grounded.passageIds).contains("elec-damaged-cable-en")
    }

    @Test
    fun `a hazard summary about something the corpus does not cover still grounds`() {
        val result = retriever.retrieveForTask(
            AiTask.HazardSummary(
                language = AiLanguage.ENGLISH,
                categoryLabel = "Other",
                severityLabel = "Low",
                note = "The canteen roof sheet is loose.",
            ),
        )
        assertThat(result).isInstanceOf(RetrievalResult.Grounded::class.java)
    }

    // -----------------------------------------------------------------------
    // Coaching
    // -----------------------------------------------------------------------

    @Test
    fun `coaching dispatches to the identity-anchored path`() {
        val task = AiTask.StepCoaching(
            language = AiLanguage.ENGLISH,
            moduleId = "gas-confined-space",
            stepId = "gas_rescue_decision",
            focus = CoachingFocus.WRONG_ANSWER,
            promptText = "Your buddy has collapsed inside the tank.",
            chosenLabels = listOf("Go in and pull them out"),
            correctLabels = listOf("Do not enter, raise the alarm"),
            latencyMs = 7_000,
            expertMs = 2_500,
            critical = true,
        )
        val grounded = retriever.retrieveForTask(task) as RetrievalResult.Grounded
        assertThat(grounded.passageIds.first()).isEqualTo("gas-never-enter-to-rescue-en")
    }

    // -----------------------------------------------------------------------
    // The fallback widens reference, not assertion
    // -----------------------------------------------------------------------

    @Test
    fun `a fallback-grounded prompt still has its numbers checked`() {
        val task = AiTask.ShiftBriefing(AiLanguage.ENGLISH, facts(zones = listOf("Crusher house")))
        val grounded = retriever.retrieveForTask(task) as RetrievalResult.Grounded
        val prompt = PromptBuilder.build(task, grounded.passages.map { it.passage })!!
        // A figure that is in neither the general passages nor the supplied facts is still rejected.
        val verdict = AnswerGuard.check("137 workers are overdue.", prompt)
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.UNGROUNDED_NUMBER)
    }
}
