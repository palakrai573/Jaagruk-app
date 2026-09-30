package org.jaagruk.safety

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jaagruk.ai.AiOutcome
import org.jaagruk.core.ai.*
import org.jaagruk.safety.ai.repository.AIRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyDisplayDeviceTest {
    @Test fun realHarnessAnswerCannotBypassDisplayGate() = runBlocking {
        val repository = AIRepository.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            assertTrue(repository.initialize().isSuccess)
            val question = "How should I check a safety harness before working at height?"
            val sources = (SafetyCorpus.retriever.retrieveForTask(AiTask.SafetyQuestion(AiLanguage.ENGLISH, question))
                as RetrievalResult.Grounded).passages.map { it.passage }
            assertTrue(sources.isNotEmpty())
            val outcome = withTimeout(180_000) { repository.askSafety(question, "en") }
            if (outcome is AiOutcome.Answer) {
                assertTrue(SafetyDisplayPolicy.permits(outcome.text, outcome.truncated, sources))
            } else {
                assertFalse("Missing model is not evidence of a working gate", outcome is AiOutcome.Unavailable)
            }
            val unsupported = repository.askSafety(question, "sat") as AiOutcome.Unavailable
            assertEquals(AiCapability.LANGUAGE_UNSUPPORTED, unsupported.capability)
        } finally { repository.unload() }
    }
}
