package org.jaagruk.core.ai

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AiTokenizerTest {

    // -----------------------------------------------------------------------
    // The bug this class exists to prevent
    // -----------------------------------------------------------------------

    @Test
    fun `a devanagari word survives as one token`() {
        // "गैस" is ग + ◌ै + स. Char.isLetterOrDigit() is false for the matra, so splitting on
        // letters-or-digits alone would yield "ग" and "स". If this test fails, Hindi retrieval is
        // matching single consonants and every query matches every passage.
        assertThat(AiTokenizer.tokenize("गैस")).containsExactly("गैस")
    }

    @Test
    fun `a conjunct with a virama stays whole`() {
        // "स्थान" carries a virama (U+094D), also a non-spacing mark.
        assertThat(AiTokenizer.tokenize("स्थान")).containsExactly("स्थान")
    }

    @Test
    fun `every devanagari mark class is treated as part of a word`() {
        val words = listOf(
            "ऑक्सीजन",   // candrabindu-adjacent vowel sign + conjunct
            "मज़दूर",     // nukta
            "प्रमाणपत्र",  // multiple conjuncts
            "हिन्दी",      // virama plus dependent vowels
            "क्षेत्र",
        )
        for (word in words) {
            assertThat(AiTokenizer.tokenize(word)).hasSize(1)
        }
    }

    @Test
    fun `a devanagari sentence splits on the danda and on spaces only`() {
        val tokens = AiTokenizer.tokenize("मेथेन छत के पास जमा होती है। माप लें।")
        // के, है are stopwords; the danda is punctuation and separates.
        assertThat(tokens).containsAtLeast("मेथेन", "छत", "पास", "जमा", "माप")
        assertThat(tokens).doesNotContain("है")
        assertThat(tokens.none { it.contains('\u0964') }).isTrue()
    }

    // -----------------------------------------------------------------------
    // Numbers
    // -----------------------------------------------------------------------

    @Test
    fun `a decimal stays one token`() {
        assertThat(AiTokenizer.tokenize("methane at 1.25 percent")).contains("1.25")
    }

    @Test
    fun `a sentence-ending full stop does not join two numbers`() {
        val tokens = AiTokenizer.tokenize("Withdraw at 1.25. Nobody enters at 2.")
        assertThat(tokens).contains("1.25")
        assertThat(tokens).doesNotContain("1.25.")
    }

    @Test
    fun `numbers are never stemmed`() {
        // "1.250" must not be shortened by the suffix strippers; only AnswerGuard normalises values.
        assertThat(AiTokenizer.tokenize("reading 19.5 and 1.250")).containsAtLeast("19.5", "1.250")
    }

    // -----------------------------------------------------------------------
    // Stopwords and stemming
    // -----------------------------------------------------------------------

    @Test
    fun `english stopwords are dropped`() {
        assertThat(AiTokenizer.tokenize("this is the extinguisher for that fire"))
            .containsExactly("extinguisher", "fire")
    }

    @Test
    fun `hindi stopwords are dropped`() {
        // Only function words here. "आग" is deliberately absent: it is a content word and must
        // survive, which the next test asserts.
        assertThat(AiTokenizer.tokenize("यह के लिए है और इस से")).isEmpty()
    }

    @Test
    fun `a short hindi content word survives`() {
        // "आग" (fire) is two characters. The minimum token length must not discard it.
        assertThat(AiTokenizer.tokenize("यह आग के लिए है")).containsExactly("आग")
    }

    @Test
    fun `single characters carry no signal and are dropped`() {
        assertThat(AiTokenizer.tokenize("a b c oxygen")).containsExactly("oxygen")
    }

    @Test
    fun `english plurals and participles reduce to a shared stem`() {
        assertThat(AiTokenizer.tokenize("guards")).isEqualTo(AiTokenizer.tokenize("guard"))
        assertThat(AiTokenizer.tokenize("ventilating")).isEqualTo(AiTokenizer.tokenize("ventilate"))
    }

    @Test
    fun `short words are not stemmed into noise`() {
        // "gas" must not lose its "s" — a three-letter stem is below the floor.
        assertThat(AiTokenizer.tokenize("gas")).containsExactly("gas")
    }

    @Test
    fun `hindi oblique plurals reduce to a shared stem`() {
        assertThat(AiTokenizer.tokenize("मज़दूरों")).isEqualTo(AiTokenizer.tokenize("मज़दूर"))
    }

    @Test
    fun `case is folded`() {
        assertThat(AiTokenizer.tokenize("METHANE Methane methane").distinct()).hasSize(1)
    }

    @Test
    fun `token set preserves first-seen order and removes duplicates`() {
        // "methane" stems to "methan" via the silent-e rule. Tokens are stems, not words.
        assertThat(AiTokenizer.tokenSet("oxygen methane oxygen").toList())
            .containsExactly("oxygen", "methan")
            .inOrder()
    }

    @Test
    fun `mixed script text tokenises both halves`() {
        val tokens = AiTokenizer.tokenize("SCBA उपकरण पहनें")
        assertThat(tokens).contains("scba")
        assertThat(tokens).contains("उपकरण")
    }

    @Test
    fun `an empty or punctuation-only string yields nothing`() {
        assertThat(AiTokenizer.tokenize("")).isEmpty()
        assertThat(AiTokenizer.tokenize("  ।।  ... !? ")).isEmpty()
    }
}
