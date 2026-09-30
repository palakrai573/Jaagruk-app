package org.jaagruk.safety.circle

/**
 * ContentTypeDetector
 *
 * Analyzes raw OCR text to classify content type.
 * Used by CircleLearnBottomSheet to prioritize and reorder action suggestions.
 * Purely heuristic — no AI call required.
 */
object ContentTypeDetector {

    enum class ContentType {
        CODE, FORMULA, TABLE, PARAGRAPH
    }

    data class DetectionResult(
        val type           : ContentType,
        val primaryActions : List<CircleAction>,
        val allActions     : List<CircleAction>
    )

    private val CODE_SIGNALS    = listOf("{", "}", "def ", "fun ", "class ", "import ", "=>",
        "->", "val ", "var ", "int ", "void ", "public ", "private ", "return ", "if (", "for (")
    private val FORMULA_SIGNALS = listOf("=", "∫", "∑", "√", "∂", "∞", "π", "α", "β", "θ",
        "^2", "^n", "dx", "dy", "sin(", "cos(", "log(", "lim")
    private val TABLE_SIGNALS   = listOf("\t", "  |  ", "---", "===")

    fun detect(text: String): DetectionResult {
        val type = when {
            isCode(text)    -> ContentType.CODE
            isFormula(text) -> ContentType.FORMULA
            isTable(text)   -> ContentType.TABLE
            else            -> ContentType.PARAGRAPH
        }

        val primary = when (type) {
            ContentType.CODE    -> listOf(CircleAction.EXPLAIN_CODE, CircleAction.FIND_BUGS,
                                          CircleAction.INTERVIEW_QUESTIONS)
            ContentType.FORMULA -> listOf(CircleAction.EXPLAIN, CircleAction.SOLVE_EXAMPLE,
                                          CircleAction.PRACTICE_QUESTIONS)
            ContentType.TABLE   -> listOf(CircleAction.SUMMARIZE, CircleAction.KEY_POINTS,
                                          CircleAction.NOTES)
            ContentType.PARAGRAPH -> listOf(CircleAction.NOTES, CircleAction.SUMMARIZE,
                                            CircleAction.FLASHCARDS)
        }

        return DetectionResult(type, primary, CircleAction.entries.toList())
    }

    private fun isCode(text: String)    = CODE_SIGNALS.count { text.contains(it) } >= 2
    private fun isFormula(text: String) = FORMULA_SIGNALS.count { text.contains(it) } >= 2
    private fun isTable(text: String)   = TABLE_SIGNALS.any { text.contains(it) }
}

enum class CircleAction(val label: String, val emoji: String, val prompt: String) {
    EXPLAIN            ("Explain",             "💡", "Explain the following clearly and concisely:"),
    SUMMARIZE          ("Summarize",           "📝", "Summarize the following in 5 concise bullet points:"),
    NOTES              ("Generate Notes",      "📚", "Convert the following into organized study notes with headings:"),
    FLASHCARDS         ("Flashcards",          "🃏", "Create 5 question-answer flashcards from the following text:"),
    QUIZ               ("Quiz",                "❓", "Generate 5 multiple choice questions (with A B C D options and answer) from:"),
    VIVA               ("Viva Questions",      "🎓", "Generate 5 important viva/oral exam questions with answers from:"),
    TRANSLATE          ("Translate",           "🌐", "Translate the following text to English (or explain if already English):"),
    EXPLAIN_CODE       ("Explain Code",        "⚙️", "Explain what this code does step by step:"),
    FIND_BUGS          ("Find Bugs",           "🐛", "Identify any bugs or issues in the following code and suggest fixes:"),
    INTERVIEW_QUESTIONS("Interview Questions", "💼", "Generate 5 technical interview questions based on this code:"),
    SOLVE_EXAMPLE      ("Solve Example",       "🔢", "Solve a numeric example using the following formula or equation:"),
    PRACTICE_QUESTIONS ("Practice Questions",  "✏️", "Generate 5 practice problems based on the following formula or concept:"),
    KEY_POINTS         ("Key Points",          "🔑", "List the 5 most important key points from:"),
    SAVE_TO_VAULT      ("Save to Vault",       "💾", "")   // handled separately — no AI prompt
}
