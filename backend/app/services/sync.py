"""Idempotent batch ingest from devices.

Three properties this module has to guarantee, in order of importance:

1. **Replaying a batch never duplicates anything.** ``(device_id, client_batch_id)`` is uniquely
   constrained, and a replay returns the stored response byte for byte. A phone whose reply was
   lost on a flaky uplink retries and gets the same answer it would have got the first time.
2. **One bad record cannot reject the batch.** Results are per item. A phone that has been offline
   for six weeks does not lose four hundred good records because of one malformed one.
3. **Ingest is additive only.** There is no client-driven update or delete path, so no device can
   rewrite history.
"""

from __future__ import annotations

import json
import logging
import uuid
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core import canonical
from app.core.config import Settings
from app.db.base import utcnow
from app.models import (
    AssessmentRun,
    Certificate,
    Device,
    Hazard,
    HazardCategory,
    HazardSeverity,
    ModuleRecord,
    Site,
    SyncBatch,
    TrainingProgress,
    Worker,
)
from app.schemas import (
    AssessmentUpload,
    CertificateUpload,
    HazardUpload,
    ProgressUpload,
    SyncBatchRequest,
    SyncBatchResponse,
    SyncItemResult,
)
from app.services import audit, chain, hazards as hazard_service

logger = logging.getLogger("jaagruk.sync")


class SyncError(Exception):
    """Batch-level failure. Item-level problems are reported as results, not raised."""


class DeviceNotRegistered(SyncError):
    def __init__(self, device_id: str) -> None:
        super().__init__(
            f"device {device_id} is not registered. A supervisor must register it against a "
            "site before it can upload. Keep the queue and retry after registration."
        )
        self.device_id = device_id


class BatchTooLarge(SyncError):
    def __init__(self, count: int, limit: int) -> None:
        super().__init__(
            f"batch carries {count} items, over the per-request limit of {limit}. "
            "Split it and retry."
        )


@dataclass(slots=True)
class IngestSummary:
    response: SyncBatchResponse
    #: Events to publish once the transaction commits. Publishing before commit could announce a
    #: certificate that a later rollback erases.
    pending_events: list[tuple[str, str | None, dict]]


def _now_sec() -> int:
    return int(utcnow().timestamp())


def find_existing_batch(
    session: Session, device_id: str, client_batch_id: str
) -> SyncBatch | None:
    return session.scalar(
        select(SyncBatch).where(
            SyncBatch.device_id == device_id,
            SyncBatch.client_batch_id == client_batch_id,
        )
    )


def ingest_batch(
    session: Session,
    request: SyncBatchRequest,
    settings: Settings,
    *,
    actor: str,
    ip_address: str | None = None,
) -> IngestSummary:
    if request.item_count > settings.max_batch_items:
        raise BatchTooLarge(request.item_count, settings.max_batch_items)

    device = session.get(Device, request.device_id)
    if device is None or not device.active:
        raise DeviceNotRegistered(request.device_id)

    replayed = find_existing_batch(session, request.device_id, request.client_batch_id)
    if replayed is not None:
        replayed.replay_count += 1
        audit.record(
            session,
            actor=actor,
            action=audit.ACTION_SYNC_REPLAY,
            target_type="sync_batch",
            target_id=replayed.id,
            detail=f"replay #{replayed.replay_count} of batch {request.client_batch_id}",
            ip_address=ip_address,
            site_id=device.site_id,
        )
        stored = json.loads(replayed.response_json)
        stored["replayed"] = True
        stored["server_time_sec"] = _now_sec()
        return IngestSummary(
            response=SyncBatchResponse.model_validate(stored), pending_events=[]
        )

    device.last_seen_at = utcnow()
    device.last_sync_at = utcnow()

    site = session.get(Site, device.site_id)
    company_id = site.company_id if site is not None else None

    results: list[SyncItemResult] = []
    events: list[tuple[str, str | None, dict]] = []
    now_sec = _now_sec()

    for upload in request.certificates:
        results.append(
            _ingest_certificate(
                session,
                upload,
                device=device,
                settings=settings,
                now_sec=now_sec,
                events=events,
                actor=actor,
                ip_address=ip_address,
            )
        )

    for upload in request.assessments:
        results.append(_ingest_assessment(session, upload, device=device))

    for upload in request.hazards:
        results.append(
            _ingest_hazard(
                session, upload, device=device, settings=settings, events=events
            )
        )

    for upload in request.progress:
        results.append(_ingest_progress(session, upload, device=device))

    accepted = sum(1 for r in results if r.status == "accepted")
    duplicates = sum(1 for r in results if r.status == "duplicate")
    quarantined = sum(1 for r in results if r.status == "quarantined")
    rejected = sum(1 for r in results if r.status == "rejected")

    batch = SyncBatch(
        id=str(uuid.uuid4()),
        device_id=request.device_id,
        client_batch_id=request.client_batch_id,
        item_count=request.item_count,
        accepted_count=accepted,
        rejected_count=rejected,
    )
    response = SyncBatchResponse(
        batch_id=batch.id,
        accepted=accepted,
        rejected=rejected,
        quarantined=quarantined,
        duplicates=duplicates,
        results=results,
        server_time_sec=now_sec,
        replayed=False,
    )
    batch.response_json = response.model_dump_json()
    session.add(batch)

    try:
        session.flush()
    except IntegrityError:
        # Two concurrent uploads of the same batch id. The unique constraint decided the winner;
        # fall back to replaying the stored response so both callers get a consistent answer.
        session.rollback()
        existing = find_existing_batch(session, request.device_id, request.client_batch_id)
        if existing is None:
            raise
        stored = json.loads(existing.response_json)
        stored["replayed"] = True
        stored["server_time_sec"] = _now_sec()
        return IngestSummary(
            response=SyncBatchResponse.model_validate(stored), pending_events=[]
        )

    audit.record(
        session,
        actor=actor,
        action=audit.ACTION_SYNC_BATCH,
        target_type="sync_batch",
        target_id=batch.id,
        detail=(
            f"items={request.item_count} accepted={accepted} duplicates={duplicates} "
            f"quarantined={quarantined} rejected={rejected}"
        ),
        outcome=audit.OUTCOME_FLAGGED if quarantined else audit.OUTCOME_SUCCESS,
        ip_address=ip_address,
        site_id=device.site_id,
    )

    events.append(
        (
            "sync.batch",
            device.site_id,
            {
                "device_id": device.id,
                "accepted": accepted,
                "duplicates": duplicates,
                "quarantined": quarantined,
                "rejected": rejected,
                "company_id": company_id,
            },
        )
    )
    return IngestSummary(response=response, pending_events=events)


# ---------------------------------------------------------------------------
# Per-kind ingest
# ---------------------------------------------------------------------------


def _ingest_certificate(
    session: Session,
    upload: CertificateUpload,
    *,
    device: Device,
    settings: Settings,
    now_sec: int,
    events: list[tuple[str, str | None, dict]],
    actor: str,
    ip_address: str | None,
) -> SyncItemResult:
    outcome = chain.ingest_certificate(
        session,
        qr_text=upload.qr_text,
        worker_id=upload.worker_id,
        device_id=device.id,
        key_epoch=upload.key_epoch,
        now_sec=now_sec,
        max_clock_skew_seconds=settings.max_clock_skew_seconds,
    )
    certificate = outcome.certificate

    if outcome.status == "accepted" and certificate is not None:
        audit.record(
            session,
            actor=actor,
            action=audit.ACTION_CERT_INGEST,
            target_type="certificate",
            target_id=certificate.id,
            detail=f"site={certificate.site_id} seq={certificate.seq}",
            ip_address=ip_address,
            site_id=certificate.site_id,
        )
        events.append(
            (
                "cert.issued",
                certificate.site_id,
                {
                    "certificate_id": certificate.id,
                    "seq": certificate.seq,
                    "worker_id": certificate.worker_id,
                    "module_code": certificate.module_code,
                    "score_permille": certificate.score_permille,
                    "hesitation": bool(certificate.outcome_flags & 0x02),
                },
            )
        )
    elif outcome.status == "quarantined" and certificate is not None:
        audit.record(
            session,
            actor=actor,
            action=audit.ACTION_CERT_QUARANTINE,
            outcome=audit.OUTCOME_FLAGGED,
            target_type="certificate",
            target_id=certificate.id,
            detail=outcome.reason,
            ip_address=ip_address,
            site_id=certificate.site_id,
        )
        events.append(
            (
                "cert.quarantined",
                certificate.site_id,
                {
                    "certificate_id": certificate.id,
                    "seq": certificate.seq,
                    "reason": outcome.reason,
                },
            )
        )
        events.append(
            (
                "chain.break",
                certificate.site_id,
                {"seq": certificate.seq, "reason": outcome.reason},
            )
        )

    return SyncItemResult(
        kind="certificate",
        ref_id=certificate.id if certificate is not None else upload.idempotency_key,
        idempotency_key=upload.idempotency_key,
        status=outcome.status,  # type: ignore[arg-type]
        reason=outcome.reason,
        retryable=outcome.retryable,
    )


def _ingest_assessment(
    session: Session, upload: AssessmentUpload, *, device: Device
) -> SyncItemResult:
    existing = session.get(AssessmentRun, upload.run_id)
    if existing is not None:
        return SyncItemResult(
            kind="assessment",
            ref_id=upload.run_id,
            idempotency_key=upload.idempotency_key,
            status="duplicate",
            reason="run already ingested",
        )

    if upload.site_id != device.site_id:
        return SyncItemResult(
            kind="assessment",
            ref_id=upload.run_id,
            idempotency_key=upload.idempotency_key,
            status="rejected",
            reason=(
                f"device {device.id} is registered to site {device.site_id} but the run claims "
                f"site {upload.site_id}"
            ),
        )

    if session.get(ModuleRecord, upload.module_id) is None:
        return SyncItemResult(
            kind="assessment",
            ref_id=upload.run_id,
            idempotency_key=upload.idempotency_key,
            status="rejected",
            reason=f"unknown module '{upload.module_id}'",
            # Retryable: the module catalog may simply not have been seeded yet.
            retryable=True,
        )

    if upload.finished_at_sec < upload.started_at_sec:
        return SyncItemResult(
            kind="assessment",
            ref_id=upload.run_id,
            idempotency_key=upload.idempotency_key,
            status="rejected",
            reason="finished_at_sec precedes started_at_sec",
        )

    session.add(
        AssessmentRun(
            id=upload.run_id,
            worker_id=upload.worker_id,
            site_id=upload.site_id,
            module_id=upload.module_id,
            module_code=upload.module_code,
            scenario_id=upload.scenario_id,
            catalog_version=upload.catalog_version,
            device_id=device.id,
            mode=upload.mode.value,
            presentation=upload.presentation.value,
            completion=upload.completion.value,
            score_permille=upload.score_permille,
            passed=upload.passed,
            hesitation_flag=upload.hesitation_flag,
            hesitation_ratio=upload.hesitation_ratio,
            median_latency_ms=upload.median_latency_ms,
            started_at_sec=upload.started_at_sec,
            finished_at_sec=upload.finished_at_sec,
            total_duration_ms=upload.total_duration_ms,
            steps_json=json.dumps([step.model_dump() for step in upload.steps]),
            failed_critical_steps_json=json.dumps(upload.failed_critical_step_ids),
            void_reason=upload.void_reason,
            abort_reason=upload.abort_reason,
            buddy_peer_device_id=upload.buddy_peer_device_id,
        )
    )
    return SyncItemResult(
        kind="assessment",
        ref_id=upload.run_id,
        idempotency_key=upload.idempotency_key,
        status="accepted",
    )


def _ingest_hazard(
    session: Session,
    upload: HazardUpload,
    *,
    device: Device,
    settings: Settings,
    events: list[tuple[str, str | None, dict]],
) -> SyncItemResult:
    existing = session.get(Hazard, upload.hazard_id)
    if existing is not None:
        return SyncItemResult(
            kind="hazard",
            ref_id=upload.hazard_id,
            idempotency_key=upload.idempotency_key,
            status="duplicate",
            reason="hazard already ingested",
        )

    if session.get(Site, upload.site_id) is None:
        return SyncItemResult(
            kind="hazard",
            ref_id=upload.hazard_id,
            idempotency_key=upload.idempotency_key,
            status="rejected",
            reason=f"unknown site '{upload.site_id}'",
            retryable=True,
        )

    try:
        result = hazard_service.create_hazard(
            session,
            hazard_id=upload.hazard_id,
            site_id=upload.site_id,
            category=HazardCategory(upload.category),
            severity=HazardSeverity(upload.severity),
            note=upload.note,
            reporter_worker_id=upload.reporter_worker_id,
            latitude=upload.latitude,
            longitude=upload.longitude,
            zone_label=upload.zone_label,
            ar_anchor_id=upload.ar_anchor_id,
            photo_media_id=upload.photo_media_id,
            voice_media_id=upload.voice_media_id,
            reported_at_sec=upload.reported_at_sec,
            device_id=device.id,
            rate_limit_per_hour=settings.hazard_reports_per_worker_per_hour,
            duplicate_window_seconds=settings.hazard_duplicate_window_seconds,
            duplicate_radius_metres=settings.hazard_duplicate_radius_metres,
            # Not enforced on the sync path: a phone that was offline for a week legitimately
            # arrives with a burst of reports, and dropping real hazards to satisfy a rate limit
            # would be exactly the wrong trade.
            enforce_rate_limit=False,
        )
    except hazard_service.HazardError as exc:
        return SyncItemResult(
            kind="hazard",
            ref_id=upload.hazard_id,
            idempotency_key=upload.idempotency_key,
            status="rejected",
            reason=str(exc),
        )

    events.append(
        (
            "hazard.created",
            upload.site_id,
            {
                "hazard_id": result.hazard.id,
                "category": result.hazard.category,
                "severity": result.hazard.severity,
                "is_duplicate": result.is_duplicate,
                "merged_into": result.merged_into.id if result.merged_into else None,
                "latitude": result.hazard.latitude,
                "longitude": result.hazard.longitude,
                "zone_label": result.hazard.zone_label,
            },
        )
    )
    return SyncItemResult(
        kind="hazard",
        ref_id=result.hazard.id,
        idempotency_key=upload.idempotency_key,
        status="accepted",
        reason=(
            f"clustered with existing report {result.merged_into.id}"
            if result.merged_into
            else None
        ),
    )


def _ingest_progress(
    session: Session, upload: ProgressUpload, *, device: Device
) -> SyncItemResult:
    if session.get(Worker, upload.worker_id) is None:
        # A progress row can legitimately arrive before the roster does, when the worker was
        # registered while the device was offline. Create a provisional record rather than
        # discarding real training history; bootstrap reconciles it later.
        session.add(
            Worker(
                id=upload.worker_id,
                site_id=upload.site_id,
                full_name=f"Unregistered ({upload.worker_id})",
                worker_id_hash=canonical.worker_id_hash(upload.worker_id),
                preferred_language="hi",
                pictogram_mode=False,
                active=True,
                provisional=True,
            )
        )
        session.flush()

    row = session.get(TrainingProgress, (upload.worker_id, upload.module_id))
    if row is None:
        # Every numeric field is initialised explicitly. A `mapped_column(default=...)` is applied
        # at flush time, not at construction, so a freshly built instance would carry None and the
        # recency comparison below would raise on `int < None`.
        row = TrainingProgress(
            worker_id=upload.worker_id,
            module_id=upload.module_id,
            site_id=upload.site_id,
            module_code=upload.module_code,
            base_score=0,
            last_pass_at_sec=0,
            certified_at_sec=0,
            refresher_stage=0,
            next_due_at_sec=0,
            consecutive_failures=0,
            attempts=0,
            best_score_permille=0,
            last_hesitation_flag=False,
        )
        session.add(row)

    # Last-writer-wins guarded by recency: an out-of-order relay from an older device state must
    # not roll a worker's progress backwards.
    if upload.last_pass_at_sec < row.last_pass_at_sec:
        return SyncItemResult(
            kind="progress",
            ref_id=f"{upload.worker_id}:{upload.module_id}",
            idempotency_key=upload.idempotency_key,
            status="duplicate",
            reason="a newer progress record is already stored",
        )

    row.site_id = upload.site_id
    row.module_code = upload.module_code
    row.base_score = upload.base_score
    row.last_pass_at_sec = upload.last_pass_at_sec
    row.certified_at_sec = upload.certified_at_sec
    row.refresher_stage = upload.refresher_stage
    row.next_due_at_sec = upload.next_due_at_sec
    row.consecutive_failures = upload.consecutive_failures
    row.attempts = max(row.attempts, upload.attempts)
    row.best_score_permille = max(row.best_score_permille, upload.best_score_permille)
    row.last_hesitation_flag = upload.last_hesitation_flag
    row.updated_at = utcnow()

    return SyncItemResult(
        kind="progress",
        ref_id=f"{upload.worker_id}:{upload.module_id}",
        idempotency_key=upload.idempotency_key,
        status="accepted",
    )


def resolve_provisional_workers(session: Session, site_id: str) -> int:
    """Link certificates that arrived before the worker roster did.

    Called at bootstrap. Matching is by ``worker_id_hash``, which is what the certificate carries,
    so it works without the QR ever having contained a plaintext identity.
    """
    unresolved = list(
        session.scalars(
            select(Certificate).where(
                Certificate.site_id == site_id,
                Certificate.worker_resolved.is_(False),
            )
        ).all()
    )
    if not unresolved:
        return 0

    workers = {
        worker.worker_id_hash: worker
        for worker in session.scalars(
            select(Worker).where(Worker.site_id == site_id)
        ).all()
    }

    resolved = 0
    for certificate in unresolved:
        worker = workers.get(certificate.worker_id_hash)
        if worker is None:
            continue
        certificate.worker_id = worker.id
        certificate.worker_resolved = True
        resolved += 1
    return resolved
