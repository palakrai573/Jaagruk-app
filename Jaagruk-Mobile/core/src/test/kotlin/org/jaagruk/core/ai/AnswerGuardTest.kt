package org.jaagruk.core.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AnswerGuardTest {

    private fun source(
        id: String,
        title: String,
        body: String,
        language: AiLanguage = AiLanguage.ENGLISH,
    ) = CorpusPassage(
        passageId = id,
        title = title,
        body = body,
        language = language,
        scope = PassageScope.GENERAL,
        sourceLabel = "Mines Act 1952 s.29",
    )

    private val methaneSource = source(
        id = "methane",
        title = "Methane action levels",
        body = "Withdraw persons at 1.25 percent methane. Nobody is permitted at 2 percent. " +
            "Methane collects at the roof.",
    )

    private val hindiSource = source(
        id = "methane-hi",
        title = "मेथेन के कार्रवाई स्तर",
        body = "1.25 प्रतिशत पर लोगों को बाहर निकाला जाता है। 2 प्रतिशत पर किसी को अनुमति नहीं है।",
        language = AiLanguage.HINDI,
    )

    private fun prompt(
        language: AiLanguage = AiLanguage.ENGLISH,
        kind: AiTaskKind = AiTaskKind.SAFETY_QUESTION,
    ): BuiltPrompt {
        val task: AiTask = when (kind) {
            AiTaskKind.SAFETY_QUESTION -> AiTask.SafetyQuestion(
                language = language,
                question = if (language == AiLanguage.HINDI) "मेथेन की सीमा क्या है?" else "What is the methane limit?",
            )
            AiTaskKind.SHIFT_BRIEFING -> AiTask.ShiftBriefing(
                language = language,
                facts = SiteBriefingFacts(
                    siteLabel = "JH-DHN-001",
                    workersTotal = 80,
                    readyCount = 41,
                    dueCount = 17,
                    staleCount = 14,
                    expiredCount = 8,
                    statutorilyValidButStaleCount = 22,
                    hesitationRiskCount = 9,
                    openHazardCount = 5,
                ),
            )
            AiTaskKind.HAZARD_SUMMARY -> AiTask.HazardSummary(
                language = language,
                categoryLabel = "Exposed wiring",
                severityLabel = "High",
                note = "Cable insulation split near the crusher motor.",
            )
            AiTaskKind.STEP_COACHING -> AiTask.StepCoaching(
                language = language,
                moduleId = "gas-confined-space",
                stepId = "gas_recognise_zone",
                focus = CoachingFocus.WRONG_ANSWER,
                promptText = "The methane reading is rising. What now?",
                chosenLabels = listOf("Carry on working"),
                correctLabels = listOf("Withdraw from the area"),
                latencyMs = 6_000,
                expertMs = 2_000,
                critical = true,
            )
        }
        val grounding = if (language == AiLanguage.HINDI) hindiSource else methaneSource
        return PromptBuilder.build(task, listOf(grounding))!!
    }

    // -----------------------------------------------------------------------
    // Accepting good output
    // -----------------------------------------------------------------------

    @Test
    fun `a grounded answer is accepted with its citations`() {
        val verdict = AnswerGuard.check(
            "Withdraw from the area. Methane collects at the roof, so test there.",
            prompt(),
        )
        val accepted = verdict as GuardVerdict.Accepted
        assertThat(accepted.citations).containsExactly("Mines Act 1952 s.29")
        assertThat(accepted.passageIds).containsExactly("methane")
        assertThat(accepted.truncated).isFalse()
    }

    @Test
    fun `a figure that appears in the sources is allowed`() {
        val verdict = AnswerGuard.check("Persons are withdrawn at 1.25 percent.", prompt())
        assertThat(verdict).isInstanceOf(GuardVerdict.Accepted::class.java)
    }

    @Test
    fun `a hindi answer in devanagari is accepted`() {
        val verdict = AnswerGuard.check(
            "1.25 प्रतिशत पर क्षेत्र से बाहर निकलें। मेथेन छत के पास जमा होती है।",
            prompt(AiLanguage.HINDI),
        )
        assertThat(verdict).isInstanceOf(GuardVerdict.Accepted::class.java)
    }

    @Test
    fun `devanagari digits are recognised as the same claim`() {
        // "१.२५" is 1.25 written in Devanagari digits. It is grounded and must be accepted.
        val verdict = AnswerGuard.check("१.२५ प्रतिशत पर बाहर निकलें।", prompt(AiLanguage.HINDI))
        assertThat(verdict).isInstanceOf(GuardVerdict.Accepted::class.java)
    }

    @Test
    fun `technical english inside a hindi answer does not trip drift`() {
        val verdict = AnswerGuard.check(
            "SCBA पहनकर ही अंदर जाएँ। मेथेन छत के पास जमा होती है, इसलिए वहाँ माप लें।",
            prompt(AiLanguage.HINDI),
        )
        assertThat(verdict).isInstanceOf(GuardVerdict.Accepted::class.java)
    }

    // -----------------------------------------------------------------------
    // Refusal is a valid outcome
    // -----------------------------------------------------------------------

    @Test
    fun `the refusal sentinel is reported as a refusal not a rejection`() {
        val verdict = AnswerGuard.check(PromptBuilder.REFUSAL_SENTINEL, prompt())
        assertThat(verdict).isEqualTo(GuardVerdict.Refused)
    }

    @Test
    fun `a refusal wrapped in stray whitespace is still a refusal`() {
        val verdict = AnswerGuard.check("\n  ${PromptBuilder.REFUSAL_SENTINEL}  \n", prompt())
        assertThat(verdict).isEqualTo(GuardVerdict.Refused)
    }

    @Test
    fun `a refusal is not checked for ungrounded numbers`() {
        // The sentinel path returns before the numeric check, so a refusal can never be rejected
        // for content it does not contain.
        val verdict = AnswerGuard.check(
            "${PromptBuilder.REFUSAL_SENTINEL} 999 percent",
            prompt(),
        )
        assertThat(verdict).isEqualTo(GuardVerdict.Refused)
    }

    // -----------------------------------------------------------------------
    // Ungrounded numbers — the most important rejection
    // -----------------------------------------------------------------------

    @Test
    fun `an invented threshold is rejected`() {
        val verdict = AnswerGuard.check("Withdraw at 1.5 percent methane.", prompt())
        val rejected = verdict as GuardVerdict.Rejected
        assertThat(rejected.rejection).isEqualTo(GuardRejection.UNGROUNDED_NUMBER)
        assertThat(rejected.detail).contains("1.5")
    }

    @Test
    fun `an invented figure in a hindi answer is rejected`() {
        val verdict = AnswerGuard.check("2.5 प्रतिशत पर बाहर निकलें।", prompt(AiLanguage.HINDI))
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.UNGROUNDED_NUMBER)
    }

    @Test
    fun `an invented figure written in devanagari digits is still rejected`() {
        val verdict = AnswerGuard.check("१.५ प्रतिशत पर बाहर निकलें।", prompt(AiLanguage.HINDI))
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.UNGROUNDED_NUMBER)
    }

    @Test
    fun `trailing zeros do not make a grounded figure look invented`() {
        val verdict = AnswerGuard.check("Withdraw at 1.250 percent.", prompt())
        assertThat(verdict).isInstanceOf(GuardVerdict.Accepted::class.java)
    }

    @Test
    fun `a numbered list ordinal is not treated as a claim`() {
        val verdict = AnswerGuard.check(
            "1. Withdraw from the area.\n2. Tell the supervisor.\n3. Do not restart equipment.",
            prompt(),
        )
        assertThat(verdict).isInstanceOf(GuardVerdict.Accepted::class.java)
    }

    @Test
    fun `briefing counts from the task facts are permitted`() {
        // These numbers are in the task block, not in a source passage, and the model is expected
        // to echo them. Checking against the whole prompt rather than the sources is what allows it.
        val verdict = AnswerGuard.check(
            "22 workers hold a valid certificate but are operationally stale.",
            prompt(kind = AiTaskKind.SHIFT_BRIEFING),
        )
        assertThat(verdict).isInstanceOf(GuardVerdict.Accepted::class.java)
    }

    @Test
    fun `a briefing figure that is in no fact is rejected`() {
        val verdict = AnswerGuard.check(
            "137 workers are overdue for a refresher.",
            prompt(kind = AiTaskKind.SHIFT_BRIEFING),
        )
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.UNGROUNDED_NUMBER)
    }

    @Test
    fun `numbersIn normalises separators and devanagari digits`() {
        assertThat(AnswerGuard.numbersIn("1,000 and 1.250 and १९.५"))
            .containsExactly("1000", "1.25", "19.5")
    }

    // -----------------------------------------------------------------------
    // Verdict language
    // -----------------------------------------------------------------------

    @Test
    fun `claiming the worker passed is rejected`() {
        val verdict = AnswerGuard.check("Good work, you passed this module.", prompt())
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.VERDICT_LANGUAGE)
    }

    @Test
    fun `quoting a score is rejected`() {
        val verdict = AnswerGuard.check("Your score was high, but be quicker.", prompt())
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.VERDICT_LANGUAGE)
    }

    @Test
    fun `hindi pass and fail words are rejected`() {
        val verdict = AnswerGuard.check(
            "आप इस अभ्यास में उत्तीर्ण हुए। अच्छा काम।",
            prompt(AiLanguage.HINDI),
        )
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.VERDICT_LANGUAGE)
    }

    @Test
    fun `mentioning a certificate is rejected`() {
        val verdict = AnswerGuard.check("Your certificate has been issued.", prompt())
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.VERDICT_LANGUAGE)
    }

    @Test
    fun `a legitimate use of the word failed is not rejected`() {
        // This is why the English rules are phrase-based. "the ventilation failed" is correct safety
        // advice and matching a bare "failed" would throw it away.
        val verdict = AnswerGuard.check(
            "If the ventilation failed, withdraw and do not restart equipment.",
            prompt(),
        )
        assertThat(verdict).isInstanceOf(GuardVerdict.Accepted::class.java)
    }

    // -----------------------------------------------------------------------
    // Script and language
    // -----------------------------------------------------------------------

    @Test
    fun `an english answer to a hindi question is rejected as drift`() {
        val verdict = AnswerGuard.check(
            "Withdraw from the area immediately and inform your supervisor at once.",
            prompt(AiLanguage.HINDI),
        )
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.LANGUAGE_DRIFT)
    }

    @Test
    fun `a hindi answer to an english question is rejected as drift`() {
        val verdict = AnswerGuard.check(
            "क्षेत्र से तुरंत बाहर निकलें और अपने सुपरवाइज़र को बताएँ, यह ज़रूरी है।",
            prompt(),
        )
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.LANGUAGE_DRIFT)
    }

    @Test
    fun `a very short answer is not judged for drift`() {
        // Too few letters to compute a meaningful share; rejecting here would be noise.
        val verdict = AnswerGuard.check("Withdraw.", prompt(AiLanguage.HINDI))
        assertThat(verdict).isInstanceOf(GuardVerdict.Accepted::class.java)
    }

    @Test
    fun `ol chiki output is rejected outright`() {
        val verdict = AnswerGuard.check("ᱡᱟᱜᱨᱩᱠ ᱥᱟᱱᱛᱟᱲᱤ ᱠᱟᱛ�heᱟ ᱨᱮ", prompt())
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.UNSUPPORTED_SCRIPT)
    }

    // -----------------------------------------------------------------------
    // Degenerate output
    // -----------------------------------------------------------------------

    @Test
    fun `empty output is rejected`() {
        assertThat((AnswerGuard.check("   ", prompt()) as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.EMPTY)
    }

    @Test
    fun `a repeated sentence is rejected as a loop`() {
        val verdict = AnswerGuard.check(
            "Withdraw from the area. Withdraw from the area. Withdraw from the area.",
            prompt(),
        )
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.DEGENERATE_LOOP)
    }

    @Test
    fun `low trigram diversity is rejected as a loop`() {
        val verdict = AnswerGuard.check(
            "methane roof methane roof methane roof methane roof methane roof methane roof",
            prompt(),
        )
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.DEGENERATE_LOOP)
    }

    @Test
    fun `absurdly long output is rejected before anything else is checked`() {
        val verdict = AnswerGuard.check("Withdraw from the area. ".repeat(400), prompt())
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.DEGENERATE_LOOP)
    }

    // -----------------------------------------------------------------------
    // Leakage
    // -----------------------------------------------------------------------

    @Test
    fun `echoing the instruction block is rejected`() {
        val verdict = AnswerGuard.check(
            "Rules you must follow: 1. Use ONLY the numbered safety sources below.",
            prompt(),
        )
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.PROMPT_LEAKAGE)
    }

    @Test
    fun `echoing the source header is rejected`() {
        val verdict = AnswerGuard.check("### SAFETY SOURCES Withdraw at 1.25 percent.", prompt())
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.PROMPT_LEAKAGE)
    }

    @Test
    fun `chat control tokens are stripped rather than shown`() {
        val verdict = AnswerGuard.check(
            "Withdraw from the area.<end_of_turn>",
            prompt(),
        )
        val accepted = verdict as GuardVerdict.Accepted
        assertThat(accepted.text).doesNotContain("end_of_turn")
    }

    // -----------------------------------------------------------------------
    // Truncation
    // -----------------------------------------------------------------------

    @Test
    fun `output over the sentence limit is truncated and flagged`() {
        val verdict = AnswerGuard.check(
            "One thing. Two things. Three things. Four things. Five things. Six things.",
            prompt(kind = AiTaskKind.HAZARD_SUMMARY),
        )
        val accepted = verdict as GuardVerdict.Accepted
        assertThat(accepted.truncated).isTrue()
        assertThat(AnswerGuard.sentences(accepted.text)).hasSize(1)
    }

    @Test
    fun `a briefing is truncated by line not by sentence`() {
        val raw = (1..9).joinToString("\n") { "Line about the roof and the crusher motor." }
        val verdict = AnswerGuard.check(raw, prompt(kind = AiTaskKind.SHIFT_BRIEFING))
        // Repeated identical lines trip the loop detector first, which is correct: nine identical
        // lines is not a briefing. Assert the loop, not the truncation.
        assertThat((verdict as GuardVerdict.Rejected).rejection)
            .isEqualTo(GuardRejection.DEGENERATE_LOOP)
    }

    @Test
    fun `distinct briefing lines are truncated to the limit`() {
        val raw = listOf(
            "Twenty-two workers hold valid papers but are operationally stale.",
            "Nine workers were slow to decide in their last drill.",
            "Five hazard reports remain open across the site.",
            "Test methane at the roof before restarting any equipment.",
            "Nobody enters a confined space to rescue without breathing apparatus.",
            "Report any blocked exit before the shift begins.",
            "Check your harness webbing and stitching before climbing.",
        ).joinToString("\n")
        val verdict = AnswerGuard.check(raw, prompt(kind = AiTaskKind.SHIFT_BRIEFING))
        val accepted = verdict as GuardVerdict.Accepted
        assertThat(accepted.truncated).isTrue()
        assertThat(AnswerGuard.lines(accepted.text)).hasSize(5)
    }

    // -----------------------------------------------------------------------
    // Segmentation helpers
    // -----------------------------------------------------------------------

    @Test
    fun `sentences split on the devanagari danda`() {
        assertThat(AnswerGuard.sentences("पहला वाक्य। दूसरा वाक्य।")).hasSize(2)
    }

    @Test
    fun `sentences keep their terminator`() {
        assertThat(AnswerGuard.sentences("Stop. Look.")).containsExactly("Stop.", "Look.").inOrder()
    }

    @Test
    fun `a trailing fragment without a terminator is still a sentence`() {
        assertThat(AnswerGuard.sentences("Stop. Look")).hasSize(2)
    }

    @Test
    fun `punctuation-only input yields no sentences`() {
        assertThat(AnswerGuard.sentences("... !! ??")).isEmpty()
    }

    @Test
    fun `lines drops blank lines`() {
        assertThat(AnswerGuard.lines("one\n\n  \ntwo\n")).containsExactly("one", "two").inOrder()
    }
}
