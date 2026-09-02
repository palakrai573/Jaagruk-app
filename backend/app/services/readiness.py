"""Server-side mirror of the on-device retention model.

Must agree with ``core/src/main/kotlin/org/jaagruk/core/retention/`` exactly. A worker looking at
"readiness 640" on their phone and an officer looking at "readiness 655" for the same worker on
the dashboard would destroy confidence in the number, and the number is the whole point.

Two rounding details matter for parity:

* Kotlin's ``roundToInt()`` rounds halves away from zero. Python's built-in ``round()`` uses
  banker's rounding, which disagrees at exactly ``.5``. ``_round_half_up`` is used throughout.
* Readiness is computed on read, never stored, so a value that synced weeks ago still reports
  correctly today without any scheduled job having run.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from enum import Enum

SECONDS_PER_DAY = 86_400

# --- decay -----------------------------------------------------------------
INITIAL_HALF_LIFE_DAYS = 45.0
HALF_LIFE_GROWTH_PER_STAGE = 0.5
MAX_HALF_LIFE_DAYS = 180.0

READY_THRESHOLD = 700
DUE_THRESHOLD = 500
STALE_THRESHOLD = 300

# --- spaced repetition -----------------------------------------------------
STAGE_INTERVALS_DAYS = (2, 7, 21, 60, 120)
MAX_STAGE = len(STAGE_INTERVALS_DAYS) - 1
FAILURE_RETRY_DAYS = 1
FAILURES_BEFORE_FULL_RERUN = 3

# --- statutory -------------------------------------------------------------
STATUTORY_VALIDITY_DAYS = 365
EXPIRY_WARNING_DAYS = 30


class ReadinessBand(str, Enum):
    READY = "ready"
    DUE = "due"
    STALE = "stale"
    EXPIRED = "expired"

    @property
    def needs_action(self) -> bool:
        return self is not ReadinessBand.READY

    @property
    def refresher_is_enough(self) -> bool:
        return self in (ReadinessBand.DUE, ReadinessBand.STALE)


class RequiredAction(str, Enum):
    NONE = "none"
    REFRESHER_DUE = "refresher_due"
    FULL_RERUN_REQUIRED = "full_rerun_required"
    NEVER_CERTIFIED = "never_certified"

    @property
    def blocks_hazardous_work(self) -> bool:
        return self in (RequiredAction.FULL_RERUN_REQUIRED, RequiredAction.NEVER_CERTIFIED)


def _round_half_up(value: float) -> int:
    """Match Kotlin's ``roundToInt()``: halves go away from zero."""
    return int(math.floor(value + 0.5)) if value >= 0 else -int(math.floor(-value + 0.5))


def half_life_days(refresher_stage: int) -> float:
    if refresher_stage < 0:
        raise ValueError(f"refresher_stage must be >= 0, got {refresher_stage}")
    grown = INITIAL_HALF_LIFE_DAYS * (1.0 + HALF_LIFE_GROWTH_PER_STAGE * refresher_stage)
    return min(grown, MAX_HALF_LIFE_DAYS)


def readiness_permille(
    base_score: int,
    last_pass_at_sec: int,
    now_sec: int,
    refresher_stage: int,
) -> int:
    """Exponential decay from the last genuine pass.

    A ``last_pass_at_sec`` in the future — routine when a device clock is wrong, or when a record
    synced from a phone that was ahead — is clamped to now rather than producing a readiness above
    the stored base score.
    """
    if not 0 <= base_score <= 1000:
        raise ValueError(f"base_score must be 0..1000, got {base_score}")
    if base_score == 0:
        return 0

    elapsed_seconds = max(0, now_sec - last_pass_at_sec)
    elapsed_days = elapsed_seconds / SECONDS_PER_DAY
    stage = min(max(refresher_stage, 0), MAX_STAGE)
    decayed = base_score * (0.5 ** (elapsed_days / half_life_days(stage)))
    return max(0, min(1000, _round_half_up(decayed)))


def band_for(readiness: int) -> ReadinessBand:
    if readiness >= READY_THRESHOLD:
        return ReadinessBand.READY
    if readiness >= DUE_THRESHOLD:
        return ReadinessBand.DUE
    if readiness >= STALE_THRESHOLD:
        return ReadinessBand.STALE
    return ReadinessBand.EXPIRED


def consolidate(current_base: int, achieved_score: int) -> int:
    """Move halfway toward full retention, never below the score just achieved."""
    if not 0 <= current_base <= 1000:
        raise ValueError(f"current_base must be 0..1000, got {current_base}")
    if not 0 <= achieved_score <= 1000:
        raise ValueError(f"achieved_score must be 0..1000, got {achieved_score}")
    consolidated = current_base + _round_half_up((1000 - current_base) * 0.5)
    return max(0, min(1000, max(consolidated, achieved_score)))


def interval_days(stage: int) -> int:
    if stage < 0:
        raise ValueError(f"stage must be >= 0, got {stage}")
    return STAGE_INTERVALS_DAYS[min(stage, MAX_STAGE)]


def next_due_sec(last_pass_at_sec: int, stage: int) -> int:
    return last_pass_at_sec + interval_days(stage) * SECONDS_PER_DAY


def statutory_expiry_sec(certified_at_sec: int) -> int:
    if certified_at_sec < 0:
        raise ValueError(f"certified_at_sec must be >= 0, got {certified_at_sec}")
    return certified_at_sec + STATUTORY_VALIDITY_DAYS * SECONDS_PER_DAY


def refresher_is_sufficient(
    consecutive_failures: int,
    readiness: int,
) -> bool:
    """A refresher checks retained knowledge. Once readiness has expired, or three consecutive
    refreshers have failed, there is nothing left to check and re-running the module is the only
    honest option."""
    if consecutive_failures >= FAILURES_BEFORE_FULL_RERUN:
        return False
    return band_for(readiness) is not ReadinessBand.EXPIRED


@dataclass(frozen=True, slots=True)
class ValidityAssessment:
    """Combined statutory and operational verdict.

    Reported as two dimensions, never merged. The most dangerous cohort on a site is the one that
    is statutorily valid and operationally stale — legally clear to work, practically unprepared —
    and a single blended score would hide exactly that group.
    """

    statutory_valid: bool
    statutory_expiry_sec: int
    days_until_statutory_expiry: int
    readiness_permille: int
    band: ReadinessBand
    required_action: RequiredAction
    refresher_due: bool
    seconds_until_refresher_due: int

    @property
    def statutorily_valid_but_stale(self) -> bool:
        return self.statutory_valid and self.band in (
            ReadinessBand.STALE,
            ReadinessBand.EXPIRED,
        )

    @property
    def cleared_for_hazardous_work(self) -> bool:
        return self.statutory_valid and not self.required_action.blocks_hazardous_work


NEVER_CERTIFIED = ValidityAssessment(
    statutory_valid=False,
    statutory_expiry_sec=0,
    days_until_statutory_expiry=0,
    readiness_permille=0,
    band=ReadinessBand.EXPIRED,
    required_action=RequiredAction.NEVER_CERTIFIED,
    refresher_due=False,
    seconds_until_refresher_due=0,
)


def evaluate(
    *,
    base_score: int,
    last_pass_at_sec: int,
    certified_at_sec: int,
    refresher_stage: int,
    next_due_at_sec: int,
    consecutive_failures: int,
    now_sec: int,
) -> ValidityAssessment:
    if base_score <= 0 or certified_at_sec <= 0:
        return NEVER_CERTIFIED

    expiry = statutory_expiry_sec(certified_at_sec)
    statutory_valid = now_sec < expiry
    days_left = max(0, expiry - now_sec) // SECONDS_PER_DAY

    readiness = readiness_permille(
        base_score=base_score,
        last_pass_at_sec=last_pass_at_sec,
        now_sec=now_sec,
        refresher_stage=refresher_stage,
    )
    band = band_for(readiness)
    due = now_sec >= next_due_at_sec
    refresher_enough = refresher_is_sufficient(consecutive_failures, readiness)

    if not statutory_valid or not refresher_enough:
        action = RequiredAction.FULL_RERUN_REQUIRED
    elif due or band.needs_action:
        action = RequiredAction.REFRESHER_DUE
    else:
        action = RequiredAction.NONE

    return ValidityAssessment(
        statutory_valid=statutory_valid,
        statutory_expiry_sec=expiry,
        days_until_statutory_expiry=days_left,
        readiness_permille=readiness,
        band=band,
        required_action=action,
        refresher_due=due,
        seconds_until_refresher_due=max(0, next_due_at_sec - now_sec),
    )


def evaluate_progress(progress, now_sec: int) -> ValidityAssessment:  # noqa: ANN001
    """Convenience wrapper over a :class:`app.models.TrainingProgress` row."""
    if progress is None:
        return NEVER_CERTIFIED
    return evaluate(
        base_score=progress.base_score,
        last_pass_at_sec=progress.last_pass_at_sec,
        certified_at_sec=progress.certified_at_sec,
        refresher_stage=progress.refresher_stage,
        next_due_at_sec=progress.next_due_at_sec,
        consecutive_failures=progress.consecutive_failures,
        now_sec=now_sec,
    )
