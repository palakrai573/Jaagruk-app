package org.jaagruk.core.catalog

/**
 * Named targets a scenario can place in an AR scene.
 *
 * The contract between scenario authoring and the AR renderer. A scenario references a target
 * by one of these constants; the AR layer resolves it to a real position — a site Cloud Anchor
 * where the site has been scanned, a template offset otherwise. Because the indirection is
 * explicit, the same scenario runs unchanged in full site-scanned AR, in a generic room, on a
 * sensor-only fallback, and in a flat 2D pictogram drill.
 *
 * [SEMANTIC_ANCHORED] targets are the ones worth scanning a site for: they only mean something
 * if they sit where the real object sits. The rest are scene dressing and can be placed
 * relative to the worker.
 */
object ArTargets {

    // --- exits and evacuation --------------------------------------------
    const val EXIT_PRIMARY: String = "EXIT_PRIMARY"
    const val EXIT_SECONDARY: String = "EXIT_SECONDARY"
    const val EXIT_BLOCKED: String = "EXIT_BLOCKED"
    const val ASSEMBLY_POINT: String = "ASSEMBLY_POINT"
    const val LIFT_DOOR: String = "LIFT_DOOR"
    const val STORE_ROOM: String = "STORE_ROOM"
    const val REFUGE_CHAMBER: String = "REFUGE_CHAMBER"

    // --- fire equipment ---------------------------------------------------
    const val EXTINGUISHER_STATION: String = "EXTINGUISHER_STATION"
    const val FIRE_ALARM_POINT: String = "FIRE_ALARM_POINT"
    const val HOSE_REEL: String = "HOSE_REEL"

    // --- gas and confined space ------------------------------------------
    const val GAS_ZONE: String = "GAS_ZONE"
    const val VENT_FAN: String = "VENT_FAN"
    const val CONFINED_SPACE_ENTRY: String = "CONFINED_SPACE_ENTRY"
    const val GAS_VALVE: String = "GAS_VALVE"

    // --- machinery --------------------------------------------------------
    const val MACHINE_NIP_POINT: String = "MACHINE_NIP_POINT"
    const val MACHINE_GUARD: String = "MACHINE_GUARD"
    const val ISOLATOR_SWITCH: String = "ISOLATOR_SWITCH"
    const val CONVEYOR_PULL_CORD: String = "CONVEYOR_PULL_CORD"
    const val WINCH_DRUM: String = "WINCH_DRUM"

    // --- height and electrical -------------------------------------------
    const val ANCHOR_POINT_VALID: String = "ANCHOR_POINT_VALID"
    const val ANCHOR_POINT_INVALID: String = "ANCHOR_POINT_INVALID"
    const val DAMAGED_CABLE: String = "DAMAGED_CABLE"
    const val LV_PANEL: String = "LV_PANEL"

    // --- neutral scene dressing ------------------------------------------
    const val WALKWAY: String = "WALKWAY"
    const val TOOLBOX: String = "TOOLBOX"
    const val LADDER: String = "LADDER"
    const val WATER_PUMP: String = "WATER_PUMP"
    const val VEHICLE_PARK: String = "VEHICLE_PARK"
    const val MAIN_GATE: String = "MAIN_GATE"
    const val SCAFFOLD_RAIL: String = "SCAFFOLD_RAIL"
    const val PIPE_RUN: String = "PIPE_RUN"

    /**
     * Targets whose training value depends on being anchored to the real object. These are what
     * a supervisor places during a site scan, and what makes the drill build memory of *this*
     * corridor rather than a generic room.
     */
    val SEMANTIC_ANCHORED: Set<String> = setOf(
        EXIT_PRIMARY,
        EXIT_SECONDARY,
        ASSEMBLY_POINT,
        EXTINGUISHER_STATION,
        FIRE_ALARM_POINT,
        GAS_ZONE,
        VENT_FAN,
        CONFINED_SPACE_ENTRY,
        ISOLATOR_SWITCH,
        MACHINE_NIP_POINT,
        REFUGE_CHAMBER,
        // A valid anchor point and a damaged cable are exactly the kind of thing a supervisor
        // pins in place during a site scan: "the anchor you may clip onto is *that* beam" only
        // teaches anything if the marker sits on the real beam.
        ANCHOR_POINT_VALID,
        DAMAGED_CABLE,
    )

    val ALL: Set<String> = setOf(
        EXIT_PRIMARY, EXIT_SECONDARY, EXIT_BLOCKED, ASSEMBLY_POINT, LIFT_DOOR, STORE_ROOM,
        REFUGE_CHAMBER, EXTINGUISHER_STATION, FIRE_ALARM_POINT, HOSE_REEL, GAS_ZONE, VENT_FAN,
        CONFINED_SPACE_ENTRY, GAS_VALVE, MACHINE_NIP_POINT, MACHINE_GUARD, ISOLATOR_SWITCH,
        CONVEYOR_PULL_CORD, WINCH_DRUM, ANCHOR_POINT_VALID, ANCHOR_POINT_INVALID, DAMAGED_CABLE,
        LV_PANEL, WALKWAY, TOOLBOX, LADDER, WATER_PUMP, VEHICLE_PARK, MAIN_GATE, SCAFFOLD_RAIL,
        PIPE_RUN,
    )

    fun isKnown(target: String): Boolean = target in ALL

    fun requiresSiteAnchor(target: String): Boolean = target in SEMANTIC_ANCHORED
}
