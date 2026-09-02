"""Hazard reporting and triage."""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select

from app.api.deps import (
    ClientIpDep,
    DbDep,
    PaginationDep,
    ScopeDep,
    require_roles,
    require_site_access,
)
from app.api.serializers import hazard_out
from app.core.security import Role
from app.db.base import utcnow
from app.models import Hazard, HazardStatus, Site
from app.schemas import HazardCreate, HazardOut, HazardPatch, Page
from app.services import audit, events
from app.services import hazards as hazard_service
from app.services.hazards import (
    ConcurrentModification,
    IllegalTransition,
    RateLimited,
)

logger = logging.getLogger("jaagruk.hazards_api")

router = APIRouter(prefix="/hazards", tags=["hazards"])


@router.get("", response_model=Page[HazardOut], summary="Hazard list with filters")
def list_hazards(
    db: DbDep,
    scope: ScopeDep,
    page: PaginationDep,
    site_id: Annotated[str | None, Query()] = None,
    status_filter: Annotated[HazardStatus | None, Query(alias="status")] = None,
    severity: Annotated[str | None, Query(max_length=16)] = None,
    category: Annotated[str | None, Query(max_length=32)] = None,
    include_duplicates: Annotated[bool, Query()] = False,
    bbox: Annotated[
        str | None,
        Query(
            description="min_lon,min_lat,max_lon,max_lat — for the map viewport",
            max_length=100,
        ),
    ] = None,
) -> Page[HazardOut]:
    statement = select(Hazard)
    statement = scope.restrict(statement, Hazard.site_id)
    count_statement = select(func.count()).select_from(Hazard)
    count_statement = scope.restrict(count_statement, Hazard.site_id)

    def both(condition):  # noqa: ANN001, ANN202
        nonlocal statement, count_statement
        statement = statement.where(condition)
        count_statement = count_statement.where(condition)

    if not include_duplicates:
        both(Hazard.duplicate_of_id.is_(None))
    if site_id:
        both(Hazard.site_id == site_id)
    if status_filter is not None:
        both(Hazard.status == status_filter.value)
    if severity:
        both(Hazard.severity == severity)
    if category:
        both(Hazard.category == category)

    if bbox:
        try:
            min_lon, min_lat, max_lon, max_lat = (float(part) for part in bbox.split(","))
        except (ValueError, TypeError) as exc:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="bbox must be four comma-separated numbers: min_lon,min_lat,max_lon,max_lat",
            ) from exc
        # Hazards with no coordinates are excluded from a viewport query rather than pinned at
        # (0, 0). The dashboard lists them separately, because underground reports legitimately
        # have no GPS fix and dropping them entirely would hide real hazards.
        both(Hazard.latitude.is_not(None))
        both(Hazard.longitude.is_not(None))
        both(Hazard.longitude >= min_lon)
        both(Hazard.longitude <= max_lon)
        both(Hazard.latitude >= min_lat)
        both(Hazard.latitude <= max_lat)

    total = db.scalar(count_statement) or 0
    rows = list(
        db.scalars(
            statement.order_by(Hazard.reported_at_sec.desc())
            .offset(page.offset)
            .limit(page.page_size)
        ).all()
    )
    site_names = {
        site.id: site.name
        for site in db.scalars(
            select(Site).where(Site.id.in_({row.site_id for row in rows}))
        ).all()
    } if rows else {}

    items = []
    for row in rows:
        out = hazard_out(row)
        items.append(out.model_copy(update={"site_name": site_names.get(row.site_id)}))

    return Page[HazardOut](
        items=items, total=total, page=page.page, page_size=page.page_size
    )


@router.get(
    "/without-coordinates",
    response_model=Page[HazardOut],
    summary="Hazards located only by zone label",
)
def hazards_without_coordinates(
    db: DbDep,
    scope: ScopeDep,
    page: PaginationDep,
    site_id: Annotated[str | None, Query()] = None,
) -> Page[HazardOut]:
    """Reports with no GPS fix.

    Routine underground, and the reason the map page shows a companion list rather than only pins:
    a hazard that cannot be plotted still has to be visible.
    """
    statement = select(Hazard).where(
        Hazard.latitude.is_(None), Hazard.duplicate_of_id.is_(None)
    )
    statement = scope.restrict(statement, Hazard.site_id)
    count_statement = select(func.count()).select_from(Hazard).where(
        Hazard.latitude.is_(None), Hazard.duplicate_of_id.is_(None)
    )
    count_statement = scope.restrict(count_statement, Hazard.site_id)

    if site_id:
        statement = statement.where(Hazard.site_id == site_id)
        count_statement = count_statement.where(Hazard.site_id == site_id)

    total = db.scalar(count_statement) or 0
    rows = list(
        db.scalars(
            statement.order_by(Hazard.reported_at_sec.desc())
            .offset(page.offset)
            .limit(page.page_size)
        ).all()
    )
    return Page[HazardOut](
        items=[hazard_out(row, db) for row in rows],
        total=total,
        page=page.page,
        page_size=page.page_size,
    )


@router.get("/{hazard_id}", response_model=HazardOut, summary="One hazard")
def get_hazard(hazard_id: str, db: DbDep, scope: ScopeDep) -> HazardOut:
    hazard = _load_in_scope(db, scope, hazard_id)
    return hazard_out(hazard, db)


@router.post(
    "", response_model=HazardOut, status_code=status.HTTP_201_CREATED, summary="File a hazard"
)
def create_hazard(
    payload: HazardCreate,
    db: DbDep,
    scope: ScopeDep,
    ip: ClientIpDep,
) -> HazardOut:
    """File a hazard report.

    Deliberately available to every authenticated role, including a supervisor account with no
    triage rights. Safety reporting is never gated on seniority or on the reporter being certified:
    an uncertified worker noticing exposed wiring is exactly who should be able to say so.
    """
    from app.core.config import get_settings

    settings = get_settings()
    site = require_site_access(db, scope, payload.site_id)

    try:
        result = hazard_service.create_hazard(
            db,
            hazard_id=None,
            site_id=site.id,
            category=payload.category,
            severity=payload.severity,
            note=payload.note,
            reporter_worker_id=payload.reporter_worker_id,
            latitude=payload.latitude,
            longitude=payload.longitude,
            zone_label=payload.zone_label,
            ar_anchor_id=payload.ar_anchor_id,
            photo_media_id=payload.photo_media_id,
            voice_media_id=payload.voice_media_id,
            reported_at_sec=int(utcnow().timestamp()),
            device_id=None,
            rate_limit_per_hour=settings.hazard_reports_per_worker_per_hour,
            duplicate_window_seconds=settings.hazard_duplicate_window_seconds,
            duplicate_radius_metres=settings.hazard_duplicate_radius_metres,
        )
    except RateLimited as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=str(exc),
            headers={"Retry-After": str(exc.retry_after_seconds)},
        ) from exc

    audit.record(
        db,
        actor=scope.username,
        actor_role=scope.role.value,
        action=audit.ACTION_HAZARD_CREATE,
        target_type="hazard",
        target_id=result.hazard.id,
        detail=f"category={payload.category.value} severity={payload.severity.value}",
        ip_address=ip,
        site_id=site.id,
    )
    events.publish_threadsafe(
        "hazard.created",
        site_id=site.id,
        company_id=site.company_id,
        at_epoch_sec=int(utcnow().timestamp()),
        payload={
            "hazard_id": result.hazard.id,
            "category": result.hazard.category,
            "severity": result.hazard.severity,
            "is_duplicate": result.is_duplicate,
            "latitude": result.hazard.latitude,
            "longitude": result.hazard.longitude,
            "zone_label": result.hazard.zone_label,
        },
    )
    return hazard_out(result.hazard, db)


@router.patch("/{hazard_id}", response_model=HazardOut, summary="Triage a hazard")
def patch_hazard(
    hazard_id: str,
    payload: HazardPatch,
    db: DbDep,
    ip: ClientIpDep,
    scope: Annotated[
        object, Depends(require_roles(Role.COMPANY_ADMIN, Role.SITE_OFFICER))
    ],
) -> HazardOut:
    """Move a hazard through the triage workflow.

    Transitions are validated against a fixed graph; an illegal move returns 409 with the permitted
    set. ``resolved`` and ``invalid`` are terminal on purpose — reopening would let a closed
    hazard be quietly relitigated, so a fresh report is required and the original stays on record.
    """
    hazard = _load_in_scope(db, scope, hazard_id)
    previous = hazard.status

    try:
        hazard_service.transition(
            db,
            hazard,
            requested=payload.status,
            actor_user_id=scope.user_id,  # type: ignore[attr-defined]
            resolution_note=payload.resolution_note,
            expected_updated_at_iso=payload.expected_updated_at_iso,
        )
    except IllegalTransition as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    except ConcurrentModification as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    db.flush()
    site = db.get(Site, hazard.site_id)

    audit.record(
        db,
        actor=scope.username,  # type: ignore[attr-defined]
        actor_role=scope.role.value,  # type: ignore[attr-defined]
        action=audit.ACTION_HAZARD_TRANSITION,
        target_type="hazard",
        target_id=hazard.id,
        detail=f"{previous} -> {hazard.status}",
        ip_address=ip,
        site_id=hazard.site_id,
    )
    events.publish_threadsafe(
        "hazard.updated",
        site_id=hazard.site_id,
        company_id=site.company_id if site else None,
        at_epoch_sec=int(utcnow().timestamp()),
        payload={
            "hazard_id": hazard.id,
            "from_status": previous,
            "to_status": hazard.status,
            "severity": hazard.severity,
        },
    )
    return hazard_out(hazard, db)


def _load_in_scope(db, scope, hazard_id: str) -> Hazard:  # noqa: ANN001
    hazard = db.get(Hazard, hazard_id)
    if hazard is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=f"Hazard '{hazard_id}' was not found."
        )
    visible = scope.visible_site_ids(db)
    if visible is not None and hazard.site_id not in visible:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=f"Hazard '{hazard_id}' was not found."
        )
    return hazard
