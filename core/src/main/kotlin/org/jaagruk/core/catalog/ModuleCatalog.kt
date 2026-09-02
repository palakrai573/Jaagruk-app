package org.jaagruk.core.catalog

import org.jaagruk.core.assessment.ScenarioSpec
import org.jaagruk.core.assessment.StepKind
import org.jaagruk.core.assessment.StepOption
import org.jaagruk.core.assessment.StepSpec

/**
 * The five industrial safety domains, with every scenario defined as data.
 *
 * Scenarios live in `:core` as plain immutable objects rather than JSON on a server for three
 * reasons that matter in this deployment:
 *
 *  1. **They work offline on day one.** A worker at an unconnected mine gets the full drill
 *     from the APK, with no bootstrap fetch.
 *  2. **They are validated at construction.** An unreachable correct answer, a timeout below
 *     the expert baseline, a spatial step with no AR target — all fail in a unit test rather
 *     than mid-drill underground.
 *  3. **Timings are reviewable.** Every `expertMs` and `timeoutMs` is visible in one file,
 *     which is what a DGMS reviewer or a site safety officer would need to sign off on.
 *
 * `expertMs` is the time a confident, trained worker takes to decide. `timeoutMs` is the point
 * where not deciding is itself the failure. The window between them is where hesitation is
 * measured — the single most important thing this platform records. The values below are
 * authored starting points for calibration against real cohorts, not measured constants, and
 * `docs/CALIBRATION.md` sets out how to tune them.
 *
 * Content notes anchored to Indian practice:
 *  * 1.25 % CH4 is the DGMS statutory withdrawal threshold in Indian coal mines.
 *  * "Never enter to rescue without breathing apparatus" is the single highest-value rule in
 *    confined-space work — would-be rescuers are a large share of confined-space fatalities —
 *    so it carries the heaviest weight in the catalog.
 */
object ModuleCatalog {

    /** Bumped whenever any scenario changes. Stored on runs so scores stay comparable. */
    const val CATALOG_VERSION: Int = 1

    // Frozen. These codes are signed into certificates; renumbering invalidates the field.
    const val CODE_FIRE: Int = 1
    const val CODE_GAS: Int = 2
    const val CODE_MACHINERY: Int = 3
    const val CODE_PPE_HEIGHT: Int = 4
    const val CODE_ELECTRICAL: Int = 5

    const val ID_FIRE: String = "fire-evacuation"
    const val ID_GAS: String = "gas-confined-space"
    const val ID_MACHINERY: String = "machinery-loto"
    const val ID_PPE_HEIGHT: String = "ppe-height"
    const val ID_ELECTRICAL: String = "electrical-first-response"

    // =======================================================================
    // Authoring helpers
    // =======================================================================

    private fun opt(
        id: String,
        pictogram: Pictogram,
        arTarget: String? = null,
        distractor: Boolean = false,
    ): StepOption = StepOption(
        optionId = id,
        labelKey = "opt_$id",
        pictogram = pictogram,
        arTargetKey = arTarget,
        isDistractor = distractor,
    )

    private fun step(
        id: String,
        kind: StepKind,
        options: List<StepOption>,
        correct: List<String>,
        expertMs: Long,
        timeoutMs: Long,
        weight: Double = 1.0,
        critical: Boolean = false,
        dwellMs: Long = 0L,
    ): StepSpec = StepSpec(
        stepId = id,
        kind = kind,
        promptKey = "step_${id}_prompt",
        options = options,
        correctOptionIds = correct,
        expertMs = expertMs,
        timeoutMs = timeoutMs,
        weight = weight,
        critical = critical,
        dwellMs = dwellMs,
        remediationKey = "step_${id}_remedy",
    )

    // =======================================================================
    // 1. Fire & Explosion Response
    // =======================================================================

    private val fireDetectAlarm = step(
        id = "fire_detect_alarm",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("raise_alarm", Pictogram.FIRE_ALARM_CALL_POINT),
            opt("fight_fire_first", Pictogram.FIRE_EXTINGUISHER, distractor = true),
            opt("find_supervisor", Pictogram.CALL_SUPERVISOR, distractor = true),
            opt("photograph_it", Pictogram.WARNING_GENERAL, distractor = true),
        ),
        correct = listOf("raise_alarm"),
        expertMs = 3_000,
        timeoutMs = 12_000,
        weight = 1.5,
        critical = true,
    )

    private val firePickExtinguisher = step(
        id = "fire_pick_extinguisher",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("ext_co2", Pictogram.FIRE_EXTINGUISHER_CO2),
            opt("ext_water", Pictogram.FIRE_EXTINGUISHER_WATER, distractor = true),
            opt("ext_foam", Pictogram.FIRE_EXTINGUISHER_FOAM, distractor = true),
            opt("ext_dry_powder", Pictogram.FIRE_EXTINGUISHER_DRY_POWDER, distractor = true),
        ),
        correct = listOf("ext_co2"),
        expertMs = 4_000,
        timeoutMs = 15_000,
        weight = 2.0,
        critical = true,
    )

    private val fireExtinguisherSequence = step(
        id = "fire_extinguisher_sequence",
        kind = StepKind.SEQUENCE,
        options = listOf(
            opt("pass_pull", Pictogram.FIRE_EXTINGUISHER),
            opt("pass_aim", Pictogram.ARROW_STRAIGHT),
            opt("pass_squeeze", Pictogram.STOP_HAND),
            opt("pass_sweep", Pictogram.ARROW_LEFT),
        ),
        correct = listOf("pass_pull", "pass_aim", "pass_squeeze", "pass_sweep"),
        expertMs = 8_000,
        timeoutMs = 25_000,
        weight = 1.5,
    )

    private val fireLocateExit = step(
        id = "fire_locate_exit",
        kind = StepKind.AR_POINT,
        options = listOf(
            opt("exit_primary", Pictogram.EMERGENCY_EXIT_RIGHT, ArTargets.EXIT_PRIMARY),
            opt("exit_blocked", Pictogram.NO_ENTRY, ArTargets.EXIT_BLOCKED, distractor = true),
            opt("lift_door", Pictogram.DO_NOT_USE_LIFT_IN_FIRE, ArTargets.LIFT_DOOR, distractor = true),
            opt("store_room", Pictogram.NO_ENTRY, ArTargets.STORE_ROOM, distractor = true),
        ),
        correct = listOf("exit_primary"),
        expertMs = 2_500,
        timeoutMs = 10_000,
        weight = 2.0,
        critical = true,
    )

    private val fireMovementPosture = step(
        id = "fire_movement_posture",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("crawl_low", Pictogram.CRAWL_LOW),
            opt("walk_upright", Pictogram.DO_NOT_RUN, distractor = true),
            opt("run_fast", Pictogram.DO_NOT_RUN, distractor = true),
            opt("stand_and_wait", Pictogram.STAY_PUT, distractor = true),
        ),
        correct = listOf("crawl_low"),
        expertMs = 3_000,
        timeoutMs = 12_000,
        weight = 1.0,
    )

    private val fireDoorAction = step(
        id = "fire_door_action",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("close_door_behind", Pictogram.CLOSE_DOOR),
            opt("leave_door_open", Pictogram.WARNING_FLAMMABLE, distractor = true),
            opt("wedge_door_open", Pictogram.WARNING_FLAMMABLE, distractor = true),
            opt("lock_door", Pictogram.NO_ENTRY, distractor = true),
        ),
        correct = listOf("close_door_behind"),
        expertMs = 2_500,
        timeoutMs = 10_000,
        weight = 1.0,
    )

    private val fireAssemblyPoint = step(
        id = "fire_assembly_point",
        kind = StepKind.AR_DWELL,
        options = listOf(
            opt("assembly_marker", Pictogram.ASSEMBLY_POINT, ArTargets.ASSEMBLY_POINT),
            opt("vehicle_park", Pictogram.WARNING_GENERAL, ArTargets.VEHICLE_PARK, distractor = true),
            opt("main_gate", Pictogram.WARNING_GENERAL, ArTargets.MAIN_GATE, distractor = true),
        ),
        correct = listOf("assembly_marker"),
        expertMs = 6_000,
        timeoutMs = 20_000,
        weight = 1.0,
        dwellMs = 1_500,
    )

    private val fireHeadcount = step(
        id = "fire_headcount",
        kind = StepKind.MULTI_SELECT,
        options = listOf(
            opt("report_to_marshal", Pictogram.CALL_SUPERVISOR),
            opt("report_missing_persons", Pictogram.RAISE_ALARM),
            opt("return_for_belongings", Pictogram.NO_ENTRY, distractor = true),
            opt("re_enter_to_help", Pictogram.NO_ENTRY, distractor = true),
        ),
        correct = listOf("report_to_marshal", "report_missing_persons"),
        expertMs = 6_000,
        timeoutMs = 20_000,
        weight = 1.0,
    )

    private val fireFullScenario = ScenarioSpec(
        scenarioId = "fire-evac-full",
        moduleId = ID_FIRE,
        titleKey = "scenario_fire_evac_full_title",
        steps = listOf(
            fireDetectAlarm,
            firePickExtinguisher,
            fireExtinguisherSequence,
            fireLocateExit,
            fireMovementPosture,
            fireDoorAction,
            fireAssemblyPoint,
            fireHeadcount,
        ),
    )

    private val fireRefresherScenario = ScenarioSpec(
        scenarioId = "fire-evac-refresher",
        moduleId = ID_FIRE,
        titleKey = "scenario_fire_evac_refresher_title",
        steps = listOf(firePickExtinguisher, fireLocateExit, fireMovementPosture),
        isRefresherVariant = true,
    )

    val fireEvacuation = SafetyModule(
        moduleId = ID_FIRE,
        moduleCode = CODE_FIRE,
        titleKey = "module_fire_title",
        descriptionKey = "module_fire_desc",
        pictogram = Pictogram.FIRE_EXTINGUISHER,
        sectors = setOf(Sector.ALL),
        statutoryReference = "Mines Act 1952 s.58; Factories Act 1948 s.38",
        estimatedMinutes = 9,
        scenarios = listOf(fireFullScenario, fireRefresherScenario),
    )

    // =======================================================================
    // 2. Gas Leak & Confined Space Protocol
    // =======================================================================

    private val gasRecogniseZone = step(
        id = "gas_recognise_zone",
        kind = StepKind.AR_POINT,
        options = listOf(
            opt("gas_zone_marker", Pictogram.WARNING_TOXIC_GAS, ArTargets.GAS_ZONE),
            opt("gas_walkway", Pictogram.ARROW_STRAIGHT, ArTargets.WALKWAY, distractor = true),
            opt("gas_toolbox", Pictogram.WARNING_GENERAL, ArTargets.TOOLBOX, distractor = true),
            opt("gas_ladder", Pictogram.LADDER, ArTargets.LADDER, distractor = true),
        ),
        correct = listOf("gas_zone_marker"),
        expertMs = 3_000,
        timeoutMs = 12_000,
        weight = 2.0,
        critical = true,
    )

    private val gasFirstAction = step(
        id = "gas_first_action",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("withdraw_and_alarm", Pictogram.RAISE_ALARM),
            opt("switch_on_lights", Pictogram.NO_OPEN_FLAME, distractor = true),
            opt("continue_working", Pictogram.DO_NOT_TOUCH, distractor = true),
            opt("open_more_valves", Pictogram.VALVE, distractor = true),
        ),
        correct = listOf("withdraw_and_alarm"),
        expertMs = 3_000,
        timeoutMs = 12_000,
        weight = 2.0,
        critical = true,
    )

    private val gasPpeSelect = step(
        id = "gas_ppe_select",
        kind = StepKind.MULTI_SELECT,
        options = listOf(
            opt("ppe_scba", Pictogram.WEAR_SELF_CONTAINED_BREATHING_APPARATUS),
            opt("ppe_harness_lifeline", Pictogram.WEAR_SAFETY_HARNESS),
            opt("ppe_helmet", Pictogram.WEAR_HELMET),
            opt("ppe_gas_detector", Pictogram.GAS_DETECTOR),
            opt("ppe_dust_mask", Pictogram.WEAR_RESPIRATOR, distractor = true),
            opt("ppe_cotton_cloth", Pictogram.WARNING_GENERAL, distractor = true),
            opt("ppe_earplugs", Pictogram.WEAR_EAR_PROTECTION, distractor = true),
        ),
        correct = listOf("ppe_scba", "ppe_harness_lifeline", "ppe_helmet", "ppe_gas_detector"),
        expertMs = 9_000,
        timeoutMs = 28_000,
        weight = 2.0,
        critical = true,
    )

    private val gasEntrySequence = step(
        id = "gas_entry_sequence",
        kind = StepKind.SEQUENCE,
        options = listOf(
            opt("cs_permit", Pictogram.NO_ENTRY_WITHOUT_PERMIT),
            opt("cs_isolate", Pictogram.DISCONNECT_BEFORE_WORK),
            opt("cs_ventilate", Pictogram.VENTILATE_BEFORE_ENTRY),
            opt("cs_test_atmosphere", Pictogram.TEST_ATMOSPHERE),
            opt("cs_station_attendant", Pictogram.BUDDY_CHECK),
            opt("cs_enter", Pictogram.ARROW_STRAIGHT),
        ),
        correct = listOf(
            "cs_permit",
            "cs_isolate",
            "cs_ventilate",
            "cs_test_atmosphere",
            "cs_station_attendant",
            "cs_enter",
        ),
        expertMs = 14_000,
        timeoutMs = 40_000,
        weight = 1.5,
    )

    private val gasContactInterval = step(
        id = "gas_contact_interval",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("contact_continuous", Pictogram.BUDDY_CHECK),
            opt("contact_every_15_min", Pictogram.WARNING_GENERAL, distractor = true),
            opt("contact_every_hour", Pictogram.WARNING_GENERAL, distractor = true),
            opt("contact_only_if_called", Pictogram.DO_NOT_ENTER_ALONE, distractor = true),
        ),
        correct = listOf("contact_continuous"),
        expertMs = 3_500,
        timeoutMs = 12_000,
        weight = 1.0,
    )

    private val gasRescueDecision = step(
        id = "gas_rescue_decision",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("raise_alarm_do_not_enter", Pictogram.RAISE_ALARM),
            opt("enter_immediately", Pictogram.DO_NOT_ENTER_ALONE, distractor = true),
            opt("enter_with_dust_mask", Pictogram.WEAR_RESPIRATOR, distractor = true),
            opt("wait_and_watch", Pictogram.STAY_PUT, distractor = true),
        ),
        correct = listOf("raise_alarm_do_not_enter"),
        expertMs = 2_500,
        timeoutMs = 10_000,
        weight = 2.5,
        critical = true,
    )

    private val gasVentilationCheck = step(
        id = "gas_ventilation_check",
        kind = StepKind.AR_DWELL,
        options = listOf(
            opt("vent_fan", Pictogram.VENTILATE_BEFORE_ENTRY, ArTargets.VENT_FAN),
            opt("vent_water_pump", Pictogram.WARNING_GENERAL, ArTargets.WATER_PUMP, distractor = true),
            opt("vent_winch", Pictogram.WINCH, ArTargets.WINCH_DRUM, distractor = true),
        ),
        correct = listOf("vent_fan"),
        expertMs = 6_000,
        timeoutMs = 18_000,
        weight = 1.0,
        dwellMs = 1_500,
    )

    private val buddyPeriodicCheck = step(
        id = "buddy_periodic_check",
        kind = StepKind.BUDDY_RESPONSE,
        options = listOf(
            opt("buddy_ok_signal", Pictogram.BUDDY_CHECK),
            opt("buddy_ignore", Pictogram.DO_NOT_TOUCH, distractor = true),
            opt("buddy_pull_lifeline_hard", Pictogram.WARNING_GENERAL, distractor = true),
        ),
        correct = listOf("buddy_ok_signal"),
        expertMs = 4_000,
        timeoutMs = 15_000,
        weight = 1.5,
    )

    private val buddyDistressResponse = step(
        id = "buddy_distress_response",
        kind = StepKind.BUDDY_RESPONSE,
        options = listOf(
            opt("signal_alarm_and_call_rescue", Pictogram.RAISE_ALARM),
            opt("buddy_enter_to_pull_out", Pictogram.DO_NOT_ENTER_ALONE, distractor = true),
            opt("buddy_go_find_supervisor", Pictogram.CALL_SUPERVISOR, distractor = true),
            opt("buddy_do_nothing", Pictogram.STAY_PUT, distractor = true),
        ),
        correct = listOf("signal_alarm_and_call_rescue"),
        expertMs = 2_500,
        timeoutMs = 9_000,
        weight = 3.0,
        critical = true,
    )

    private val gasFullScenario = ScenarioSpec(
        scenarioId = "gas-confined-full",
        moduleId = ID_GAS,
        titleKey = "scenario_gas_confined_full_title",
        steps = listOf(
            gasRecogniseZone,
            gasFirstAction,
            gasPpeSelect,
            gasEntrySequence,
            gasContactInterval,
            gasVentilationCheck,
            gasRescueDecision,
        ),
    )

    private val gasBuddyScenario = ScenarioSpec(
        scenarioId = "gas-confined-buddy",
        moduleId = ID_GAS,
        titleKey = "scenario_gas_confined_buddy_title",
        steps = listOf(
            gasRecogniseZone,
            gasPpeSelect,
            gasEntrySequence,
            buddyPeriodicCheck,
            buddyDistressResponse,
            gasRescueDecision,
        ),
        requiresBuddy = true,
    )

    private val gasRefresherScenario = ScenarioSpec(
        scenarioId = "gas-confined-refresher",
        moduleId = ID_GAS,
        titleKey = "scenario_gas_confined_refresher_title",
        steps = listOf(gasFirstAction, gasRescueDecision, gasContactInterval),
        isRefresherVariant = true,
    )

    val gasConfinedSpace = SafetyModule(
        moduleId = ID_GAS,
        moduleCode = CODE_GAS,
        titleKey = "module_gas_title",
        descriptionKey = "module_gas_desc",
        pictogram = Pictogram.WARNING_TOXIC_GAS,
        sectors = setOf(Sector.COAL_MINE, Sector.STEEL_PLANT, Sector.MICA_PROCESSING),
        statutoryReference = "Mines Act 1952 s.29; Mines Rules 1955 r.130",
        estimatedMinutes = 11,
        scenarios = listOf(gasFullScenario, gasBuddyScenario, gasRefresherScenario),
    )

    // =======================================================================
    // 3. Machinery Guarding & Lockout–Tagout
    // =======================================================================

    private val lotoIdentifyHazard = step(
        id = "loto_identify_hazard",
        kind = StepKind.AR_POINT,
        options = listOf(
            opt("nip_point", Pictogram.WARNING_ENTANGLEMENT, ArTargets.MACHINE_NIP_POINT),
            opt("loto_guard", Pictogram.MACHINE_GUARD, ArTargets.MACHINE_GUARD, distractor = true),
            opt("loto_pipe_run", Pictogram.WARNING_GENERAL, ArTargets.PIPE_RUN, distractor = true),
            opt("loto_walkway", Pictogram.ARROW_STRAIGHT, ArTargets.WALKWAY, distractor = true),
        ),
        correct = listOf("nip_point"),
        expertMs = 3_500,
        timeoutMs = 13_000,
        weight = 2.0,
        critical = true,
    )

    private val lotoSequence = step(
        id = "loto_sequence",
        kind = StepKind.SEQUENCE,
        options = listOf(
            opt("loto_notify", Pictogram.CALL_SUPERVISOR),
            opt("loto_shutdown", Pictogram.STOP_HAND),
            opt("loto_isolate", Pictogram.DISCONNECT_BEFORE_WORK),
            opt("loto_lock", Pictogram.LOCKOUT_TAGOUT),
            opt("loto_dissipate", Pictogram.WARNING_GENERAL),
            opt("loto_verify_zero", Pictogram.TEST_ATMOSPHERE),
        ),
        correct = listOf(
            "loto_notify",
            "loto_shutdown",
            "loto_isolate",
            "loto_lock",
            "loto_dissipate",
            "loto_verify_zero",
        ),
        expertMs = 14_000,
        timeoutMs = 40_000,
        weight = 2.0,
        critical = true,
    )

    private val lotoKeyControl = step(
        id = "loto_key_control",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("each_worker_own_lock", Pictogram.LOCKOUT_TAGOUT),
            opt("supervisor_holds_all_keys", Pictogram.WARNING_GENERAL, distractor = true),
            opt("key_in_panel_door", Pictogram.DO_NOT_TOUCH, distractor = true),
            opt("no_lock_just_tag", Pictogram.WARNING_GENERAL, distractor = true),
        ),
        correct = listOf("each_worker_own_lock"),
        expertMs = 4_000,
        timeoutMs = 14_000,
        weight = 1.5,
        critical = true,
    )

    private val lotoConveyorJam = step(
        id = "loto_conveyor_jam",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("stop_isolate_then_clear", Pictogram.CONVEYOR),
            opt("clear_while_running", Pictogram.WARNING_ENTANGLEMENT, distractor = true),
            opt("use_bar_while_running", Pictogram.WARNING_MOVING_MACHINERY, distractor = true),
            opt("increase_speed", Pictogram.WARNING_MOVING_MACHINERY, distractor = true),
        ),
        correct = listOf("stop_isolate_then_clear"),
        expertMs = 3_000,
        timeoutMs = 12_000,
        weight = 1.5,
    )

    private val lotoGuardMissing = step(
        id = "loto_guard_missing",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("stop_and_report_guard", Pictogram.STOP_HAND),
            opt("run_carefully_without_guard", Pictogram.WARNING_MOVING_MACHINERY, distractor = true),
            opt("cover_with_cloth", Pictogram.WARNING_GENERAL, distractor = true),
            opt("finish_shift_then_report", Pictogram.WARNING_GENERAL, distractor = true),
        ),
        correct = listOf("stop_and_report_guard"),
        expertMs = 3_000,
        timeoutMs = 12_000,
        weight = 2.0,
        critical = true,
    )

    private val machineryFullScenario = ScenarioSpec(
        scenarioId = "machinery-loto-full",
        moduleId = ID_MACHINERY,
        titleKey = "scenario_machinery_loto_full_title",
        steps = listOf(
            lotoIdentifyHazard,
            lotoSequence,
            lotoKeyControl,
            lotoConveyorJam,
            lotoGuardMissing,
        ),
    )

    private val machineryRefresherScenario = ScenarioSpec(
        scenarioId = "machinery-loto-refresher",
        moduleId = ID_MACHINERY,
        titleKey = "scenario_machinery_loto_refresher_title",
        steps = listOf(lotoKeyControl, lotoGuardMissing, lotoConveyorJam),
        isRefresherVariant = true,
    )

    val machineryLoto = SafetyModule(
        moduleId = ID_MACHINERY,
        moduleCode = CODE_MACHINERY,
        titleKey = "module_machinery_title",
        descriptionKey = "module_machinery_desc",
        pictogram = Pictogram.WARNING_MOVING_MACHINERY,
        sectors = setOf(Sector.STEEL_PLANT, Sector.COAL_MINE, Sector.MICA_PROCESSING),
        statutoryReference = "Factories Act 1948 s.21-24; Mines Rules 1955 r.187",
        estimatedMinutes = 8,
        scenarios = listOf(machineryFullScenario, machineryRefresherScenario),
    )

    // =======================================================================
    // 4. PPE Selection & Working at Height
    // =======================================================================

    private val ppeTaskMatch = step(
        id = "ppe_task_match",
        kind = StepKind.MULTI_SELECT,
        options = listOf(
            opt("ppe_face_shield", Pictogram.WEAR_EYE_PROTECTION),
            opt("ppe_grind_gloves", Pictogram.WEAR_GLOVES),
            opt("ppe_grind_helmet", Pictogram.WEAR_HELMET),
            opt("ppe_grind_earmuffs", Pictogram.WEAR_EAR_PROTECTION),
            opt("ppe_loose_scarf", Pictogram.WARNING_ENTANGLEMENT, distractor = true),
            opt("ppe_flip_flops", Pictogram.WEAR_SAFETY_BOOTS, distractor = true),
        ),
        correct = listOf("ppe_face_shield", "ppe_grind_gloves", "ppe_grind_helmet", "ppe_grind_earmuffs"),
        expertMs = 9_000,
        timeoutMs = 28_000,
        weight = 2.0,
        critical = true,
    )

    private val heightAnchorPoint = step(
        id = "height_anchor_point",
        kind = StepKind.AR_POINT,
        options = listOf(
            opt("anchor_structural", Pictogram.WEAR_SAFETY_HARNESS, ArTargets.ANCHOR_POINT_VALID),
            opt("anchor_pipe", Pictogram.WARNING_DROP, ArTargets.ANCHOR_POINT_INVALID, distractor = true),
            opt("anchor_scaffold_rail", Pictogram.WARNING_DROP, ArTargets.SCAFFOLD_RAIL, distractor = true),
            opt("anchor_ladder", Pictogram.WARNING_DROP, ArTargets.LADDER, distractor = true),
        ),
        correct = listOf("anchor_structural"),
        expertMs = 4_000,
        timeoutMs = 14_000,
        weight = 2.0,
        critical = true,
    )

    private val harnessInspectionSequence = step(
        id = "harness_inspection_sequence",
        kind = StepKind.SEQUENCE,
        options = listOf(
            opt("harness_check_label", Pictogram.WARNING_GENERAL),
            opt("harness_check_webbing", Pictogram.WEAR_SAFETY_HARNESS),
            opt("harness_check_stitching", Pictogram.WEAR_SAFETY_HARNESS),
            opt("harness_check_hardware", Pictogram.LOCKOUT_TAGOUT),
            opt("harness_check_lanyard", Pictogram.WEAR_SAFETY_HARNESS),
        ),
        correct = listOf(
            "harness_check_label",
            "harness_check_webbing",
            "harness_check_stitching",
            "harness_check_hardware",
            "harness_check_lanyard",
        ),
        expertMs = 12_000,
        timeoutMs = 34_000,
        weight = 1.5,
    )

    private val ladderAngle = step(
        id = "ladder_angle",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("ladder_one_in_four", Pictogram.LADDER),
            opt("ladder_one_in_one", Pictogram.WARNING_DROP, distractor = true),
            opt("ladder_one_in_eight", Pictogram.WARNING_DROP, distractor = true),
            opt("ladder_vertical", Pictogram.WARNING_DROP, distractor = true),
        ),
        correct = listOf("ladder_one_in_four"),
        expertMs = 4_500,
        timeoutMs = 15_000,
        weight = 1.0,
    )

    private val ppeDefectiveItem = step(
        id = "ppe_defective_item",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("replace_defective_ppe", Pictogram.STOP_HAND),
            opt("tape_the_crack", Pictogram.WARNING_GENERAL, distractor = true),
            opt("use_for_one_shift", Pictogram.WARNING_FALLING_OBJECTS, distractor = true),
            opt("swap_with_colleague", Pictogram.WARNING_GENERAL, distractor = true),
        ),
        correct = listOf("replace_defective_ppe"),
        expertMs = 3_000,
        timeoutMs = 12_000,
        weight = 2.0,
        critical = true,
    )

    private val ppeHeightFullScenario = ScenarioSpec(
        scenarioId = "ppe-height-full",
        moduleId = ID_PPE_HEIGHT,
        titleKey = "scenario_ppe_height_full_title",
        steps = listOf(
            ppeTaskMatch,
            heightAnchorPoint,
            harnessInspectionSequence,
            ladderAngle,
            ppeDefectiveItem,
        ),
    )

    private val ppeHeightRefresherScenario = ScenarioSpec(
        scenarioId = "ppe-height-refresher",
        moduleId = ID_PPE_HEIGHT,
        titleKey = "scenario_ppe_height_refresher_title",
        steps = listOf(heightAnchorPoint, ppeDefectiveItem, ladderAngle),
        isRefresherVariant = true,
    )

    val ppeHeight = SafetyModule(
        moduleId = ID_PPE_HEIGHT,
        moduleCode = CODE_PPE_HEIGHT,
        titleKey = "module_ppe_height_title",
        descriptionKey = "module_ppe_height_desc",
        pictogram = Pictogram.WEAR_SAFETY_HARNESS,
        sectors = setOf(Sector.ALL),
        statutoryReference = "Factories Act 1948 s.32-33; Mines Rules 1955 r.191",
        estimatedMinutes = 8,
        scenarios = listOf(ppeHeightFullScenario, ppeHeightRefresherScenario),
    )

    // =======================================================================
    // 5. Electrical Safety & Emergency First Response
    // =======================================================================

    private val elecShockFirstAction = step(
        id = "elec_shock_first_action",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("isolate_supply_first", Pictogram.DISCONNECT_BEFORE_WORK),
            opt("pull_victim_by_hand", Pictogram.WARNING_ELECTRICITY, distractor = true),
            opt("throw_water", Pictogram.WARNING_ELECTRICITY, distractor = true),
            opt("run_for_help_first", Pictogram.CALL_SUPERVISOR, distractor = true),
        ),
        correct = listOf("isolate_supply_first"),
        expertMs = 2_500,
        timeoutMs = 10_000,
        weight = 2.5,
        critical = true,
    )

    private val elecCableDamage = step(
        id = "elec_cable_damage",
        kind = StepKind.AR_POINT,
        options = listOf(
            opt("damaged_cable", Pictogram.WARNING_ELECTRICITY, ArTargets.DAMAGED_CABLE),
            opt("elec_lv_panel", Pictogram.LV_PANEL, ArTargets.LV_PANEL, distractor = true),
            opt("elec_pipe_run", Pictogram.WARNING_GENERAL, ArTargets.PIPE_RUN, distractor = true),
            opt("elec_toolbox", Pictogram.WARNING_GENERAL, ArTargets.TOOLBOX, distractor = true),
        ),
        correct = listOf("damaged_cable"),
        expertMs = 3_500,
        timeoutMs = 13_000,
        weight = 2.0,
        critical = true,
    )

    private val firstResponseSequence = step(
        id = "first_response_sequence",
        kind = StepKind.SEQUENCE,
        options = listOf(
            opt("fr_check_danger", Pictogram.WARNING_GENERAL),
            opt("fr_check_response", Pictogram.FIRST_AID),
            opt("fr_shout_for_help", Pictogram.RAISE_ALARM),
            opt("fr_open_airway", Pictogram.FIRST_AID),
            opt("fr_check_breathing", Pictogram.FIRST_AID),
            opt("fr_start_compressions", Pictogram.FIRST_AID),
        ),
        correct = listOf(
            "fr_check_danger",
            "fr_check_response",
            "fr_shout_for_help",
            "fr_open_airway",
            "fr_check_breathing",
            "fr_start_compressions",
        ),
        expertMs = 15_000,
        timeoutMs = 42_000,
        weight = 2.0,
        critical = true,
    )

    private val elecPermitToWork = step(
        id = "elec_permit_to_work",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("permit_and_isolation", Pictogram.NO_ENTRY_WITHOUT_PERMIT),
            opt("verbal_ok_from_mate", Pictogram.WARNING_GENERAL, distractor = true),
            opt("work_live_carefully", Pictogram.WARNING_ELECTRICITY, distractor = true),
            opt("no_permit_needed_lv", Pictogram.WARNING_ELECTRICITY, distractor = true),
        ),
        correct = listOf("permit_and_isolation"),
        expertMs = 4_000,
        timeoutMs = 14_000,
        weight = 1.5,
    )

    private val burnFirstAid = step(
        id = "burn_first_aid",
        kind = StepKind.SINGLE_CHOICE,
        options = listOf(
            opt("cool_with_water_20min", Pictogram.EYEWASH_STATION),
            opt("apply_oil_or_paste", Pictogram.DO_NOT_TOUCH, distractor = true),
            opt("burst_the_blister", Pictogram.DO_NOT_TOUCH, distractor = true),
            opt("apply_ice_directly", Pictogram.WARNING_GENERAL, distractor = true),
        ),
        correct = listOf("cool_with_water_20min"),
        expertMs = 3_500,
        timeoutMs = 13_000,
        weight = 2.0,
        critical = true,
    )

    private val electricalFullScenario = ScenarioSpec(
        scenarioId = "electrical-first-response-full",
        moduleId = ID_ELECTRICAL,
        titleKey = "scenario_electrical_full_title",
        steps = listOf(
            elecShockFirstAction,
            elecCableDamage,
            firstResponseSequence,
            elecPermitToWork,
            burnFirstAid,
        ),
    )

    private val electricalRefresherScenario = ScenarioSpec(
        scenarioId = "electrical-first-response-refresher",
        moduleId = ID_ELECTRICAL,
        titleKey = "scenario_electrical_refresher_title",
        steps = listOf(elecShockFirstAction, burnFirstAid, elecPermitToWork),
        isRefresherVariant = true,
    )

    val electricalFirstResponse = SafetyModule(
        moduleId = ID_ELECTRICAL,
        moduleCode = CODE_ELECTRICAL,
        titleKey = "module_electrical_title",
        descriptionKey = "module_electrical_desc",
        pictogram = Pictogram.WARNING_ELECTRICITY,
        sectors = setOf(Sector.ALL),
        statutoryReference = "Factories Act 1948 s.36A; Mines Rules 1955 r.123",
        estimatedMinutes = 9,
        scenarios = listOf(electricalFullScenario, electricalRefresherScenario),
    )

    // =======================================================================
    // Registry
    // =======================================================================

    val all: List<SafetyModule> = listOf(
        fireEvacuation,
        gasConfinedSpace,
        machineryLoto,
        ppeHeight,
        electricalFirstResponse,
    )

    /**
     * The two modules the problem statement requires as complete AR experiences, and the two
     * that ship with full AR scenes, buddy support and refreshers wired end to end. The other
     * three are complete as assessable content and run through the same engine; their bespoke
     * AR scenes are the documented next increment rather than an implied capability.
     */
    val fullyImplemented: List<SafetyModule> = listOf(fireEvacuation, gasConfinedSpace)

    private val byIdIndex: Map<String, SafetyModule> = all.associateBy { it.moduleId }
    private val byCodeIndex: Map<Int, SafetyModule> = all.associateBy { it.moduleCode }
    private val scenarioIndex: Map<String, ScenarioSpec> =
        all.flatMap { it.scenarios }.associateBy { it.scenarioId }

    init {
        // Fail at class-load rather than at certificate-issue time.
        check(byIdIndex.size == all.size) { "duplicate moduleId in the catalog" }
        check(byCodeIndex.size == all.size) { "duplicate moduleCode in the catalog" }
        check(scenarioIndex.size == all.sumOf { it.scenarios.size }) {
            "duplicate scenarioId across modules in the catalog"
        }
    }

    fun byId(moduleId: String): SafetyModule? = byIdIndex[moduleId]

    fun byCode(moduleCode: Int): SafetyModule? = byCodeIndex[moduleCode]

    fun scenario(scenarioId: String): ScenarioSpec? = scenarioIndex[scenarioId]

    fun forSector(sector: Sector): List<SafetyModule> = all.filter { it.appliesTo(sector) }

    /** Every step across the catalog. Used by tests and by the string-resource audit. */
    fun allSteps(): List<StepSpec> = all.flatMap { module ->
        module.scenarios.flatMap { it.steps }
    }.distinctBy { it.stepId }

    /**
     * Every string key the catalog expects to exist in `strings.xml`.
     *
     * A test walks this list against the Android resources so a missing Hindi or Santali
     * translation is a build-time failure rather than a blank button in front of a worker who
     * cannot read the fallback.
     */
    fun requiredStringKeys(): List<String> {
        val keys = mutableListOf<String>()
        for (module in all) {
            keys += module.titleKey
            keys += module.descriptionKey
            for (scenario in module.scenarios) {
                keys += scenario.titleKey
                for (step in scenario.steps) {
                    keys += step.promptKey
                    step.remediationKey?.let { keys += it }
                    for (option in step.options) {
                        keys += option.labelKey
                    }
                }
            }
        }
        return keys.distinct().sorted()
    }

    /** Every AR target the catalog references. Checked against [ArTargets.ALL] by a test. */
    fun referencedArTargets(): Set<String> =
        allSteps().flatMap { it.options }.mapNotNull { it.arTargetKey }.toSet()
}
