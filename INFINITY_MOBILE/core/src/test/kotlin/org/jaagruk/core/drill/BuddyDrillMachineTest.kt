package org.jaagruk.core.drill

import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.util.FixedMonotonicTimeSource
import org.junit.jupiter.api.Test

/**
 * Two real [BuddyDrillMachine] instances wired to each other through a controllable link.
 *
 * The transport can drop, duplicate and reorder frames on demand, and the two devices run on
 * clocks that are hours apart, which is the normal state of shared site phones. That is the whole
 * point of keeping the protocol in `:core`: every failure mode a Bluetooth link produces
 * underground is reproducible here without two physical handsets.
 */
private class DrillHarness(
    scenarioIdA: String = "gas-confined-buddy",
    scenarioIdB: String = "gas-confined-buddy",
    catalogVersionA: Int = 1,
    catalogVersionB: Int = 1,
    workerA: String = "JH-DHN-001-W00001",
    workerB: String = "JH-DHN-001-W00002",
    val deviceA: String = "device-aaa",
    val deviceB: String = "device-zzz",
    config: DrillConfig = DrillConfig.DEFAULT,
) {
    val clockA = FixedMonotonicTimeSource(1_000L)

    // Deliberately hours ahead of A. Host-relative logical time must make this irrelevant.
    val clockB = FixedMonotonicTimeSource(9_000_000L)

    val a = BuddyDrillMachine(deviceA, workerA, scenarioIdA, catalogVersionA, "1.0.0", clockA, config)
    val b = BuddyDrillMachine(deviceB, workerB, scenarioIdB, catalogVersionB, "1.0.0", clockB, config)

    val effectsA = mutableListOf<DrillEffect>()
    val effectsB = mutableListOf<DrillEffect>()

    /** Frames captured instead of delivered, so a test can replay or reorder them. */
    val intercepted = mutableListOf<Pair<Boolean, ByteArray>>()
    var interceptFromA = false
    var interceptFromB = false

    private val queue = ArrayDeque<Pair<Boolean, ByteArray>>()

    fun advance(millis: Long) {
        clockA.advance(millis)
        clockB.advance(millis)
    }

    /** @param fromA true when [effects] were produced by machine A. */
    fun submit(effects: List<DrillEffect>, fromA: Boolean) {
        (if (fromA) effectsA else effectsB) += effects
        for (effect in effects) {
            if (effect !is DrillEffect.Send) continue
            val shouldIntercept = if (fromA) interceptFromA else interceptFromB
            if (shouldIntercept) {
                intercepted += fromA to effect.bytes
            } else {
                queue.addLast(fromA to effect.bytes)
            }
        }
    }

    fun deliver(fromA: Boolean, bytes: ByteArray) {
        if (fromA) submit(b.onBytesReceived(bytes), fromA = false)
        else submit(a.onBytesReceived(bytes), fromA = true)
    }

    fun drain(maxIterations: Int = 500) {
        var iterations = 0
        while (queue.isNotEmpty()) {
            check(iterations++ < maxIterations) { "drill message loop did not settle" }
            val (fromA, bytes) = queue.removeFirst()
            deliver(fromA, bytes)
        }
    }

    fun connect() {
        submit(a.onPeerConnected(deviceB), fromA = true)
        submit(b.onPeerConnected(deviceA), fromA = false)
        drain()
    }

    fun tickBoth() {
        submit(a.onTick(), fromA = true)
        submit(b.onTick(), fromA = false)
        drain()
    }

    /** Runs the handshake, countdown and start so a test can begin at RUNNING. */
    fun startRunning(config: DrillConfig = DrillConfig.DEFAULT) {
        connect()
        advance(config.countdownMs + 10L)
        tickBoth()
    }

    inline fun <reified T : DrillEffect> aEffects(): List<T> = effectsA.filterIsInstance<T>()

    inline fun <reified T : DrillEffect> bEffects(): List<T> = effectsB.filterIsInstance<T>()
}

class BuddyDrillHandshakeTest {

    @Test
    fun `handshake elects the lower device id as host, deterministically`() {
        val harness = DrillHarness()
        harness.connect()

        // No negotiation round and no coin flip: both sides compute the same answer, so a lost
        // frame can never leave the pair disagreeing about who is host.
        assertThat(harness.a.role).isEqualTo(DrillRole.HOST)
        assertThat(harness.b.role).isEqualTo(DrillRole.GUEST)
        assertThat(harness.aEffects<DrillEffect.RoleAssigned>().single().role)
            .isEqualTo(DrillRole.HOST)
        assertThat(harness.bEffects<DrillEffect.RoleAssigned>().single().role)
            .isEqualTo(DrillRole.GUEST)
    }

    @Test
    fun `role election is stable when the id order is reversed`() {
        val harness = DrillHarness(deviceA = "device-zzz", deviceB = "device-aaa")
        harness.connect()
        assertThat(harness.a.role).isEqualTo(DrillRole.GUEST)
        assertThat(harness.b.role).isEqualTo(DrillRole.HOST)
    }

    @Test
    fun `both devices derive the same scenario seed`() {
        val harness = DrillHarness()
        harness.connect()
        assertThat(harness.a.scenarioSeed).isEqualTo(harness.b.scenarioSeed)
        assertThat(harness.a.scenarioSeed).isAtLeast(0L)
    }

    @Test
    fun `handshake leads into a countdown then the scenario starts`() {
        val harness = DrillHarness()
        harness.connect()

        assertThat(harness.a.phase).isEqualTo(DrillPhase.COUNTDOWN)
        assertThat(harness.aEffects<DrillEffect.CountdownStarted>()).hasSize(1)
        assertThat(harness.aEffects<DrillEffect.StartScenario>()).isEmpty()

        harness.advance(DrillConfig.DEFAULT.countdownMs + 1)
        harness.tickBoth()

        assertThat(harness.a.phase).isEqualTo(DrillPhase.RUNNING)
        assertThat(harness.b.phase).isEqualTo(DrillPhase.RUNNING)
        val start = harness.aEffects<DrillEffect.StartScenario>().single()
        assertThat(start.scenarioId).isEqualTo("gas-confined-buddy")
        assertThat(start.role).isEqualTo(DrillRole.HOST)
    }

    @Test
    fun `clock skew between devices does not affect the drill`() {
        // Machine B's clock is hours ahead of A's. Only host-relative logical time is compared.
        val harness = DrillHarness()
        harness.startRunning()
        assertThat(harness.a.phase).isEqualTo(DrillPhase.RUNNING)
        assertThat(harness.b.phase).isEqualTo(DrillPhase.RUNNING)
    }

    @Test
    fun `the same worker on both phones is refused`() {
        val harness = DrillHarness(workerA = "JH-DHN-001-W00007", workerB = "JH-DHN-001-W00007")
        harness.connect()

        assertThat(harness.a.phase).isEqualTo(DrillPhase.ABORTED)
        assertThat(harness.a.abortedBecause)
            .isEqualTo(DrillAbortReason.SAME_WORKER_ON_BOTH_DEVICES)
        assertThat(harness.b.abortedBecause)
            .isEqualTo(DrillAbortReason.SAME_WORKER_ON_BOTH_DEVICES)
    }

    @Test
    fun `a scenario mismatch is refused`() {
        val harness = DrillHarness(scenarioIdB = "fire-evac-full")
        harness.connect()
        assertThat(harness.a.abortedBecause).isEqualTo(DrillAbortReason.SCENARIO_MISMATCH)
    }

    @Test
    fun `a catalog version mismatch is refused`() {
        // Different catalogs mean different step timings, so the two scores would not be
        // comparable and the pair would see different prompts.
        val harness = DrillHarness(catalogVersionB = 2)
        harness.connect()
        assertThat(harness.a.abortedBecause).isEqualTo(DrillAbortReason.SCENARIO_MISMATCH)
    }

    @Test
    fun `handshake times out when no peer ever appears`() {
        val harness = DrillHarness()
        harness.submit(harness.a.begin(), fromA = true)
        harness.advance(DrillConfig.DEFAULT.handshakeTimeoutMs + 1)
        harness.submit(harness.a.onTick(), fromA = true)

        assertThat(harness.a.phase).isEqualTo(DrillPhase.ABORTED)
        assertThat(harness.a.abortedBecause).isEqualTo(DrillAbortReason.HANDSHAKE_TIMEOUT)
    }

    @Test
    fun `begin is idempotent`() {
        val harness = DrillHarness()
        harness.submit(harness.a.begin(), fromA = true)
        assertThat(harness.a.begin()).isEmpty()
        assertThat(harness.a.phase).isEqualTo(DrillPhase.HANDSHAKE)
    }

    @Test
    fun `a third device in range is ignored`() {
        val harness = DrillHarness()
        harness.connect()

        val thirdWheel = BuddyDrillMachine(
            "device-mmm",
            "JH-DHN-001-W00099",
            "gas-confined-buddy",
            1,
            "1.0.0",
            FixedMonotonicTimeSource(500L),
        )
        val intruderFrames = thirdWheel.onPeerConnected(harness.deviceA)
            .filterIsInstance<DrillEffect.Send>()

        val phaseBefore = harness.a.phase
        intruderFrames.forEach { harness.submit(harness.a.onBytesReceived(it.bytes), fromA = true) }

        assertThat(harness.a.phase).isEqualTo(phaseBefore)
        assertThat(harness.a.connectedPeerDeviceId).isEqualTo(harness.deviceB)
    }

    @Test
    fun `a device ignores its own echoed frames`() {
        val harness = DrillHarness()
        harness.submit(harness.a.onPeerConnected(harness.deviceB), fromA = true)
        val ownFrame = harness.effectsA.filterIsInstance<DrillEffect.Send>().first().bytes

        val effects = harness.a.onBytesReceived(ownFrame)
        assertThat(effects).isEmpty()
        assertThat(harness.a.phase).isEqualTo(DrillPhase.HANDSHAKE)
    }
}

class BuddyDrillRunTest {

    @Test
    fun `actions are mirrored to the peer`() {
        val harness = DrillHarness()
        harness.startRunning()

        harness.submit(harness.a.onLocalAction("gas_ppe_select", listOf("ppe_scba"), true), fromA = true)
        harness.drain()

        val peerAction = harness.bEffects<DrillEffect.PeerAction>().single()
        assertThat(peerAction.stepId).isEqualTo("gas_ppe_select")
        assertThat(peerAction.optionIds).containsExactly("ppe_scba")
        assertThat(peerAction.peerWasCorrect).isTrue()
    }

    @Test
    fun `buddy checks reach the peer`() {
        val harness = DrillHarness()
        harness.startRunning()

        harness.submit(harness.a.onLocalBuddyCheck("buddy_periodic_check"), fromA = true)
        harness.drain()

        assertThat(harness.bEffects<DrillEffect.PeerCheckedIn>().single().stepId)
            .isEqualTo("buddy_periodic_check")
    }

    @Test
    fun `only the host may trigger the distress event`() {
        val harness = DrillHarness()
        harness.startRunning()

        // The guest trying is a no-op: one distress event, at one moment, agreed by both.
        assertThat(harness.b.triggerDistress("buddy_distress_response")).isEmpty()
        assertThat(harness.b.phase).isEqualTo(DrillPhase.RUNNING)

        harness.submit(harness.a.triggerDistress("buddy_distress_response"), fromA = true)
        harness.drain()

        assertThat(harness.a.phase).isEqualTo(DrillPhase.DISTRESS_WINDOW)
        assertThat(harness.b.phase).isEqualTo(DrillPhase.DISTRESS_WINDOW)
        assertThat(harness.bEffects<DrillEffect.DistressTriggered>().single().stepId)
            .isEqualTo("buddy_distress_response")
        assertThat(harness.bEffects<DrillEffect.DistressTriggered>().single().respondWithinMs)
            .isEqualTo(DrillConfig.DEFAULT.distressWindowMs)
    }

    @Test
    fun `a rescue action during the distress window reaches the peer`() {
        val harness = DrillHarness()
        harness.startRunning()
        harness.submit(harness.a.triggerDistress("buddy_distress_response"), fromA = true)
        harness.drain()

        harness.submit(
            harness.b.onLocalAction("buddy_distress_response", listOf("signal_alarm_and_call_rescue"), true),
            fromA = false,
        )
        harness.drain()

        assertThat(harness.aEffects<DrillEffect.PeerRescueAction>().single().optionIds)
            .containsExactly("signal_alarm_and_call_rescue")
    }

    @Test
    fun `a missed distress response closes the window without aborting`() {
        val harness = DrillHarness()
        harness.startRunning()
        harness.submit(harness.a.triggerDistress("buddy_distress_response"), fromA = true)
        harness.drain()

        harness.advance(DrillConfig.DEFAULT.distressWindowMs + 100L)
        harness.tickBoth()

        // The assessment engine already scored the missed response as a timeout; the drill goes on.
        assertThat(harness.a.phase).isEqualTo(DrillPhase.RUNNING)
        assertThat(harness.a.abortedBecause).isNull()
    }

    @Test
    fun `both results complete the drill`() {
        val harness = DrillHarness()
        harness.startRunning()

        harness.submit(harness.a.onLocalResult(842, true), fromA = true)
        harness.submit(harness.b.onLocalResult(790, true), fromA = false)
        harness.drain()

        assertThat(harness.a.phase).isEqualTo(DrillPhase.COMPLETE)
        assertThat(harness.b.phase).isEqualTo(DrillPhase.COMPLETE)
        assertThat(harness.a.bothSidesCompleted).isTrue()
        assertThat(harness.b.bothSidesCompleted).isTrue()

        val completedOnA = harness.aEffects<DrillEffect.Completed>().single()
        assertThat(completedOnA.peerScorePermille).isEqualTo(790)
        assertThat(completedOnA.peerPassed).isTrue()
        assertThat(completedOnA.peerDeviceId).isEqualTo(harness.deviceB)
    }

    @Test
    fun `a one sided result does not count as a paired drill`() {
        val harness = DrillHarness()
        harness.startRunning()

        harness.submit(harness.a.onLocalResult(842, true), fromA = true)
        harness.interceptFromB = true
        harness.drain()

        assertThat(harness.a.phase).isEqualTo(DrillPhase.RESULT_EXCHANGE)
        assertThat(harness.a.bothSidesCompleted).isFalse()
    }

    @Test
    fun `a stalled result exchange times out`() {
        val harness = DrillHarness()
        harness.startRunning()
        harness.interceptFromB = true

        harness.submit(harness.a.onLocalResult(842, true), fromA = true)
        harness.drain()
        harness.advance(DrillConfig.DEFAULT.resultExchangeTimeoutMs + 100L)
        harness.submit(harness.a.onTick(), fromA = true)

        assertThat(harness.a.phase).isEqualTo(DrillPhase.ABORTED)
        assertThat(harness.a.abortedBecause).isEqualTo(DrillAbortReason.RESULT_EXCHANGE_TIMEOUT)
    }
}

class BuddyDrillResilienceTest {

    @Test
    fun `duplicate frames are applied once`() {
        val harness = DrillHarness()
        harness.startRunning()

        harness.interceptFromA = true
        harness.submit(harness.a.onLocalAction("s1", listOf("o1"), true), fromA = true)
        val (fromA, bytes) = harness.intercepted.single()
        harness.interceptFromA = false

        // The transport is allowed to redeliver freely.
        harness.deliver(fromA, bytes)
        harness.deliver(fromA, bytes)
        harness.deliver(fromA, bytes)
        harness.drain()

        assertThat(harness.bEffects<DrillEffect.PeerAction>()).hasSize(1)
    }

    @Test
    fun `out of order frames are buffered then applied in order`() {
        val harness = DrillHarness()
        harness.startRunning()

        harness.interceptFromA = true
        harness.submit(harness.a.onLocalAction("first", listOf("o1"), true), fromA = true)
        harness.submit(harness.a.onLocalAction("second", listOf("o2"), false), fromA = true)
        harness.submit(harness.a.onLocalAction("third", listOf("o3"), true), fromA = true)
        val frames = harness.intercepted.toList()
        harness.intercepted.clear()
        harness.interceptFromA = false

        // Arrive 3, 1, 2.
        harness.deliver(frames[2].first, frames[2].second)
        assertThat(harness.bEffects<DrillEffect.PeerAction>()).isEmpty()

        harness.deliver(frames[0].first, frames[0].second)
        harness.deliver(frames[1].first, frames[1].second)
        harness.drain()

        assertThat(harness.bEffects<DrillEffect.PeerAction>().map { it.stepId })
            .containsExactly("first", "second", "third").inOrder()
    }

    @Test
    fun `an overfull reorder buffer skips the gap instead of deadlocking`() {
        val config = DrillConfig(maxBufferedFrames = 3)
        val harness = DrillHarness(config = config)
        harness.startRunning(config)

        harness.interceptFromA = true
        repeat(6) { index ->
            harness.submit(harness.a.onLocalAction("step$index", listOf("o"), true), fromA = true)
        }
        val frames = harness.intercepted.toList()
        harness.intercepted.clear()
        harness.interceptFromA = false

        // Deliver everything except the first, so the buffer overflows.
        frames.drop(1).forEach { harness.deliver(it.first, it.second) }
        harness.drain()

        // A stalled peer must degrade the drill, never deadlock it.
        assertThat(harness.bEffects<DrillEffect.SequenceGapSkipped>()).isNotEmpty()
        assertThat(harness.bEffects<DrillEffect.PeerAction>()).isNotEmpty()
        assertThat(harness.b.phase).isNotEqualTo(DrillPhase.ABORTED)
    }

    @Test
    fun `heartbeats keep the peer healthy`() {
        val harness = DrillHarness()
        harness.startRunning()

        repeat(10) {
            harness.advance(DrillConfig.DEFAULT.heartbeatIntervalMs)
            harness.tickBoth()
        }
        assertThat(harness.a.peerHealth).isEqualTo(PeerHealth.HEALTHY)
        assertThat(harness.a.phase).isEqualTo(DrillPhase.RUNNING)
    }

    @Test
    fun `three missed heartbeats warn without aborting`() {
        val harness = DrillHarness()
        harness.startRunning()
        harness.interceptFromB = true

        harness.advance(DrillConfig.DEFAULT.peerStaleAfterMs + 100L)
        harness.submit(harness.a.onTick(), fromA = true)

        assertThat(harness.a.peerHealth).isEqualTo(PeerHealth.STALE)
        assertThat(harness.a.phase).isEqualTo(DrillPhase.RUNNING)
        assertThat(harness.aEffects<DrillEffect.PeerHealthChanged>().last().health)
            .isEqualTo(PeerHealth.STALE)
    }

    @Test
    fun `ten seconds of silence aborts and reports peer lost`() {
        // The caller still scores and saves the partial run, so a radio glitch never costs a
        // worker the steps they already did.
        val harness = DrillHarness()
        harness.startRunning()
        harness.interceptFromB = true

        harness.advance(DrillConfig.DEFAULT.peerLostAfterMs + 100L)
        harness.submit(harness.a.onTick(), fromA = true)

        assertThat(harness.a.phase).isEqualTo(DrillPhase.ABORTED)
        assertThat(harness.a.abortedBecause).isEqualTo(DrillAbortReason.PEER_LOST)
        assertThat(harness.aEffects<DrillEffect.Aborted>().single().reason)
            .isEqualTo(DrillAbortReason.PEER_LOST)
    }

    @Test
    fun `health recovers when the peer comes back before the deadline`() {
        val harness = DrillHarness()
        harness.startRunning()

        harness.interceptFromB = true
        harness.advance(DrillConfig.DEFAULT.peerStaleAfterMs + 100L)
        harness.submit(harness.a.onTick(), fromA = true)
        assertThat(harness.a.peerHealth).isEqualTo(PeerHealth.STALE)

        harness.interceptFromB = false
        harness.intercepted.clear()
        harness.submit(harness.b.onLocalBuddyCheck("recovered"), fromA = false)
        harness.drain()

        assertThat(harness.a.peerHealth).isEqualTo(PeerHealth.HEALTHY)
        assertThat(harness.a.phase).isEqualTo(DrillPhase.RUNNING)
    }

    @Test
    fun `a transport level disconnect aborts`() {
        val harness = DrillHarness()
        harness.startRunning()
        harness.submit(harness.a.onPeerDisconnected(), fromA = true)

        assertThat(harness.a.phase).isEqualTo(DrillPhase.ABORTED)
        assertThat(harness.a.abortedBecause).isEqualTo(DrillAbortReason.PEER_LOST)
    }

    @Test
    fun `a protocol version mismatch aborts with a clear reason`() {
        val harness = DrillHarness()
        harness.startRunning()

        val futureFrame = DrillFrameCodec.encode(
            DrillFrame(
                protocolVersion = DrillFrameCodec.PROTOCOL_VERSION + 1,
                type = DrillMessageType.ACTION,
                senderSeq = 99L,
                logicalMs = 1_000L,
                senderDeviceId = harness.deviceB,
            ),
        )
        harness.submit(harness.a.onBytesReceived(futureFrame), fromA = true)

        assertThat(harness.a.phase).isEqualTo(DrillPhase.ABORTED)
        assertThat(harness.a.abortedBecause).isEqualTo(DrillAbortReason.PEER_VERSION_MISMATCH)
    }

    @Test
    fun `a few malformed frames are tolerated`() {
        val harness = DrillHarness()
        harness.startRunning()

        repeat(3) { harness.submit(harness.a.onBytesReceived(byteArrayOf(1, 2)), fromA = true) }
        assertThat(harness.a.phase).isEqualTo(DrillPhase.RUNNING)
    }

    @Test
    fun `sustained malformed frames abort the drill`() {
        val harness = DrillHarness()
        harness.startRunning()

        repeat(DrillConfig.DEFAULT.maxConsecutiveMalformed) {
            harness.submit(harness.a.onBytesReceived(byteArrayOf(1, 2, 3)), fromA = true)
        }
        assertThat(harness.a.phase).isEqualTo(DrillPhase.ABORTED)
        assertThat(harness.a.abortedBecause)
            .isEqualTo(DrillAbortReason.TOO_MANY_MALFORMED_FRAMES)
    }

    @Test
    fun `a valid frame resets the malformed counter`() {
        val harness = DrillHarness()
        harness.startRunning()

        repeat(DrillConfig.DEFAULT.maxConsecutiveMalformed - 1) {
            harness.submit(harness.a.onBytesReceived(byteArrayOf(9)), fromA = true)
        }
        harness.submit(harness.b.onLocalBuddyCheck("ok"), fromA = false)
        harness.drain()
        repeat(DrillConfig.DEFAULT.maxConsecutiveMalformed - 1) {
            harness.submit(harness.a.onBytesReceived(byteArrayOf(9)), fromA = true)
        }

        assertThat(harness.a.phase).isNotEqualTo(DrillPhase.ABORTED)
    }

    @Test
    fun `a local cancel tells the peer`() {
        val harness = DrillHarness()
        harness.startRunning()

        harness.submit(harness.a.onLocalAbort(), fromA = true)
        harness.drain()

        assertThat(harness.a.abortedBecause).isEqualTo(DrillAbortReason.USER_CANCELLED)
        assertThat(harness.b.abortedBecause).isEqualTo(DrillAbortReason.PEER_CANCELLED)
        assertThat(harness.b.phase).isEqualTo(DrillPhase.ABORTED)
    }

    @Test
    fun `everything after an abort is inert`() {
        val harness = DrillHarness()
        harness.startRunning()
        harness.submit(harness.a.onLocalAbort(), fromA = true)
        harness.drain()

        assertThat(harness.a.onLocalAction("s", listOf("o"), true)).isEmpty()
        assertThat(harness.a.onTick()).isEmpty()
        assertThat(harness.a.onLocalBuddyCheck("s")).isEmpty()
        assertThat(harness.a.triggerDistress("s")).isEmpty()
        assertThat(harness.a.onLocalResult(900, true)).isEmpty()
        assertThat(harness.a.onLocalAbort()).isEmpty()
        assertThat(harness.a.onPeerDisconnected()).isEmpty()
        assertThat(harness.a.onBytesReceived(byteArrayOf(1, 2, 3))).isEmpty()
    }

    @Test
    fun `actions outside the running phases are dropped`() {
        val harness = DrillHarness()
        harness.connect()
        assertThat(harness.a.phase).isEqualTo(DrillPhase.COUNTDOWN)
        assertThat(harness.a.onLocalAction("s", listOf("o"), true)).isEmpty()
    }

    @Test
    fun `ticking an idle machine does nothing`() {
        val harness = DrillHarness()
        assertThat(harness.a.onTick()).isEmpty()
        assertThat(harness.a.phase).isEqualTo(DrillPhase.IDLE)
    }

    @Test
    fun `phase helpers are consistent`() {
        assertThat(DrillPhase.IDLE.isActive).isFalse()
        assertThat(DrillPhase.RUNNING.isActive).isTrue()
        assertThat(DrillPhase.COMPLETE.isTerminal).isTrue()
        assertThat(DrillPhase.ABORTED.isTerminal).isTrue()
        assertThat(DrillPhase.RESULT_EXCHANGE.isTerminal).isFalse()
    }
}
