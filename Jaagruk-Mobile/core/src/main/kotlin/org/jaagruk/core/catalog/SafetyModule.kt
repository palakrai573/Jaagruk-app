package org.jaagruk.core.catalog

import org.jaagruk.core.assessment.ScenarioSpec

/** Which sectors a module is relevant to. Drives the module list a worker actually sees. */
enum class Sector {
    COAL_MINE,
    STEEL_PLANT,
    MICA_PROCESSING,
    ALL,
}

/**
 * A certifiable safety domain.
 *
 * [moduleCode] is written into every certificate's signed payload, so it is a permanent
 * identifier. Renumbering it would invalidate every certificate already in the field, which
 * is why the codes are declared once, documented as frozen, and asserted unique by a test.
 */
class SafetyModule(
    val moduleId: String,
    val moduleCode: Int,
    val titleKey: String,
    val descriptionKey: String,
    val pictogram: Pictogram,
    val sectors: Set<Sector>,
    /** Statutory hook this module trains against, shown on exported compliance reports. */
    val statutoryReference: String,
    val estimatedMinutes: Int,
    val scenarios: List<ScenarioSpec>,
) {
    init {
        require(moduleId.isNotBlank()) { "moduleId must not be blank" }
        require(moduleCode in 1..255) { "moduleCode must be 1..255, got $moduleCode for $moduleId" }
        require(titleKey.isNotBlank()) { "titleKey must not be blank for $moduleId" }
        require(descriptionKey.isNotBlank()) { "descriptionKey must not be blank for $moduleId" }
        require(sectors.isNotEmpty()) { "module $moduleId must apply to at least one sector" }
        require(estimatedMinutes > 0) { "estimatedMinutes must be positive for $moduleId" }
        require(scenarios.isNotEmpty()) { "module $moduleId has no scenarios" }
        require(scenarios.all { it.moduleId == moduleId }) {
            "module $moduleId contains scenario(s) declaring a different moduleId: " +
                scenarios.filter { it.moduleId != moduleId }.map { it.scenarioId }
        }
        val ids = scenarios.map { it.scenarioId }
        require(ids.distinct().size == ids.size) {
            "module $moduleId has duplicate scenarioIds: " +
                ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        }
        require(scenarios.any { !it.isRefresherVariant }) {
            "module $moduleId has no full scenario, only refreshers"
        }
        require(scenarios.any { it.isRefresherVariant }) {
            "module $moduleId has no refresher scenario, so spaced repetition could never run"
        }
    }

    /** The full certifying scenario for a solo worker. */
    val fullScenario: ScenarioSpec
        get() = scenarios.first { !it.isRefresherVariant && !it.requiresBuddy }

    /** Short spaced-repetition variant. */
    val refresherScenario: ScenarioSpec
        get() = scenarios.first { it.isRefresherVariant }

    /** Two-device variant, when this domain has one. */
    val buddyScenario: ScenarioSpec?
        get() = scenarios.firstOrNull { it.requiresBuddy }

    val supportsBuddyDrill: Boolean get() = buddyScenario != null

    fun scenario(scenarioId: String): ScenarioSpec? =
        scenarios.firstOrNull { it.scenarioId == scenarioId }

    fun appliesTo(sector: Sector): Boolean =
        Sector.ALL in sectors || sector == Sector.ALL || sector in sectors

    override fun toString(): String =
        "SafetyModule($moduleId, code=$moduleCode, scenarios=${scenarios.size})"
}
