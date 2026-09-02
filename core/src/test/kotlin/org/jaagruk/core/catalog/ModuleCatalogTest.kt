package org.jaagruk.core.catalog

import com.google.common.truth.Truth.assertThat
import org.jaagruk.core.assessment.AssessmentConfig
import org.jaagruk.core.assessment.AssessmentMode
import org.jaagruk.core.assessment.AssessmentSession
import org.jaagruk.core.assessment.ArPresentation
import org.jaagruk.core.assessment.Completion
import org.jaagruk.core.assessment.InputMethod
import org.jaagruk.core.assessment.StepKind
import org.jaagruk.core.util.FixedMonotonicTimeSource
import org.jaagruk.core.util.FixedWallClock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ModuleCatalogTest {

    @Test
    fun `the catalog covers the five mandated safety domains`() {
        assertThat(ModuleCatalog.all).hasSize(5)
        assertThat(ModuleCatalog.all.map { it.moduleId }).containsExactly(
            ModuleCatalog.ID_FIRE,
            ModuleCatalog.ID_GAS,
            ModuleCatalog.ID_MACHINERY,
            ModuleCatalog.ID_PPE_HEIGHT,
            ModuleCatalog.ID_ELECTRICAL,
        )
    }

    @Test
    fun `the two fully implemented modules are the ones the problem statement requires`() {
        assertThat(ModuleCatalog.fullyImplemented.map { it.moduleId })
            .containsExactly(ModuleCatalog.ID_FIRE, ModuleCatalog.ID_GAS)
    }

    @Test
    fun `module codes are unique and frozen`() {
        // These codes are signed into every certificate. Renumbering would invalidate every
        // certificate already issued in the field, so the values are asserted explicitly.
        assertThat(ModuleCatalog.all.map { it.moduleCode }).containsExactly(1, 2, 3, 4, 5)
        assertThat(ModuleCatalog.byCode(1)?.moduleId).isEqualTo(ModuleCatalog.ID_FIRE)
        assertThat(ModuleCatalog.byCode(2)?.moduleId).isEqualTo(ModuleCatalog.ID_GAS)
        assertThat(ModuleCatalog.byCode(3)?.moduleId).isEqualTo(ModuleCatalog.ID_MACHINERY)
        assertThat(ModuleCatalog.byCode(4)?.moduleId).isEqualTo(ModuleCatalog.ID_PPE_HEIGHT)
        assertThat(ModuleCatalog.byCode(5)?.moduleId).isEqualTo(ModuleCatalog.ID_ELECTRICAL)
    }

    @Test
    fun `lookups behave`() {
        assertThat(ModuleCatalog.byId(ModuleCatalog.ID_FIRE)).isNotNull()
        assertThat(ModuleCatalog.byId("does-not-exist")).isNull()
        assertThat(ModuleCatalog.byCode(99)).isNull()
        assertThat(ModuleCatalog.scenario("fire-evac-full")).isNotNull()
        assertThat(ModuleCatalog.scenario("nope")).isNull()
    }

    @Test
    fun `every module has a full scenario and a refresher`() {
        // Without a refresher variant, spaced repetition could never run for that module, which
        // would silently disable the retention mechanism the platform is built around.
        ModuleCatalog.all.forEach { module ->
            assertThat(module.fullScenario.isRefresherVariant).isFalse()
            assertThat(module.refresherScenario.isRefresherVariant).isTrue()
        }
    }

    @Test
    fun `only the gas module ships a two device buddy scenario`() {
        assertThat(ModuleCatalog.gasConfinedSpace.supportsBuddyDrill).isTrue()
        assertThat(ModuleCatalog.gasConfinedSpace.buddyScenario?.scenarioId)
            .isEqualTo("gas-confined-buddy")
        assertThat(ModuleCatalog.fireEvacuation.supportsBuddyDrill).isFalse()
    }

    @Test
    fun `scenario ids are unique across the whole catalog`() {
        val ids = ModuleCatalog.all.flatMap { it.scenarios }.map { it.scenarioId }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `step ids are unique across the whole catalog`() {
        // Steps are shared between full and refresher scenarios by reference, so uniqueness is
        // checked on the distinct set rather than on every occurrence.
        val steps = ModuleCatalog.allSteps()
        assertThat(steps.map { it.stepId }).containsNoDuplicates()
    }

    @Test
    fun `option ids are unique within every step`() {
        ModuleCatalog.allSteps().forEach { step ->
            assertThat(step.options.map { it.optionId }).containsNoDuplicates()
        }
    }

    @Test
    fun `every scenario has at least one critical step`() {
        ModuleCatalog.all.flatMap { it.scenarios }.forEach { scenario ->
            assertThat(scenario.criticalStepIds).isNotEmpty()
        }
    }

    @Test
    fun `every step leaves a window in which hesitation can be measured`() {
        ModuleCatalog.allSteps().forEach { step ->
            assertThat(step.timeoutMs).isGreaterThan(step.expertMs)
            // Beyond the raw constraint: the window must also be wide enough for the
            // fast/slow distinction to mean something rather than being a rounding artefact.
            assertThat(step.timeoutMs).isAtLeast(step.expertMs * 2)
        }
    }

    @Test
    fun `expert baselines are humanly plausible`() {
        ModuleCatalog.allSteps().forEach { step ->
            // Nothing faster than a person can read a prompt, and nothing so slow that the
            // measurement stops being about decisiveness.
            assertThat(step.expertMs).isAtLeast(AssessmentConfig.SUSPICIOUS_FAST_MS * 2)
            assertThat(step.expertMs).isAtMost(20_000L)
            assertThat(step.timeoutMs).isAtMost(60_000L)
        }
    }

    @Test
    fun `every referenced ar target is known to the ar layer`() {
        // The compiler cannot catch a typo in an AR target string, so a test does.
        val unknown = ModuleCatalog.referencedArTargets().filterNot { ArTargets.isKnown(it) }
        assertThat(unknown).isEmpty()
    }

    @Test
    fun `spatial steps reference at least one site anchored target`() {
        // Otherwise scanning the site would add nothing to that step and the site-scan mode would
        // be decorative.
        ModuleCatalog.allSteps().filter { it.kind.isSpatial }.forEach { step ->
            val targets = step.options.mapNotNull { it.arTargetKey }
            assertThat(targets.any { ArTargets.requiresSiteAnchor(it) }).isTrue()
        }
    }

    @Test
    fun `spatial steps give every option a distinct ar target`() {
        ModuleCatalog.allSteps().filter { it.kind.isSpatial }.forEach { step ->
            val targets = step.options.mapNotNull { it.arTargetKey }
            assertThat(targets).hasSize(step.options.size)
            assertThat(targets).containsNoDuplicates()
        }
    }

    @Test
    fun `every wrong option is a deliberate distractor`() {
        // An option that is neither correct nor marked as a distractor is an authoring oversight:
        // it means nobody decided why it is there.
        ModuleCatalog.allSteps().forEach { step ->
            step.options.filterNot { it.optionId in step.correctOptionIds }.forEach { option ->
                assertThat(option.isDistractor).isTrue()
            }
        }
    }

    @Test
    fun `correct options are never marked as distractors`() {
        ModuleCatalog.allSteps().forEach { step ->
            step.correctOptionIds.forEach { id ->
                assertThat(step.option(id)?.isDistractor).isFalse()
            }
        }
    }

    @Test
    fun `every non sequence step offers at least one wrong answer`() {
        // A SEQUENCE step is excluded by design: it uses every option, and the wrong answer is
        // the wrong order rather than a wrong item.
        ModuleCatalog.allSteps()
            .filterNot { it.kind == StepKind.SEQUENCE }
            .forEach { step ->
                assertThat(step.options.size).isGreaterThan(step.correctOptionIds.size)
            }
    }

    @Test
    fun `string keys follow the naming convention and are unique`() {
        val keys = ModuleCatalog.requiredStringKeys()
        assertThat(keys).containsNoDuplicates()
        assertThat(keys).isNotEmpty()
        assertThat(keys.all { it.matches(Regex("[a-z0-9_]+")) }).isTrue()
        assertThat(keys.any { it.startsWith("module_") }).isTrue()
        assertThat(keys.any { it.startsWith("scenario_") }).isTrue()
        assertThat(keys.any { it.startsWith("step_") }).isTrue()
        assertThat(keys.any { it.startsWith("opt_") }).isTrue()
    }

    @Test
    fun `every step has a remediation key so a wrong answer can be explained`() {
        ModuleCatalog.allSteps().forEach { step ->
            assertThat(step.remediationKey).isNotNull()
        }
    }

    @Test
    fun `refreshers are short enough for a sixty to ninety second check`() {
        ModuleCatalog.all.forEach { module ->
            val refresher = module.refresherScenario
            assertThat(refresher.steps.size).isAtMost(4)
            // Worst case, every step timing out, still stays inside a couple of minutes.
            assertThat(refresher.maxDurationMs).isAtMost(60_000L)
        }
    }

    @Test
    fun `full scenarios stay inside their advertised duration`() {
        ModuleCatalog.all.forEach { module ->
            val advertisedMs = module.estimatedMinutes * 60_000L
            // maxDurationMs is the pathological all-timeouts case, so allow generous headroom
            // while still catching a module that could not possibly fit its advertised time.
            assertThat(module.fullScenario.maxDurationMs).isAtMost(advertisedMs * 2)
        }
    }

    @Test
    fun `sector filtering returns the applicable modules`() {
        assertThat(ModuleCatalog.forSector(Sector.COAL_MINE)).hasSize(5)
        assertThat(ModuleCatalog.forSector(Sector.STEEL_PLANT)).hasSize(5)
        assertThat(ModuleCatalog.forSector(Sector.MICA_PROCESSING)).hasSize(5)
        assertThat(ModuleCatalog.fireEvacuation.appliesTo(Sector.COAL_MINE)).isTrue()
    }

    @Test
    fun `every module cites a statutory reference for audit exports`() {
        ModuleCatalog.all.forEach { module ->
            assertThat(module.statutoryReference).isNotEmpty()
            assertThat(module.statutoryReference).containsMatch("Act|Rules")
        }
    }

    @Test
    fun `catalog version is positive`() {
        assertThat(ModuleCatalog.CATALOG_VERSION).isAtLeast(1)
    }

    @Test
    fun `sequence steps have a defensible ordering`() {
        ModuleCatalog.allSteps().filter { it.kind == StepKind.SEQUENCE }.forEach { step ->
            assertThat(step.correctOptionIds.size).isAtLeast(3)
            // The ordered answer must use every option, otherwise the worker is being asked to
            // both order and filter without being told.
            assertThat(step.correctOptionIds.toSet()).isEqualTo(step.options.map { it.optionId }.toSet())
        }
    }

    @Test
    fun `the highest weighted rule in the catalog is do not enter to rescue`() {
        // Would-be rescuers are a large share of confined-space fatalities, so this is the single
        // highest-value thing the platform teaches and it carries the heaviest weight.
        val heaviest = ModuleCatalog.allSteps().maxByOrNull { it.weight }
        assertThat(heaviest?.stepId).isEqualTo("buddy_distress_response")
        assertThat(heaviest?.critical).isTrue()
    }
}

/** Proves every catalog scenario is actually runnable by the engine, not just constructible. */
class CatalogRunnabilityTest {

    @Test
    fun `every scenario can be completed correctly and passes`() {
        ModuleCatalog.all.forEach { module ->
            module.scenarios.forEach { scenario ->
                val clock = FixedMonotonicTimeSource(0L)
                val session = AssessmentSession(
                    runId = "run-${scenario.scenarioId}",
                    scenario = scenario,
                    moduleCode = module.moduleCode,
                    mode = if (scenario.requiresBuddy) AssessmentMode.BUDDY else AssessmentMode.INITIAL,
                    presentation = ArPresentation.SITE_SCANNED,
                    monotonic = clock,
                    wallClock = FixedWallClock(1_760_000_000_000L),
                    buddyPeerDeviceId = if (scenario.requiresBuddy) "peer-device" else null,
                )
                session.start()

                scenario.steps.forEach { step ->
                    clock.advance(step.expertMs / 2)
                    session.submit(step.stepId, step.correctOptionIds, InputMethod.TOUCH)
                }

                val result = session.finish()
                assertThat(result.completion).isEqualTo(Completion.COMPLETED)
                assertThat(result.scorePermille).isEqualTo(1_000)
                assertThat(result.passed).isTrue()
                assertThat(result.certifiable).isTrue()
            }
        }
    }

    @Test
    fun `every scenario fails when every answer is wrong`() {
        ModuleCatalog.all.forEach { module ->
            module.scenarios.forEach { scenario ->
                val clock = FixedMonotonicTimeSource(0L)
                val session = AssessmentSession(
                    runId = "run-fail-${scenario.scenarioId}",
                    scenario = scenario,
                    moduleCode = module.moduleCode,
                    mode = if (scenario.requiresBuddy) AssessmentMode.BUDDY else AssessmentMode.INITIAL,
                    presentation = ArPresentation.ARCORE_GENERIC,
                    monotonic = clock,
                    wallClock = FixedWallClock(1_760_000_000_000L),
                    buddyPeerDeviceId = if (scenario.requiresBuddy) "peer-device" else null,
                )
                session.start()

                scenario.steps.forEach { step ->
                    clock.advance(step.expertMs)
                    // A SEQUENCE step uses every option, so the only wrong answer available is
                    // the wrong order — which is precisely the mistake it exists to catch.
                    val wrongAnswer = if (step.kind == StepKind.SEQUENCE) {
                        step.correctOptionIds.reversed()
                    } else {
                        listOf(step.options.first { it.optionId !in step.correctOptionIds }.optionId)
                    }
                    session.submit(step.stepId, wrongAnswer, InputMethod.TOUCH)
                }

                val result = session.finish()
                assertThat(result.scorePermille).isEqualTo(0)
                assertThat(result.passed).isFalse()
            }
        }
    }

    @Test
    fun `a hesitant but perfectly accurate run fails every scenario`() {
        // The platform's core claim: knowing the answer is not the same as acting in time.
        ModuleCatalog.all.forEach { module ->
            val scenario = module.fullScenario
            val clock = FixedMonotonicTimeSource(0L)
            val session = AssessmentSession(
                runId = "run-slow-${scenario.scenarioId}",
                scenario = scenario,
                moduleCode = module.moduleCode,
                mode = AssessmentMode.INITIAL,
                presentation = ArPresentation.SITE_SCANNED,
                monotonic = clock,
                wallClock = FixedWallClock(1_760_000_000_000L),
            )
            session.start()

            scenario.steps.forEach { step ->
                // Just inside the timeout, well past the hesitation threshold.
                clock.advance(step.timeoutMs - 100L)
                session.submit(step.stepId, step.correctOptionIds, InputMethod.TOUCH)
            }

            val result = session.finish()
            assertThat(result.correctCount).isEqualTo(scenario.steps.size)
            assertThat(result.hesitationFlag).isTrue()
            assertThat(result.passed).isFalse()
        }
    }
}

class SafetyModuleValidationTest {

    @Test
    fun `rejects a module with no refresher`() {
        val error = assertThrows<IllegalArgumentException> {
            SafetyModule(
                moduleId = ModuleCatalog.ID_FIRE,
                moduleCode = 1,
                titleKey = "t",
                descriptionKey = "d",
                pictogram = Pictogram.FIRE_EXTINGUISHER,
                sectors = setOf(Sector.ALL),
                statutoryReference = "Mines Act 1952",
                estimatedMinutes = 5,
                scenarios = listOf(ModuleCatalog.fireEvacuation.fullScenario),
            )
        }
        assertThat(error).hasMessageThat().contains("no refresher")
    }

    @Test
    fun `rejects a scenario belonging to another module`() {
        val error = assertThrows<IllegalArgumentException> {
            SafetyModule(
                moduleId = "some-other-module",
                moduleCode = 9,
                titleKey = "t",
                descriptionKey = "d",
                pictogram = Pictogram.WARNING_GENERAL,
                sectors = setOf(Sector.ALL),
                statutoryReference = "Mines Act 1952",
                estimatedMinutes = 5,
                scenarios = ModuleCatalog.fireEvacuation.scenarios,
            )
        }
        assertThat(error).hasMessageThat().contains("different moduleId")
    }

    @Test
    fun `rejects invalid metadata`() {
        fun build(
            moduleId: String = ModuleCatalog.ID_FIRE,
            moduleCode: Int = 1,
            sectors: Set<Sector> = setOf(Sector.ALL),
            minutes: Int = 5,
        ) = SafetyModule(
            moduleId = moduleId,
            moduleCode = moduleCode,
            titleKey = "t",
            descriptionKey = "d",
            pictogram = Pictogram.FIRE_EXTINGUISHER,
            sectors = sectors,
            statutoryReference = "Mines Act 1952",
            estimatedMinutes = minutes,
            scenarios = ModuleCatalog.fireEvacuation.scenarios,
        )

        assertThrows<IllegalArgumentException> { build(moduleId = " ") }
        assertThrows<IllegalArgumentException> { build(moduleCode = 0) }
        assertThrows<IllegalArgumentException> { build(moduleCode = 256) }
        assertThrows<IllegalArgumentException> { build(sectors = emptySet()) }
        assertThrows<IllegalArgumentException> { build(minutes = 0) }
    }
}

class PictogramTest {

    @Test
    fun `every pictogram belongs to a family with a signal colour`() {
        Pictogram.entries.forEach { pictogram ->
            assertThat(pictogram.signalColour).isEqualTo(pictogram.family.signalColour)
        }
    }

    @Test
    fun `iso families use their standard signal colours`() {
        assertThat(PictogramFamily.SAFE_CONDITION.signalColour).isEqualTo(PictogramColour.GREEN)
        assertThat(PictogramFamily.FIRE_EQUIPMENT.signalColour).isEqualTo(PictogramColour.RED)
        assertThat(PictogramFamily.MANDATORY.signalColour).isEqualTo(PictogramColour.BLUE)
        assertThat(PictogramFamily.PROHIBITION.signalColour).isEqualTo(PictogramColour.RED)
        assertThat(PictogramFamily.WARNING.signalColour).isEqualTo(PictogramColour.YELLOW)
    }

    @Test
    fun `iso references look like iso 7010 codes`() {
        Pictogram.entries.mapNotNull { it.isoReference }.forEach { reference ->
            assertThat(reference).matches("[EFMPW][0-9]{3}")
        }
    }

    @Test
    fun `the catalog only uses pictograms that exist`() {
        // Guaranteed by the type system; asserted so the intent is documented alongside the
        // exhaustive-when contract the Android icon mapper relies on.
        val used = ModuleCatalog.allSteps().flatMap { it.options }.map { it.pictogram }.toSet()
        assertThat(Pictogram.entries).containsAtLeastElementsIn(used)
        assertThat(used).isNotEmpty()
    }
}
