package org.jaagruk.safety.ui.ask

import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.jaagruk.ai.AiCoach
import org.jaagruk.ai.AiOutcome
import org.jaagruk.core.ai.AiCapability
import org.jaagruk.safety.ui.components.AiPanelState
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class AskViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val coach = mockk<AiCoach>(relaxed = true)
    private lateinit var vm: AskViewModel

    @Before fun setup() { Dispatchers.setMain(dispatcher); vm = AskViewModel(coach) }
    @After fun teardown() { vm.stop(); Dispatchers.resetMain() }

    @Test fun `missing model still provides actual related sources`() = runTest(dispatcher) {
        coEvery { coach.run(any(), any()) } returns AiOutcome.Unavailable(AiCapability.MODEL_MISSING)
        vm.onQuestionChanged("what methane level means we must leave the area")
        vm.ask()
        // Retrieval runs on Default in production; await its state rather than sleeping a guessed time.
        withContext(Dispatchers.Default) {
            withTimeout(10000) {
                while (vm.state.value.panel is AiPanelState.Working) delay(10)
            }
        }
        assertThat(vm.state.value.panel).isInstanceOf(AiPanelState.Unavailable::class.java)
        assertThat(vm.state.value.sources.map { it.passageId }).contains("gas-methane-levels-en")
        vm.onQuestionChanged("new question")
        assertThat(vm.state.value.sources).isEmpty()
        assertThat(vm.state.value.panel).isEqualTo(AiPanelState.Idle)
    }

    @Test fun `duplicate submit cannot start competing requests and stop allows retry`() = runTest(dispatcher) {
        coEvery { coach.run(any(), any()) } coAnswers { awaitCancellation() }
        vm.onQuestionChanged("methane level")
        vm.ask()
        vm.ask()
        vm.stop()
        runCurrent()
        assertThat(vm.state.value.panel).isEqualTo(AiPanelState.Idle)
        verify(exactly = 1) { coach.stop() }
        vm.ask()
        assertThat(vm.state.value.panel).isInstanceOf(AiPanelState.Working::class.java)
        vm.stop()
    }

    @Test fun `blank question never reaches inference`() {
        vm.onQuestionChanged("   ")
        vm.ask()
        assertThat(vm.state.value.panel).isInstanceOf(AiPanelState.NoAnswer::class.java)
        coVerify(exactly = 0) { coach.run(any(), any()) }
    }

    @Test fun `late native progress cannot overwrite an edited question`() = runTest(dispatcher) {
        val started = CompletableDeferred<(Int) -> Unit>()
        coEvery { coach.run(any(), any()) } coAnswers {
            started.complete(secondArg())
            awaitCancellation()
        }
        vm.onQuestionChanged("methane level")
        vm.ask()
        val callback = withContext(Dispatchers.Default) { withTimeout(10000) { started.await() } }
        vm.ask()
        coVerify(exactly = 1) { coach.run(any(), any()) }
        vm.onQuestionChanged("ladder angle")
        callback(42)
        assertThat(vm.state.value.question).isEqualTo("ladder angle")
        assertThat(vm.state.value.panel).isEqualTo(AiPanelState.Idle)
        assertThat(vm.state.value.sources).isEmpty()
    }
}
