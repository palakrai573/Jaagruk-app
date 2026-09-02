"""Retention model parity with the on-device Kotlin implementation.

The numbers asserted here are the same ones asserted in
``core/src/test/kotlin/org/jaagruk/core/retention/RetentionTest.kt``. A worker seeing readiness 640
on their phone while an officer sees 655 for the same worker on the dashboard would destroy
confidence in the figure, and the figure is what the whole platform is built around.
"""

from __future__ import annotations

import pytest

from app.services import readiness as r

DAY = 86_400
T0 = 1_760_000_000


class TestDecay:
    def test_readiness_equals_base_at_the_moment_of_passing(self) -> None:
        assert r.readiness_permille(900, T0, T0, 0) == 900

    def test_readiness_halves_after_one_half_life(self) -> None:
        after = T0 + int(r.INITIAL_HALF_LIFE_DAYS * DAY)
        assert r.readiness_permille(1_000, T0, after, 0) == 500

    def test_readiness_quarters_after_two_half_lives(self) -> None:
        after = T0 + int(2 * r.INITIAL_HALF_LIFE_DAYS * DAY)
        assert r.readiness_permille(1_000, T0, after, 0) == 250

    def test_readiness_decays_monotonically(self) -> None:
        previous = 10_000
        for day in range(0, 200):
            value = r.readiness_permille(1_000, T0, T0 + day * DAY, 0)
            assert value <= previous
            previous = value

    def test_a_future_last_pass_is_clamped(self) -> None:
        # Routine when a device clock is wrong, or a record synced from a phone that was ahead.
        # It must never produce a readiness above the stored base score.
        assert r.readiness_permille(800, T0 + 30 * DAY, T0, 0) == 800

    def test_zero_base_stays_zero(self) -> None:
        assert r.readiness_permille(0, T0, T0 + 500 * DAY, 3) == 0

    def test_readiness_stays_inside_permille_range(self) -> None:
        assert r.readiness_permille(1_000, T0, T0, 5) <= 1_000
        assert r.readiness_permille(1, T0, T0 + 10_000 * DAY, 0) >= 0

    def test_rejects_an_out_of_range_base(self) -> None:
        with pytest.raises(ValueError):
            r.readiness_permille(1_001, T0, T0, 0)
        with pytest.raises(ValueError):
            r.readiness_permille(-1, T0, T0, 0)

    def test_rounding_matches_kotlin_half_up(self) -> None:
        """Python's ``round()`` uses banker's rounding and would disagree at exactly .5.

        This is the specific reason ``_round_half_up`` exists: without it, a score landing on a
        half-permille boundary differs between the phone and the server.
        """
        assert r._round_half_up(0.5) == 1  # noqa: SLF001
        assert r._round_half_up(1.5) == 2  # noqa: SLF001
        assert r._round_half_up(2.5) == 3  # noqa: SLF001
        assert round(0.5) == 0  # the behaviour being avoided
        assert round(2.5) == 2


class TestHalfLife:
    def test_half_life_lengthens_with_each_stage(self) -> None:
        assert r.half_life_days(0) == pytest.approx(45.0)
        assert r.half_life_days(1) > r.half_life_days(0)
        assert r.half_life_days(2) > r.half_life_days(1)

    def test_half_life_is_capped(self) -> None:
        assert r.half_life_days(100) == pytest.approx(r.MAX_HALF_LIFE_DAYS)

    def test_rejects_a_negative_stage(self) -> None:
        with pytest.raises(ValueError):
            r.half_life_days(-1)


class TestBands:
    @pytest.mark.parametrize(
        ("value", "band"),
        [
            (1_000, r.ReadinessBand.READY),
            (700, r.ReadinessBand.READY),
            (699, r.ReadinessBand.DUE),
            (500, r.ReadinessBand.DUE),
            (499, r.ReadinessBand.STALE),
            (300, r.ReadinessBand.STALE),
            (299, r.ReadinessBand.EXPIRED),
            (0, r.ReadinessBand.EXPIRED),
        ],
    )
    def test_thresholds(self, value: int, band: r.ReadinessBand) -> None:
        assert r.band_for(value) is band

    def test_band_properties(self) -> None:
        assert not r.ReadinessBand.READY.needs_action
        assert r.ReadinessBand.DUE.refresher_is_enough
        assert r.ReadinessBand.STALE.refresher_is_enough
        assert not r.ReadinessBand.EXPIRED.refresher_is_enough


class TestConsolidation:
    def test_moves_halfway_toward_full_retention(self) -> None:
        assert r.consolidate(600, 600) == 800
        assert r.consolidate(800, 700) == 900
        assert r.consolidate(1_000, 1_000) == 1_000

    def test_never_drops_below_the_score_just_achieved(self) -> None:
        assert r.consolidate(200, 950) == 950
        assert r.consolidate(900, 720) == 950

    def test_rejects_out_of_range_input(self) -> None:
        with pytest.raises(ValueError):
            r.consolidate(1_100, 500)
        with pytest.raises(ValueError):
            r.consolidate(500, -1)


class TestSchedule:
    def test_intervals_expand(self) -> None:
        assert list(r.STAGE_INTERVALS_DAYS) == [2, 7, 21, 60, 120]

    def test_intervals_clamp_past_the_last_stage(self) -> None:
        assert r.interval_days(99) == 120
        with pytest.raises(ValueError):
            r.interval_days(-1)

    def test_next_due_is_arithmetic_on_the_last_pass(self) -> None:
        assert r.next_due_sec(T0, 0) == T0 + 2 * DAY
        assert r.next_due_sec(T0, 1) == T0 + 7 * DAY

    def test_statutory_expiry_is_one_year(self) -> None:
        assert r.statutory_expiry_sec(T0) == T0 + 365 * DAY
        with pytest.raises(ValueError):
            r.statutory_expiry_sec(-1)

    def test_refresher_is_not_enough_after_three_failures(self) -> None:
        assert r.refresher_is_sufficient(2, 800)
        assert not r.refresher_is_sufficient(3, 800)

    def test_refresher_is_not_enough_once_readiness_has_expired(self) -> None:
        assert r.refresher_is_sufficient(0, 400)
        assert not r.refresher_is_sufficient(0, 100)


class TestValidityEvaluation:
    def _evaluate(self, **overrides) -> r.ValidityAssessment:  # noqa: ANN003
        params = {
            "base_score": 950,
            "last_pass_at_sec": T0,
            "certified_at_sec": T0,
            "refresher_stage": 0,
            "next_due_at_sec": T0 + 2 * DAY,
            "consecutive_failures": 0,
            "now_sec": T0,
        }
        params.update(overrides)
        return r.evaluate(**params)

    def test_never_certified_is_reported_distinctly(self) -> None:
        assessment = r.evaluate_progress(None, T0)
        assert assessment.required_action is r.RequiredAction.NEVER_CERTIFIED
        assert not assessment.statutory_valid
        assert not assessment.cleared_for_hazardous_work

    def test_zero_base_score_reads_as_never_certified(self) -> None:
        assert self._evaluate(base_score=0).required_action is r.RequiredAction.NEVER_CERTIFIED

    def test_a_fresh_certification_needs_nothing(self) -> None:
        assessment = self._evaluate()
        assert assessment.statutory_valid
        assert assessment.band is r.ReadinessBand.READY
        assert assessment.required_action is r.RequiredAction.NONE
        assert assessment.cleared_for_hazardous_work
        assert assessment.days_until_statutory_expiry == 365

    def test_statutory_validity_expires_after_one_year(self) -> None:
        assert self._evaluate(now_sec=T0 + 364 * DAY).statutory_valid
        assert not self._evaluate(now_sec=T0 + 366 * DAY).statutory_valid

    def test_expired_statutory_validity_requires_a_full_rerun(self) -> None:
        assessment = self._evaluate(now_sec=T0 + 400 * DAY)
        assert assessment.required_action is r.RequiredAction.FULL_RERUN_REQUIRED

    def test_surfaces_the_statutorily_valid_but_stale_cohort(self) -> None:
        # Legally clear to work, practically unprepared. The group a site officer should look at
        # first, and precisely what a single blended score would hide.
        assessment = self._evaluate(base_score=1_000, now_sec=T0 + 100 * DAY)
        assert assessment.statutory_valid
        assert assessment.band in (r.ReadinessBand.STALE, r.ReadinessBand.EXPIRED)
        assert assessment.statutorily_valid_but_stale

    def test_a_due_refresher_does_not_stop_work(self) -> None:
        assessment = self._evaluate(now_sec=T0 + 3 * DAY)
        assert assessment.refresher_due
        assert assessment.required_action is r.RequiredAction.REFRESHER_DUE
        assert assessment.cleared_for_hazardous_work

    def test_seconds_until_due_never_goes_negative(self) -> None:
        assert self._evaluate(now_sec=T0 + 50 * DAY).seconds_until_refresher_due == 0
        assert self._evaluate(now_sec=T0).seconds_until_refresher_due == 2 * DAY
