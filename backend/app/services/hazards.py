"""Near-miss hazard reporting: creation, clustering, rate limiting, triage.

This is what turns the platform from a one-way pipeline — train, certify, forget — into a closed
loop where trained workers actively make the site safer. It also gives DGMS something it currently
has no source for: ground-level hazard reporting from the workforce, rather than only
after-the-fact accident investigation.
"""

from __future__ import annotations

import logging
import math
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.db.base import as_utc, to_utc_iso, utcnow
from app.models import (
    HAZARD_TRANSITIONS,
    Hazard,
    HazardCategory,
    HazardSeverity,
    HazardStatus,
    Worker,
)

logger = logging.getLogger("jaagruk.hazards")

EARTH_RADIUS_METRES = 6_371_000.0


class HazardError(Exception):
    """Base for hazard rule violations, each mapped to a specific HTTP status by the router."""


class RateLimited(HazardError):
    def __init__(self, limit: int, retry_after_seconds: int) -> None:
        super().__init__(
            f"hazard report limit of {limit} per hour reached; try again in "
            f"{retry_after_seconds}s"
        )
        self.limit = limit
        self.retry_after_seconds = retry_after_seconds


class IllegalTransition(HazardError):
    def __init__(self, current: HazardStatus, requested: HazardStatus) -> None:
        allowed = sorted(s.value for s in HAZARD_TRANSITIONS[current])
        super().__init__(
            f"cannot move a hazard from '{current.value}' to '{requested.value}'. "
            f"Allowed from '{current.value}': {allowed or 'none (terminal state)'}"
        )
        self.current = current
        self.requested = requested
        self.allowed = allowed


class ConcurrentModification(HazardError):
    def __init__(self, actual_iso: str) -> None:
        super().__init__(
            "this hazard was changed by someone else since you loaded it. "
            f"Refresh and retry (server timestamp {actual_iso})."
        )
        self.actual_iso = actual_iso


def haversine_metres(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance. Used for duplicate clustering, so metres are plenty."""
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = phi2 - phi1
    d_lambda = math.radians(lon2 - lon1)
    a = (
        math.sin(d_phi / 2) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    )
    # Clamped before asin: floating-point error can push the argument a hair above 1.0 for
    # antipodal points, which would raise instead of returning half the circumference.
    return 2 * EARTH_RADIUS_METRES * math.asin(min(1.0, math.sqrt(a)))


def check_rate_limit(
    session: Session,
    *,
    worker_id: str | None,
    limit_per_hour: int,
    now: datetime | None = None,
) -> None:
    """Cap reports per worker per hour.

    Anonymous reports skip the cap deliberately: safety reporting is never gated on being
    identified, and an unattributed report is better than a suppressed one.
    """
    if not worker_id:
        return
    now = now or utcnow()
    window_start = now - timedelta(hours=1)

    recent = (
        session.scalar(
            select(func.count())
            .select_from(Hazard)
            .where(
                Hazard.reporter_worker_id == worker_id,
                Hazard.created_at >= window_start,
            )
        )
        or 0
    )
    if recent >= limit_per_hour:
        oldest = session.scalar(
            select(Hazard.created_at)
            .where(
                Hazard.reporter_worker_id == worker_id,
                Hazard.created_at >= window_start,
            )
            .order_by(Hazard.created_at.asc())
            .limit(1)
        )
        retry_after = 3_600
        oldest_utc = as_utc(oldest)
        if oldest_utc is not None:
            retry_after = max(
                1, int((oldest_utc + timedelta(hours=1) - now).total_seconds())
            )
        raise RateLimited(limit_per_hour, retry_after)


def find_duplicate(
    session: Session,
    *,
    site_id: str,
    category: HazardCategory,
    latitude: float | None,
    longitude: float | None,
    zone_label: str | None,
    reported_at_sec: int,
    window_seconds: int,
    radius_metres: float,
) -> Hazard | None:
    """Find an existing open report for the same hazard.

    Five workers walking past the same blocked exit produce five reports, and an officer should
    see one pin with a count of five rather than five pins. Matching is by site, category and time
    window, then by coordinates when available and by zone label when not — underground there is
    usually no GPS, which is exactly why the zone label exists.
    """
    candidates = list(
        session.scalars(
            select(Hazard).where(
                Hazard.site_id == site_id,
                Hazard.category == category.value,
                Hazard.duplicate_of_id.is_(None),
                Hazard.status.in_(
                    [
                        HazardStatus.OPEN.value,
                        HazardStatus.ACKNOWLEDGED.value,
                        HazardStatus.IN_PROGRESS.value,
                    ]
                ),
                Hazard.reported_at_sec >= reported_at_sec - window_seconds,
                Hazard.reported_at_sec <= reported_at_sec + window_seconds,
            )
        ).all()
    )
    if not candidates:
        return None

    for candidate in candidates:
        if (
            latitude is not None
            and longitude is not None
            and candidate.latitude is not None
            and candidate.longitude is not None
        ):
            distance = haversine_metres(
                latitude, longitude, candidate.latitude, candidate.longitude
            )
            if distance <= radius_metres:
                return candidate
            continue

        if zone_label and candidate.zone_label and zone_label == candidate.zone_label:
            return candidate

    return None


@dataclass(slots=True)
class HazardCreateResult:
    hazard: Hazard
    is_duplicate: bool
    merged_into: Hazard | None = None


def create_hazard(
    session: Session,
    *,
    hazard_id: str | None,
    site_id: str,
    category: HazardCategory,
    severity: HazardSeverity,
    note: str | None,
    reporter_worker_id: str | None,
    latitude: float | None,
    longitude: float | None,
    zone_label: str | None,
    ar_anchor_id: str | None,
    photo_media_id: str | None,
    voice_media_id: str | None,
    reported_at_sec: int,
    device_id: str | None,
    rate_limit_per_hour: int,
    duplicate_window_seconds: int,
    duplicate_radius_metres: float,
    enforce_rate_limit: bool = True,
) -> HazardCreateResult:
    if enforce_rate_limit:
        check_rate_limit(
            session, worker_id=reporter_worker_id, limit_per_hour=rate_limit_per_hour
        )

    reporter_label = "Anonymous"
    if reporter_worker_id:
        worker = session.get(Worker, reporter_worker_id)
        # Denormalised so safety evidence survives a worker record being deactivated or removed.
        reporter_label = (
            worker.full_name if worker is not None else f"Unknown ({reporter_worker_id})"
        )

    duplicate = find_duplicate(
        session,
        site_id=site_id,
        category=category,
        latitude=latitude,
        longitude=longitude,
        zone_label=zone_label,
        reported_at_sec=reported_at_sec,
        window_seconds=duplicate_window_seconds,
        radius_metres=duplicate_radius_metres,
    )

    hazard = Hazard(
        id=hazard_id or str(uuid.uuid4()),
        site_id=site_id,
        reporter_worker_id=reporter_worker_id,
        reporter_label=reporter_label,
        category=category.value,
        severity=severity.value,
        note=note,
        latitude=latitude,
        longitude=longitude,
        zone_label=zone_label,
        ar_anchor_id=ar_anchor_id,
        photo_media_id=photo_media_id,
        voice_media_id=voice_media_id,
        status=HazardStatus.OPEN.value,
        reported_at_sec=reported_at_sec,
        device_id=device_id,
        duplicate_of_id=duplicate.id if duplicate is not None else None,
    )
    session.add(hazard)

    if duplicate is not None:
        duplicate.duplicate_count += 1
        # Corroboration is a signal. Several people flagging the same thing raises its priority.
        duplicate.severity = _max_severity(
            HazardSeverity(duplicate.severity), severity
        ).value
        duplicate.updated_at = utcnow()
        session.flush()
        return HazardCreateResult(hazard=hazard, is_duplicate=True, merged_into=duplicate)

    session.flush()
    return HazardCreateResult(hazard=hazard, is_duplicate=False)


_SEVERITY_ORDER = {
    HazardSeverity.LOW: 0,
    HazardSeverity.MEDIUM: 1,
    HazardSeverity.HIGH: 2,
    HazardSeverity.CRITICAL: 3,
}


def _max_severity(a: HazardSeverity, b: HazardSeverity) -> HazardSeverity:
    return a if _SEVERITY_ORDER[a] >= _SEVERITY_ORDER[b] else b


def transition(
    session: Session,
    hazard: Hazard,
    *,
    requested: HazardStatus,
    actor_user_id: str,
    resolution_note: str | None,
    expected_updated_at_iso: str | None,
) -> Hazard:
    """Move a hazard to a new status, enforcing the legal transition graph.

    Optimistic concurrency via ``expected_updated_at_iso``: when two officers triage the same
    hazard, the second gets a 409 and refreshes rather than silently overwriting the first.
    """
    current = HazardStatus(hazard.status)

    if expected_updated_at_iso:
        actual_iso = to_utc_iso(hazard.updated_at) or ""
        if _as_instant(expected_updated_at_iso) != _as_instant(actual_iso):
            raise ConcurrentModification(actual_iso)

    if requested is current:
        return hazard

    if requested not in HAZARD_TRANSITIONS[current]:
        raise IllegalTransition(current, requested)

    hazard.status = requested.value
    hazard.updated_at = utcnow()

    if requested is HazardStatus.ACKNOWLEDGED:
        hazard.acknowledged_by_user_id = actor_user_id
        hazard.acknowledged_at = utcnow()
    if requested in (HazardStatus.RESOLVED, HazardStatus.INVALID):
        hazard.resolved_at = utcnow()
        if resolution_note:
            hazard.resolution_note = resolution_note

    return hazard


def _as_instant(value: str) -> datetime | str:
    """Parse an ISO timestamp to a UTC instant for comparison, not to a string.

    Comparing the strings directly cannot work here. A JavaScript client renders UTC with a
    trailing ``Z`` while Python writes ``+00:00``, and SQLite hands timestamps back with no offset
    at all — three spellings of the same instant. A naive value is read as UTC, because everything
    this server writes is UTC; assuming local time instead would shift every comparison by the
    machine's offset.

    Returns the raw string when the value is unparseable, so a malformed client token fails the
    comparison rather than crashing the request.
    """
    text = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return text
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def allowed_next_statuses(hazard: Hazard) -> list[str]:
    try:
        current = HazardStatus(hazard.status)
    except ValueError:
        return []
    return sorted(status.value for status in HAZARD_TRANSITIONS[current])
