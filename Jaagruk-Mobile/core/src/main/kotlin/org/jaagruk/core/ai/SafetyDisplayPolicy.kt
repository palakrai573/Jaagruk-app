package org.jaagruk.core.ai

/**
 * Temporary release gate after real-device testing found unsafe paraphrases that passed AnswerGuard.
 * Only a complete, verbatim passage can be displayed as generated safety guidance. Do not weaken this
 * to keyword overlap: removing one negation can reverse an instruction while retaining every keyword.
 */
object SafetyDisplayPolicy {
    fun permits(text: String, truncated: Boolean, passages: List<CorpusPassage>): Boolean {
        if (truncated || text.isBlank()) return false
        fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ")
        val answer = normalized(text)
        return passages.any { normalized(it.body) == answer }
    }
}
