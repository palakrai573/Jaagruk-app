package org.jaagruk.core.drill

import org.jaagruk.core.util.CanonicalFormatException
import org.jaagruk.core.util.MonotonicTimeSource
import org.jaagruk.core.util.SystemMonotonicTimeSource

enum class DrillPhase {
    IDLE,
    HANDSHAKE,
    ROLE_ASSIGNED,
    COUNTDOWN,
    RUNNING,
    DISTRESS_WINDOW,
    RESULT_EXCHANGE,
    COMPLETE,
    ABORTED,
    ;

    val isActive: Boolean
        get() = this != IDLE && this != COMPLETE && this != ABORTED

    val isTerminal: Boolean get() = this == COMPLETE || this == ABORTED
}

enum class DrillRole {
    UNDECIDED,
    HOST,
    GUEST,
}

enum class PeerHealth {
    UNKNOWN,
    HEALTHY,

    /** Heartbeats missed but not yet lost. The drill keeps running with a warning. */
    STALE,
    LOST,
}

enum class DrillAbortReason {
    PEER_LOST,
    PEER_VERSION_MISMATCH,
    SAME_WORKER_ON_BOTH_DEVICES,
    SCENARIO_MISMATCH,
    USER_CANCELLED,
    PEER_CANCELLED,
    TOO_MANY_MALFORMED_FRAMES,
    HANDSHAKE_TIMEOUT,
    RESULT_EXCHANGE_TIMEOUT,
}

/** Side effects the hosting app performs. The machine itself does no I/O. */
sealed interface DrillEffect {
    /** Hand these bytes to the transport. */
    class Send(val bytes: ByteArray, val type: DrillMessageType) : DrillEffect

    class PhaseChanged(val phase: DrillPhase) : DrillEffect

    class RoleAssigned(val role: DrillRole, val peerDeviceId: String) : DrillEffect

    class PeerHealthChanged(val health: PeerHealth) : DrillEffect

    /** Countdown before the scenario begins, so both workers start together. */
    class CountdownStarted(val millis: Long) : DrillEffect

    class StartScenario(val scenarioId: String, val seed: Long, val role: DrillRole) : DrillEffect

    class PeerAction(
        val stepId: String,
        val optionIds: List<String>,
        val peerWasCorrect: Boolean,
    ) : DrillEffect

    /** The peer confirmed they are alright during a periodic buddy check. */
    class PeerCheckedIn(val stepId: String) : DrillEffect

    /** The simulated collapse has happened; the responder's window is open. */
    class DistressTriggered(val stepId: String, val respondWithinMs: Long) : DrillEffect

    class PeerRescueAction(val stepId: String, val optionIds: List<String>) : DrillEffect

    class PeerResult(val scorePermille: Int, val passed: Boolean) : DrillEffect

    class Completed(
        val peerDeviceId: String,
        val peerScorePermille: Int?,
        val peerPassed: Boolean?,
    ) : DrillEffect

    class Aborted(val reason: DrillAbortReason, val peerDeviceId: String?) : DrillEffect

    /** A gap in the peer's sequence had to be skipped. Recorded, not fatal. */
    class SequenceGapSkipped(val fromSeq: Long, val toSeq: Long) : DrillEffect
}

/**
 * Transport-agnostic state machine for a two-device buddy drill.
 *
 * The buddy system is two humans coordinating under stress. Simulating the second human as an
 * NPC — which is the simpler build, and the one most implementations settle for — trains none of
 * the skill actually being certified. So this runs across two real phones over Nearby
 * Connections, with no internet, no cell signal and no shared network, because underground sites
 * have none of those.
 *
 * `:core` deliberately knows nothing about Nearby Connections. The host app supplies bytes in and
 * takes [DrillEffect.Send] bytes out, which means the whole protocol — role election, duplicate
 * suppression, reordering, heartbeat loss, version mismatch, partner abandonment — is driven
 * deterministically in unit tests with two machines wired to each other and a fake clock.
 *
 * Design commitments that make it robust rather than merely optimistic:
 *
 *  * **Deterministic role election.** The lexicographically smaller device id is host. No
 *    negotiation round, no tie-break, no coin flip that can disagree across devices.
 *  * **Host-relative logical time only.** Wall clocks are never compared.
 *  * **Losing the peer never loses the worker's progress.** A drop produces
 *    [DrillAbortReason.PEER_LOST] and the partial run is still scored and saved by the caller.
 */
class BuddyDrillMachine(
    val localDeviceId: String,
    val localWorkerId: String,
    val scenarioId: String,
    val catalogVersion: Int,
    val appVersion: String,
    private val monotonic: MonotonicTimeSource = SystemMonotonicTimeSource,
    private val config: DrillConfig = DrillConfig.DEFAULT,
) {

    init {
        require(localDeviceId.isNotBlank()) { "localDeviceId must not be blank" }
        require(localWorkerId.isNotBlank()) { "localWorkerId must not be blank" }
        require(scenarioId.isNotBlank()) { "scenarioId must not be blank" }
    }

    private var _phase: DrillPhase = DrillPhase.IDLE
    private var _role: DrillRole = DrillRole.UNDECIDED
    private var _peerHealth: PeerHealth = PeerHealth.UNKNOWN

    private var peerDeviceId: String? = null
    private var peerWorkerId: String? = null
    private var seed: Long = 0L

    private var outboundSeq: Long = 0L
    private var nextExpectedPeerSeq: Long = 1L
    private val pendingFrames = HashMap<Long, DrillFrame>()
    private val recentPeerSeqs = LinkedHashSet<Long>()

    private var startMonotonicMs: Long = 0L
    private var lastPeerFrameAtMs: Long = 0L
    private var lastHeartbeatSentAtMs: Long = 0L
    private var phaseEnteredAtMs: Long = 0L
    private var distressOpenedAtMs: Long = 0L

    private var consecutiveMalformed: Int = 0
    private var localReadySent: Boolean = false
    private var peerReadyReceived: Boolean = false
    private var localResultSent: Boolean = false
    private var peerScorePermille: Int? = null
    private var peerPassed: Boolean? = null
    private var abortReason: DrillAbortReason? = null
    private var distressStepId: String? = null

    val phase: DrillPhase get() = _phase
    val role: DrillRole get() = _role
    val peerHealth: PeerHealth get() = _peerHealth
    val connectedPeerDeviceId: String? get() = peerDeviceId
    val abortedBecause: DrillAbortReason? get() = abortReason
    val scenarioSeed: Long get() = seed

    /** True only when both devices genuinely completed. Gates the buddy certificate flag. */
    val bothSidesCompleted: Boolean
        get() = _phase == DrillPhase.COMPLETE && localResultSent && peerScorePermille != null

    // =======================================================================
    // Local lifecycle
    // =======================================================================

    /** Begins searching. Call once the transport is up but before a peer is known. */
    fun begin(): List<DrillEffect> {
        if (_phase != DrillPhase.IDLE) return emptyList()
        val now = monotonic.elapsedMillis()
        startMonotonicMs = now
        lastPeerFrameAtMs = now
        return transitionTo(DrillPhase.HANDSHAKE)
    }

    /** The transport has connected to [remoteDeviceId]. Sends HELLO. */
    fun onPeerConnected(remoteDeviceId: String): List<DrillEffect> {
        if (_phase.isTerminal) return emptyList()

        val effects = mutableListOf<DrillEffect>()
        // Forward begin()'s phase change rather than swallowing it, so a caller that connects
        // without calling begin() first still sees the complete effect stream.
        if (_phase == DrillPhase.IDLE) effects += begin()

        // P2P_STAR can offer more than one peer. The first accepted connection wins; anything
        // else is politely ignored rather than allowed to corrupt an in-flight drill.
        val existing = peerDeviceId
        if (existing != null && existing != remoteDeviceId) return effects

        peerDeviceId = remoteDeviceId
        lastPeerFrameAtMs = monotonic.elapsedMillis()

        effects += send(
            DrillMessageType.HELLO,
            mapOf(
                DrillFrame.KEY_WORKER_ID to localWorkerId,
                DrillFrame.KEY_SCENARIO_ID to scenarioId,
                DrillFrame.KEY_CATALOG_VERSION to catalogVersion.toString(),
                DrillFrame.KEY_APP_VERSION to appVersion,
            ),
        )
        return effects
    }

    fun onLocalAbort(reason: DrillAbortReason = DrillAbortReason.USER_CANCELLED): List<DrillEffect> {
        if (_phase.isTerminal) return emptyList()
        val effects = mutableListOf<DrillEffect>()
        if (peerDeviceId != null) {
            effects += send(DrillMessageType.ABORT, mapOf(DrillFrame.KEY_REASON to reason.name))
        }
        effects += abortLocally(reason)
        return effects
    }

    /** The transport reports the link is gone. */
    fun onPeerDisconnected(): List<DrillEffect> {
        if (_phase.isTerminal) return emptyList()
        return abortLocally(DrillAbortReason.PEER_LOST)
    }

    /** Records the local worker's answer and mirrors it to the peer. */
    fun onLocalAction(
        stepId: String,
        optionIds: List<String>,
        wasCorrect: Boolean,
    ): List<DrillEffect> {
        if (_phase != DrillPhase.RUNNING && _phase != DrillPhase.DISTRESS_WINDOW) return emptyList()
        val type = if (_phase == DrillPhase.DISTRESS_WINDOW) {
            DrillMessageType.RESCUE_ACTION
        } else {
            DrillMessageType.ACTION
        }
        return listOf(
            send(
                type,
                mapOf(
                    DrillFrame.KEY_STEP_ID to stepId,
                    DrillFrame.KEY_OPTIONS to optionIds.joinToString(","),
                    DrillFrame.KEY_CORRECT to if (wasCorrect) "1" else "0",
                ),
            ),
        )
    }

    /** Tells the peer this worker has confirmed they are alright. */
    fun onLocalBuddyCheck(stepId: String): List<DrillEffect> {
        if (!_phase.isActive) return emptyList()
        return listOf(
            send(DrillMessageType.CHECK_BUDDY, mapOf(DrillFrame.KEY_STEP_ID to stepId)),
        )
    }

    /**
     * Host-only: fires the simulated collapse.
     *
     * Only the host may trigger it, so both devices agree on exactly one distress event at one
     * moment. If the guest could also trigger, a lost frame would give the two workers different
     * pictures of who is in trouble.
     */
    fun triggerDistress(stepId: String): List<DrillEffect> {
        if (_phase != DrillPhase.RUNNING) return emptyList()
        if (_role != DrillRole.HOST) return emptyList()

        distressStepId = stepId
        distressOpenedAtMs = monotonic.elapsedMillis()
        val effects = mutableListOf<DrillEffect>()
        effects += send(DrillMessageType.DISTRESS_TRIGGER, mapOf(DrillFrame.KEY_STEP_ID to stepId))
        effects += transitionTo(DrillPhase.DISTRESS_WINDOW)
        effects += DrillEffect.DistressTriggered(stepId, config.distressWindowMs)
        return effects
    }

    fun onLocalResult(scorePermille: Int, passed: Boolean): List<DrillEffect> {
        require(scorePermille in 0..1000) { "scorePermille must be 0..1000, got $scorePermille" }
        if (_phase.isTerminal) return emptyList()

        localResultSent = true
        val effects = mutableListOf<DrillEffect>()
        effects += send(
            DrillMessageType.RESULT,
            mapOf(
                DrillFrame.KEY_SCORE to scorePermille.toString(),
                DrillFrame.KEY_PASSED to if (passed) "1" else "0",
            ),
        )
        if (_phase != DrillPhase.RESULT_EXCHANGE) {
            effects += transitionTo(DrillPhase.RESULT_EXCHANGE)
        }
        effects += maybeComplete()
        return effects
    }

    // =======================================================================
    // Inbound
    // =======================================================================

    /**
     * Feeds raw transport bytes in.
     *
     * Never throws. A malformed frame is dropped and counted; enough of them in a row aborts the
     * drill with a reason, because a peer producing garbage is worse than no peer.
     */
    fun onBytesReceived(bytes: ByteArray): List<DrillEffect> {
        if (_phase.isTerminal) return emptyList()

        val frame = try {
            DrillFrameCodec.decode(bytes)
        } catch (e: CanonicalFormatException) {
            consecutiveMalformed++
            return if (consecutiveMalformed >= config.maxConsecutiveMalformed) {
                abortLocally(DrillAbortReason.TOO_MANY_MALFORMED_FRAMES)
            } else {
                emptyList()
            }
        }
        consecutiveMalformed = 0

        if (frame.protocolVersion != DrillFrameCodec.PROTOCOL_VERSION) {
            return abortLocally(DrillAbortReason.PEER_VERSION_MISMATCH)
        }
        if (frame.senderDeviceId == localDeviceId) {
            // Our own frame echoed back by the transport. Ignore rather than self-desynchronise.
            return emptyList()
        }

        val knownPeer = peerDeviceId
        if (knownPeer == null) {
            peerDeviceId = frame.senderDeviceId
        } else if (knownPeer != frame.senderDeviceId) {
            // A third device. Not an error, just not part of this drill.
            return emptyList()
        }

        lastPeerFrameAtMs = monotonic.elapsedMillis()
        val effects = mutableListOf<DrillEffect>()
        effects += refreshPeerHealth(PeerHealth.HEALTHY)

        // Unordered frames (heartbeats) carry sequence 0 and are exempt from both ordering and
        // duplicate suppression. They must be, on both counts: they carry no state worth
        // deduplicating, and if they consumed sequence numbers they would leave a permanent hole
        // in the ordered stream that every later frame would queue behind forever.
        if (frame.senderSeq == UNORDERED_SEQ) return effects

        // Duplicate suppression: the transport may redeliver freely.
        if (frame.senderSeq in recentPeerSeqs) return effects
        rememberSeq(frame.senderSeq)

        when {
            frame.senderSeq == nextExpectedPeerSeq -> {
                effects += applyFrame(frame)
                nextExpectedPeerSeq++
                effects += drainPending()
            }

            frame.senderSeq > nextExpectedPeerSeq -> {
                pendingFrames[frame.senderSeq] = frame
                if (pendingFrames.size > config.maxBufferedFrames) {
                    // A stalled peer must degrade the drill, never deadlock it. Force the oldest
                    // buffered frame through and record the gap.
                    val lowest = pendingFrames.keys.min()
                    effects += DrillEffect.SequenceGapSkipped(nextExpectedPeerSeq, lowest)
                    nextExpectedPeerSeq = lowest
                    effects += drainPending()
                }
            }

            else -> {
                // Late arrival for an already-applied slot. Nothing to do.
            }
        }
        return effects
    }

    /** Drives heartbeats, the countdown and every timeout. Safe to call at any rate. */
    fun onTick(): List<DrillEffect> {
        if (_phase.isTerminal || _phase == DrillPhase.IDLE) return emptyList()
        val now = monotonic.elapsedMillis()
        val effects = mutableListOf<DrillEffect>()

        // Heartbeat out.
        if (peerDeviceId != null && now - lastHeartbeatSentAtMs >= config.heartbeatIntervalMs) {
            lastHeartbeatSentAtMs = now
            effects += send(DrillMessageType.HEARTBEAT, emptyMap())
        }

        // Peer health in.
        if (peerDeviceId != null) {
            val silence = now - lastPeerFrameAtMs
            when {
                silence >= config.peerLostAfterMs -> {
                    effects += refreshPeerHealth(PeerHealth.LOST)
                    effects += abortLocally(DrillAbortReason.PEER_LOST)
                    return effects
                }

                silence >= config.peerStaleAfterMs -> effects += refreshPeerHealth(PeerHealth.STALE)
                else -> effects += refreshPeerHealth(PeerHealth.HEALTHY)
            }
        }

        when (_phase) {
            DrillPhase.HANDSHAKE -> {
                if (now - phaseEnteredAtMs >= config.handshakeTimeoutMs) {
                    effects += abortLocally(DrillAbortReason.HANDSHAKE_TIMEOUT)
                }
            }

            DrillPhase.COUNTDOWN -> {
                if (now - phaseEnteredAtMs >= config.countdownMs) {
                    effects += transitionTo(DrillPhase.RUNNING)
                    effects += DrillEffect.StartScenario(scenarioId, seed, _role)
                }
            }

            DrillPhase.DISTRESS_WINDOW -> {
                if (now - distressOpenedAtMs >= config.distressWindowMs) {
                    // The window closing is not an abort. The assessment engine has already
                    // scored the missed response as a timeout; the drill continues.
                    effects += transitionTo(DrillPhase.RUNNING)
                }
            }

            DrillPhase.RESULT_EXCHANGE -> {
                if (now - phaseEnteredAtMs >= config.resultExchangeTimeoutMs) {
                    // Our own result is safe locally; only the peer's copy is missing.
                    effects += abortLocally(DrillAbortReason.RESULT_EXCHANGE_TIMEOUT)
                }
            }

            DrillPhase.IDLE,
            DrillPhase.ROLE_ASSIGNED,
            DrillPhase.RUNNING,
            DrillPhase.COMPLETE,
            DrillPhase.ABORTED,
            -> Unit
        }
        return effects
    }

    // =======================================================================
    // Frame handling
    // =======================================================================

    private fun applyFrame(frame: DrillFrame): List<DrillEffect> = when (frame.type) {
        DrillMessageType.HELLO -> handleHello(frame)
        DrillMessageType.ROLE_ASSIGN -> emptyList() // Role is derived, never dictated.
        DrillMessageType.SCENARIO_SEED -> handleScenarioSeed(frame)
        DrillMessageType.READY -> handleReady()
        DrillMessageType.STEP_ADVANCE -> emptyList() // Steps advance locally; kept for telemetry.
        DrillMessageType.ACTION -> handlePeerAction(frame)
        DrillMessageType.DISTRESS_TRIGGER -> handleDistressTrigger(frame)
        DrillMessageType.CHECK_BUDDY -> listOf(
            DrillEffect.PeerCheckedIn(frame.bodyValue(DrillFrame.KEY_STEP_ID).orEmpty()),
        )

        DrillMessageType.RESCUE_ACTION -> listOf(
            DrillEffect.PeerRescueAction(
                frame.bodyValue(DrillFrame.KEY_STEP_ID).orEmpty(),
                frame.bodyList(DrillFrame.KEY_OPTIONS),
            ),
        )

        DrillMessageType.HEARTBEAT -> emptyList()
        DrillMessageType.ABORT -> abortLocally(DrillAbortReason.PEER_CANCELLED)
        DrillMessageType.RESULT -> handlePeerResult(frame)
    }

    private fun handleHello(frame: DrillFrame): List<DrillEffect> {
        val remoteWorkerId = frame.bodyValue(DrillFrame.KEY_WORKER_ID)
        val remoteScenarioId = frame.bodyValue(DrillFrame.KEY_SCENARIO_ID)
        val remoteCatalogVersion = frame.bodyInt(DrillFrame.KEY_CATALOG_VERSION)

        if (remoteWorkerId.isNullOrBlank() || remoteScenarioId.isNullOrBlank()) {
            return abortLocally(DrillAbortReason.SCENARIO_MISMATCH)
        }
        // The same worker signed in on both phones is not a buddy drill. Refused with a clear
        // reason rather than quietly certifying a solo run as a paired one.
        if (remoteWorkerId == localWorkerId) {
            return abortLocally(DrillAbortReason.SAME_WORKER_ON_BOTH_DEVICES)
        }
        if (remoteScenarioId != scenarioId) {
            return abortLocally(DrillAbortReason.SCENARIO_MISMATCH)
        }
        // Different catalogs mean different step timings, so the two scores would not be
        // comparable and the pair would see different prompts.
        if (remoteCatalogVersion != null && remoteCatalogVersion != catalogVersion) {
            return abortLocally(DrillAbortReason.SCENARIO_MISMATCH)
        }

        peerWorkerId = remoteWorkerId
        val remoteDeviceId = frame.senderDeviceId

        // Deterministic election. Both devices compute the same answer with no extra round trip.
        _role = if (localDeviceId < remoteDeviceId) DrillRole.HOST else DrillRole.GUEST
        seed = deriveSeed(localDeviceId, remoteDeviceId, scenarioId)

        val effects = mutableListOf<DrillEffect>()
        effects += transitionTo(DrillPhase.ROLE_ASSIGNED)
        effects += DrillEffect.RoleAssigned(_role, remoteDeviceId)

        if (_role == DrillRole.HOST) {
            effects += send(
                DrillMessageType.SCENARIO_SEED,
                mapOf(
                    DrillFrame.KEY_SEED to seed.toString(),
                    DrillFrame.KEY_SCENARIO_ID to scenarioId,
                ),
            )
        }
        if (!localReadySent) {
            localReadySent = true
            effects += send(DrillMessageType.READY, emptyMap())
        }
        effects += maybeStartCountdown()
        return effects
    }

    private fun handleScenarioSeed(frame: DrillFrame): List<DrillEffect> {
        val remoteSeed = frame.bodyLong(DrillFrame.KEY_SEED)
        // The host is authoritative on the seed, so both devices generate identical scene layout.
        if (remoteSeed != null && _role == DrillRole.GUEST) seed = remoteSeed

        val effects = mutableListOf<DrillEffect>()
        if (!localReadySent) {
            localReadySent = true
            effects += send(DrillMessageType.READY, emptyMap())
        }
        effects += maybeStartCountdown()
        return effects
    }

    private fun handleReady(): List<DrillEffect> {
        peerReadyReceived = true
        return maybeStartCountdown()
    }

    private fun handlePeerAction(frame: DrillFrame): List<DrillEffect> = listOf(
        DrillEffect.PeerAction(
            stepId = frame.bodyValue(DrillFrame.KEY_STEP_ID).orEmpty(),
            optionIds = frame.bodyList(DrillFrame.KEY_OPTIONS),
            peerWasCorrect = frame.bodyValue(DrillFrame.KEY_CORRECT) == "1",
        ),
    )

    private fun handleDistressTrigger(frame: DrillFrame): List<DrillEffect> {
        if (_phase != DrillPhase.RUNNING) return emptyList()
        val stepId = frame.bodyValue(DrillFrame.KEY_STEP_ID).orEmpty()
        distressStepId = stepId
        distressOpenedAtMs = monotonic.elapsedMillis()
        val effects = mutableListOf<DrillEffect>()
        effects += transitionTo(DrillPhase.DISTRESS_WINDOW)
        effects += DrillEffect.DistressTriggered(stepId, config.distressWindowMs)
        return effects
    }

    private fun handlePeerResult(frame: DrillFrame): List<DrillEffect> {
        val score = frame.bodyInt(DrillFrame.KEY_SCORE)?.coerceIn(0, 1000)
        peerScorePermille = score
        peerPassed = frame.bodyValue(DrillFrame.KEY_PASSED) == "1"

        val effects = mutableListOf<DrillEffect>()
        effects += DrillEffect.PeerResult(score ?: 0, peerPassed == true)
        if (_phase != DrillPhase.RESULT_EXCHANGE && !_phase.isTerminal) {
            effects += transitionTo(DrillPhase.RESULT_EXCHANGE)
        }
        effects += maybeComplete()
        return effects
    }

    // =======================================================================
    // Internals
    // =======================================================================

    private fun maybeStartCountdown(): List<DrillEffect> {
        if (_phase != DrillPhase.ROLE_ASSIGNED) return emptyList()
        if (!localReadySent || !peerReadyReceived) return emptyList()
        if (_role == DrillRole.UNDECIDED) return emptyList()

        val effects = mutableListOf<DrillEffect>()
        effects += transitionTo(DrillPhase.COUNTDOWN)
        effects += DrillEffect.CountdownStarted(config.countdownMs)
        return effects
    }

    private fun maybeComplete(): List<DrillEffect> {
        if (_phase != DrillPhase.RESULT_EXCHANGE) return emptyList()
        if (!localResultSent || peerScorePermille == null) return emptyList()

        val effects = mutableListOf<DrillEffect>()
        effects += transitionTo(DrillPhase.COMPLETE)
        effects += DrillEffect.Completed(
            peerDeviceId = peerDeviceId.orEmpty(),
            peerScorePermille = peerScorePermille,
            peerPassed = peerPassed,
        )
        return effects
    }

    private fun abortLocally(reason: DrillAbortReason): List<DrillEffect> {
        if (_phase.isTerminal) return emptyList()
        abortReason = reason
        val effects = mutableListOf<DrillEffect>()
        effects += transitionTo(DrillPhase.ABORTED)
        effects += DrillEffect.Aborted(reason, peerDeviceId)
        return effects
    }

    private fun transitionTo(next: DrillPhase): List<DrillEffect> {
        if (_phase == next) return emptyList()
        _phase = next
        phaseEnteredAtMs = monotonic.elapsedMillis()
        return listOf(DrillEffect.PhaseChanged(next))
    }

    private fun refreshPeerHealth(health: PeerHealth): List<DrillEffect> {
        if (_peerHealth == health) return emptyList()
        _peerHealth = health
        return listOf(DrillEffect.PeerHealthChanged(health))
    }

    private fun drainPending(): List<DrillEffect> {
        val effects = mutableListOf<DrillEffect>()
        while (true) {
            val next = pendingFrames.remove(nextExpectedPeerSeq) ?: break
            effects += applyFrame(next)
            nextExpectedPeerSeq++
        }
        return effects
    }

    private fun rememberSeq(seq: Long) {
        recentPeerSeqs += seq
        while (recentPeerSeqs.size > config.seqMemory) {
            val oldest = recentPeerSeqs.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            } else {
                break
            }
        }
    }

    private fun send(type: DrillMessageType, body: Map<String, String>): DrillEffect.Send {
        // Heartbeats are unordered, so they must not consume a sequence number.
        val seq = if (type == DrillMessageType.HEARTBEAT) {
            UNORDERED_SEQ
        } else {
            outboundSeq++
            outboundSeq.coerceAtMost(DrillFrame.MAX_U32)
        }
        val logical = (monotonic.elapsedMillis() - startMonotonicMs)
            .coerceIn(0L, DrillFrame.MAX_U32)
        val frame = DrillFrame(
            protocolVersion = DrillFrameCodec.PROTOCOL_VERSION,
            type = type,
            senderSeq = seq,
            logicalMs = logical,
            senderDeviceId = localDeviceId,
            body = body,
        )
        return DrillEffect.Send(DrillFrameCodec.encode(frame), type)
    }

    private companion object {
        /**
         * Reserved sequence number for frames that carry no state and must never block the
         * ordered stream. Ordered frames start at 1.
         */
        const val UNORDERED_SEQ: Long = 0L

        /** FNV-1a, so both devices derive an identical scene seed with no extra round trip. */
        fun deriveSeed(deviceA: String, deviceB: String, scenarioId: String): Long {
            val ordered = if (deviceA < deviceB) "$deviceA|$deviceB" else "$deviceB|$deviceA"
            val material = "$ordered|$scenarioId"
            var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
            for (ch in material) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L
            }
            // Positive, so callers can use it directly as a Random seed or a modulus.
            return hash and Long.MAX_VALUE
        }
    }
}

/** Protocol timings. Grouped so they can be relaxed for a demo without touching logic. */
class DrillConfig(
    val heartbeatIntervalMs: Long = 1_000L,
    /** Three missed heartbeats: warn, keep running. */
    val peerStaleAfterMs: Long = 3_000L,
    /** Ten seconds of silence: abort, and save the partial run. */
    val peerLostAfterMs: Long = 10_000L,
    val handshakeTimeoutMs: Long = 15_000L,
    val countdownMs: Long = 3_000L,
    /** How long the responder has to react to the simulated collapse. */
    val distressWindowMs: Long = 9_000L,
    val resultExchangeTimeoutMs: Long = 8_000L,
    val maxBufferedFrames: Int = 32,
    val maxConsecutiveMalformed: Int = 10,
    val seqMemory: Int = 256,
) {
    init {
        require(heartbeatIntervalMs > 0) { "heartbeatIntervalMs must be positive" }
        require(peerStaleAfterMs > heartbeatIntervalMs) {
            "peerStaleAfterMs ($peerStaleAfterMs) must exceed heartbeatIntervalMs ($heartbeatIntervalMs)"
        }
        require(peerLostAfterMs > peerStaleAfterMs) {
            "peerLostAfterMs ($peerLostAfterMs) must exceed peerStaleAfterMs ($peerStaleAfterMs)"
        }
        require(handshakeTimeoutMs > 0) { "handshakeTimeoutMs must be positive" }
        require(countdownMs >= 0) { "countdownMs must be >= 0" }
        require(distressWindowMs > 0) { "distressWindowMs must be positive" }
        require(resultExchangeTimeoutMs > 0) { "resultExchangeTimeoutMs must be positive" }
        require(maxBufferedFrames > 0) { "maxBufferedFrames must be positive" }
        require(maxConsecutiveMalformed > 0) { "maxConsecutiveMalformed must be positive" }
        require(seqMemory > 0) { "seqMemory must be positive" }
    }

    companion object {
        val DEFAULT: DrillConfig = DrillConfig()
    }
}
