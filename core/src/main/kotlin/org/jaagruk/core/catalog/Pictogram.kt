package org.jaagruk.core.catalog

/**
 * The pictogram vocabulary, derived from ISO 7010 safety-sign families.
 *
 * This enum is the contract between scenario authoring (which lives in `:core`) and icon
 * rendering (which lives in the Android UI). Keeping it here means a scenario cannot
 * reference an icon that does not exist — the compiler catches it — and the UI has an
 * exhaustive `when` that fails to compile if a new pictogram is added without artwork.
 *
 * These are also the *only* thing a non-literate worker sees in zero-text mode, so the set is
 * intentionally standard rather than decorative: a worker who learns these icons here can
 * read the signs actually bolted to the walls of a Jharkhand mine.
 *
 * ISO 7010 family prefixes: E = safe condition, F = fire equipment,
 * M = mandatory action, P = prohibition, W = warning.
 */
enum class Pictogram(val isoReference: String?, val family: PictogramFamily) {

    // --- E: safe condition ------------------------------------------------
    EMERGENCY_EXIT_LEFT("E001", PictogramFamily.SAFE_CONDITION),
    EMERGENCY_EXIT_RIGHT("E002", PictogramFamily.SAFE_CONDITION),
    EMERGENCY_EXIT_UP("E002", PictogramFamily.SAFE_CONDITION),
    ASSEMBLY_POINT("E007", PictogramFamily.SAFE_CONDITION),
    FIRST_AID("E003", PictogramFamily.SAFE_CONDITION),
    EMERGENCY_TELEPHONE("E004", PictogramFamily.SAFE_CONDITION),
    EYEWASH_STATION("E011", PictogramFamily.SAFE_CONDITION),
    REFUGE_CHAMBER(null, PictogramFamily.SAFE_CONDITION),

    // --- F: fire equipment ------------------------------------------------
    FIRE_EXTINGUISHER("F001", PictogramFamily.FIRE_EQUIPMENT),
    FIRE_EXTINGUISHER_CO2(null, PictogramFamily.FIRE_EQUIPMENT),
    FIRE_EXTINGUISHER_DRY_POWDER(null, PictogramFamily.FIRE_EQUIPMENT),
    FIRE_EXTINGUISHER_FOAM(null, PictogramFamily.FIRE_EQUIPMENT),
    FIRE_EXTINGUISHER_WATER(null, PictogramFamily.FIRE_EQUIPMENT),
    FIRE_HOSE_REEL("F002", PictogramFamily.FIRE_EQUIPMENT),
    FIRE_ALARM_CALL_POINT("F005", PictogramFamily.FIRE_EQUIPMENT),

    // --- M: mandatory action ----------------------------------------------
    WEAR_HELMET("M014", PictogramFamily.MANDATORY),
    WEAR_EYE_PROTECTION("M004", PictogramFamily.MANDATORY),
    WEAR_EAR_PROTECTION("M003", PictogramFamily.MANDATORY),
    WEAR_RESPIRATOR("M017", PictogramFamily.MANDATORY),
    WEAR_SELF_CONTAINED_BREATHING_APPARATUS(null, PictogramFamily.MANDATORY),
    WEAR_SAFETY_HARNESS("M018", PictogramFamily.MANDATORY),
    WEAR_SAFETY_BOOTS("M008", PictogramFamily.MANDATORY),
    WEAR_GLOVES("M009", PictogramFamily.MANDATORY),
    WEAR_HIGH_VIS("M015", PictogramFamily.MANDATORY),
    DISCONNECT_BEFORE_WORK("M021", PictogramFamily.MANDATORY),
    LOCKOUT_TAGOUT(null, PictogramFamily.MANDATORY),
    VENTILATE_BEFORE_ENTRY(null, PictogramFamily.MANDATORY),
    TEST_ATMOSPHERE(null, PictogramFamily.MANDATORY),
    RAISE_ALARM(null, PictogramFamily.MANDATORY),
    CALL_SUPERVISOR(null, PictogramFamily.MANDATORY),
    BUDDY_CHECK(null, PictogramFamily.MANDATORY),

    // --- P: prohibition ---------------------------------------------------
    NO_ENTRY("P004", PictogramFamily.PROHIBITION),
    NO_OPEN_FLAME("P003", PictogramFamily.PROHIBITION),
    DO_NOT_USE_LIFT_IN_FIRE("P020", PictogramFamily.PROHIBITION),
    DO_NOT_TOUCH("P010", PictogramFamily.PROHIBITION),
    NO_ENTRY_WITHOUT_PERMIT(null, PictogramFamily.PROHIBITION),
    DO_NOT_RUN(null, PictogramFamily.PROHIBITION),
    DO_NOT_ENTER_ALONE(null, PictogramFamily.PROHIBITION),

    // --- W: warning -------------------------------------------------------
    WARNING_GENERAL("W001", PictogramFamily.WARNING),
    WARNING_FLAMMABLE("W021", PictogramFamily.WARNING),
    WARNING_EXPLOSIVE("W002", PictogramFamily.WARNING),
    WARNING_TOXIC_GAS("W016", PictogramFamily.WARNING),
    WARNING_ASPHYXIANT(null, PictogramFamily.WARNING),
    WARNING_ELECTRICITY("W012", PictogramFamily.WARNING),
    WARNING_MOVING_MACHINERY("W024", PictogramFamily.WARNING),
    WARNING_ENTANGLEMENT(null, PictogramFamily.WARNING),
    WARNING_FALLING_OBJECTS("W035", PictogramFamily.WARNING),
    WARNING_DROP("W008", PictogramFamily.WARNING),
    WARNING_CONFINED_SPACE(null, PictogramFamily.WARNING),
    WARNING_ROOF_FALL(null, PictogramFamily.WARNING),
    WARNING_HOT_SURFACE("W017", PictogramFamily.WARNING),
    WARNING_SLIPPERY("W011", PictogramFamily.WARNING),

    // --- neutral UI / answer affordances ----------------------------------
    ANSWER_YES(null, PictogramFamily.NEUTRAL),
    ANSWER_NO(null, PictogramFamily.NEUTRAL),
    ARROW_LEFT(null, PictogramFamily.NEUTRAL),
    ARROW_RIGHT(null, PictogramFamily.NEUTRAL),
    ARROW_STRAIGHT(null, PictogramFamily.NEUTRAL),
    ARROW_BACK(null, PictogramFamily.NEUTRAL),
    STOP_HAND(null, PictogramFamily.NEUTRAL),
    STAY_PUT(null, PictogramFamily.NEUTRAL),
    LISTEN_AGAIN(null, PictogramFamily.NEUTRAL),
    SMOKE(null, PictogramFamily.NEUTRAL),
    GAS_CLOUD(null, PictogramFamily.NEUTRAL),
    VALVE(null, PictogramFamily.NEUTRAL),
    MACHINE_GUARD(null, PictogramFamily.NEUTRAL),
    CONVEYOR(null, PictogramFamily.NEUTRAL),
    WINCH(null, PictogramFamily.NEUTRAL),
    GAS_DETECTOR(null, PictogramFamily.NEUTRAL),
    LADDER(null, PictogramFamily.NEUTRAL),
    LV_PANEL(null, PictogramFamily.NEUTRAL),
    CRAWL_LOW(null, PictogramFamily.NEUTRAL),
    CLOSE_DOOR(null, PictogramFamily.NEUTRAL),
    DRAG_CASUALTY(null, PictogramFamily.NEUTRAL),
    ;

    /**
     * Signal colour of the ISO family.
     *
     * Never the sole carrier of meaning — every state that uses colour also carries a shape
     * and a label, so the drill remains usable for a colour-blind worker.
     */
    val signalColour: PictogramColour get() = family.signalColour
}

enum class PictogramFamily(val signalColour: PictogramColour) {
    SAFE_CONDITION(PictogramColour.GREEN),
    FIRE_EQUIPMENT(PictogramColour.RED),
    MANDATORY(PictogramColour.BLUE),
    PROHIBITION(PictogramColour.RED),
    WARNING(PictogramColour.YELLOW),
    NEUTRAL(PictogramColour.GREY),
}

enum class PictogramColour {
    GREEN,
    RED,
    BLUE,
    YELLOW,
    GREY,
}
