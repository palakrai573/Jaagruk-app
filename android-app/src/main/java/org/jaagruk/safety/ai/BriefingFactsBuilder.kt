package org.jaagruk.safety.ai

import org.jaagruk.core.ai.SiteBriefingFacts
import org.jaagruk.core.catalog.ModuleCatalog
import org.jaagruk.core.util.TimeUnits
import org.jaagruk.core.util.WallClock
import org.jaagruk.safety.data.db.JaagrukDatabase
import org.jaagruk.safety.data.repo.RetentionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the numbers a shift briefing is written from.
 *
 * Every figure here is one the app already computes for its own screens: the readiness bands come from
 * [RetentionRepository.siteReadinessSummary], which the supervisor dashboard already shows, and the
 * hazard counts come from the same table the hazard list reads. Nothing is derived specially for the
 * briefing, which matters for a reason beyond tidiness — a supervisor reading a briefing figure should
 * be able to find the same figure on the screen above it, and a second calculation would eventually
 * disagree with the first.
 *
 * `statutorilyValidButStale` is carried through deliberately. It is the cohort a blended compliance
 * percentage hides: workers whose certificate is still legally valid and whose readiness has decayed
 * past the point where it means anything. Naming it out loud at shift start is the entire argument this
 * platform makes, and a briefing that omitted it would be a briefing about the wrong thing.
 */
@Singleton
class BriefingFactsBuilder @Inject constructor(
    private val database: JaagrukDatabase,
    private val retention: RetentionRepository,
    private val resolver: CatalogResolver,
    private val clock: WallClock,
) {

    private companion object {
        /**
         * How far back a hesitation or a failure still counts as current.
         *
         * 90 days. Long enough to cover a quarter's training, short enough that a supervisor is not
         * told about a worker who hesitated once last winter and has passed three refreshers since.
         */
        const val LOOKBACK_DAYS = 90L

        const val MAX_ZONES = 3
        const val MAX_TOPICS = 2
    }

    suspend fun build(siteId: String, siteLabel: String): SiteBriefingFacts {
        val since = clock.epochSeconds() - LOOKBACK_DAYS * TimeUnits.SECONDS_PER_DAY

        val roster = database.workerDao().all()
        val readiness: RetentionRepository.ReadinessSummary =
            retention.siteReadinessSummary(roster.map { it.workerId })

        val hazards = database.hazardTagDao()
        val runs = database.assessmentRunDao()

        val topics = runs.mostFailedModuleIds(siteId, since, MAX_TOPICS)
            .mapNotNull { moduleId -> ModuleCatalog.byId(moduleId)?.titleKey }
            .map { resolver.resolve(it) }
            .filter { it.isNotBlank() }

        return SiteBriefingFacts(
            siteLabel = siteLabel.ifBlank { siteId },
            // The roster total is the band total rather than the PIN count: a worker who has not chosen
            // a PIN yet is still somebody the shift is responsible for.
            workersTotal = maxOf(readiness.total, roster.size),
            readyCount = readiness.ready,
            dueCount = readiness.due,
            staleCount = readiness.stale,
            expiredCount = readiness.expired + readiness.neverCertified,
            statutorilyValidButStaleCount = readiness.statutorilyValidButStale,
            hesitationRiskCount = runs.countHesitationFlaggedWorkers(siteId, since),
            openHazardCount = hazards.countForSite(siteId),
            openHazardZones = hazards.busiestZones(siteId, MAX_ZONES),
            mostMissedTopicLabels = topics,
        )
    }
}
