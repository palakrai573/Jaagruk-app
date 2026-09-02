"""Worker roster and per-worker readiness detail."""

from __future__ import annotations

import logging
from collections import defaultdict
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
from app.api.serializers import module_readiness_out, worker_out
from app.core import canonical
from app.core.security import Role
from app.db.base import utcnow
from app.models import Certificate, Hazard, ModuleRecord, TrainingProgress, Worker
from app.schemas import Page, WorkerCreate, WorkerDetailOut, WorkerOut
from app.services import audit
from app.services import readiness as readiness_service

logger = logging.getLogger("jaagruk.workers")

router = APIRouter(prefix="/workers", tags=["workers"])


@router.get("", response_model=Page[WorkerOut], summary="Paginated worker roster")
def list_workers(
    db: DbDep,
    scope: ScopeDep,
    page: PaginationDep,
    site_id: Annotated[str | None, Query()] = None,
    q: Annotated[str | None, Query(max_length=100, description="Name or id substring")] = None,
    readiness_below: Annotated[
        int | None, Query(ge=0, le=1000, description="Only workers under this readiness")
    ] = None,
    include_inactive: Annotated[bool, Query()] = False,
) -> Page[WorkerOut]:
    now_sec = int(utcnow().timestamp())

    statement = select(Worker)
    statement = scope.restrict(statement, Worker.site_id)
    count_statement = select(func.count()).select_from(Worker)
    count_statement = scope.restrict(count_statement, Worker.site_id)

    if not include_inactive:
        statement = statement.where(Worker.active.is_(True))
        count_statement = count_statement.where(Worker.active.is_(True))
    if site_id:
        statement = statement.where(Worker.site_id == site_id)
        count_statement = count_statement.where(Worker.site_id == site_id)
    if q:
        pattern = f"%{q.strip()}%"
        condition = Worker.full_name.ilike(pattern) | Worker.id.ilike(pattern)
        statement = statement.where(condition)
        count_statement = count_statement.where(condition)

    # Readiness is a decayed value computed in Python, so it cannot be a SQL predicate. When the
    # filter is used, the page is assembled after filtering rather than before, which keeps the
    # reported total honest instead of counting rows the caller will never see.
    if readiness_below is not None:
        rows = list(db.scalars(statement.order_by(Worker.site_id, Worker.id)).all())
        progress_map = _progress_by_worker(db, [w.id for w in rows])
        filtered = [
            worker
            for worker in rows
            if _overall_readiness(progress_map.get(worker.id, []), now_sec) < readiness_below
        ]
        total = len(filtered)
        window = filtered[page.offset : page.offset + page.page_size]
        return Page[WorkerOut](
            items=[
                worker_out(worker, progress_map.get(worker.id, []), now_sec)
                for worker in window
            ],
            total=total,
            page=page.page,
            page_size=page.page_size,
        )

    total = db.scalar(count_statement) or 0
    rows = list(
        db.scalars(
            statement.order_by(Worker.site_id, Worker.id)
            .offset(page.offset)
            .limit(page.page_size)
        ).all()
    )
    progress_map = _progress_by_worker(db, [w.id for w in rows])
    return Page[WorkerOut](
        items=[worker_out(w, progress_map.get(w.id, []), now_sec) for w in rows],
        total=total,
        page=page.page,
        page_size=page.page_size,
    )


@router.get(
    "/{worker_id}", response_model=WorkerDetailOut, summary="One worker with per-module readiness"
)
def get_worker(worker_id: str, db: DbDep, scope: ScopeDep) -> WorkerDetailOut:
    worker = db.get(Worker, worker_id)
    if worker is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=f"Worker '{worker_id}' was not found."
        )
    # 404 rather than 403 for an out-of-scope worker, so the endpoint cannot be used to confirm
    # that a worker id exists at another site.
    visible = scope.visible_site_ids(db)
    if visible is not None and worker.site_id not in visible:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=f"Worker '{worker_id}' was not found."
        )

    now_sec = int(utcnow().timestamp())
    progress_rows = list(
        db.scalars(
            select(TrainingProgress)
            .where(TrainingProgress.worker_id == worker.id)
            .order_by(TrainingProgress.module_code)
        ).all()
    )
    modules = {
        module.id: module
        for module in db.scalars(
            select(ModuleRecord).where(
                ModuleRecord.id.in_([row.module_id for row in progress_rows])
            )
        ).all()
    } if progress_rows else {}

    base = worker_out(worker, progress_rows, now_sec)
    certificate_count = (
        db.scalar(
            select(func.count())
            .select_from(Certificate)
            .where(Certificate.worker_id == worker.id)
        )
        or 0
    )
    hazard_count = (
        db.scalar(
            select(func.count())
            .select_from(Hazard)
            .where(Hazard.reporter_worker_id == worker.id)
        )
        or 0
    )

    return WorkerDetailOut(
        **base.model_dump(),
        phone_number=worker.phone_number,
        employment_type=worker.employment_type,
        joined_at_iso=worker.joined_at.isoformat() if worker.joined_at else None,
        modules=[
            module_readiness_out(row, modules.get(row.module_id), now_sec)
            for row in progress_rows
        ],
        certificate_count=certificate_count,
        hazard_reports_filed=hazard_count,
    )


@router.post(
    "",
    response_model=WorkerOut,
    status_code=status.HTTP_201_CREATED,
    summary="Register a worker",
)
def create_worker(
    payload: WorkerCreate,
    db: DbDep,
    ip: ClientIpDep,
    scope: Annotated[
        object,
        Depends(require_roles(Role.SUPERVISOR, Role.SITE_OFFICER, Role.COMPANY_ADMIN)),
    ],
) -> WorkerOut:
    """Register a worker, or complete a provisional record created by an earlier sync.

    A worker enrolled while a device was offline arrives as ``provisional`` with a placeholder
    name. Registering them here fills in the real details and clears the flag rather than failing
    on a duplicate id, because the training history already attached to that id must survive.
    """
    site = require_site_access(db, scope, payload.site_id)
    if not scope.may_write_site(site.id):  # type: ignore[attr-defined]
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"You cannot register workers for site '{site.id}'.",
        )
    if not payload.id.startswith(site.id):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Worker id '{payload.id}' does not belong to site '{site.id}'. "
                f"Expected the form '{site.id}-Wnnnnn'."
            ),
        )

    worker = db.get(Worker, payload.id)
    if worker is not None and not worker.provisional:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Worker '{payload.id}' is already registered.",
        )

    if worker is None:
        worker = Worker(
            id=payload.id,
            site_id=site.id,
            worker_id_hash=canonical.worker_id_hash(payload.id),
        )
        db.add(worker)

    worker.site_id = site.id
    worker.full_name = payload.full_name
    worker.preferred_language = payload.preferred_language.value
    worker.pictogram_mode = payload.pictogram_mode
    worker.phone_number = payload.phone_number
    worker.employment_type = payload.employment_type
    worker.provisional = False
    worker.active = True
    if worker.joined_at is None:
        worker.joined_at = utcnow()

    db.flush()
    audit.record(
        db,
        actor=scope.username,  # type: ignore[attr-defined]
        actor_role=scope.role.value,  # type: ignore[attr-defined]
        action=audit.ACTION_WORKER_CREATE,
        target_type="worker",
        target_id=worker.id,
        ip_address=ip,
        site_id=site.id,
    )
    return worker_out(worker, [], int(utcnow().timestamp()))


def _progress_by_worker(
    db, worker_ids: list[str]
) -> dict[str, list[TrainingProgress]]:  # noqa: ANN001
    if not worker_ids:
        return {}
    grouped: dict[str, list[TrainingProgress]] = defaultdict(list)
    for row in db.scalars(
        select(TrainingProgress).where(TrainingProgress.worker_id.in_(worker_ids))
    ).all():
        grouped[row.worker_id].append(row)
    return grouped


def _overall_readiness(rows: list[TrainingProgress], now_sec: int) -> int:
    if not rows:
        return 0
    values = [
        readiness_service.evaluate_progress(row, now_sec).readiness_permille for row in rows
    ]
    return round(sum(values) / len(values))
