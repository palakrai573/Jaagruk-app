package org.jaagruk.safety.data.hazard

import org.jaagruk.core.catalog.Pictogram

/**
 * The hazard vocabulary a worker can report from.
 *
 * A closed list, not free text. Three reasons, in order of importance:
 *
 *  1. A worker who cannot read still has to be able to file a report, so every category needs a
 *     pictogram, and a pictogram only exists for a category somebody defined.
 *  2. Free-text categories cannot be counted, and the whole value of near-miss reporting to a site
 *     officer is the count — five people flagging the same blocked exit is the signal.
 *  3. [wireName] must match `HazardCategory` in `backend/app/models.py` exactly, because the server
 *     enforces it with a database check constraint. A mismatch is a 422, not a silent insert.
 *
 * A free-text note is still available on top, and voice notes cover the cases the list does not.
 */
enum class HazardCategory(
    val wireName: String,
    val labelKey: String,
    val pictogram: Pictogram,
) {
    EXPOSED_WIRING("exposed_wiring", "hazard_cat_exposed_wiring", Pictogram.WARNING_ELECTRICITY),
    BLOCKED_EXIT("blocked_exit", "hazard_cat_blocked_exit", Pictogram.EMERGENCY_EXIT_RIGHT),
    MISSING_EXTINGUISHER(
        "missing_extinguisher",
        "hazard_cat_missing_extinguisher",
        Pictogram.FIRE_EXTINGUISHER,
    ),
    MISSING_GUARD("missing_guard", "hazard_cat_missing_guard", Pictogram.MACHINE_GUARD),
    GAS_SMELL("gas_smell", "hazard_cat_gas_smell", Pictogram.WARNING_TOXIC_GAS),
    WATER_ACCUMULATION(
        "water_accumulation",
        "hazard_cat_water_accumulation",
        Pictogram.WARNING_SLIPPERY,
    ),
    ROOF_SUPPORT("roof_support", "hazard_cat_roof_support", Pictogram.WARNING_ROOF_FALL),
    SPILL("spill", "hazard_cat_spill", Pictogram.WARNING_SLIPPERY),
    DAMAGED_PPE("damaged_ppe", "hazard_cat_damaged_ppe", Pictogram.WEAR_HELMET),
    UNSAFE_ACT("unsafe_act", "hazard_cat_unsafe_act", Pictogram.WARNING_GENERAL),
    OTHER("other", "hazard_cat_other", Pictogram.WARNING_GENERAL),
    ;

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        fun fromWireName(name: String): HazardCategory? = byWireName[name]
    }
}

/**
 * Reported severity.
 *
 * The worker's own judgement, recorded as given. A site officer can escalate or dismiss it later,
 * but the original assessment stays on the record — overwriting it would remove the evidence that
 * somebody said "critical" and was overruled.
 */
enum class HazardSeverity(
    val wireName: String,
    val labelKey: String,
    val rank: Int,
    val pictogram: Pictogram,
) {
    LOW("low", "hazard_sev_low", 0, Pictogram.WARNING_GENERAL),
    MEDIUM("medium", "hazard_sev_medium", 1, Pictogram.WARNING_GENERAL),
    HIGH("high", "hazard_sev_high", 2, Pictogram.WARNING_GENERAL),
    CRITICAL("critical", "hazard_sev_critical", 3, Pictogram.STOP_HAND),
    ;

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        fun fromWireName(name: String): HazardSeverity? = byWireName[name]

        /**
         * Severity at or above which a report is worth interrupting a supervisor for.
         *
         * Used to decide whether to attempt an immediate relay over Nearby rather than waiting for
         * the next sync window. A blocked exit found at the start of a night shift is not something
         * to discover from a report six hours later.
         */
        val URGENT_THRESHOLD: HazardSeverity = HIGH
    }
}
