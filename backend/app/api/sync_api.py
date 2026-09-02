"""Device sync: idempotent batch upload and offline bootstrap."""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select

from app.api.deps import (
    ClientIpDep,
    DbDep,
    ScopeDep,
    SettingsDep,
    require_roles,
    require_site_access,
)
from app.api.serializers import (
    empty_chain_head_out,
    module_out,
    site_key_out,
    site_out,
    worker_out,
)
from app.core.security import Role
from app.db.base import utcnow
from app.models import ChainHead, ModuleRecord, SiteKey, TrainingProgress, Worker
from app.schemas import BootstrapResponse, SyncBatchRequest, SyncBatchResponse
from app.services import events, sync as sync_service
from app.services.sync import BatchTooLarge, DeviceNotRegistered

logger = logging.getLogger("jaagruk.sync_api")

router = APIRouter(prefix="/sync", tags=["sync"])


@router.post(
    "/batch",
    response_model=SyncBatchResponse,
    summary="Upload a batch of offline records (idempotent)",
)
def upload_batch(
    payload: SyncBatchRequest,
    db: DbDep,
    settings: SettingsDep,
    ip: ClientIpDep,
    scope: Annotated[
        object,
        Depends(require_roles(Role.SUPERVISOR, Role.SITE_OFFICER, Role.COMPANY_ADMIN)),
    ],
) -> SyncBatchResponse:
    """Ingest certificates, assessment runs, hazard reports and progress in one call.

    Replaying the same ``(device_id, client_batch_id)`` returns the stored response with
    ``replayed: true`` and ingests nothing. That is what makes an upload safe to retry after a
    lost reply, which on a mine-site uplink is the normal case rather than the exception.

    Results are per item. One malformed record cannot reject the batch, so a phone returning after
    six weeks offline does not lose hundreds of good records to a single bad one.
    """
    try:
        summary = sync_service.ingest_batch(
            db,
            payload,
            settings,
            actor=scope.username,  # type: ignore[attr-defined]
            ip_address=ip,
        )
    except DeviceNotRegistered as exc:
        # 403 with the queue-retention instruction spelled out: the device must keep its queue.
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=str(exc)) from exc
    except BatchTooLarge as exc:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail=str(exc)
        ) from exc

    # Published after the service call so nothing is announced that a rollback would erase. The
    # session commits in the get_db dependency once this handler returns cleanly.
    now_sec = int(utcnow().timestamp())
    for event_type, site_id, event_payload in summary.pending_events:
        events.publish_threadsafe(
            event_type,
            site_id=site_id,
            company_id=event_payload.get("company_id"),
            at_epoch_sec=now_sec,
            payload=event_payload,
        )
    return summary.response


@router.get(
    "/bootstrap",
    response_model=BootstrapResponse,
    summary="Everything a device needs to work offline",
)
def bootstrap(
    db: DbDep,
    scope: ScopeDep,
    site_id: Annotated[str, Query(min_length=6, max_length=16)],
    include_roster: Annotated[bool, Query()] = True,
) -> BootstrapResponse:
    """Down-sync: site keys, module catalog, worker roster and the current chain head.

    Called when a device has connectivity, so it can then run for weeks without any. Every key
    epoch is returned, not just the active one, so the device can verify certificates issued under
    a previous key entirely offline.

    Reconciling provisional workers happens here too: certificates that arrived before the roster
    get linked by ``worker_id_hash`` as a side effect of the device checking in.
    """
    site = require_site_access(db, scope, site_id)

    resolved = sync_service.resolve_provisional_workers(db, site.id)
    if resolved:
        logger.info("bootstrap resolved %d provisional certificate(s) for %s", resolved, site.id)

    keys = db.scalars(
        select(SiteKey).where(SiteKey.site_id == site.id).order_by(SiteKey.epoch.desc())
    ).all()
    modules = list(
        db.scalars(
            select(ModuleRecord)
            .where(ModuleRecord.enabled.is_(True))
            .order_by(ModuleRecord.module_code)
        ).all()
    )

    workers_out = []
    if include_roster:
        now_sec = int(utcnow().timestamp())
        roster = list(
            db.scalars(
                select(Worker)
                .where(Worker.site_id == site.id, Worker.active.is_(True))
                .order_by(Worker.id)
            ).all()
        )
        progress_rows = list(
            db.scalars(
                select(TrainingProgress).where(TrainingProgress.site_id == site.id)
            ).all()
        )
        grouped: dict[str, list[TrainingProgress]] = {}
        for row in progress_rows:
            grouped.setdefault(row.worker_id, []).append(row)
        workers_out = [
            worker_out(worker, grouped.get(worker.id, []), now_sec) for worker in roster
        ]

    head = db.get(ChainHead, site.id)
    head_out = (
        empty_chain_head_out(site.id)
        if head is None
        else empty_chain_head_out(site.id).model_copy(
            update={
                "last_seq": head.last_seq,
                "last_record_hash_hex": head.last_record_hash.hex(),
                "certificate_count": head.certificate_count,
                "quarantined_count": head.quarantined_count,
            }
        )
    )

    return BootstrapResponse(
        site=site_out(site),
        site_keys=[site_key_out(key) for key in keys],
        modules=[module_out(module) for module in modules],
        workers=workers_out,
        chain_head_seq=head_out.last_seq,
        chain_head_hash_hex=head_out.last_record_hash_hex,
        catalog_version=max((m.catalog_version for m in modules), default=1),
        server_time_sec=int(utcnow().timestamp()),
    )
