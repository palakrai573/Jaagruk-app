package org.jaagruk.core.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafetyDisplayPolicyTest {
    private val passage = CorpusPassage("test", "Harness", "Do not use a deployed energy absorber.",
        AiLanguage.ENGLISH, PassageScope.GENERAL, "Test-only authored text")

    @Test fun `complete source survives whitespace changes`() {
        assertThat(SafetyDisplayPolicy.permits(" Do not use a deployed\nenergy absorber. ", false, listOf(passage))).isTrue()
    }
    @Test fun `removed negation is rejected`() {
        assertThat(SafetyDisplayPolicy.permits("Use a deployed energy absorber.", false, listOf(passage))).isFalse()
    }
    @Test fun `unsupported extra instruction is rejected`() {
        assertThat(SafetyDisplayPolicy.permits(passage.body + " Deploy it before use.", false, listOf(passage))).isFalse()
    }
    @Test fun `truncated and unsourced output are rejected`() {
        assertThat(SafetyDisplayPolicy.permits(passage.body, true, listOf(passage))).isFalse()
        assertThat(SafetyDisplayPolicy.permits(passage.body, false, emptyList())).isFalse()
    }
}
