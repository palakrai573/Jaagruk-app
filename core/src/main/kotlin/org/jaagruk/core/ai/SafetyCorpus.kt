package org.jaagruk.core.ai

import org.jaagruk.core.catalog.ModuleCatalog

/**
 * The bundled safety text every generated answer is grounded in.
 *
 * Compiled into `:core` for the same three reasons [ModuleCatalog] is: it works on a handset that
 * has never had signal, it is validated at construction rather than at first use, and it is
 * reviewable in a diff by somebody who has to sign off on safety content.
 *
 * ## Why this file is the important half of the AI work
 *
 * The model contributes phrasing. This file contributes facts. Published benchmarking on sub-1B
 * models shows accuracy on classification tasks collapsing without retrieval and recovering sharply
 * once relevant text is supplied, which matches what a 1B model is: fluent, and not a reliable
 * store of specifics. So every figure a worker can be shown lives here, in text a reviewer can
 * check, and [AnswerGuard] rejects any number that does not appear in the prompt built from it.
 *
 * ## Rules for editing
 *
 *  * **Write figures as digits, never as words.** [AnswerGuard] compares numeric tokens. If a
 *    passage says "twenty minutes" and the model writes "20 minutes", the answer is rejected as
 *    ungrounded — a false rejection, but a fail-safe one. Digits keep it correct.
 *  * **One idea per passage.** Retrieval returns whole passages; two ideas means the model is
 *    handed one it did not need and may quote from.
 *  * **Every passage needs a real `sourceLabel`.** It is shown to the worker as the citation. An
 *    answer whose source cannot be checked is an assertion.
 *  * **English and Hindi are authored in pairs**, `<id>-en` and `<id>-hi`, and a test asserts the
 *    pairing so a passage cannot exist in one language only.
 *
 * ## Provenance of the figures used
 *
 *  * 1.25 % methane — withdraw persons; 2 % — nobody permitted. DGMS graded action levels for
 *    Indian coal mines. 1.25 % is the same figure [ModuleCatalog] already documents.
 *  * 19.5 % oxygen — below this an atmosphere is oxygen-deficient and immediately dangerous;
 *    standard confined-space practice.
 *  * 75 degrees, 1:4 — portable ladder pitch.
 *  * 20 minutes — cooling time for a thermal burn under clean running water.
 *
 * Statutory citations are the same ones [ModuleCatalog] attaches to each module, so an exported
 * compliance report and a generated explanation cite the same law.
 */
object SafetyCorpus {

    private fun passage(
        id: String,
        language: AiLanguage,
        title: String,
        body: String,
        scope: PassageScope,
        sourceLabel: String,
        moduleId: String? = null,
        steps: Set<String> = emptySet(),
        statute: String? = null,
    ): CorpusPassage = CorpusPassage(
        passageId = id,
        title = title,
        body = body.trimIndent().replace("\n", " ").replace(Regex(" {2,}"), " ").trim(),
        language = language,
        scope = scope,
        sourceLabel = sourceLabel,
        moduleId = moduleId,
        stepIds = steps,
        statutoryReference = statute,
    )

    private fun en(
        id: String,
        title: String,
        body: String,
        scope: PassageScope,
        sourceLabel: String,
        moduleId: String? = null,
        steps: Set<String> = emptySet(),
        statute: String? = null,
    ) = passage("$id-en", AiLanguage.ENGLISH, title, body, scope, sourceLabel, moduleId, steps, statute)

    private fun hi(
        id: String,
        title: String,
        body: String,
        scope: PassageScope,
        sourceLabel: String,
        moduleId: String? = null,
        steps: Set<String> = emptySet(),
        statute: String? = null,
    ) = passage("$id-hi", AiLanguage.HINDI, title, body, scope, sourceLabel, moduleId, steps, statute)

    private const val SRC_MINES_FIRE = "Mines Act 1952 s.58; Factories Act 1948 s.38"
    private const val SRC_MINES_GAS = "Mines Act 1952 s.29; Mines Rules 1955 r.130"
    private const val SRC_MACHINERY = "Factories Act 1948 s.21-24; Mines Rules 1955 r.187"
    private const val SRC_HEIGHT = "Factories Act 1948 s.32-33; Mines Rules 1955 r.191"
    private const val SRC_ELECTRICAL = "Factories Act 1948 s.36A; Mines Rules 1955 r.123"
    private const val SRC_DGMS_GAS = "DGMS graded methane action levels, Indian coal mines"
    private const val SRC_PRACTICE = "Jaagruk safety training notes"
    private const val SRC_CONFINED = "Confined space entry practice"
    private const val SRC_FIRST_AID = "Industrial first aid practice"

    // =======================================================================
    // 1. Fire and explosion response
    // =======================================================================

    private val fireEnglish = listOf(
        en(
            id = "fire-alarm-first",
            title = "Raise the alarm before doing anything else",
            body = """
                The first action on discovering a fire is to raise the alarm, using the nearest
                call point or by shouting a clear warning. Everybody else in the area needs the
                same time to get out that you have. Fighting a fire first, looking for a
                supervisor first, or photographing it first all spend the one thing an
                evacuation cannot get back, which is time. Raising the alarm also brings help
                that is trained and equipped, and it starts the headcount that tells the site
                whether anybody is still inside.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_FIRE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_detect_alarm"),
            statute = SRC_MINES_FIRE,
        ),
        en(
            id = "fire-extinguisher-class",
            title = "Choosing the right extinguisher",
            body = """
                The extinguisher has to match what is burning. Carbon dioxide is for electrical
                equipment and for flammable liquids, because it does not conduct and leaves no
                residue in a motor or a panel. Water must never go on an electrical fire or on
                burning oil: on live equipment it conducts current back to the person holding
                the hose, and on oil it flashes the burning liquid outward. Foam suits liquid
                fires but not live electrical gear. Dry powder covers many classes but wrecks
                machinery and blinds the room, so it is not the first choice indoors. If you
                cannot identify what is burning, do not guess; evacuate and let the trained team
                deal with it.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_FIRE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_pick_extinguisher"),
        ),
        en(
            id = "fire-extinguisher-technique",
            title = "Using an extinguisher: pull, aim, squeeze, sweep",
            body = """
                Pull the safety pin, aim at the base of the flames rather than at the smoke,
                squeeze the lever, and sweep side to side across the base. Aiming at the visible
                flame wastes the charge, because the fire is fed at the fuel, not in the air
                above it. Keep your back to a clear escape route the whole time and never let
                the fire come between you and the way out. An extinguisher is for a fire small
                enough to be out in seconds. If it is not going out, or the room is filling with
                smoke, leave.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_PRACTICE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_extinguisher_sequence"),
        ),
        en(
            id = "fire-exit-and-smoke",
            title = "The nearest usable exit, and staying under the smoke",
            body = """
                Take the nearest exit that is actually usable, which is not always the nearest
                exit or the way you came in. An exit behind the fire, or one that opens into
                smoke, is not usable. Smoke and hot gas collect at the roof first, so the
                breathable air is low down; move quickly but keep low, and keep one hand on a
                wall so you can still follow the route when visibility goes. Never use a lift
                during a fire. Count doorways or pillars as you go, because in thick smoke that
                count is the only thing that tells you where you are.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_FIRE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_locate_exit", "fire_movement_posture"),
        ),
        en(
            id = "fire-doors",
            title = "Doors during an evacuation",
            body = """
                Before opening a door on an escape route, feel it with the back of your hand
                near the top. A hot door means fire on the other side, and opening it feeds the
                fire a rush of air and puts flame into your route. Use another way out. When you
                do pass through a door, close it behind you: a closed door slows fire and smoke
                and buys time for anybody still behind you. Never wedge a fire door open, and
                never block one with stored material.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_PRACTICE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_door_action"),
        ),
        en(
            id = "fire-assembly-and-headcount",
            title = "The assembly point, and never going back in",
            body = """
                Go to the designated assembly point and stay there. The headcount at the
                assembly point is how the site learns whether anybody is unaccounted for, and a
                worker who wanders off to a different gate is recorded as missing, which sends
                a rescue team into a burning building to look for somebody who is safe. Report
                to the person taking the count even if you think it is obvious you are out.
                Never re-enter to fetch belongings or to look for a colleague; tell the count
                keeper who you think is still inside and let the trained team go.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_FIRE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_assembly_point", "fire_headcount"),
        ),
        en(
            id = "fire-statute",
            title = "What the law requires on fire safety",
            body = """
                The Mines Act 1952 and the Factories Act 1948 both require a means of escape
                that is kept clear and usable, and both require workers to be instructed in it.
                Escape routes have to be marked, unobstructed and known to the people who would
                use them. Periodic drills and refresher instruction are part of the duty, not an
                optional extra, which is why a certificate that was earned once and never
                revisited does not satisfy the intent even when the date on it is still valid.
            """,
            scope = PassageScope.STATUTE,
            sourceLabel = SRC_MINES_FIRE,
            moduleId = ModuleCatalog.ID_FIRE,
            statute = SRC_MINES_FIRE,
        ),
    )

    // =======================================================================
    // 2. Gas leak and confined space
    // =======================================================================

    private val gasEnglish = listOf(
        en(
            id = "gas-methane-levels",
            title = "Methane action levels in an Indian coal mine",
            body = """
                Methane is measured as a percentage of the air. At 1.25 % persons are withdrawn
                from the affected area. At 2 % nobody is permitted in it at all. These are
                graded action levels, not warnings to be judged by feel, and they exist because
                methane is explosive across a band well below the point where it would displace
                enough oxygen to make anybody feel unwell. Methane is lighter than air and
                collects at the roof and in cavities, so a reading taken at waist height can be
                clear while the roof is already over the limit. Test at the roof, at the face,
                on return sides and around goaf edges, and test again before any equipment is
                restarted.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_DGMS_GAS,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_recognise_zone", "gas_first_action", "gas_ventilation_check"),
            statute = SRC_MINES_GAS,
        ),
        en(
            id = "gas-oxygen-deficiency",
            title = "Oxygen below 19.5 % is immediately dangerous",
            body = """
                Normal air is close to 21 % oxygen. Below 19.5 % an atmosphere is treated as
                oxygen-deficient and immediately dangerous to life, and the danger is that it
                does not feel like anything: judgement and coordination go before the person
                notices, so somebody in a low-oxygen space often cannot decide to leave. That is
                why entry depends on a meter reading and not on how the space seems. An
                enriched atmosphere is also dangerous, for a different reason: materials that
                merely smoulder in normal air burn fiercely in extra oxygen.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_CONFINED,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_ventilation_check", "gas_entry_sequence"),
        ),
        en(
            id = "gas-ppe-selection",
            title = "A dust mask is not respiratory protection",
            body = """
                A cloth or dust mask filters particles. It adds no oxygen and removes no gas, so
                in an oxygen-deficient or gassy space it does nothing except make the wearer feel
                protected, which is worse than wearing nothing. Entry into a space that has
                failed a gas test needs self-contained breathing apparatus, which carries its own
                air supply. A cartridge respirator is also not enough where oxygen is low,
                because it filters the air that is there rather than supplying air. Check the
                cylinder pressure and the mask seal before entry, not after.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_CONFINED,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_ppe_select"),
        ),
        en(
            id = "gas-entry-sequence",
            title = "The order of a confined space entry",
            body = """
                The order is fixed and each step exists because skipping it has killed somebody.
                Isolate and lock off anything that could feed the space. Test the atmosphere from
                outside, at several depths, before anybody goes in. Ventilate, then test again,
                because ventilation moves pockets around rather than removing them instantly.
                Get the written entry permit, which is the record that the tests were actually
                done. Post an attendant who stays outside for the whole entry and who has the
                means to raise a rescue. Only then does anybody enter, wearing the protection the
                tests showed was needed. Testing after entry, or ventilating without retesting,
                inverts the sequence and removes the protection it was supposed to give.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_GAS,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_entry_sequence"),
            statute = SRC_MINES_GAS,
        ),
        en(
            id = "gas-buddy-system",
            title = "The buddy system and the attendant outside",
            body = """
                Confined space work is done in pairs with an attendant who never enters. Contact
                is kept at a fixed interval agreed before entry, by voice, line or radio, and a
                missed contact is treated as an emergency rather than as a faulty radio. The
                interval matters because unconsciousness in a bad atmosphere arrives without
                warning, and the whole value of the pairing is that somebody notices within a
                known number of minutes. The attendant's job is to raise the alarm and to
                account for who is inside, which is why the attendant staying outside is not
                caution but the entire mechanism.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_CONFINED,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_contact_interval", "buddy_periodic_check"),
        ),
        en(
            id = "gas-never-enter-to-rescue",
            title = "Never enter to rescue without breathing apparatus",
            body = """
                Your buddy collapsed inside a tank, pit, silo or any other confined space is the
                single most dangerous situation on a site, because the instinct to go inside and
                pull them out is overwhelming, and acting on it is what kills the second and
                third casualty. The atmosphere that dropped the first person is unchanged and
                will drop you just as fast, usually within a breath or two. The correct actions
                are: do not enter, raise the alarm, start or increase ventilation if that can be
                done from outside, and let the trained rescue team go inside with breathing
                apparatus. Would-be rescuers are a large share of confined-space deaths, and
                every one of them believed they would be quick enough. This is the
                highest-weighted rule in the whole training catalogue.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_GAS,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_rescue_decision", "buddy_distress_response"),
            statute = SRC_MINES_GAS,
        ),
        en(
            id = "gas-statute",
            title = "What the law requires on gassy and confined workings",
            body = """
                The Mines Act 1952 and the Mines Rules 1955 require the atmosphere of a working
                to be tested and recorded, require withdrawal when prescribed limits are
                exceeded, and require that entry into a place where the air may be dangerous is
                controlled by a written permit and supervised. The permit is not paperwork for
                its own sake: it is the evidence that the test was performed before entry rather
                than reconstructed afterwards.
            """,
            scope = PassageScope.STATUTE,
            sourceLabel = SRC_MINES_GAS,
            moduleId = ModuleCatalog.ID_GAS,
            statute = SRC_MINES_GAS,
        ),
    )

    // =======================================================================
    // 3. Machinery and lockout/tagout
    // =======================================================================

    private val machineryEnglish = listOf(
        en(
            id = "loto-sequence",
            title = "The lockout and tagout sequence",
            body = """
                Lockout has a fixed order: notify everybody affected, shut the machine down by
                its normal controls, isolate every energy source, apply your own lock and tag,
                release or restrain stored energy such as springs, raised parts, hydraulic
                pressure and capacitors, and then verify by trying to start the machine. The
                verification attempt is the step people skip and the one that catches a valve
                that did not seat or a second feed nobody knew about. Stored energy is what
                injures workers who believed an isolated machine was a safe machine.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MACHINERY,
            moduleId = ModuleCatalog.ID_MACHINERY,
            steps = setOf("loto_identify_hazard", "loto_sequence"),
            statute = SRC_MACHINERY,
        ),
        en(
            id = "loto-key-control",
            title = "One worker, one lock, one key",
            body = """
                The worker who applies a lock keeps its only key and is the only person who
                removes it. Handing the key to a supervisor, leaving it in the office or using a
                shared lock all break the single guarantee lockout provides, which is that the
                machine cannot start while you are inside it. Where several people work on one
                machine, each applies their own lock to a multi-lock hasp and the machine stays
                dead until the last of them has finished. A lock removed by anybody other than
                its owner is a lock that meant nothing.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MACHINERY,
            moduleId = ModuleCatalog.ID_MACHINERY,
            steps = setOf("loto_key_control"),
        ),
        en(
            id = "loto-conveyor-jam",
            title = "Never clear a jam on a running belt",
            body = """
                A blocked conveyor is cleared only after the belt is stopped, isolated, locked
                and tested for movement. Reaching into a running belt, or one that is stopped but
                not isolated, is how hands and arms are lost: a belt that has stalled under load
                is storing energy and can start moving the instant the blockage shifts, with no
                control input at all. Poking at a jam with a bar from what feels like a safe
                distance is the same hazard, because the bar transmits the belt's movement to
                the person holding it.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MACHINERY,
            moduleId = ModuleCatalog.ID_MACHINERY,
            steps = setOf("loto_conveyor_jam"),
        ),
        en(
            id = "loto-missing-guard",
            title = "A missing guard stops the job",
            body = """
                A machine with a guard missing, defeated or tied back is not to be run. The
                correct action is to stop, report it and have the guard restored before work
                continues. Guards are sized and positioned so that a hand cannot reach the
                dangerous part before the part has stopped moving; a guard that has been lifted
                to make a task easier removes exactly that margin. Continuing because the job is
                nearly done, or because the machine has always been run that way, is the
                reasoning that precedes most machinery injuries.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MACHINERY,
            moduleId = ModuleCatalog.ID_MACHINERY,
            steps = setOf("loto_guard_missing"),
            statute = SRC_MACHINERY,
        ),
        en(
            id = "machinery-statute",
            title = "What the law requires on machinery guarding",
            body = """
                The Factories Act 1948 requires dangerous parts of machinery to be securely
                fenced, and requires that examination or adjustment of moving machinery is
                carried out only by trained adult workers under specified conditions. The Mines
                Rules 1955 impose the equivalent duty underground. The duty is on the condition
                of the machine as it is actually run, not as it was supplied.
            """,
            scope = PassageScope.STATUTE,
            sourceLabel = SRC_MACHINERY,
            moduleId = ModuleCatalog.ID_MACHINERY,
            statute = SRC_MACHINERY,
        ),
    )

    // =======================================================================
    // 4. PPE and work at height
    // =======================================================================

    private val heightEnglish = listOf(
        en(
            id = "ppe-task-match",
            title = "Matching protection to the actual hazard",
            body = """
                Protective equipment is selected against the hazard of the task in front of you,
                not by habit or by what is nearest the door. Eye protection against grinding
                sparks is not eye protection against chemical splash. Leather gloves that suit
                handling steel plate conduct electricity and offer nothing against solvents.
                Hearing protection left around the neck protects nothing. The question to ask
                before a job is what could reach me, from where, and what stops that specific
                thing.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_HEIGHT,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            steps = setOf("ppe_task_match"),
        ),
        en(
            id = "height-anchor-point",
            title = "Where a fall-arrest lanyard is anchored",
            body = """
                An anchor point must be structurally sound and, wherever possible, at or above
                shoulder level. Anchoring low increases the distance you fall before the lanyard
                begins to act, and that distance decides whether the system stops you or simply
                measures your landing. Handrails, pipework, cable trays, ladders and scaffold
                guardrails are not anchor points. Anchor one person per point unless it is rated
                otherwise, and check that a fall would swing you clear of edges and structures
                rather than into them.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_HEIGHT,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            steps = setOf("height_anchor_point"),
            statute = SRC_HEIGHT,
        ),
        en(
            id = "harness-inspection",
            title = "Checking a harness before every use",
            body = """
                Inspect a harness before each use, in order: webbing for cuts, fraying, chemical
                burns and heat glazing; stitching for pulled or broken threads; D-rings and
                buckles for distortion, cracks and corrosion; the lanyard and energy absorber for
                deployment; and the label for identity and inspection date. A harness that has
                already arrested a fall is withdrawn from service even if it looks sound, because
                the energy absorber is spent and will not work twice. Fit it so it is snug, with
                leg straps and chest strap done up; a loose harness lets the body move inside it
                during arrest, which is its own injury.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_PRACTICE,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            steps = setOf("harness_inspection_sequence"),
        ),
        en(
            id = "ladder-angle",
            title = "Setting a portable ladder: 75 degrees",
            body = """
                A portable ladder stands at about 75 degrees, which is 1 unit out for every 4
                units of height. Too shallow and the foot slides out; too steep and it topples
                backwards as the climber's weight passes the top. Set it on firm level ground,
                secure or foot it, and extend it far enough past a landing to give something to
                hold. Climb facing the ladder and keep three points of contact, which means
                carrying tools on a belt or hoisting them rather than in a hand.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_HEIGHT,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            steps = setOf("ladder_angle"),
        ),
        en(
            id = "ppe-defective-item",
            title = "Defective equipment is withdrawn, not worked around",
            body = """
                Damaged protective equipment is taken out of use, tagged and replaced before the
                job starts. A cracked helmet, a harness with cut webbing, a respirator with a
                perished seal and a glove with a hole all present the same problem: the worker
                behaves as though protected while the protection is absent. Never repair
                protective equipment yourself, never borrow an item that has not been checked,
                and never accept a defective item because a replacement would mean waiting.
                Report it so the next person does not draw the same item from the store.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_HEIGHT,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            steps = setOf("ppe_defective_item"),
        ),
        en(
            id = "height-statute",
            title = "What the law requires on floors, openings and height",
            body = """
                The Factories Act 1948 requires floors, steps, stairs, passages and gangways to
                be soundly constructed and kept free from obstruction, and requires openings in
                floors to be securely fenced or covered. Where fencing is impracticable, means of
                support such as belts and harnesses must be provided and used. The Mines Rules
                1955 carry the equivalent duty. Providing equipment and not ensuring it is used
                does not discharge the duty.
            """,
            scope = PassageScope.STATUTE,
            sourceLabel = SRC_HEIGHT,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            statute = SRC_HEIGHT,
        ),
    )

    // =======================================================================
    // 5. Electrical safety and first response
    // =======================================================================

    private val electricalEnglish = listOf(
        en(
            id = "elec-shock-isolate-first",
            title = "Never touch someone who is still in contact with electricity",
            body = """
                A person receiving an electric shock may be unable to let go, because current
                across the forearm locks the grip. Touching them puts the same current through
                you and produces a second casualty who cannot help the first. Isolate the supply
                at the switch, breaker or plug. If the supply cannot be isolated, push or pull
                the person clear using something dry and non-conducting such as a wooden pole,
                and stand on something dry while doing it. Only once contact is broken do you
                check breathing and start resuscitation, and send for help at the same time
                rather than afterwards.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_ELECTRICAL,
            moduleId = ModuleCatalog.ID_ELECTRICAL,
            steps = setOf("elec_shock_first_action", "first_response_sequence"),
            statute = SRC_ELECTRICAL,
        ),
        en(
            id = "elec-damaged-cable",
            title = "A damaged cable is de-energised, not taped",
            body = """
                Exposed conductors, cracked insulation, a cable crushed by traffic or a joint
                made with tape are all reported and de-energised, and the repair is done by a
                competent electrical person. Insulating tape over damaged insulation looks like a
                repair and is not one: it fails with heat, damp and vibration, and coal dust
                settling on a warm taped joint is an ignition source in its own right. Do not
                move or coil a damaged cable to tidy it, and keep people away from it until the
                supply is proved dead.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_ELECTRICAL,
            moduleId = ModuleCatalog.ID_ELECTRICAL,
            steps = setOf("elec_cable_damage"),
        ),
        en(
            id = "elec-permit-to-work",
            title = "Permit to work, test dead, then earth",
            body = """
                Work on electrical equipment follows a sequence: obtain the permit, identify the
                correct circuit, isolate it, lock and tag the isolation, prove the tester works,
                test the circuit dead at the point of work, and apply earths where the system
                requires them. Testing dead somewhere other than the point of work proves the
                wrong thing. Proving the tester before and after the test is what catches a
                tester that failed between checks and would otherwise show a live circuit as
                dead. A permit closed before the earths are removed and the work checked is a
                permit that has stopped describing reality.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_ELECTRICAL,
            moduleId = ModuleCatalog.ID_ELECTRICAL,
            steps = setOf("elec_permit_to_work"),
            statute = SRC_ELECTRICAL,
        ),
        en(
            id = "burn-first-aid",
            title = "Cooling a burn: 20 minutes of clean running water",
            body = """
                Cool a thermal burn with clean running water for 20 minutes, starting as soon as
                possible. Cooling limits how deep the injury goes, and it still helps up to
                several hours after the event. Remove rings, watches and tight clothing near the
                burn before swelling starts, but leave anything stuck to the skin alone. Do not
                use ice, which deepens the injury by restricting blood flow, and do not apply
                oil, toothpaste, ash or turmeric, which trap heat and introduce infection. Cover
                loosely with clean non-fluffy material and get medical help. An electrical burn
                needs medical assessment even when the skin looks almost unmarked, because the
                damage follows the current through the body.
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_FIRST_AID,
            moduleId = ModuleCatalog.ID_ELECTRICAL,
            steps = setOf("burn_first_aid"),
        ),
        en(
            id = "electrical-statute",
            title = "What the law requires on electrical safety",
            body = """
                The Factories Act 1948 requires precautions against danger from electricity, and
                the Mines Rules 1955 require electrical apparatus to be installed and maintained
                so as to prevent danger, with work on it restricted to authorised competent
                persons. In gassy workings there are additional requirements for flameproof
                equipment, because an arc that would be a nuisance elsewhere is an ignition
                source there.
            """,
            scope = PassageScope.STATUTE,
            sourceLabel = SRC_ELECTRICAL,
            moduleId = ModuleCatalog.ID_ELECTRICAL,
            statute = SRC_ELECTRICAL,
        ),
    )

    // =======================================================================
    // Cross-cutting
    // =======================================================================

    private val generalEnglish = listOf(
        en(
            id = "general-report-hazard",
            title = "Reporting a near miss is worth more than reporting an accident",
            body = """
                A near miss is an accident that happened to miss. Reporting a blocked exit, an
                exposed conductor, a missing extinguisher or a guard tied back gets it fixed
                while it is still free to fix. Report what you saw and where, plainly, without
                worrying about whether it is somebody's fault; the point is the condition, not
                the blame. A report with a photograph and a zone is easier to act on than one
                without, but a report with neither still beats no report.
            """,
            scope = PassageScope.GENERAL,
            sourceLabel = SRC_PRACTICE,
        ),
        en(
            id = "general-decide-fast",
            title = "Knowing the answer and acting on it are different skills",
            body = """
                In an emergency the gap between knowing what to do and doing it is where people
                are hurt. Hesitation under pressure is a documented failure mode that is
                independent of knowledge: workers who can state the correct action in a classroom
                still freeze in a corridor filling with smoke. The way through it is to fix one
                cue per situation and act on that cue without re-deciding, which is what
                practising in the place you actually work is for. Being interrupted is not
                hesitation, and neither is checking a meter before entering; hesitation is
                knowing the action and not starting it.
            """,
            scope = PassageScope.GENERAL,
            sourceLabel = SRC_PRACTICE,
        ),
        en(
            id = "general-refresher",
            title = "Why short refreshers beat one long course",
            body = """
                Safety knowledge fades. Retention after a single classroom session drops sharply
                within the first week, which is why a certificate earned once and never revisited
                says less about today than its date suggests. Short, spaced checks rebuild it far
                more cheaply than an annual re-run: each one extends how long the material stays
                available before it needs revisiting. A refresher restores readiness. It does not
                extend the statutory validity of a certificate, which only a full re-run does.
            """,
            scope = PassageScope.GENERAL,
            sourceLabel = SRC_PRACTICE,
        ),
    )

    private val english: List<CorpusPassage> =
        fireEnglish + gasEnglish + machineryEnglish + heightEnglish + electricalEnglish + generalEnglish

    // =======================================================================
    // Hindi — authored as pairs of the English passages above.
    //
    // Translated rather than generated, and deliberately plain: the reader may have limited formal
    // schooling and no industrial background. Every figure is written in digits and matches its
    // English pair exactly, because AnswerGuard compares numeric tokens and a figure spelled out in
    // words in one language and in digits in the other would reject a correct answer.
    // =======================================================================

    private val fireHindi = listOf(
        hi(
            id = "fire-alarm-first",
            title = "सबसे पहले चेतावनी दें",
            body = """
                आग दिखते ही पहला काम है चेतावनी देना — सबसे नज़दीक के अलार्म बटन से, या ज़ोर से आवाज़
                लगाकर। आपके आसपास के सब लोगों को बाहर निकलने के लिए उतना ही समय चाहिए जितना आपको
                चाहिए। पहले आग बुझाने की कोशिश करना, पहले सुपरवाइज़र को ढूँढ़ना, या पहले फ़ोटो लेना —
                इन सबमें वही चीज़ बर्बाद होती है जो निकासी में दोबारा नहीं मिलती, यानी समय। चेतावनी
                देने से प्रशिक्षित मदद भी आती है और गिनती शुरू होती है, जिससे पता चलता है कि कोई
                अंदर रह गया है या नहीं।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_FIRE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_detect_alarm"),
            statute = SRC_MINES_FIRE,
        ),
        hi(
            id = "fire-extinguisher-class",
            title = "सही अग्निशामक यंत्र चुनना",
            body = """
                यंत्र उसी चीज़ के हिसाब से चुनें जो जल रही है। बिजली के उपकरण और ज्वलनशील तरल के लिए
                कार्बन डाइऑक्साइड का यंत्र है, क्योंकि यह बिजली नहीं पहुँचाता और मोटर या पैनल में कुछ
                छोड़कर नहीं जाता। बिजली की आग या जलते तेल पर पानी कभी न डालें: चालू उपकरण पर पानी
                करंट को वापस पकड़ने वाले तक पहुँचाता है, और तेल पर जलता तरल बाहर की तरफ़ फैला देता है।
                फ़ोम तरल की आग के लिए ठीक है, चालू बिजली के लिए नहीं। सूखा पाउडर कई तरह की आग पर काम
                करता है पर मशीन खराब कर देता है और कमरे में कुछ दिखता नहीं, इसलिए अंदर यह पहली पसंद
                नहीं है। अगर पहचान न हो कि क्या जल रहा है, तो अंदाज़ा न लगाएँ — बाहर निकलें और
                प्रशिक्षित टीम को करने दें।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_FIRE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_pick_extinguisher"),
        ),
        hi(
            id = "fire-extinguisher-technique",
            title = "यंत्र चलाने का तरीका: पिन खींचें, निशाना लगाएँ, दबाएँ, फैलाएँ",
            body = """
                सुरक्षा पिन खींचें, धुएँ पर नहीं बल्कि लपटों की जड़ पर निशाना लगाएँ, लीवर दबाएँ, और
                जड़ पर बाएँ-दाएँ फैलाएँ। दिखती लपट पर निशाना लगाने से यंत्र खाली हो जाता है, क्योंकि
                आग को ईंधन से खुराक मिलती है, ऊपर की हवा से नहीं। पूरे समय अपनी पीठ खुले रास्ते की
                तरफ़ रखें और आग को अपने और बाहर निकलने के रास्ते के बीच कभी न आने दें। यंत्र सिर्फ़
                उतनी छोटी आग के लिए है जो कुछ सेकंड में बुझ जाए। अगर आग बुझ नहीं रही, या कमरे में धुआँ
                भर रहा है, तो बाहर निकलें।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_PRACTICE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_extinguisher_sequence"),
        ),
        hi(
            id = "fire-exit-and-smoke",
            title = "काम आने वाला सबसे नज़दीक का रास्ता, और धुएँ के नीचे रहना",
            body = """
                वह सबसे नज़दीक का रास्ता लें जो असल में काम आ सके — यह ज़रूरी नहीं कि सबसे नज़दीक का
                दरवाज़ा हो या वही रास्ता हो जिससे आप आए थे। आग के पीछे का रास्ता, या वह जो धुएँ में
                खुलता है, काम का नहीं है। धुआँ और गरम गैस पहले छत के पास जमा होती है, इसलिए साँस लेने
                लायक हवा नीचे रहती है; तेज़ चलें पर नीचे झुके रहें, और एक हाथ दीवार पर रखें ताकि कुछ
                दिखना बंद हो जाए तब भी रास्ता पकड़ा रहे। आग के समय लिफ़्ट कभी न लें। चलते हुए दरवाज़े
                या खंभे गिनते चलें, क्योंकि गहरे धुएँ में वही गिनती बताती है कि आप कहाँ हैं।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_FIRE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_locate_exit", "fire_movement_posture"),
        ),
        hi(
            id = "fire-doors",
            title = "निकासी के समय दरवाज़े",
            body = """
                निकासी के रास्ते में दरवाज़ा खोलने से पहले उसे हाथ के पिछले हिस्से से ऊपर की तरफ़
                छूकर देखें। दरवाज़ा गरम है तो दूसरी तरफ़ आग है, और उसे खोलने से आग को हवा मिलती है और
                लपट आपके रास्ते में आ जाती है। दूसरा रास्ता लें। जिस दरवाज़े से निकलें उसे पीछे बंद
                करें: बंद दरवाज़ा आग और धुएँ को धीमा करता है और पीछे रह गए लोगों के लिए समय बनाता है।
                आग का दरवाज़ा कभी खुला बाँधकर न रखें और उसके सामने सामान न रखें।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_PRACTICE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_door_action"),
        ),
        hi(
            id = "fire-assembly-and-headcount",
            title = "इकट्ठा होने की जगह, और वापस अंदर न जाना",
            body = """
                तय की गई इकट्ठा होने की जगह पर जाएँ और वहीं रुकें। वहाँ की गिनती से ही पता चलता है
                कि कोई लापता है या नहीं, और जो मज़दूर किसी दूसरे गेट पर चला जाता है वह लापता दर्ज
                होता है — इससे बचाव टीम जलती इमारत में उस आदमी को खोजने जाती है जो असल में सुरक्षित
                है। गिनती करने वाले को अपनी हाज़िरी बताएँ, चाहे आपको लगे कि आपका बाहर होना साफ़ दिख
                रहा है। सामान लेने या किसी साथी को खोजने के लिए वापस अंदर कभी न जाएँ; गिनती करने वाले
                को बताएँ कि आपके हिसाब से कौन अंदर है, और प्रशिक्षित टीम को जाने दें।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_FIRE,
            moduleId = ModuleCatalog.ID_FIRE,
            steps = setOf("fire_assembly_point", "fire_headcount"),
        ),
        hi(
            id = "fire-statute",
            title = "आग सुरक्षा पर कानून क्या कहता है",
            body = """
                खान अधिनियम 1952 और कारखाना अधिनियम 1948, दोनों में बाहर निकलने का रास्ता खुला और
                काम लायक रखना ज़रूरी है, और दोनों में मज़दूरों को उसकी जानकारी देना ज़रूरी है। निकासी
                के रास्ते चिह्नित हों, बिना रुकावट हों, और जिन लोगों को उनका उपयोग करना है उन्हें
                मालूम हों। समय-समय पर अभ्यास और दोहराव भी इसी कर्तव्य का हिस्सा है, अलग से मर्ज़ी की
                बात नहीं — इसीलिए एक बार लिया गया प्रमाणपत्र, जिसे फिर कभी दोहराया न गया हो, तारीख़
                चालू रहने पर भी मंशा पूरी नहीं करता।
            """,
            scope = PassageScope.STATUTE,
            sourceLabel = SRC_MINES_FIRE,
            moduleId = ModuleCatalog.ID_FIRE,
            statute = SRC_MINES_FIRE,
        ),
    )

    private val gasHindi = listOf(
        hi(
            id = "gas-methane-levels",
            title = "भारतीय कोयला खदान में मेथेन के कार्रवाई स्तर",
            body = """
                मेथेन हवा में प्रतिशत के हिसाब से मापी जाती है। 1.25 % पर प्रभावित क्षेत्र से लोगों
                को बाहर निकाला जाता है। 2 % पर किसी को भी उस क्षेत्र में रहने की अनुमति नहीं है। ये
                तय किए गए कार्रवाई स्तर हैं, अंदाज़े से तय करने वाली चेतावनी नहीं, और ये इसलिए हैं
                क्योंकि मेथेन उस स्तर से बहुत नीचे ही विस्फोटक हो जाती है जहाँ ऑक्सीजन इतनी घटे कि
                किसी की तबीयत बिगड़े। मेथेन हवा से हल्की है और छत के पास तथा खाली जगहों में जमा होती
                है, इसलिए कमर की ऊँचाई पर लिया गया माप साफ़ आ सकता है जबकि छत के पास पहले ही सीमा पार
                हो चुकी हो। छत पर, फ़ेस पर, रिटर्न साइड पर और गोआफ़ के किनारों पर माप लें, और कोई भी
                उपकरण दोबारा चालू करने से पहले फिर माप लें।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_DGMS_GAS,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_recognise_zone", "gas_first_action", "gas_ventilation_check"),
            statute = SRC_MINES_GAS,
        ),
        hi(
            id = "gas-oxygen-deficiency",
            title = "19.5 % से नीचे ऑक्सीजन तुरंत जानलेवा है",
            body = """
                सामान्य हवा में ऑक्सीजन लगभग 21 % होती है। 19.5 % से नीचे हवा को ऑक्सीजन की कमी वाली
                और तुरंत जानलेवा माना जाता है, और ख़तरा यही है कि इसमें कुछ महसूस नहीं होता: सोचने की
                क्षमता और शरीर का तालमेल पहले जाते हैं, पता बाद में चलता है — इसलिए कम ऑक्सीजन वाली
                जगह में फँसा आदमी अक्सर बाहर निकलने का फ़ैसला ही नहीं कर पाता। इसीलिए अंदर जाना मीटर
                के माप पर तय होता है, इस पर नहीं कि जगह कैसी लग रही है। ज़्यादा ऑक्सीजन भी ख़तरनाक है,
                दूसरी वजह से: जो चीज़ें सामान्य हवा में सिर्फ़ सुलगती हैं, वे ज़्यादा ऑक्सीजन में तेज़ी
                से जलती हैं।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_CONFINED,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_ventilation_check", "gas_entry_sequence"),
        ),
        hi(
            id = "gas-ppe-selection",
            title = "धूल का मास्क साँस की सुरक्षा नहीं है",
            body = """
                कपड़े या धूल का मास्क कणों को छानता है। यह ऑक्सीजन नहीं देता और गैस नहीं हटाता,
                इसलिए ऑक्सीजन की कमी वाली या गैस वाली जगह में यह कुछ नहीं करता — बस पहनने वाले को
                सुरक्षित होने का भरम देता है, जो कुछ न पहनने से भी बुरा है। जिस जगह की गैस जाँच फेल
                हुई है, वहाँ जाने के लिए स्वयं-निहित श्वास उपकरण चाहिए, जो अपनी हवा साथ रखता है।
                कारतूस वाला रेस्पिरेटर भी कम ऑक्सीजन में पर्याप्त नहीं है, क्योंकि वह मौजूद हवा को
                छानता है, हवा देता नहीं। सिलेंडर का दबाव और मास्क की सील अंदर जाने से पहले जाँचें,
                बाद में नहीं।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_CONFINED,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_ppe_select"),
        ),
        hi(
            id = "gas-entry-sequence",
            title = "बंद जगह में जाने का क्रम",
            body = """
                क्रम तय है और उसका हर कदम इसलिए है कि उसे छोड़ने से किसी की जान गई है। जो कुछ उस जगह
                में आ सकता है, उसे अलग करें और ताला लगाएँ। किसी के अंदर जाने से पहले, बाहर से ही, कई
                गहराइयों पर हवा की जाँच करें। हवादार करें, फिर दोबारा जाँचें — क्योंकि हवा चलाने से
                गैस के गुच्छे इधर-उधर होते हैं, तुरंत ख़त्म नहीं होते। लिखित प्रवेश परमिट लें, जो इस
                बात का रिकॉर्ड है कि जाँच असल में हुई थी। एक निगरान बाहर तैनात करें जो पूरे समय बाहर
                रहे और जिसके पास बचाव बुलाने का साधन हो। इसके बाद ही कोई अंदर जाए, और वही सुरक्षा
                पहनकर जो जाँच के हिसाब से ज़रूरी थी। अंदर जाने के बाद जाँचना, या हवादार करके दोबारा न
                जाँचना, इस क्रम को उलट देता है और वह सुरक्षा हटा देता है जिसके लिए क्रम बना है।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_GAS,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_entry_sequence"),
            statute = SRC_MINES_GAS,
        ),
        hi(
            id = "gas-buddy-system",
            title = "साथी प्रणाली और बाहर तैनात निगरान",
            body = """
                बंद जगह का काम जोड़ी में होता है, और एक निगरान बाहर रहता है जो कभी अंदर नहीं जाता।
                अंदर जाने से पहले तय किए गए अंतराल पर आवाज़, रस्सी या रेडियो से संपर्क बनाए रखा जाता
                है, और संपर्क टूटने को खराब रेडियो न मानकर आपात स्थिति माना जाता है। अंतराल इसलिए
                मायने रखता है कि खराब हवा में बेहोशी बिना चेतावनी आती है, और जोड़ी का पूरा फ़ायदा यही
                है कि तय मिनटों के अंदर किसी को पता चल जाए। निगरान का काम है चेतावनी देना और हिसाब
                रखना कि अंदर कौन है — इसीलिए निगरान का बाहर रहना सावधानी नहीं, बल्कि पूरी व्यवस्था
                है।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_CONFINED,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_contact_interval", "buddy_periodic_check"),
        ),
        hi(
            id = "gas-never-enter-to-rescue",
            title = "श्वास उपकरण के बिना बचाने कभी अंदर न जाएँ",
            body = """
                टंकी, गड्ढे, साइलो या किसी भी बंद जगह के अंदर गिरा हुआ आपका साथी साइट की सबसे
                ख़तरनाक स्थिति है, क्योंकि अंदर जाकर उसे खींचकर बाहर निकालने की भावना बहुत तेज़ होती
                है, और उस पर अमल करना ही दूसरे और तीसरे आदमी की जान लेता है। जिस हवा ने पहले आदमी
                को गिराया वह वैसी ही है और आपको भी उतनी ही तेज़ी से गिराएगी, अक्सर एक-दो साँस में।
                सही कार्रवाई यह है: अंदर न जाएँ, चेतावनी दें, अगर बाहर से हो सके तो हवा चालू करें या
                बढ़ाएँ, और प्रशिक्षित बचाव टीम को श्वास उपकरण के साथ अंदर जाने दें। बंद जगह में हुई
                मौतों का बड़ा हिस्सा बचाने गए लोगों का है, और उनमें से हर एक को यही लगा था कि वह
                जल्दी कर लेगा। पूरे प्रशिक्षण में यही नियम सबसे भारी है।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MINES_GAS,
            moduleId = ModuleCatalog.ID_GAS,
            steps = setOf("gas_rescue_decision", "buddy_distress_response"),
            statute = SRC_MINES_GAS,
        ),
        hi(
            id = "gas-statute",
            title = "गैस वाली और बंद जगहों पर कानून क्या कहता है",
            body = """
                खान अधिनियम 1952 और खान नियम 1955 के अनुसार काम की जगह की हवा की जाँच और उसका
                रिकॉर्ड ज़रूरी है, निर्धारित सीमा पार होने पर लोगों को बाहर निकालना ज़रूरी है, और जहाँ
                हवा ख़तरनाक हो सकती है वहाँ जाना लिखित परमिट और निगरानी में ही होता है। परमिट कागज़ी
                काम नहीं है: यह सबूत है कि जाँच अंदर जाने से पहले हुई थी, बाद में बनाकर नहीं लिखी
                गई।
            """,
            scope = PassageScope.STATUTE,
            sourceLabel = SRC_MINES_GAS,
            moduleId = ModuleCatalog.ID_GAS,
            statute = SRC_MINES_GAS,
        ),
    )

    private val machineryHindi = listOf(
        hi(
            id = "loto-sequence",
            title = "ताला और चेतावनी पर्ची का क्रम",
            body = """
                लॉकआउट का क्रम तय है: प्रभावित सब लोगों को बताएँ, मशीन को उसके सामान्य नियंत्रण से
                बंद करें, ऊर्जा के हर स्रोत को अलग करें, अपना ताला और पर्ची लगाएँ, जमा ऊर्जा — जैसे
                स्प्रिंग, उठे हुए हिस्से, हाइड्रोलिक दबाव और कैपेसिटर — को छोड़ें या रोकें, और फिर
                मशीन चालू करने की कोशिश करके पुष्टि करें। यही पुष्टि वाला कदम लोग छोड़ते हैं और यही
                उस वाल्व को पकड़ता है जो पूरा बंद नहीं हुआ, या उस दूसरे स्रोत को जिसका किसी को पता
                नहीं था। जमा ऊर्जा ही उन मज़दूरों को घायल करती है जो मानते हैं कि अलग की गई मशीन
                सुरक्षित मशीन है।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MACHINERY,
            moduleId = ModuleCatalog.ID_MACHINERY,
            steps = setOf("loto_identify_hazard", "loto_sequence"),
            statute = SRC_MACHINERY,
        ),
        hi(
            id = "loto-key-control",
            title = "एक मज़दूर, एक ताला, एक चाबी",
            body = """
                जो मज़दूर ताला लगाता है वही उसकी एकमात्र चाबी रखता है और वही उसे खोलता है। चाबी
                सुपरवाइज़र को देना, दफ़्तर में छोड़ना, या साझा ताला इस्तेमाल करना — ये सब लॉकआउट की
                उस एक गारंटी को तोड़ देते हैं कि जब आप मशीन के अंदर हैं तब वह चालू नहीं हो सकती।
                जहाँ एक मशीन पर कई लोग काम करें, वहाँ हर एक अपना ताला बहु-ताला हैस्प पर लगाता है और
                मशीन तब तक बंद रहती है जब तक आख़िरी आदमी का काम पूरा न हो। जिस ताले को उसका मालिक
                छोड़कर कोई और खोल दे, उस ताले का कोई मतलब नहीं था।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MACHINERY,
            moduleId = ModuleCatalog.ID_MACHINERY,
            steps = setOf("loto_key_control"),
        ),
        hi(
            id = "loto-conveyor-jam",
            title = "चलती बेल्ट का जाम कभी न छुड़ाएँ",
            body = """
                रुकी हुई कन्वेयर बेल्ट को तभी साफ़ किया जाता है जब बेल्ट बंद हो, अलग हो, ताला लगा हो
                और हिलने के लिए जाँच ली गई हो। चलती बेल्ट में हाथ डालना, या ऐसी बेल्ट में जो बंद है
                पर अलग नहीं की गई, इसी तरह हाथ और बाज़ू कटते हैं: भार के नीचे अटकी बेल्ट में ऊर्जा
                जमा होती है और रुकावट हटते ही वह बिना किसी नियंत्रण के चल पड़ती है। सुरक्षित लगती
                दूरी से सरिये से जाम कुरेदना भी वही ख़तरा है, क्योंकि सरिया बेल्ट की हरकत पकड़ने वाले
                तक पहुँचा देता है।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MACHINERY,
            moduleId = ModuleCatalog.ID_MACHINERY,
            steps = setOf("loto_conveyor_jam"),
        ),
        hi(
            id = "loto-missing-guard",
            title = "गार्ड नहीं है तो काम रुकेगा",
            body = """
                जिस मशीन का गार्ड ग़ायब हो, हटा दिया गया हो या बाँधकर पीछे कर दिया गया हो, उसे चलाया
                नहीं जाता। सही कार्रवाई है रुकना, सूचना देना, और काम आगे बढ़ाने से पहले गार्ड लगवाना।
                गार्ड का नाप और जगह इस तरह तय होती है कि हाथ ख़तरनाक हिस्से तक पहुँचने से पहले वह
                हिस्सा रुक चुका हो; काम आसान करने के लिए उठाया गया गार्ड ठीक वही अंतर हटा देता है।
                काम लगभग पूरा है, या मशीन हमेशा ऐसे ही चलती आई है — यही सोच मशीन से होने वाली
                ज़्यादातर चोटों से पहले आती है।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_MACHINERY,
            moduleId = ModuleCatalog.ID_MACHINERY,
            steps = setOf("loto_guard_missing"),
            statute = SRC_MACHINERY,
        ),
        hi(
            id = "machinery-statute",
            title = "मशीन की सुरक्षा पर कानून क्या कहता है",
            body = """
                कारखाना अधिनियम 1948 के अनुसार मशीन के ख़तरनाक हिस्सों की मज़बूत घेराबंदी ज़रूरी है,
                और चलती मशीन की जाँच या समायोजन केवल प्रशिक्षित वयस्क मज़दूर, तय शर्तों के तहत ही कर
                सकते हैं। खान नियम 1955 भूमिगत काम पर वही कर्तव्य लगाते हैं। कर्तव्य मशीन की उस हालत
                पर लागू होता है जिसमें वह असल में चलाई जा रही है, उस हालत पर नहीं जिसमें वह आई थी।
            """,
            scope = PassageScope.STATUTE,
            sourceLabel = SRC_MACHINERY,
            moduleId = ModuleCatalog.ID_MACHINERY,
            statute = SRC_MACHINERY,
        ),
    )

    private val heightHindi = listOf(
        hi(
            id = "ppe-task-match",
            title = "सुरक्षा उपकरण असली ख़तरे के हिसाब से चुनें",
            body = """
                सुरक्षा उपकरण सामने के काम के ख़तरे को देखकर चुना जाता है — आदत से नहीं, और यह देखकर
                भी नहीं कि दरवाज़े के पास क्या रखा है। घिसाई की चिंगारी से बचाने वाला चश्मा रसायन के
                छींटे से नहीं बचाता। स्टील प्लेट उठाने लायक चमड़े के दस्ताने बिजली पहुँचाते हैं और
                सॉल्वेंट से कोई बचाव नहीं देते। गले में लटका कान का बचाव कुछ नहीं बचाता। काम से पहले
                यह पूछें: मुझ तक क्या पहुँच सकता है, कहाँ से, और उस एक चीज़ को कौन रोकेगा।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_HEIGHT,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            steps = setOf("ppe_task_match"),
        ),
        hi(
            id = "height-anchor-point",
            title = "गिरने से रोकने वाली रस्सी कहाँ बाँधें",
            body = """
                बाँधने की जगह मज़बूत ढाँचे पर हो और जहाँ तक हो सके कंधे के बराबर या उससे ऊपर हो।
                नीचे बाँधने से रस्सी काम शुरू करने से पहले गिरने की दूरी बढ़ जाती है, और वही दूरी तय
                करती है कि व्यवस्था आपको रोकेगी या सिर्फ़ आपके गिरने को नापेगी। रेलिंग, पाइप, केबल
                ट्रे, सीढ़ी और स्कैफ़ोल्ड की रेलिंग बाँधने की जगह नहीं हैं। जब तक अलग से क्षमता तय न
                हो, एक जगह पर एक ही व्यक्ति बाँधे, और यह देखें कि गिरने पर झूलकर आप किनारों और ढाँचे
                से टकराने की जगह उनसे बचकर निकलें।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_HEIGHT,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            steps = setOf("height_anchor_point"),
            statute = SRC_HEIGHT,
        ),
        hi(
            id = "harness-inspection",
            title = "हर बार पहनने से पहले हार्नेस की जाँच",
            body = """
                हार्नेस की जाँच हर बार पहनने से पहले, इस क्रम में करें: पट्टी में कटाव, रेशे उखड़ना,
                रसायन से जलना और गर्मी से चमकीला होना; सिलाई में खिंचे या टूटे धागे; डी-रिंग और बकल
                में मुड़ाव, दरार और जंग; रस्सी और झटका सोखने वाले हिस्से का खुल जाना; और लेबल पर
                पहचान तथा जाँच की तारीख़। जिस हार्नेस ने एक बार गिरना रोक लिया है उसे देखने में ठीक
                लगने पर भी सेवा से हटा दिया जाता है, क्योंकि झटका सोखने वाला हिस्सा ख़र्च हो चुका है
                और दोबारा काम नहीं करेगा। इसे कसकर पहनें, जाँघ की पट्टियाँ और छाती की पट्टी बंद करके;
                ढीला हार्नेस गिरने के समय शरीर को अपने अंदर हिलने देता है, और वह अपनी अलग चोट है।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_PRACTICE,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            steps = setOf("harness_inspection_sequence"),
        ),
        hi(
            id = "ladder-angle",
            title = "सीढ़ी लगाना: 75 अंश",
            body = """
                चलती-फिरती सीढ़ी लगभग 75 अंश पर खड़ी होती है, यानी हर 4 हिस्से ऊँचाई पर 1 हिस्सा
                बाहर। ज़्यादा लेटी हुई होगी तो पैर फिसलेगा; ज़्यादा खड़ी होगी तो चढ़ने वाले का वज़न
                ऊपर पहुँचते ही पीछे पलटेगी। इसे सख़्त और समतल ज़मीन पर रखें, बाँधें या नीचे कोई पकड़े,
                और ऊपर उतरने की जगह से इतना आगे बढ़ाएँ कि पकड़ने को कुछ मिले। सीढ़ी की तरफ़ मुँह करके
                चढ़ें और तीन जगह पकड़ बनाए रखें — यानी औज़ार हाथ में नहीं, बेल्ट में लेकर चलें या रस्सी
                से ऊपर खींचें।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_HEIGHT,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            steps = setOf("ladder_angle"),
        ),
        hi(
            id = "ppe-defective-item",
            title = "ख़राब उपकरण हटाया जाता है, उससे काम नहीं चलाया जाता",
            body = """
                ख़राब सुरक्षा उपकरण काम शुरू होने से पहले हटा दिया जाता है, उस पर पर्ची लगाई जाती है
                और बदला जाता है। दरका हेलमेट, कटी पट्टी वाला हार्नेस, गली सील वाला रेस्पिरेटर और छेद
                वाला दस्ताना — सबकी एक ही दिक्कत है: मज़दूर सुरक्षित होने जैसा बरतता है जबकि सुरक्षा
                मौजूद नहीं है। सुरक्षा उपकरण ख़ुद कभी न सुधारें, बिना जाँचा उपकरण उधार न लें, और
                इसलिए ख़राब उपकरण न लें कि नया लेने में इंतज़ार करना पड़ेगा। सूचना दें, ताकि अगला आदमी
                स्टोर से वही चीज़ न उठा ले।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_HEIGHT,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            steps = setOf("ppe_defective_item"),
        ),
        hi(
            id = "height-statute",
            title = "फ़र्श, खुले हिस्से और ऊँचाई पर कानून क्या कहता है",
            body = """
                कारखाना अधिनियम 1948 के अनुसार फ़र्श, सीढ़ियाँ, रास्ते और गैंगवे मज़बूती से बने हों
                और रुकावट से मुक्त रखे जाएँ, और फ़र्श के खुले हिस्से मज़बूती से घेरे या ढके जाएँ।
                जहाँ घेराबंदी संभव न हो, वहाँ बेल्ट और हार्नेस जैसे सहारे देना और उनका उपयोग कराना
                ज़रूरी है। खान नियम 1955 वही कर्तव्य रखते हैं। उपकरण दे देना और उसका उपयोग सुनिश्चित
                न करना कर्तव्य पूरा नहीं करता।
            """,
            scope = PassageScope.STATUTE,
            sourceLabel = SRC_HEIGHT,
            moduleId = ModuleCatalog.ID_PPE_HEIGHT,
            statute = SRC_HEIGHT,
        ),
    )

    private val electricalHindi = listOf(
        hi(
            id = "elec-shock-isolate-first",
            title = "बिजली से चिपके आदमी को कभी न छुएँ",
            body = """
                बिजली का झटका लगने वाला आदमी पकड़ छोड़ ही नहीं पाता, क्योंकि बाज़ू से गुज़रती धारा
                मुट्ठी को जकड़ देती है। उसे छूने पर वही धारा आपके शरीर से गुज़रती है और दूसरा घायल
                बन जाता है जो पहले की मदद नहीं कर सकता। सप्लाई को स्विच, ब्रेकर या प्लग से बंद करें।
                अगर सप्लाई बंद न हो सके, तो लकड़ी के डंडे जैसी सूखी और बिजली न पहुँचाने वाली चीज़ से
                उस आदमी को हटाएँ, और ऐसा करते समय ख़ुद किसी सूखी चीज़ पर खड़े रहें। संपर्क टूटने के
                बाद ही साँस देखें और पुनर्जीवन शुरू करें, और मदद के लिए साथ-साथ ख़बर भेजें, बाद में
                नहीं।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_ELECTRICAL,
            moduleId = ModuleCatalog.ID_ELECTRICAL,
            steps = setOf("elec_shock_first_action", "first_response_sequence"),
            statute = SRC_ELECTRICAL,
        ),
        hi(
            id = "elec-damaged-cable",
            title = "ख़राब केबल की बिजली काटी जाती है, टेप नहीं लगाई जाती",
            body = """
                खुले तार, दरकी इन्सुलेशन, गाड़ियों के नीचे दबी केबल, या टेप से बनाया गया जोड़ — इन
                सबकी सूचना दी जाती है, बिजली काटी जाती है, और मरम्मत सक्षम बिजली कर्मी करता है।
                ख़राब इन्सुलेशन पर लगाई इन्सुलेशन टेप मरम्मत जैसी दिखती है, मरम्मत नहीं होती: गर्मी,
                नमी और कंपन से वह छूट जाती है, और गरम टेप वाले जोड़ पर बैठी कोयले की धूल अपने आप में
                आग लगाने का कारण है। ख़राब केबल को समेटने या सरकाने के लिए न हिलाएँ, और जब तक सप्लाई
                बंद साबित न हो जाए, लोगों को उससे दूर रखें।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_ELECTRICAL,
            moduleId = ModuleCatalog.ID_ELECTRICAL,
            steps = setOf("elec_cable_damage"),
        ),
        hi(
            id = "elec-permit-to-work",
            title = "परमिट लें, बंद होना जाँचें, फिर अर्थ लगाएँ",
            body = """
                बिजली के उपकरण पर काम इस क्रम से होता है: परमिट लें, सही सर्किट पहचानें, उसे अलग
                करें, अलगाव पर ताला और पर्ची लगाएँ, टेस्टर के चलने की पुष्टि करें, काम की जगह पर ही
                सर्किट बंद होना जाँचें, और जहाँ व्यवस्था माँगती है वहाँ अर्थ लगाएँ। काम की जगह के
                अलावा कहीं और बंद होना जाँचना गलत चीज़ साबित करता है। जाँच से पहले और बाद में टेस्टर
                परखना ही उस टेस्टर को पकड़ता है जो बीच में ख़राब हो गया और जो चालू सर्किट को बंद
                दिखा देता। जिस परमिट को अर्थ हटाने और काम जाँचने से पहले बंद कर दिया जाए, वह परमिट
                हक़ीक़त बताना छोड़ चुका है।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_ELECTRICAL,
            moduleId = ModuleCatalog.ID_ELECTRICAL,
            steps = setOf("elec_permit_to_work"),
            statute = SRC_ELECTRICAL,
        ),
        hi(
            id = "burn-first-aid",
            title = "जलने पर ठंडा करना: 20 मिनट साफ़ बहता पानी",
            body = """
                जले हुए हिस्से को साफ़ बहते पानी से 20 मिनट ठंडा करें, और जितनी जल्दी हो सके शुरू
                करें। ठंडा करने से चोट कितनी गहरी जाएगी वह घटता है, और घटना के कई घंटे बाद तक भी
                इसका फ़ायदा रहता है। सूजन शुरू होने से पहले जले के पास की अँगूठी, घड़ी और कसे कपड़े
                हटा दें, पर जो चीज़ त्वचा से चिपक गई हो उसे न छेड़ें। बर्फ़ न लगाएँ, क्योंकि वह ख़ून
                का बहाव रोककर चोट गहरी करती है, और तेल, टूथपेस्ट, राख या हल्दी न लगाएँ, क्योंकि वे
                गर्मी अंदर रोक लेते हैं और संक्रमण लाते हैं। साफ़, रोएँ-रहित कपड़े से ढीला ढक दें और
                डॉक्टरी मदद लें। बिजली से जलने पर त्वचा लगभग बिना निशान दिखे तो भी डॉक्टरी जाँच
                ज़रूरी है, क्योंकि नुक़सान धारा के रास्ते शरीर के अंदर होता है।
            """,
            scope = PassageScope.STEP,
            sourceLabel = SRC_FIRST_AID,
            moduleId = ModuleCatalog.ID_ELECTRICAL,
            steps = setOf("burn_first_aid"),
        ),
        hi(
            id = "electrical-statute",
            title = "बिजली सुरक्षा पर कानून क्या कहता है",
            body = """
                कारखाना अधिनियम 1948 के अनुसार बिजली से ख़तरे के विरुद्ध सावधानी ज़रूरी है, और खान
                नियम 1955 के अनुसार बिजली के उपकरण ऐसे लगाए और सँभाले जाएँ कि ख़तरा न हो, तथा उन पर
                काम केवल अधिकृत सक्षम व्यक्ति करें। गैस वाली जगहों पर फ़्लेमप्रूफ़ उपकरण की अतिरिक्त
                शर्तें हैं, क्योंकि जो चिंगारी कहीं और मामूली परेशानी होती वह वहाँ आग लगाने का कारण
                है।
            """,
            scope = PassageScope.STATUTE,
            sourceLabel = SRC_ELECTRICAL,
            moduleId = ModuleCatalog.ID_ELECTRICAL,
            statute = SRC_ELECTRICAL,
        ),
    )

    private val generalHindi = listOf(
        hi(
            id = "general-report-hazard",
            title = "बची हुई दुर्घटना की सूचना, हुई दुर्घटना से ज़्यादा क़ीमती है",
            body = """
                बची हुई दुर्घटना वह हादसा है जो संयोग से टल गया। बंद निकासी, खुला तार, ग़ायब
                अग्निशामक यंत्र या बाँधकर हटाया गया गार्ड — इनकी सूचना देने से ये तब ठीक हो जाते हैं
                जब ठीक करना मुफ़्त है। जो देखा और जहाँ देखा, वही सीधे-सीधे बताएँ; यह चिंता न करें कि
                ग़लती किसकी है — बात हालत की है, दोष की नहीं। फ़ोटो और क्षेत्र के साथ दी गई सूचना पर
                काम करना आसान होता है, पर बिना इन दोनों के दी गई सूचना भी कोई सूचना न देने से बेहतर
                है।
            """,
            scope = PassageScope.GENERAL,
            sourceLabel = SRC_PRACTICE,
        ),
        hi(
            id = "general-decide-fast",
            title = "जवाब जानना और उस पर अमल करना, दो अलग कौशल हैं",
            body = """
                आपात स्थिति में जानने और करने के बीच का फ़ासला ही वह जगह है जहाँ लोग घायल होते हैं।
                दबाव में झिझक एक दर्ज की गई नाकामी है जो जानकारी से अलग है: जो मज़दूर कक्षा में सही
                कार्रवाई बता देते हैं, वे धुएँ से भरते रास्ते में भी जड़ हो जाते हैं। इससे निकलने का
                रास्ता है हर स्थिति के लिए एक संकेत तय कर लेना और उस संकेत पर दोबारा सोचे बिना अमल
                करना — अपनी असल काम की जगह पर अभ्यास इसी के लिए है। बीच में रोक दिया जाना झिझक नहीं
                है, और अंदर जाने से पहले मीटर देखना भी झिझक नहीं है; झिझक है कार्रवाई जानते हुए उसे
                शुरू न करना।
            """,
            scope = PassageScope.GENERAL,
            sourceLabel = SRC_PRACTICE,
        ),
        hi(
            id = "general-refresher",
            title = "छोटे दोहराव एक लंबे कोर्स से बेहतर क्यों हैं",
            body = """
                सुरक्षा की जानकारी धुँधली पड़ती जाती है। एक बार की कक्षा के बाद याद रखने की मात्रा
                पहले हफ़्ते में ही तेज़ी से गिरती है — इसीलिए एक बार लिया और फिर कभी न दोहराया गया
                प्रमाणपत्र आज के बारे में उतना नहीं बताता जितना उसकी तारीख़ से लगता है। छोटे,
                अंतराल पर होने वाले अभ्यास इसे सालाना दोहराव से बहुत सस्ते में बनाए रखते हैं: हर
                अभ्यास यह अवधि बढ़ा देता है जिस दौरान बात याद रहती है। दोहराव तैयारी लौटाता है। वह
                प्रमाणपत्र की कानूनी अवधि नहीं बढ़ाता — वह केवल पूरा अभ्यास दोबारा करने से बढ़ती है।
            """,
            scope = PassageScope.GENERAL,
            sourceLabel = SRC_PRACTICE,
        ),
    )

    private val hindi: List<CorpusPassage> =
        fireHindi + gasHindi + machineryHindi + heightHindi + electricalHindi + generalHindi

    // =======================================================================
    // Public surface
    // =======================================================================

    /**
     * The whole corpus, validated at class load.
     *
     * Eager rather than lazy, matching [ModuleCatalog]: a malformed passage should fail the first
     * test that touches this package, not the first drill that needed an explanation.
     */
    val corpus: Corpus = Corpus(english + hindi)

    /** Shared retriever. Indexes are built once; construction is the only expensive part. */
    val retriever: Retriever = Retriever(corpus)

    fun forLanguage(language: AiLanguage): List<CorpusPassage> = corpus.forLanguage(language)

    /** Passage id pairs, `<base>-en` / `<base>-hi`, asserted complete by `SafetyCorpusTest`. */
    fun baseIds(): Set<String> =
        corpus.passages.mapTo(mutableSetOf()) { it.passageId.substringBeforeLast('-') }
}
