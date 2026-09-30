package org.jaagruk.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The AR interlock.
 *
 * Worth testing carefully because its failure mode is invisible: if the model is still resident when
 * an AR drill starts, the handset throttles, and the decision latency measured on a throttled frame
 * loop is signed into a certificate as though it described the worker.
 */
class LlmSessionGuardTest {

    @Test
    fun `entering a drill releases the model before the caller proceeds`() = runTest {
        val guard = LlmSessionGuard()
        var released = false
        guard.addListener { released = true }

        guard.enterDrill()

        // Asserted after enterDrill returns, not eventually: a caller awaits this before starting an
        // AR session, so the release has to have happened by the time it returns.
        assertThat(released).isTrue()
        assertThat(guard.isInDrill).isTrue()
    }

    @Test
    fun `leaving a drill clears the block`() = runTest {
        val guard = LlmSessionGuard()
        guard.enterDrill()
        guard.exitDrill()
        assertThat(guard.isInDrill).isFalse()
    }

    @Test
    fun `nested drills only release once`() = runTest {
        // A buddy drill has a drill screen and a peer session that overlap. A boolean latch would let
        // the first to finish re-admit the model while the second was still running.
        val guard = LlmSessionGuard()
        var releases = 0
        guard.addListener { releases++ }

        guard.enterDrill()
        guard.enterDrill()
        assertThat(releases).isEqualTo(1)
        assertThat(guard.activeDrillCount).isEqualTo(2)

        guard.exitDrill()
        assertThat(guard.isInDrill).isTrue()

        guard.exitDrill()
        assertThat(guard.isInDrill).isFalse()
    }

    @Test
    fun `an unbalanced exit cannot drive the count negative`() = runTest {
        val guard = LlmSessionGuard()
        guard.exitDrill()
        guard.exitDrill()
        assertThat(guard.activeDrillCount).isEqualTo(0)

        // And the next real drill still blocks, rather than being cancelled out by the stray exits.
        guard.enterDrill()
        assertThat(guard.isInDrill).isTrue()
    }

    @Test
    fun `withDrill releases the device when the block throws`() = runTest {
        val guard = LlmSessionGuard()
        runCatching {
            guard.withDrill { throw IllegalStateException("tracking lost") }
        }
        assertThat(guard.isInDrill).isFalse()
    }

    @Test
    fun `withDrill returns the block's value`() = runTest {
        val guard = LlmSessionGuard()
        val result = guard.withDrill { 42 }
        assertThat(result).isEqualTo(42)
        assertThat(guard.isInDrill).isFalse()
    }

    @Test
    fun `a listener that fails does not stop the drill starting`() = runTest {
        // A drill must never be blocked by the AI layer failing to tidy up. Safety training takes
        // precedence over the optional feature.
        val guard = LlmSessionGuard()
        guard.addListener { throw IllegalStateException("unload failed") }
        var secondRan = false
        guard.addListener { secondRan = true }

        guard.enterDrill()

        assertThat(guard.isInDrill).isTrue()
        assertThat(secondRan).isTrue()
    }

    @Test
    fun `a removed listener is not called`() = runTest {
        val guard = LlmSessionGuard()
        var called = false
        val listener = LlmSessionGuard.Listener { called = true }
        guard.addListener(listener)
        guard.removeListener(listener)

        guard.enterDrill()

        assertThat(called).isFalse()
    }

    @Test
    fun `reset clears a stuck count`() = runTest {
        val guard = LlmSessionGuard()
        guard.enterDrill()
        guard.enterDrill()
        guard.reset()
        assertThat(guard.isInDrill).isFalse()
    }

    @Test
    fun `a guard with no listeners still tracks state`() = runTest {
        // The AI module can be excluded from a build entirely, in which case nothing subscribes.
        // The drill flow calls enterDrill regardless and must not care.
        val guard = LlmSessionGuard()
        guard.enterDrill()
        assertThat(guard.isInDrill).isTrue()
        guard.exitDrill()
        assertThat(guard.isInDrill).isFalse()
    }
}
