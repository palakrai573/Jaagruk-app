package org.jaagruk.core.ai

/** Why generated text was thrown away. Each one is a distinct failure with a distinct cause. */
enum class GuardRejection {
    /** Nothing usable came back. */
    EMPTY,

    /**
     * The model looped.
     *
     * Sub-1B models repeat under-specified instructions, and a wall of repeated text in front of a
     * worker reads as a broken app. Cheap to detect, so it is detected rather than shipped.
     */
    DEGENERATE_LOOP,

    /**
     * A figure appeared that was not in the prompt.
     *
     * The single most important check here. Indian coal mines withdraw at 1.25 % methane; a model
     * that writes 1.5 % has produced a fluent, confident, fatal sentence. Any number the sources
     * and the task facts do not contain is treated as invented, and invented figures are not shown
     * to a worker under any circumstances.
     */
    UNGROUNDED_NUMBER,

    /**
     * The model tried to state a verdict.
     *
     * Pass, fail, score and certification are decided by deterministic code and signed. A model
     * paraphrasing them would create a second, unsigned source of truth about whether a worker may
     * enter a confined space.
     */
    VERDICT_LANGUAGE,

    /** Answered in a script the reader did not ask for. */
    LANGUAGE_DRIFT,

    /**
     * Ol Chiki appeared in the output.
     *
     * No model in this size class generates Santali. If Ol Chiki codepoints come back, they are
     * noise shaped like a language a worker actually reads, which is the worst possible output.
     */
    UNSUPPORTED_SCRIPT,

    /** Instruction or template text leaked into the answer. */
    PROMPT_LEAKAGE,
}

/**
 * The outcome of checking one generation.
 *
 * Three outcomes, not two. [Refused] is a success: the model was asked to say when the sources do
 * not cover a question and it did. Collapsing it into [Rejected] would hide the difference between
 * "we have no answer for you" and "the model misbehaved", and those need different words in front
 * of a worker.
 */
sealed interface GuardVerdict {

    class Accepted(
        val text: String,
        val citations: List<String>,
        val passageIds: List<String>,
        val truncated: Boolean,
    ) : GuardVerdict {
        init {
            require(text.isNotBlank()) { "Accepted text must not be blank" }
        }
    }

    /** The model reported that the supplied sources do not answer it. */
    data object Refused : GuardVerdict

    class Rejected(val rejection: GuardRejection, val detail: String) : GuardVerdict
}

/**
 * Deterministic validation of everything the model produces.
 *
 * The instruction block asks the model not to invent numbers and not to state verdicts. This class
 * is what makes those constraints real. A rule that lives only in a prompt is a request; a small
 * model under an unusual input will ignore it, and nothing downstream would know. Every rule here
 * is a pure function over strings with its own unit test, which is the only form of guarantee worth
 * putting in front of a safety certification workflow.
 *
 * Checks run cheapest-and-most-decisive first, and the numeric check runs against the **whole
 * prompt** rather than just the sources: a briefing legitimately echoes counts that came from the
 * task block, and any figure not present anywhere in the prompt cannot have come from anywhere but
 * the model.
 */
object AnswerGuard {

    /** Devanagari block, used for script-share analysis. */
    private val DEVANAGARI = '\u0900'..'\u097F'

    /** Ol Chiki block. Never expected in output; its presence is a rejection. */
    private val OL_CHIKI = '\u1C50'..'\u1C7F'

    /** Minimum letters before script drift is judged. A four-word answer has no reliable share. */
    private const val MIN_LETTERS_FOR_DRIFT = 20

    /** Required share of letters in the requested script. */
    private const val MIN_SCRIPT_SHARE = 0.60

    /** Beyond this, output is degenerate whatever it says: no task here permits 5 sentences. */
    private const val HARD_CHAR_CEILING = 2_500

    /** A normalised sentence appearing this many times is a loop. */
    private const val MAX_SENTENCE_REPEATS = 3

    /** Below this ratio of distinct to total trigrams, the text is looping. */
    private const val MIN_TRIGRAM_DIVERSITY = 0.50

    private const val MIN_TOKENS_FOR_TRIGRAM_CHECK = 12

    /**
     * Verdict phrases, checked case-insensitively.
     *
     * Phrase-based for English on purpose. A bare "failed" appears legitimately — "if the
     * ventilation failed" — so matching the bare word would reject correct safety advice. The two
     * Hindi words are safe bare: उत्तीर्ण and अनुत्तीर्ण essentially only mean passing or failing an
     * examination.
     */
    private val VERDICT_PHRASES: List<String> = listOf(
        "you passed", "you have passed", "you failed", "you have failed", "you did not pass",
        "your score", "your marks", "your result", "you are certified", "you are not certified",
        "you are now certified", "certificate has been", "marks obtained",
        "उत्तीर्ण", "अनुत्तीर्ण", "आपके अंक", "आपका अंक", "आप प्रमाणित", "प्रमाणपत्र",
    )

    /** Instruction fragments that indicate the model echoed its own prompt. */
    private val LEAKAGE_MARKERS: List<String> = listOf(
        "### SAFETY SOURCES", "### सुरक्षा स्रोत", "### THE WORKER'S QUESTION", "### मज़दूर का सवाल",
        "### THE DRILL SITUATION", "### अभ्यास की स्थिति", "### TODAY'S SITE FACTS",
        "### आज साइट की स्थिति", "### THE HAZARD REPORT", "### खतरे की सूचना",
        "Rules you must follow", "इन नियमों का पालन",
    )

    fun check(raw: String, prompt: BuiltPrompt): GuardVerdict {
        val stripped = GemmaChatTemplate.sanitize(raw)

        if (stripped.isBlank()) {
            return GuardVerdict.Rejected(GuardRejection.EMPTY, "model produced no text")
        }

        // A refusal is a valid answer and is not subject to the content checks. Checked on the
        // stripped text before anything else can reshape it.
        if (stripped.contains(PromptBuilder.REFUSAL_SENTINEL)) {
            return GuardVerdict.Refused
        }

        LEAKAGE_MARKERS.firstOrNull { stripped.contains(it, ignoreCase = true) }?.let { marker ->
            return GuardVerdict.Rejected(
                GuardRejection.PROMPT_LEAKAGE,
                "output echoed the instruction block near \"$marker\"",
            )
        }

        if (stripped.length > HARD_CHAR_CEILING) {
            return GuardVerdict.Rejected(
                GuardRejection.DEGENERATE_LOOP,
                "output is ${stripped.length} chars, over the $HARD_CHAR_CEILING ceiling",
            )
        }

        stripped.firstOrNull { it in OL_CHIKI }?.let {
            return GuardVerdict.Rejected(
                GuardRejection.UNSUPPORTED_SCRIPT,
                "output contains Ol Chiki (U+%04X), which no model in this class generates"
                    .format(it.code),
            )
        }

        loopDetail(stripped)?.let {
            return GuardVerdict.Rejected(GuardRejection.DEGENERATE_LOOP, it)
        }

        driftDetail(stripped, prompt.task.language)?.let {
            return GuardVerdict.Rejected(GuardRejection.LANGUAGE_DRIFT, it)
        }

        VERDICT_PHRASES.firstOrNull { stripped.contains(it, ignoreCase = true) }?.let { phrase ->
            return GuardVerdict.Rejected(
                GuardRejection.VERDICT_LANGUAGE,
                "output stated a verdict (\"$phrase\"); pass, score and certification are not the " +
                    "model's to report",
            )
        }

        val permitted = numbersIn(prompt.text)
        val claimed = numbersIn(stripListMarkers(stripped))
        val invented = claimed - permitted
        if (invented.isNotEmpty()) {
            return GuardVerdict.Rejected(
                GuardRejection.UNGROUNDED_NUMBER,
                "output contains ${invented.sorted()} which appear nowhere in the sources or facts",
            )
        }

        val units = if (prompt.task.kind == AiTaskKind.SHIFT_BRIEFING) {
            lines(stripped)
        } else {
            sentences(stripped)
        }
        val truncated = units.size > prompt.maxSentences
        val text = if (truncated) {
            units.take(prompt.maxSentences).joinToString(
                if (prompt.task.kind == AiTaskKind.SHIFT_BRIEFING) "\n" else " ",
            )
        } else {
            stripped
        }

        if (text.isBlank()) {
            return GuardVerdict.Rejected(GuardRejection.EMPTY, "nothing survived truncation")
        }

        return GuardVerdict.Accepted(
            text = text.trim(),
            citations = prompt.citations,
            passageIds = prompt.passageIds,
            truncated = truncated,
        )
    }

    // -----------------------------------------------------------------------
    // Numeric grounding
    // -----------------------------------------------------------------------

    private val NUMBER = Regex("""\d+(?:[.,]\d+)*""")

    /** Strips `1.` / `2)` list ordinals so a numbered list is not read as a numeric claim. */
    private val LIST_MARKER = Regex("""(?m)^[ \t]*\(?\d{1,2}[.)][ \t]+""")

    internal fun stripListMarkers(text: String): String = LIST_MARKER.replace(text, "")

    /**
     * Normalised numeric tokens in [text].
     *
     * Devanagari digits are folded to ASCII, thousands separators dropped, and trailing fractional
     * zeros trimmed, so "1.25", "१.२५" and "1.250" are one claim rather than three.
     */
    fun numbersIn(text: String): Set<String> {
        val folded = foldDigits(text)
        return NUMBER.findAll(folded).map { normaliseNumber(it.value) }.toSet()
    }

    private fun foldDigits(text: String): String {
        if (text.none { it in '\u0966'..'\u096F' }) return text
        val out = StringBuilder(text.length)
        for (ch in text) {
            out.append(if (ch in '\u0966'..'\u096F') ('0' + (ch - '\u0966')) else ch)
        }
        return out.toString()
    }

    private fun normaliseNumber(raw: String): String {
        // Commas are thousands separators here; a decimal comma would be unusual in this corpus and
        // dropping it would change the value, so only comma groups of exactly three digits go.
        var value = Regex(""",(\d{3})(?!\d)""").replace(raw) { it.groupValues[1] }
        value = value.replace(",", ".")
        if (value.contains('.')) {
            value = value.trimEnd('0').trimEnd('.')
        }
        return value.ifEmpty { "0" }
    }

    // -----------------------------------------------------------------------
    // Loop and drift detection
    // -----------------------------------------------------------------------

    private fun loopDetail(text: String): String? {
        val units = sentences(text)
        if (units.size >= MAX_SENTENCE_REPEATS) {
            val counts = units.groupingBy { normaliseForComparison(it) }.eachCount()
            val worst = counts.maxByOrNull { it.value }
            if (worst != null && worst.value >= MAX_SENTENCE_REPEATS) {
                return "a sentence repeats ${worst.value} times"
            }
        }
        val tokens = AiTokenizer.tokenize(text)
        if (tokens.size >= MIN_TOKENS_FOR_TRIGRAM_CHECK) {
            val trigrams = tokens.windowed(3).map { it.joinToString(" ") }
            val diversity = trigrams.distinct().size.toDouble() / trigrams.size
            if (diversity < MIN_TRIGRAM_DIVERSITY) {
                return "trigram diversity %.2f is below %.2f".format(diversity, MIN_TRIGRAM_DIVERSITY)
            }
        }
        return null
    }

    private fun driftDetail(text: String, language: AiLanguage): String? {
        var devanagari = 0
        var latin = 0
        var letters = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            letters++
            when {
                ch in DEVANAGARI -> devanagari++
                ch.code < 0x0250 -> latin++
            }
        }
        if (letters < MIN_LETTERS_FOR_DRIFT) return null
        val share = when (language) {
            AiLanguage.HINDI -> devanagari.toDouble() / letters
            AiLanguage.ENGLISH -> latin.toDouble() / letters
        }
        if (share >= MIN_SCRIPT_SHARE) return null
        return "only %.0f%% of letters are in the requested script (%s); need %.0f%%".format(
            share * 100,
            language.tag,
            MIN_SCRIPT_SHARE * 100,
        )
    }

    private fun normaliseForComparison(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    // -----------------------------------------------------------------------
    // Segmentation
    // -----------------------------------------------------------------------

    /**
     * Splits into sentences on `.`, `!`, `?` and the Devanagari danda `।`.
     *
     * Terminators are kept, because a coaching answer stripped of its full stops reads as a
     * fragment. A run of terminators counts once.
     */
    fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\u0964') {
                val candidate = current.toString().trim()
                if (candidate.any { it.isLetterOrDigit() }) out += candidate
                current.setLength(0)
            }
        }
        val tail = current.toString().trim()
        if (tail.any { it.isLetterOrDigit() }) out += tail
        return out
    }

    /** Non-blank lines. The unit for a briefing, which is read aloud line by line. */
    fun lines(text: String): List<String> =
        text.lines().map { it.trim() }.filter { line -> line.any { it.isLetterOrDigit() } }
}
