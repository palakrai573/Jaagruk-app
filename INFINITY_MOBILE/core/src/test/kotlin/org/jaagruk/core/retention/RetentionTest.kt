package org.jaagruk.core.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val DAY = 86_400L
private const val T0 = 1_760_000_000L

class ReadinessCalculatorTest {

    @Test
    fun `readiness equals the base score at the moment of passing`() {
        assertThat(ReadinessCalculator.readiness(900, T0, T0, 0)).isEqualTo(900)
    }

    @Test
    fun `readiness halves after one half-life`() {
        val afterOneHalfLife = T0 + (ReadinessCalculator.INITIAL_HALF_LIFE_DAYS * DAY).toLong()
        assertThat(ReadinessCalculator.readiness(1_000, T0, afterOneHalfLife, 0)).isEqualTo(500)
    }

    @Test
    fun `readiness quarters after two half-lives`() {
        val afterTwo = T0 + (2 * ReadinessCalculator.INITIAL_HALF_LIFE_DAYS * DAY).toLong()
        assertThat(ReadinessCalculator.readiness(1_000, T0, afterTwo, 0)).isEqualTo(250)
    }

    @Test
    fun `readiness decays monotonically`() {
        var previous = Int.MAX_VALUE
        for (day in 0..200) {
            val value = ReadinessCalculator.readiness(1_000, T0, T0 + day * DAY, 0)
            assertThat(value).isAtMost(previous)
            previous = value
        }
    }

    @Test
    fun `a future last-pass is clamped rather than boosting readiness`() {
        // Routine when a device clock is wrong, or a record synced from a phone that was ahead.
        val readiness = ReadinessCalculator.readiness(800, T0 + 30 * DAY, T0, 0)
        assertThat(readiness).isEqualTo(800)
    }

    @Test
    fun `a zero base score stays zero`() {
        assertThat(ReadinessCalculator.readiness(0, T0, T0 + 500 * DAY, 3)).isEqualTo(0)
    }

    @Test
    fun `readiness never leaves the permille range`() {
        assertThat(ReadinessCalculator.readiness(1_000, T0, T0, 5)).isAtMost(1_000)
        assertThat(ReadinessCalculator.readiness(1, T0, T0 + 10_000 * DAY, 0)).isAtLeast(0)
    }

    @Test
    fun `rejects an out of range base score`() {
        assertThrows<IllegalArgumentException> { ReadinessCalculator.readiness(1_001, T0, T0, 0) }
        assertThrows<IllegalArgumentException> { ReadinessCalculator.readiness(-1, T0, T0, 0) }
    }

    @Test
    fun `half-life lengthens with each refresher stage`() {
        // Repeated retrieval flattening the forgetting curve is the actual mechanism behind
        // spaced repetition, so the model has to reflect it rather than using a fixed decay.
        val stage0 = ReadinessCalculator.halfLifeDays(0)
        val stage1 = ReadinessCalculator.halfLifeDays(1)
        val stage2 = ReadinessCalculator.halfLifeDays(2)

        assertThat(stage0).isWithin(1e-9).of(45.0)
        assertThat(stage1).isGreaterThan(stage0)
        assertThat(stage2).isGreaterThan(stage1)
    }

    @Test
    fun `half-life is capped`() {
        assertThat(ReadinessCalculator.halfLifeDays(100))
            .isWithin(1e-9).of(ReadinessCalculator.MAX_HALF_LIFE_DAYS)
    }

    @Test
    fun `half-life rejects a negative stage`() {
        assertThrows<IllegalArgumentException> { ReadinessCalculator.halfLifeDays(-1) }
    }

    @Test
    fun `bands follow the documented thresholds`() {
        assertThat(ReadinessCalculator.band(1_000)).isEqualTo(ReadinessBand.READY)
        assertThat(ReadinessCalculator.band(700)).isEqualTo(ReadinessBand.READY)
        assertThat(ReadinessCalculator.band(699)).isEqualTo(ReadinessBand.DUE)
        assertThat(ReadinessCalculator.band(500)).isEqualTo(ReadinessBand.DUE)
        assertThat(ReadinessCalculator.band(499)).isEqualTo(ReadinessBand.STALE)
        assertThat(ReadinessCalculator.band(300)).isEqualTo(ReadinessBand.STALE)
        assertThat(ReadinessCalculator.band(299)).isEqualTo(ReadinessBand.EXPIRED)
        assertThat(ReadinessCalculator.band(0)).isEqualTo(ReadinessBand.EXPIRED)
    }

    @Test
    fun `band properties are consistent`() {
        assertThat(ReadinessBand.READY.needsAction).isFalse()
        assertThat(ReadinessBand.DUE.refresherIsEnough).isTrue()
        assertThat(ReadinessBand.STALE.refresherIsEnough).isTrue()
        assertThat(ReadinessBand.EXPIRED.refresherIsEnough).isFalse()
    }

    @Test
    fun `consolidation moves halfway toward full retention`() {
        assertThat(ReadinessCalculator.consolidate(600, 600)).isEqualTo(800)
        assertThat(ReadinessCalculator.consolidate(800, 700)).isEqualTo(900)
        assertThat(ReadinessCalculator.consolidate(1_000, 1_000)).isEqualTo(1_000)
    }

    @Test
    fun `consolidation never drops below the score just achieved`() {
        // A strong worker's base must not fall because one refresher was merely good.
        assertThat(ReadinessCalculator.consolidate(200, 950)).isEqualTo(950)
        assertThat(ReadinessCalculator.consolidate(900, 720)).isEqualTo(950)
    }

    @Test
    fun `consolidation rejects out of range input`() {
        assertThrows<IllegalArgumentException> { ReadinessCalculator.consolidate(1_100, 500) }
        assertThrows<IllegalArgumentException> { ReadinessCalculator.consolidate(500, -1) }
    }

    @Test
    fun `predicts when readiness will fall to a target`() {
        val state = SpacedRepetitionScheduler.onInitialPass(1_000, T0)
        val days = ReadinessCalculator.daysUntilReadinessFallsTo(state, T0, 500)

        assertThat(days).isNotNull()
        assertThat(days!!).isWithin(0.5).of(ReadinessCalculator.INITIAL_HALF_LIFE_DAYS)
    }

    @Test
    fun `prediction returns zero when the target has already been passed`() {
        val state = SpacedRepetitionScheduler.onInitialPass(1_000, T0)
        assertThat(ReadinessCalculator.daysUntilReadinessFallsTo(state, T0 + 400 * DAY, 500))
            .isEqualTo(0.0)
    }

    @Test
    fun `prediction returns null for an unreachable target`() {
        val state = SpacedRepetitionScheduler.onInitialPass(1_000, T0)
        assertThat(ReadinessCalculator.daysUntilReadinessFallsTo(state, T0, 0)).isNull()
    }
}

class RetentionStateTest {

    @Test
    fun `rejects invalid state`() {
        assertThrows<IllegalArgumentException> { RetentionState(1_001, T0, 0, T0) }
        assertThrows<IllegalArgumentException> { RetentionState(500, -1L, 0, T0) }
        assertThrows<IllegalArgumentException> { RetentionState(500, T0, -1, T0) }
        assertThrows<IllegalArgumentException> { RetentionState(500, T0, 0, -1L) }
        assertThrows<IllegalArgumentException> {
            RetentionState(500, T0, 0, T0, consecutiveFailures = -1)
        }
    }

    @Test
    fun `effective stage is clamped to the last defined stage`() {
        val state = RetentionState(800, T0, 99, T0)
        assertThat(state.effectiveStage).isEqualTo(SpacedRepetitionScheduler.MAX_STAGE)
    }

    @Test
    fun `equality and copy behave by value`() {
        val a = RetentionState(800, T0, 1, T0 + DAY)
        assertThat(a).isEqualTo(RetentionState(800, T0, 1, T0 + DAY))
        assertThat(a.hashCode()).isEqualTo(RetentionState(800, T0, 1, T0 + DAY).hashCode())
        assertThat(a.copy(baseScore = 900).baseScore).isEqualTo(900)
        assertThat(a.copy(baseScore = 900).refresherStage).isEqualTo(1)
    }
}

class SpacedRepetitionSchedulerTest {

    @Test
    fun `initial pass schedules the first refresher two days out`() {
        val state = SpacedRepetitionScheduler.onInitialPass(820, T0)

        assertThat(state.baseScore).isEqualTo(820)
        assertThat(state.refresherStage).isEqualTo(0)
        assertThat(state.nextDueAtEpochSec).isEqualTo(T0 + 2 * DAY)
        assertThat(state.certifiedAtEpochSec).isEqualTo(T0)
    }

    @Test
    fun `intervals expand across stages`() {
        val intervals = (0..SpacedRepetitionScheduler.MAX_STAGE)
            .map { SpacedRepetitionScheduler.intervalDays(it) }
        assertThat(intervals).containsExactly(2, 7, 21, 60, 120).inOrder()
    }

    @Test
    fun `intervals clamp past the last stage`() {
        assertThat(SpacedRepetitionScheduler.intervalDays(99)).isEqualTo(120)
        assertThrows<IllegalArgumentException> { SpacedRepetitionScheduler.intervalDays(-1) }
    }

    @Test
    fun `a passed refresher advances the stage and consolidates the base`() {
        val initial = SpacedRepetitionScheduler.onInitialPass(700, T0)
        val after = SpacedRepetitionScheduler.onRefresherPassed(initial, 750, T0 + 2 * DAY)

        assertThat(after.refresherStage).isEqualTo(1)
        assertThat(after.baseScore).isEqualTo(850)
        assertThat(after.lastPassAtEpochSec).isEqualTo(T0 + 2 * DAY)
        assertThat(after.nextDueAtEpochSec).isEqualTo(T0 + 2 * DAY + 7 * DAY)
        assertThat(after.consecutiveFailures).isEqualTo(0)
    }

    @Test
    fun `a refresher does not renew the statutory certificate`() {
        // Conflating operational readiness with statutory validity is exactly the sloppiness the
        // problem statement objects to, so a refresher must leave the certification date alone.
        val initial = SpacedRepetitionScheduler.onInitialPass(800, T0)
        val after = SpacedRepetitionScheduler.onRefresherPassed(initial, 900, T0 + 100 * DAY)
        assertThat(after.certifiedAtEpochSec).isEqualTo(T0)
    }

    @Test
    fun `stage saturates at the last interval and keeps repeating`() {
        var state = SpacedRepetitionScheduler.onInitialPass(700, T0)
        var now = T0
        repeat(10) {
            now += 10 * DAY
            state = SpacedRepetitionScheduler.onRefresherPassed(state, 800, now)
        }
        assertThat(state.refresherStage).isEqualTo(SpacedRepetitionScheduler.MAX_STAGE)
        assertThat(state.nextDueAtEpochSec).isEqualTo(now + 120 * DAY)
    }

    @Test
    fun `a failed refresher steps back and retries tomorrow`() {
        val initial = SpacedRepetitionScheduler.onInitialPass(800, T0)
        val advanced = SpacedRepetitionScheduler.onRefresherPassed(initial, 850, T0 + 2 * DAY)
        val failed = SpacedRepetitionScheduler.onRefresherFailed(advanced, T0 + 9 * DAY)

        assertThat(failed.refresherStage).isEqualTo(0)
        assertThat(failed.nextDueAtEpochSec).isEqualTo(T0 + 10 * DAY)
        assertThat(failed.consecutiveFailures).isEqualTo(1)
        // A failure must never look like progress: readiness keeps decaying from the last
        // genuine pass.
        assertThat(failed.lastPassAtEpochSec).isEqualTo(advanced.lastPassAtEpochSec)
        assertThat(failed.baseScore).isEqualTo(advanced.baseScore)
    }

    @Test
    fun `stage never goes below zero`() {
        val state = SpacedRepetitionScheduler.onInitialPass(800, T0)
        val failedTwice = SpacedRepetitionScheduler.onRefresherFailed(
            SpacedRepetitionScheduler.onRefresherFailed(state, T0 + DAY),
            T0 + 2 * DAY,
        )
        assertThat(failedTwice.refresherStage).isEqualTo(0)
        assertThat(failedTwice.consecutiveFailures).isEqualTo(2)
    }

    @Test
    fun `three consecutive failures require the full module again`() {
        var state = SpacedRepetitionScheduler.onInitialPass(800, T0)
        repeat(3) { state = SpacedRepetitionScheduler.onRefresherFailed(state, T0 + DAY) }

        assertThat(SpacedRepetitionScheduler.refresherIsSufficient(state, T0 + 2 * DAY)).isFalse()
    }

    @Test
    fun `a full rerun resets both clocks`() {
        val state = SpacedRepetitionScheduler.onInitialPass(700, T0)
        val rerun = SpacedRepetitionScheduler.onFullRerunPassed(state, 880, T0 + 400 * DAY)

        assertThat(rerun.refresherStage).isEqualTo(0)
        assertThat(rerun.certifiedAtEpochSec).isEqualTo(T0 + 400 * DAY)
        assertThat(rerun.lastPassAtEpochSec).isEqualTo(T0 + 400 * DAY)
        assertThat(rerun.consecutiveFailures).isEqualTo(0)
    }

    @Test
    fun `due detection and countdown are consistent`() {
        val state = SpacedRepetitionScheduler.onInitialPass(800, T0)
        assertThat(SpacedRepetitionScheduler.isDue(state, T0)).isFalse()
        assertThat(SpacedRepetitionScheduler.secondsUntilDue(state, T0)).isEqualTo(2 * DAY)
        assertThat(SpacedRepetitionScheduler.isDue(state, T0 + 2 * DAY)).isTrue()
        assertThat(SpacedRepetitionScheduler.secondsUntilDue(state, T0 + 5 * DAY)).isEqualTo(0L)
    }

    @Test
    fun `a schedule computed after long downtime is still correct`() {
        // The whole schedule is arithmetic over stored timestamps, so a phone that was off for a
        // month reports the right answer the instant it boots.
        val state = SpacedRepetitionScheduler.onInitialPass(800, T0)
        assertThat(SpacedRepetitionScheduler.isDue(state, T0 + 45 * DAY)).isTrue()
    }

    @Test
    fun `refresher is not sufficient once readiness has expired`() {
        val state = SpacedRepetitionScheduler.onInitialPass(700, T0)
        assertThat(SpacedRepetitionScheduler.refresherIsSufficient(state, T0 + 10 * DAY)).isTrue()
        assertThat(SpacedRepetitionScheduler.refresherIsSufficient(state, T0 + 300 * DAY)).isFalse()
    }

    @Test
    fun `rejects an out of range score`() {
        assertThrows<IllegalArgumentException> {
            SpacedRepetitionScheduler.onInitialPass(1_001, T0)
        }
        assertThrows<IllegalArgumentException> {
            SpacedRepetitionScheduler.onRefresherPassed(
                SpacedRepetitionScheduler.onInitialPass(800, T0),
                -1,
                T0,
            )
        }
    }
}

class ValidityEvaluatorTest {

    @Test
    fun `never certified is reported distinctly`() {
        val assessment = ValidityEvaluator.evaluate(null, T0)

        assertThat(assessment.requiredAction).isEqualTo(RequiredAction.NEVER_CERTIFIED)
        assertThat(assessment.statutoryValid).isFalse()
        assertThat(assessment.clearedForHazardousWork).isFalse()
        assertThat(assessment.requiredAction.blocksHazardousWork).isTrue()
    }

    @Test
    fun `a fresh certification is valid and needs nothing`() {
        val state = SpacedRepetitionScheduler.onInitialPass(950, T0)
        val assessment = ValidityEvaluator.evaluate(state, T0)

        assertThat(assessment.statutoryValid).isTrue()
        assertThat(assessment.band).isEqualTo(ReadinessBand.READY)
        assertThat(assessment.requiredAction).isEqualTo(RequiredAction.NONE)
        assertThat(assessment.clearedForHazardousWork).isTrue()
        assertThat(assessment.daysUntilStatutoryExpiry).isEqualTo(365L)
    }

    @Test
    fun `statutory validity expires exactly one year after certification`() {
        val state = SpacedRepetitionScheduler.onInitialPass(1_000, T0)
        assertThat(ValidityEvaluator.evaluate(state, T0 + 364 * DAY).statutoryValid).isTrue()
        assertThat(ValidityEvaluator.evaluate(state, T0 + 366 * DAY).statutoryValid).isFalse()
    }

    @Test
    fun `expired statutory validity requires a full rerun`() {
        val state = SpacedRepetitionScheduler.onInitialPass(1_000, T0)
        val assessment = ValidityEvaluator.evaluate(state, T0 + 400 * DAY)
        assertThat(assessment.requiredAction).isEqualTo(RequiredAction.FULL_RERUN_REQUIRED)
    }

    @Test
    fun `surfaces the statutorily valid but operationally stale cohort`() {
        // The most dangerous group on a site: legally clear to work, practically unprepared.
        // Merging the two numbers into one score would hide exactly these people.
        val state = SpacedRepetitionScheduler.onInitialPass(1_000, T0)
        val assessment = ValidityEvaluator.evaluate(state, T0 + 100 * DAY)

        assertThat(assessment.statutoryValid).isTrue()
        assertThat(assessment.band).isAnyOf(ReadinessBand.STALE, ReadinessBand.EXPIRED)
        assertThat(assessment.statutorilyValidButStale).isTrue()
    }

    @Test
    fun `a due refresher is reported`() {
        val state = SpacedRepetitionScheduler.onInitialPass(950, T0)
        val assessment = ValidityEvaluator.evaluate(state, T0 + 3 * DAY)

        assertThat(assessment.refresherDue).isTrue()
        assertThat(assessment.requiredAction).isEqualTo(RequiredAction.REFRESHER_DUE)
        // A due refresher does not stop work; a lapsed certification does.
        assertThat(assessment.clearedForHazardousWork).isTrue()
    }

    @Test
    fun `expiry warning fires inside the lead window`() {
        val state = SpacedRepetitionScheduler.onInitialPass(900, T0)
        assertThat(ValidityEvaluator.expiringSoon(state, T0 + 300 * DAY)).isFalse()
        assertThat(ValidityEvaluator.expiringSoon(state, T0 + 350 * DAY)).isTrue()
        assertThat(ValidityEvaluator.expiringSoon(state, T0 + 400 * DAY)).isFalse()
    }

    @Test
    fun `expiry is computed from the certification date not the last refresher`() {
        val initial = SpacedRepetitionScheduler.onInitialPass(800, T0)
        val refreshed = SpacedRepetitionScheduler.onRefresherPassed(initial, 900, T0 + 200 * DAY)

        assertThat(ValidityEvaluator.statutoryExpiryEpochSec(refreshed.certifiedAtEpochSec))
            .isEqualTo(T0 + 365 * DAY)
    }

    @Test
    fun `rejects a negative certification date`() {
        assertThrows<IllegalArgumentException> { ValidityEvaluator.statutoryExpiryEpochSec(-1L) }
    }
}
