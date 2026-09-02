"""Compliance dashboard endpoints."""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, Query

from app.api.deps import DbDep, PaginationDep, ScopeDep
from app.db.base import utcnow
from app.schemas import (
    ComplianceOverviewOut,
    HesitationRiskOut,
    Page,
    ReadinessTrendOut,
    SiteComplianceOut,
)
from app.services import compliance as compliance_service

logger = logging.getLogger("jaagruk.compliance_api")

router = APIRouter(prefix="/compliance", tags=["compliance"])

SECONDS_PER_DAY = 86_400
DEFAULT_TREND_DAYS = 30
MAX_TREND_DAYS = 365


@router.get("/overview", response_model=ComplianceOverviewOut, summary="KPI tiles")
def overview(db: DbDep, scope: ScopeDep) -> ComplianceOverviewOut:
    """Headline compliance figures for everything the caller can see.

    Reports statutory certification and operational readiness as separate numbers, and calls out
    ``statutorily_valid_but_stale`` explicitly. That cohort — legally clear to work, practically
    unprepared — is the group a site officer should act on first, and a single blended score
    would hide it.
    """
    return compliance_service.overview(db, scope)


@router.get("/by-site", response_model=list[SiteComplianceOut], summary="Per-site breakdown")
def by_site(db: DbDep, scope: ScopeDep) -> list[SiteComplianceOut]:
    return compliance_service.by_site(db, scope)


@router.get(
    "/hesitation-risk",
    response_model=Page[HesitationRiskOut],
    summary="Workers who answer correctly but slowly",
)
def hesitation_risk(
    db: DbDep,
    scope: ScopeDep,
    page: PaginationDep,
    site_id: Annotated[str | None, Query()] = None,
) -> Page[HesitationRiskOut]:
    """The cohort this platform exists to surface.

    These workers pass on knowledge and would pass a conventional quiz. Their decision latency
    says they may freeze when it counts, which is a documented evacuation failure mode independent
    of knowledge. They are listed separately from failures because the intervention differs:
    repeated drilling under time pressure, not re-teaching the material.
    """
    items, total = compliance_service.hesitation_risk(
        db, scope, page=page.page, page_size=page.page_size, site_id=site_id
    )
    return Page[HesitationRiskOut](
        items=items, total=total, page=page.page, page_size=page.page_size
    )


@router.get(
    "/readiness-trend", response_model=ReadinessTrendOut, summary="Daily readiness time series"
)
def readiness_trend(
    db: DbDep,
    scope: ScopeDep,
    site_id: Annotated[str | None, Query()] = None,
    days: Annotated[int, Query(ge=1, le=MAX_TREND_DAYS)] = DEFAULT_TREND_DAYS,
) -> ReadinessTrendOut:
    """Readiness evaluated as of each day, not back-projected from today.

    The distinction matters: the line shows how prepared the workforce actually was at that point,
    which is what makes a decline visible rather than smoothed away.
    """
    now_sec = int(utcnow().timestamp())
    return compliance_service.readiness_trend(
        db,
        scope,
        from_sec=now_sec - days * SECONDS_PER_DAY,
        to_sec=now_sec,
        site_id=site_id,
    )
