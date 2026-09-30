package org.jaagruk.core.ai

/**
 * Gemma 3's chat template, and the two things about it that are easy to get wrong.
 *
 * 1. **There is no system role.** Gemma 3 instruction-tuned models have `user` and `model` turns
 *    only. System instructions are prepended to the first user turn. Emitting a `system` turn the
 *    way ChatML does produces a prompt the model was never trained on, and the symptom is bland,
 *    instruction-ignoring output rather than an error.
 * 2. **Do not write `<bos>`.** llama.cpp is called with `add_special = true`, so the tokeniser adds
 *    BOS itself. Writing it here too gives the model two, which measurably degrades the first
 *    tokens and is invisible unless you dump the token ids.
 *
 * The final `<start_of_turn>model\n` is left open on purpose: that is where generation begins.
 */
object GemmaChatTemplate {

    const val START_OF_TURN: String = "<start_of_turn>"
    const val END_OF_TURN: String = "<end_of_turn>"

    /** Every control token that must never survive inside interpolated text. */
    private val CONTROL_TOKENS: List<String> = listOf(
        "<start_of_turn>", "<end_of_turn>", "<bos>", "<eos>", "<pad>",
        "<unused0>", "<start_of_image>", "<end_of_image>",
    )

    fun singleTurn(userContent: String): String =
        "${START_OF_TURN}user\n$userContent$END_OF_TURN\n${START_OF_TURN}model\n"

    /**
     * Removes control tokens from text that came from outside the codebase.
     *
     * A worker's typed hazard note and a free-text question both end up inside the prompt. Neither
     * is hostile in a mine, but "untrusted input reaches an interpreter" is the same shape of
     * problem wherever it appears, and the fix costs one pass over a short string. Tokens are
     * replaced with a space rather than deleted so words either side cannot be glued together.
     */
    fun sanitize(text: String): String {
        var out = text
        for (token in CONTROL_TOKENS) {
            if (out.contains(token, ignoreCase = true)) {
                out = out.replace(token, " ", ignoreCase = true)
            }
        }
        // Any residual `<...of_turn>`-shaped construct, including whitespace-padded variants.
        out = CONTROL_SHAPE.replace(out, " ")
        return out.replace(WHITESPACE_RUN, " ").trim()
    }

    private val CONTROL_SHAPE =
        Regex("""<\s*/?\s*(start_of_turn|end_of_turn|bos|eos|pad|unused\d+)\s*>""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_RUN = Regex("""[ \t\u00A0]{2,}""")
}

/**
 * How much prompt fits, derived from the context window rather than guessed.
 *
 * The characters-per-token figures are estimates and are named as such. Devanagari costs far more
 * tokens per character than Latin even with Gemma's large multilingual vocabulary, so a single
 * character budget would either waste most of an English window or overflow a Hindi one. Overflow
 * is the dangerous direction: it silently pushes the earliest source out of context, and the
 * failure mode is an answer grounded in text the caller believes was supplied.
 */
class PromptBudget(
    val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    val reservedOutputTokens: Int = DEFAULT_RESERVED_OUTPUT_TOKENS,
    val charsPerTokenLatin: Double = 3.5,
    val charsPerTokenDevanagari: Double = 1.6,
) {
    init {
        require(contextTokens > 0) { "contextTokens must be positive, got $contextTokens" }
        require(reservedOutputTokens in 1 until contextTokens) {
            "reservedOutputTokens must be 1 until contextTokens, got $reservedOutputTokens"
        }
        require(charsPerTokenLatin > 0.0) { "charsPerTokenLatin must be positive" }
        require(charsPerTokenDevanagari > 0.0) { "charsPerTokenDevanagari must be positive" }
    }

    fun maxPromptChars(language: AiLanguage): Int {
        val available = contextTokens - reservedOutputTokens
        val perToken = when (language) {
            AiLanguage.ENGLISH -> charsPerTokenLatin
            AiLanguage.HINDI -> charsPerTokenDevanagari
        }
        return (available * perToken).toInt()
    }

    companion object {
        /**
         * 4096, not 2048.
         *
         * Four sources plus instructions plus a question does not fit 2048 tokens in Hindi, and the
         * KV cache for 4096 at this model size is tens of megabytes — cheap next to the weights.
         */
        const val DEFAULT_CONTEXT_TOKENS: Int = 4096
        const val DEFAULT_RESERVED_OUTPUT_TOKENS: Int = 384
    }
}

/** A finished prompt plus what it was grounded in, so the UI can cite and the guard can check. */
class BuiltPrompt(
    val text: String,
    val task: AiTask,
    val groundingPassages: List<CorpusPassage>,
    val droppedPassageIds: List<String>,
    val maxSentences: Int,
) {
    init {
        require(text.isNotBlank()) { "prompt text must not be blank" }
        require(groundingPassages.isNotEmpty()) {
            "a prompt must never be built without grounding; refuse instead"
        }
    }

    /** Concatenated source text, which is what [AnswerGuard] checks numeric claims against. */
    val groundingText: String get() = groundingPassages.joinToString("\n") { "${it.title}\n${it.body}" }

    val citations: List<String> get() = groundingPassages.map { it.sourceLabel }.distinct()

    val passageIds: List<String> get() = groundingPassages.map { it.passageId }

    override fun toString(): String =
        "BuiltPrompt(${task.kind}, ${text.length} chars, sources=${passageIds})"
}

/**
 * Turns a task plus its grounding into the exact string handed to the model.
 *
 * Pure and deterministic: no clock, no randomness, stable ordering. `PromptSnapshotTest` pins the
 * output byte for byte, for the same reason `attestation_vectors.json` pins the certificate
 * encoding — a prompt is an interface, and an interface that drifts silently is a bug you find in
 * the field.
 */
object PromptBuilder {

    /**
     * What the model must emit when the sources do not answer the question.
     *
     * A sentinel rather than a phrase, because detecting refusal in free prose across two languages
     * is exactly the kind of fuzzy check that fails open. [AnswerGuard] looks for this token and
     * nothing else.
     */
    const val REFUSAL_SENTINEL: String = "[[NOT_IN_SOURCES]]"

    private const val SOURCE_HEADER_EN = "### SAFETY SOURCES (the only facts you may use)"
    private const val SOURCE_HEADER_HI = "### सुरक्षा स्रोत (केवल इन्हीं तथ्यों का उपयोग करें)"

    fun maxSentencesFor(kind: AiTaskKind): Int = when (kind) {
        AiTaskKind.STEP_COACHING -> 3
        AiTaskKind.SAFETY_QUESTION -> 4
        AiTaskKind.SHIFT_BRIEFING -> 5
        AiTaskKind.HAZARD_SUMMARY -> 1
    }

    /**
     * Builds a prompt, or returns null when the grounding is empty.
     *
     * Null is not an error to be logged and ignored. It means the caller must refuse, and the four
     * call sites all do.
     */
    fun build(
        task: AiTask,
        grounding: List<CorpusPassage>,
        budget: PromptBudget = PromptBudget(),
    ): BuiltPrompt? {
        if (grounding.isEmpty()) return null

        val language = task.language
        val maxSentences = maxSentencesFor(task.kind)
        val instructions = instructions(task, maxSentences)
        val taskBlock = taskBlock(task)
        val fixedChars = instructions.length + taskBlock.length + TEMPLATE_OVERHEAD_CHARS
        val allowance = budget.maxPromptChars(language) - fixedChars

        val kept = mutableListOf<CorpusPassage>()
        val dropped = mutableListOf<String>()
        var used = 0
        for (passage in grounding) {
            val rendered = renderSourceLength(passage, kept.size + 1)
            if (kept.isEmpty() || used + rendered <= allowance) {
                // The highest-ranked source is always kept even if it alone exceeds the allowance:
                // a prompt with no grounding must never be produced, and a long single source is
                // better than silently degrading to an ungrounded answer.
                kept += passage
                used += rendered
            } else {
                dropped += passage.passageId
            }
        }

        val sources = kept.mapIndexed { index, passage -> renderSource(passage, index + 1) }
            .joinToString("\n\n")
        val header = if (language == AiLanguage.HINDI) SOURCE_HEADER_HI else SOURCE_HEADER_EN

        val content = buildString {
            append(instructions)
            append("\n\n")
            append(header)
            append('\n')
            append(sources)
            append("\n\n")
            append(taskBlock)
        }

        return BuiltPrompt(
            text = GemmaChatTemplate.singleTurn(content),
            task = task,
            groundingPassages = kept,
            droppedPassageIds = dropped,
            maxSentences = maxSentences,
        )
    }

    // -----------------------------------------------------------------------
    // Instruction blocks
    // -----------------------------------------------------------------------

    private fun instructions(task: AiTask, maxSentences: Int): String =
        if (task.language == AiLanguage.HINDI) hindiInstructions(task, maxSentences)
        else englishInstructions(task, maxSentences)

    private fun englishInstructions(task: AiTask, maxSentences: Int): String {
        val role = when (task.kind) {
            AiTaskKind.STEP_COACHING ->
                "You are a mine and factory safety instructor in Jharkhand, explaining one point to " +
                    "a worker who has just finished a training drill."
            AiTaskKind.SAFETY_QUESTION ->
                "You are a mine and factory safety instructor in Jharkhand, answering one question " +
                    "from a worker."
            AiTaskKind.SHIFT_BRIEFING ->
                "You are helping a site safety officer write a short spoken briefing to read aloud " +
                    "at the start of a shift."
            AiTaskKind.HAZARD_SUMMARY ->
                "You are summarising a hazard a worker reported, for a safety officer's worklist."
        }
        return buildString {
            appendLine(role)
            appendLine()
            appendLine("Rules you must follow:")
            appendLine("1. Use ONLY the numbered safety sources below. They are the only facts available to you.")
            appendLine("2. Never state a number, percentage, distance or time that does not appear in the sources.")
            appendLine("3. Never say whether the worker passed, failed, is certified, or what their score was.")
            appendLine("4. Write plainly, for someone with little formal schooling and no industrial background.")
            appendLine("5. At most $maxSentences ${if (task.kind == AiTaskKind.SHIFT_BRIEFING) "short lines" else "sentences"}. No preamble, no heading, no bullet symbols.")
            appendLine("6. Write in English.")
            append("7. If the sources do not answer it, reply with exactly ${REFUSAL_SENTINEL} and nothing else.")
        }
    }

    private fun hindiInstructions(task: AiTask, maxSentences: Int): String {
        val role = when (task.kind) {
            AiTaskKind.STEP_COACHING ->
                "आप झारखंड की खदान और कारखाना सुरक्षा के प्रशिक्षक हैं। एक मज़दूर ने अभी प्रशिक्षण अभ्यास पूरा किया है; " +
                    "आप उसे एक बात समझा रहे हैं।"
            AiTaskKind.SAFETY_QUESTION ->
                "आप झारखंड की खदान और कारखाना सुरक्षा के प्रशिक्षक हैं। एक मज़दूर के एक सवाल का जवाब दें।"
            AiTaskKind.SHIFT_BRIEFING ->
                "आप एक साइट सुरक्षा अधिकारी की मदद कर रहे हैं, जो पाली शुरू होने पर बोलकर सुनाने के लिए एक छोटी " +
                    "सूचना तैयार कर रहे हैं।"
            AiTaskKind.HAZARD_SUMMARY ->
                "एक मज़दूर ने खतरे की सूचना दी है। सुरक्षा अधिकारी की सूची के लिए उसका सारांश लिखें।"
        }
        return buildString {
            appendLine(role)
            appendLine()
            appendLine("इन नियमों का पालन अनिवार्य है:")
            appendLine("1. नीचे दिए गए क्रमांकित सुरक्षा स्रोतों का ही उपयोग करें। आपके पास केवल यही तथ्य हैं।")
            appendLine("2. कोई भी संख्या, प्रतिशत, दूरी या समय न लिखें जो स्रोतों में मौजूद नहीं है।")
            appendLine("3. यह कभी न बताएं कि मज़दूर उत्तीर्ण हुआ, अनुत्तीर्ण हुआ, प्रमाणित है, या उसके अंक क्या थे।")
            appendLine("4. सरल भाषा में लिखें — पढ़ने वाला कम पढ़ा-लिखा हो सकता है और उसे उद्योग का अनुभव नहीं है।")
            appendLine("5. अधिकतम $maxSentences ${if (task.kind == AiTaskKind.SHIFT_BRIEFING) "छोटी पंक्तियाँ" else "वाक्य"}। कोई भूमिका नहीं, कोई शीर्षक नहीं, कोई बुलेट चिह्न नहीं।")
            appendLine("6. उत्तर हिन्दी में लिखें।")
            append("7. यदि स्रोतों में उत्तर नहीं है, तो केवल ${REFUSAL_SENTINEL} लिखें और कुछ नहीं।")
        }
    }

    // -----------------------------------------------------------------------
    // Task blocks
    // -----------------------------------------------------------------------

    private fun taskBlock(task: AiTask): String = when (task) {
        is AiTask.StepCoaching -> coachingBlock(task)
        is AiTask.SafetyQuestion -> questionBlock(task)
        is AiTask.ShiftBriefing -> briefingBlock(task)
        is AiTask.HazardSummary -> hazardBlock(task)
    }

    private fun coachingBlock(task: AiTask.StepCoaching): String {
        val hindi = task.language == AiLanguage.HINDI
        val situation = GemmaChatTemplate.sanitize(task.promptText)
        val correct = task.correctLabels.joinToString(", ") { GemmaChatTemplate.sanitize(it) }
        val chosen = task.chosenLabels.joinToString(", ") { GemmaChatTemplate.sanitize(it) }
        return buildString {
            appendLine(if (hindi) "### अभ्यास की स्थिति" else "### THE DRILL SITUATION")
            appendLine(situation)
            appendLine()
            appendLine(if (hindi) "सही कार्रवाई: $correct" else "Correct action: $correct")
            when (task.focus) {
                CoachingFocus.WRONG_ANSWER -> {
                    // Only claims the worker chose something specific when that is actually known.
                    // Otherwise the authored distractors stand in, and the model is asked about them
                    // as options rather than as this worker's answer.
                    if (chosen.isNotBlank()) {
                        appendLine(if (hindi) "मज़दूर ने चुना: $chosen" else "The worker chose: $chosen")
                        appendLine()
                        append(
                            if (hindi) {
                                "समझाएँ कि सही कार्रवाई क्यों सही है, और मज़दूर ने जो चुना वह क्यों खतरनाक है।"
                            } else {
                                "Explain why the correct action is correct, and why what the worker " +
                                    "chose is dangerous."
                            },
                        )
                    } else {
                        appendLine(
                            if (hindi) "मज़दूर ने गलत विकल्प चुना।"
                            else "The worker chose a wrong option.",
                        )
                        val wrong = task.distractorLabels
                            .joinToString(", ") { GemmaChatTemplate.sanitize(it) }
                        if (wrong.isNotBlank()) {
                            appendLine(
                                if (hindi) "इस चरण के गलत विकल्प: $wrong"
                                else "Wrong options in this step: $wrong",
                            )
                        }
                        appendLine()
                        append(
                            if (hindi) {
                                "समझाएँ कि सही कार्रवाई क्यों सही है और गलत विकल्प क्यों खतरनाक हैं।"
                            } else {
                                "Explain why the correct action is correct and why the wrong options " +
                                    "are dangerous."
                            },
                        )
                    }
                }
                CoachingFocus.HESITATED -> {
                    appendLine(
                        if (hindi) "मज़दूर ने सही चुना, पर निर्णय लेने में अपेक्षा से अधिक समय लिया।"
                        else "The worker chose correctly, but took longer to decide than expected.",
                    )
                    appendLine()
                    append(
                        if (hindi) {
                            "बताएँ कि इस स्थिति में कौन-सा एक संकेत तुरंत पहचानना है, जिससे अगली बार निर्णय " +
                                "तेज़ हो। यह न कहें कि उत्तर गलत था — वह सही था।"
                        } else {
                            "Say which single cue to recognise immediately in this situation so the decision " +
                                "comes faster next time. Do not say the answer was wrong — it was right."
                        },
                    )
                }
                CoachingFocus.TIMED_OUT -> {
                    appendLine(
                        if (hindi) "मज़दूर ने समय के अंदर कोई जवाब नहीं दिया।"
                        else "The worker did not answer within the time available.",
                    )
                    appendLine()
                    append(
                        if (hindi) "पहला कदम बताएँ जो इस स्थिति में तुरंत उठाना चाहिए।"
                        else "State the first action to take immediately in this situation.",
                    )
                }
            }
        }
    }

    private fun questionBlock(task: AiTask.SafetyQuestion): String {
        val hindi = task.language == AiLanguage.HINDI
        return buildString {
            appendLine(if (hindi) "### मज़दूर का सवाल" else "### THE WORKER'S QUESTION")
            append(GemmaChatTemplate.sanitize(task.question))
        }
    }

    private fun briefingBlock(task: AiTask.ShiftBriefing): String {
        val hindi = task.language == AiLanguage.HINDI
        val f = task.facts
        return buildString {
            appendLine(if (hindi) "### आज साइट की स्थिति" else "### TODAY'S SITE FACTS")
            appendLine(
                if (hindi) "साइट: ${GemmaChatTemplate.sanitize(f.siteLabel)}"
                else "Site: ${GemmaChatTemplate.sanitize(f.siteLabel)}",
            )
            appendLine(if (hindi) "कुल मज़दूर: ${f.workersTotal}" else "Workers on roll: ${f.workersTotal}")
            appendLine(
                if (hindi) {
                    "प्रशिक्षण तैयारी — तैयार ${f.readyCount}, दोहराव बाकी ${f.dueCount}, " +
                        "पुराना ${f.staleCount}, समाप्त ${f.expiredCount}"
                } else {
                    "Training readiness — ready ${f.readyCount}, refresher due ${f.dueCount}, " +
                        "stale ${f.staleCount}, expired ${f.expiredCount}"
                },
            )
            appendLine(
                if (hindi) {
                    "कागज़ पर वैध पर व्यवहार में पुराना: ${f.statutorilyValidButStaleCount}"
                } else {
                    "Certificate still valid but readiness stale: ${f.statutorilyValidButStaleCount}"
                },
            )
            appendLine(
                if (hindi) "निर्णय में देरी वाले मज़दूर: ${f.hesitationRiskCount}"
                else "Workers flagged for slow decisions: ${f.hesitationRiskCount}",
            )
            appendLine(if (hindi) "खुली खतरे की सूचनाएँ: ${f.openHazardCount}" else "Open hazard reports: ${f.openHazardCount}")
            if (f.openHazardZones.isNotEmpty()) {
                appendLine(
                    (if (hindi) "प्रभावित क्षेत्र: " else "Zones affected: ") +
                        f.openHazardZones.joinToString(", ") { GemmaChatTemplate.sanitize(it) },
                )
            }
            if (f.mostMissedTopicLabels.isNotEmpty()) {
                appendLine(
                    (if (hindi) "सबसे अधिक गलत विषय: " else "Most-missed topics: ") +
                        f.mostMissedTopicLabels.joinToString(", ") { GemmaChatTemplate.sanitize(it) },
                )
            }
            appendLine()
            append(
                if (hindi) {
                    "इन तथ्यों के आधार पर पाली-पूर्व सूचना लिखें। सबसे पहले सबसे बड़ा जोखिम बताएँ। " +
                        "संख्याएँ ऊपर दिए गए तथ्यों से ही लें।"
                } else {
                    "Write the pre-shift briefing from these facts. Lead with the largest risk. " +
                        "Take numbers only from the facts above."
                },
            )
        }
    }

    private fun hazardBlock(task: AiTask.HazardSummary): String {
        val hindi = task.language == AiLanguage.HINDI
        return buildString {
            appendLine(if (hindi) "### खतरे की सूचना" else "### THE HAZARD REPORT")
            appendLine(
                (if (hindi) "श्रेणी: " else "Category: ") + GemmaChatTemplate.sanitize(task.categoryLabel),
            )
            appendLine(
                (if (hindi) "मज़दूर द्वारा दर्ज गंभीरता: " else "Severity as recorded by the worker: ") +
                    GemmaChatTemplate.sanitize(task.severityLabel),
            )
            task.zoneLabel?.let {
                appendLine((if (hindi) "क्षेत्र: " else "Zone: ") + GemmaChatTemplate.sanitize(it))
            }
            appendLine((if (hindi) "मज़दूर का विवरण: " else "Worker's note: ") + GemmaChatTemplate.sanitize(task.note))
            appendLine()
            append(
                if (hindi) {
                    "सुरक्षा अधिकारी की सूची के लिए एक पंक्ति में सारांश लिखें: क्या खराब है और कहाँ। " +
                        "गंभीरता न बदलें।"
                } else {
                    "Write a one-line summary for the safety officer's worklist: what is wrong and where. " +
                        "Do not change the severity."
                },
            )
        }
    }

    // -----------------------------------------------------------------------
    // Source rendering
    // -----------------------------------------------------------------------

    private fun renderSource(passage: CorpusPassage, ordinal: Int): String =
        "[$ordinal] ${GemmaChatTemplate.sanitize(passage.title)}\n${GemmaChatTemplate.sanitize(passage.body)}"

    private fun renderSourceLength(passage: CorpusPassage, ordinal: Int): Int =
        renderSource(passage, ordinal).length + 2

    /** Chat-template markers plus the source header and the blank lines between blocks. */
    private const val TEMPLATE_OVERHEAD_CHARS: Int = 120
}
