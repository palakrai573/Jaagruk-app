"""Statutory CSV exports."""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi.responses import StreamingResponse

from app.api.deps import ClientIpDep, DbDep, SettingsDep, require_roles, require_site_access
from app.core.security import Role
from app.db.base import utcnow
from app.services import audit
from app.services import reports as report_service
from app.services.reports import StatutoryAct

logger = logging.getLogger("jaagruk.reports")

router = APIRouter(prefix="/reports", tags=["reports"])

SECONDS_PER_DAY = 86_400
DEFAULT_WINDOW_DAYS = 365
MAX_WINDOW_DAYS = 1_825  # five years


def _window(days: int) -> tuple[int, int]:
    now_sec = int(utcnow().timestamp())
    return now_sec - days * SECONDS_PER_DAY, now_sec


@router.get(
    "/statutory.csv",
    summary="Per-worker certification status for a statutory audit",
    response_class=StreamingResponse,
)
def statutory_report(
    db: DbDep,
    settings: SettingsDep,
    ip: ClientIpDep,
    scope: Annotated[
        object,
        Depends(require_roles(Role.DGMS_INSPECTOR, Role.COMPANY_ADMIN, Role.SITE_OFFICER)),
    ],
    site_id: Annotated[str | None, Query(max_length=16)] = None,
    days: Annotated[int, Query(ge=1, le=MAX_WINDOW_DAYS)] = DEFAULT_WINDOW_DAYS,
    act: Annotated[StatutoryAct, Query()] = StatutoryAct.BOTH,
) -> StreamingResponse:
    """Streamed, capped, and traceable back to the ledger.

    Each row carries the certificate's ``record_hash`` prefix, so an inspector can tie a compliance
    line to a specific chain entry and re-verify it independently. A compliance report that cannot
    be traced to its evidence is only a claim.
    """
    from_sec, to_sec = _window(days)
    if site_id:
        require_site_access(db, scope, site_id)

    audit.record(
        db,
        actor=scope.username,  # type: ignore[attr-defined]
        actor_role=scope.role.value,  # type: ignore[attr-defined]
        action=audit.ACTION_REPORT_EXPORT,
        target_type="report",
        target_id="statutory",
        detail=f"site={site_id or 'all'} days={days} act={act.value}",
        ip_address=ip,
        site_id=site_id,
    )

    filename = report_service.filename_for("certification", site_id, from_sec, to_sec)
    return StreamingResponse(
        report_service.stream_certification_report(
            db,
            scope,  # type: ignore[arg-type]
            site_id=site_id,
            from_sec=from_sec,
            to_sec=to_sec,
            act=act,
            max_rows=settings.max_report_rows,
        ),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.get(
    "/hazards.csv", summary="Near-miss and hazard log", response_class=StreamingResponse
)
def hazard_report(
    db: DbDep,
    settings: SettingsDep,
    ip: ClientIpDep,
    scope: Annotated[
        object,
        Depends(require_roles(Role.DGMS_INSPECTOR, Role.COMPANY_ADMIN, Role.SITE_OFFICER)),
    ],
    site_id: Annotated[str | None, Query(max_length=16)] = None,
    days: Annotated[int, Query(ge=1, le=MAX_WINDOW_DAYS)] = 90,
) -> StreamingResponse:
    from_sec, to_sec = _window(days)
    if site_id:
        require_site_access(db, scope, site_id)

    audit.record(
        db,
        actor=scope.username,  # type: ignore[attr-defined]
        actor_role=scope.role.value,  # type: ignore[attr-defined]
        action=audit.ACTION_REPORT_EXPORT,
        target_type="report",
        target_id="hazards",
        detail=f"site={site_id or 'all'} days={days}",
        ip_address=ip,
        site_id=site_id,
    )

    filename = report_service.filename_for("hazards", site_id, from_sec, to_sec)
    return StreamingResponse(
        report_service.stream_hazard_report(
            db,
            scope,  # type: ignore[arg-type]
            site_id=site_id,
            from_sec=from_sec,
            to_sec=to_sec,
            max_rows=settings.max_report_rows,
        ),
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@router.get(
    "/chain/{site_id}.csv",
    summary="Raw certificate ledger for independent verification",
    response_class=StreamingResponse,
)
def chain_report(
    site_id: str,
    db: DbDep,
    settings: SettingsDep,
    ip: ClientIpDep,
    scope: Annotated[
        object,
        Depends(require_roles(Role.DGMS_INSPECTOR, Role.COMPANY_ADMIN, Role.SITE_OFFICER)),
    ],
) -> StreamingResponse:
    """The full ledger, with the verification recipe in the file header.

    Exists so an auditor can re-verify the chain with nothing but this file and the site's public
    key. A tamper-evidence claim that only our own software can check is not worth much.
    """
    require_site_access(db, scope, site_id)

    audit.record(
        db,
        actor=scope.username,  # type: ignore[attr-defined]
        actor_role=scope.role.value,  # type: ignore[attr-defined]
        action=audit.ACTION_REPORT_EXPORT,
        target_type="report",
        target_id=f"chain:{site_id}",
        ip_address=ip,
        site_id=site_id,
    )

    return StreamingResponse(
        report_service.stream_chain_report(
            db,
            scope,  # type: ignore[arg-type]
            site_id=site_id,
            max_rows=settings.max_report_rows,
        ),
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": f'attachment; filename="jaagruk-ledger-{site_id}.csv"'
        },
    )
