package org.jaagruk.safety.data.repo

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.jaagruk.core.catalog.ArTargets
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.data.db.ChainHeadEntity
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.db.ModuleEntity
import org.jaagruk.safety.data.db.SiteAnchorEntity
import org.jaagruk.safety.data.db.SiteEntity
import org.jaagruk.safety.data.db.WorkerEntity
import org.jaagruk.safety.sync.api.BootstrapResponse
import org.jaagruk.core.cert.AttestationCodec
import org.jaagruk.core.crypto.Sha256
import org.jaagruk.core.util.Hex
import java.util.UUID

/**
 * Site identity, key material, module catalog and AR anchors.
 *
 * This is where a down-sync lands. Two rules govern it, and both exist because the alternative
 * loses data that cannot be recovered:
 *
 *  * **A bootstrap never deletes local rows.** It upserts. A worker registered offline this morning
 *    is not on the server's roster yet, and a naive replace would erase them along with the PIN they
 *    just set.
 *  * **The local chain head only ever moves forward.** If this handset has issued certificates the
 *    server has not seen, the server's head is *behind*, and adopting it would make the next
 *    issuance reuse a sequence number — producing two different records in one chain slot, which is
 *    indistinguishable from tampering.
 */
class SiteRepository(
    private val database: JaagrukDatabase,
    private val clock: WallClock,
) {

    private val sites = database.siteDao()
    private val anchors = database.siteAnchorDao()
    private val modules = database.moduleDao()
    private val workers = database.workerDao()
    private val heads = database.chainHeadDao()
    private val certificates = database.certificateDao()

    fun observe(siteId: String): Flow<SiteEntity?> = sites.observe(siteId)

    fun observeAll(): Flow<List<SiteEntity>> = sites.observeAll()

    suspend fun find(siteId: String): SiteEntity? = sites.find(siteId)

    suspend fun upsert(site: SiteEntity) = sites.upsert(site)

    /** Result of applying a down-sync, so the UI can say what actually changed. */
    data class BootstrapOutcome(
        val siteId: String,
        val modulesWritten: Int,
        val workersWritten: Int,
        val keyEpochsSeen: Int,
        /** True when this device's chain is ahead of the server's, i.e. it has records to upload. */
        val localChainAhead: Boolean,
        val localSeq: Long,
        val serverSeq: Long,
        /** Clock skew against the server, in seconds. Signed: positive means this device is ahead. */
        val clockSkewSeconds: Long,
    )

    /**
     * Applies a [BootstrapResponse] atomically.
     *
     * The whole thing is one transaction so a connection dropped halfway cannot leave the device
     * with a roster from the new payload and a chain head from the old one.
     */
    suspend fun applyBootstrap(response: BootstrapResponse): BootstrapOutcome =
        database.withTransaction {
            val site = response.site
            val nowSec = clock.epochSeconds()

            // The active key is the one certificates are signed against. Earlier epochs stay
            // verifiable through the archived keys the backend also returns; a superseded key is
            // never deleted, or every certificate signed under it would become unverifiable.
            val activeKey = response.siteKeys.firstOrNull { it.active }
                ?: response.siteKeys.maxByOrNull { it.epoch }

            val existing = sites.find(site.id)
            sites.upsert(
                SiteEntity(
                    siteId = site.id,
                    name = site.name,
                    district = site.district,
                    sector = site.sector,
                    publicKeyHex = activeKey?.publicKeyHex ?: existing?.publicKeyHex,
                    keyEpoch = activeKey?.epoch ?: existing?.keyEpoch ?: 1,
                    // Local scan state wins. The server does not know that a supervisor placed
                    // anchors five minutes ago with no signal.
                    arScanned = existing?.arScanned ?: site.arScanned,
                    anchorCount = existing?.anchorCount ?: site.arAnchorCount,
                    latitude = site.latitude ?: existing?.latitude,
                    longitude = site.longitude ?: existing?.longitude,
                    createdAtSec = existing?.createdAtSec ?: nowSec,
                ),
            )

            if (response.modules.isNotEmpty()) {
                modules.upsertAll(
                    response.modules.map { dto ->
                        ModuleEntity(
                            moduleId = dto.id,
                            moduleCode = dto.moduleCode,
                            catalogVersion = dto.catalogVersion,
                            titleEn = dto.titleEn,
                            statutoryReference = dto.statutoryReference,
                            estimatedMinutes = dto.estimatedMinutes,
                            supportsBuddyDrill = dto.supportsBuddyDrill,
                            fullyImplemented = dto.fullyImplemented,
                            enabled = dto.enabled,
                        )
                    },
                )
            }

            var workersWritten = 0
            for (dto in response.workers) {
                val local = workers.find(dto.id)
                workers.upsert(
                    WorkerEntity(
                        workerId = dto.id,
                        siteId = dto.siteId,
                        fullName = dto.fullName,
                        workerIdHash = local?.workerIdHash
                            ?: Hex.encode(AttestationCodec.workerIdHash(dto.id)),
                        preferredLanguage = dto.preferredLanguage,
                        pictogramMode = dto.pictogramMode,
                        // PIN material is local-only and never round-trips through the server.
                        // Overwriting it from a bootstrap would lock a worker out of their own
                        // handset for no reason.
                        pinHash = local?.pinHash,
                        pinSalt = local?.pinSalt,
                        failedPinAttempts = local?.failedPinAttempts ?: 0,
                        lockedUntilEpochMs = local?.lockedUntilEpochMs ?: 0L,
                        lockedUntilElapsedMs = local?.lockedUntilElapsedMs ?: 0L,
                        registeredAtSec = local?.registeredAtSec ?: nowSec,
                        serverSynced = true,
                        active = dto.active,
                    ),
                )
                workersWritten++
            }

            val localHead = heads.find(site.id)
            val localSeq = maxOf(localHead?.lastSeq ?: 0L, certificates.highestSeq(site.id) ?: 0L)
            val serverSeq = response.chainHeadSeq

            if (serverSeq > localSeq && response.chainHeadHashHex.isNotBlank()) {
                // The server is ahead: another handset at this site issued certificates this device
                // has never seen. Adopting the server head is correct and is what lets a second
                // supervisor phone continue the same chain instead of forking it.
                heads.upsert(
                    ChainHeadEntity(
                        siteId = site.id,
                        lastSeq = serverSeq,
                        lastRecordHashHex = response.chainHeadHashHex,
                        updatedAtSec = nowSec,
                    ),
                )
            } else if (localHead == null && serverSeq == 0L) {
                heads.upsert(
                    ChainHeadEntity(
                        siteId = site.id,
                        lastSeq = 0L,
                        lastRecordHashHex = Hex.encode(Sha256.ZERO),
                        updatedAtSec = nowSec,
                    ),
                )
            }

            BootstrapOutcome(
                siteId = site.id,
                modulesWritten = response.modules.size,
                workersWritten = workersWritten,
                keyEpochsSeen = response.siteKeys.size,
                localChainAhead = localSeq > serverSeq,
                localSeq = localSeq,
                serverSeq = serverSeq,
                clockSkewSeconds = nowSec - response.serverTimeSec,
            )
        }

    suspend fun recordSiteKey(siteId: String, publicKeyHex: String, epoch: Int) {
        sites.updateKey(siteId, publicKeyHex, epoch)
    }

    // -----------------------------------------------------------------------
    // AR anchors
    // -----------------------------------------------------------------------

    fun observeAnchors(siteId: String): Flow<List<SiteAnchorEntity>> = anchors.observeForSite(siteId)

    suspend fun anchors(siteId: String): List<SiteAnchorEntity> = anchors.forSite(siteId)

    suspend fun anchor(siteId: String, targetKey: String): SiteAnchorEntity? =
        anchors.find(siteId, targetKey)

    /**
     * Stores a placed anchor and refreshes the site's scan summary.
     *
     * [cloudAnchorId] is null when hosting failed or there was no connectivity during the scan. The
     * anchor still works, but only on this handset, which is recorded in `deviceScoped` so a
     * supervisor is told rather than left to discover it when a colleague's phone shows nothing.
     */
    suspend fun saveAnchor(
        siteId: String,
        targetKey: String,
        cloudAnchorId: String?,
        label: String?,
    ): SiteAnchorEntity {
        require(ArTargets.isKnown(targetKey)) { "unknown AR target '$targetKey'" }
        val nowSec = clock.epochSeconds()
        val existing = anchors.find(siteId, targetKey)
        val entity = SiteAnchorEntity(
            anchorId = existing?.anchorId ?: UUID.randomUUID().toString(),
            siteId = siteId,
            targetKey = targetKey,
            cloudAnchorId = cloudAnchorId,
            deviceScoped = cloudAnchorId == null,
            label = label,
            createdAtSec = existing?.createdAtSec ?: nowSec,
            lastResolvedAtSec = nowSec,
            resolveFailureCount = 0,
        )
        database.withTransaction {
            anchors.upsert(entity)
            val count = anchors.countForSite(siteId)
            // "Scanned" means the semantically anchored targets are placed, not that some arbitrary
            // number of anchors exists. A site with three decorative anchors is not scanned.
            val placed = anchors.forSite(siteId).map { it.targetKey }.toSet()
            val semanticPlaced = ArTargets.SEMANTIC_ANCHORED.count { it in placed }
            sites.updateScanState(
                siteId = siteId,
                scanned = semanticPlaced >= MIN_SEMANTIC_ANCHORS_FOR_SCANNED,
                anchorCount = count,
            )
        }
        return entity
    }

    suspend fun recordResolveFailure(anchorId: String) = anchors.recordResolveFailure(anchorId)

    suspend fun recordResolveSuccess(anchorId: String) =
        anchors.recordResolveSuccess(anchorId, clock.epochSeconds())

    /**
     * Clears a site's anchors, for a re-scan after the corridor was rearranged.
     *
     * Loud on purpose: training against anchors that no longer match the real layout is worse than
     * training against a generic template, because it teaches a wrong location confidently.
     */
    suspend fun clearAnchors(siteId: String) {
        database.withTransaction {
            anchors.clearSite(siteId)
            sites.updateScanState(siteId, scanned = false, anchorCount = 0)
        }
        Log.i(TAG, "cleared all AR anchors for $siteId; site reverts to generic AR placement")
    }

    /** Whether enough semantic targets are anchored for a run to claim SITE_SCANNED presentation. */
    suspend fun isSiteScanned(siteId: String): Boolean {
        val placed = anchors.forSite(siteId).map { it.targetKey }.toSet()
        return ArTargets.SEMANTIC_ANCHORED.count { it in placed } >=
            MIN_SEMANTIC_ANCHORS_FOR_SCANNED
    }

    companion object {
        private const val TAG = "SiteRepository"

        /**
         * How many semantically anchored targets must be placed before a site counts as scanned.
         *
         * Four, not one: a single anchored exit does not make a drill site-specific, and the
         * SITE_SCANNED flag is signed into certificates, so overclaiming it is a permanent record
         * of a claim that was not true.
         */
        const val MIN_SEMANTIC_ANCHORS_FOR_SCANNED: Int = 4
    }
}
