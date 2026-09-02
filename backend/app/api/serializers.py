"""ORM row to response schema conversion.

Kept in one place so the wire format is consistent: hex for binary, ISO-8601 with an explicit
offset for timestamps, and epoch seconds wherever the value also has to be arithmetic on a device.
"""

from __future__ import annotations

import json
from datetime import datetime

from sqlalchemy.orm import Session

from app.core import canonical
from app.db.base import to_utc_iso
from app.models import (
    Certificate,
    ChainHead,
    Device,
    Hazard,
    ModuleRecord,
    Site,
    SiteKey,
    TrainingProgress,
    Worker,
)
from app.schemas import (
    CertificateOut,
    ChainHeadOut,
    DeviceOut,
    HazardOut,
    ModuleOut,
    ModuleReadinessOut,
    SiteKeyOut,
    SiteOut,
    WorkerOut,
)
from app.services import hazards as hazard_service
from app.services import readiness as readiness_service


def iso_or_none(value: datetime | None) -> str | None:
    """Always UTC with an explicit offset.

    SQLite returns naive datetimes and PostgreSQL returns aware ones. Emitting whatever the driver
    happened to give us would make the same record serialise differently on the two backends, and
    would make a client's round-tripped timestamp fail an optimistic-concurrency check.
    """
    return to_utc_iso(value)


def site_out(site: Site) -> SiteOut:
    return SiteOut(
        id=site.id,
        company_id=site.company_id,
        name=site.name,
        district=site.district,
        sector=site.sector,
        ar_scanned=site.ar_scanned,
        ar_anchor_count=site.ar_anchor_count,
        latitude=site.latitude,
        longitude=site.longitude,
        active=site.active,
    )


def site_key_out(key: SiteKey) -> SiteKeyOut:
    return SiteKeyOut(
        epoch=key.epoch,
        public_key_hex=key.public_key.hex(),
        active=key.active,
        registered_at_iso=to_utc_iso(key.created_at) or "",
        revoked_at_iso=iso_or_none(key.revoked_at),
        revocation_reason=key.revocation_reason,
    )


def device_out(device: Device) -> DeviceOut:
    return DeviceOut(
        id=device.id,
        site_id=device.site_id,
        model=device.model,
        android_release=device.android_release,
        app_version=device.app_version,
        active=device.active,
        last_seen_at_iso=iso_or_none(device.last_seen_at),
        last_sync_at_iso=iso_or_none(device.last_sync_at),
    )


def module_out(module: ModuleRecord) -> ModuleOut:
    try:
        sectors = json.loads(module.sectors_json)
        if not isinstance(sectors, list):
            sectors = []
    except (json.JSONDecodeError, TypeError):
        sectors = []
    return ModuleOut(
        id=module.id,
        module_code=module.module_code,
        catalog_version=module.catalog_version,
        title_key=module.title_key,
        title_en=module.title_en,
        description_key=module.description_key,
        statutory_reference=module.statutory_reference,
        estimated_minutes=module.estimated_minutes,
        supports_buddy_drill=module.supports_buddy_drill,
        fully_implemented=module.fully_implemented,
        enabled=module.enabled,
        sectors=[str(s) for s in sectors],
    )


def module_readiness_out(
    progress: TrainingProgress,
    module: ModuleRecord | None,
    now_sec: int,
) -> ModuleReadinessOut:
    assessment = readiness_service.evaluate_progress(progress, now_sec)
    return ModuleReadinessOut(
        module_id=progress.module_id,
        module_code=progress.module_code,
        module_title_en=module.title_en if module else progress.module_id,
        attempts=progress.attempts,
        best_score_permille=progress.best_score_permille,
        base_score_permille=progress.base_score,
        readiness_permille=assessment.readiness_permille,
        readiness_band=assessment.band.value,
        statutory_valid=assessment.statutory_valid,
        days_until_statutory_expiry=assessment.days_until_statutory_expiry,
        required_action=assessment.required_action.value,
        refresher_due=assessment.refresher_due,
        next_due_at_sec=progress.next_due_at_sec,
        last_pass_at_sec=progress.last_pass_at_sec,
        certified_at_sec=progress.certified_at_sec,
        hesitation_flagged=progress.last_hesitation_flag,
    )


def worker_out(
    worker: Worker,
    progress_rows: list[TrainingProgress],
    now_sec: int,
) -> WorkerOut:
    assessments = [
        readiness_service.evaluate_progress(row, now_sec) for row in progress_rows
    ]
    readiness_values = [a.readiness_permille for a in assessments]
    return WorkerOut(
        id=worker.id,
        site_id=worker.site_id,
        full_name=worker.full_name,
        preferred_language=worker.preferred_language,
        pictogram_mode=worker.pictogram_mode,
        active=worker.active,
        provisional=worker.provisional,
        overall_readiness_permille=(
            round(sum(readiness_values) / len(readiness_values)) if readiness_values else 0
        ),
        modules_certified=sum(1 for a in assessments if a.statutory_valid),
        modules_due=sum(1 for a in assessments if a.refresher_due),
        hesitation_flagged=any(row.last_hesitation_flag for row in progress_rows),
    )


def certificate_out(
    certificate: Certificate,
    worker: Worker | None = None,
    module: ModuleRecord | None = None,
) -> CertificateOut:
    return CertificateOut(
        id=certificate.id,
        site_id=certificate.site_id,
        seq=certificate.seq,
        key_epoch=certificate.key_epoch,
        worker_id=certificate.worker_id,
        worker_full_name=worker.full_name if worker else None,
        module_code=certificate.module_code,
        module_title_en=module.title_en if module else None,
        score_permille=certificate.score_permille,
        median_latency_ms=certificate.median_latency_ms,
        outcome_flags=certificate.outcome_flags,
        flag_names=canonical.describe_flags(certificate.outcome_flags),
        issued_at_sec=certificate.issued_at_sec,
        status=certificate.status,
        quarantine_reason=certificate.quarantine_reason,
        clock_skew_flagged=certificate.clock_skew_flagged,
        record_hash_hex=certificate.record_hash.hex(),
        prev_record_hash_hex=certificate.prev_record_hash.hex(),
        qr_text=certificate.qr_text,
        device_id=certificate.device_id,
    )


def chain_head_out(head: ChainHead, missing: list[int]) -> ChainHeadOut:
    return ChainHeadOut(
        site_id=head.site_id,
        last_seq=head.last_seq,
        last_record_hash_hex=head.last_record_hash.hex(),
        certificate_count=head.certificate_count,
        quarantined_count=head.quarantined_count,
        updated_at_iso=iso_or_none(head.updated_at),
        missing_sequences=missing,
    )


def empty_chain_head_out(site_id: str) -> ChainHeadOut:
    return ChainHeadOut(
        site_id=site_id,
        last_seq=0,
        last_record_hash_hex=canonical.ZERO_HASH.hex(),
        certificate_count=0,
        quarantined_count=0,
        updated_at_iso=None,
        missing_sequences=[],
    )


def hazard_out(hazard: Hazard, session: Session | None = None) -> HazardOut:
    site_name: str | None = None
    if session is not None:
        site = session.get(Site, hazard.site_id)
        site_name = site.name if site is not None else None
    return HazardOut(
        id=hazard.id,
        site_id=hazard.site_id,
        site_name=site_name,
        reporter_worker_id=hazard.reporter_worker_id,
        reporter_label=hazard.reporter_label,
        category=hazard.category,
        severity=hazard.severity,
        note=hazard.note,
        latitude=hazard.latitude,
        longitude=hazard.longitude,
        zone_label=hazard.zone_label,
        ar_anchor_id=hazard.ar_anchor_id,
        photo_media_id=hazard.photo_media_id,
        voice_media_id=hazard.voice_media_id,
        status=hazard.status,
        duplicate_of_id=hazard.duplicate_of_id,
        duplicate_count=hazard.duplicate_count,
        reported_at_sec=hazard.reported_at_sec,
        created_at_iso=to_utc_iso(hazard.created_at) or "",
        updated_at_iso=to_utc_iso(hazard.updated_at) or "",
        resolved_at_iso=iso_or_none(hazard.resolved_at),
        resolution_note=hazard.resolution_note,
        allowed_next_statuses=hazard_service.allowed_next_statuses(hazard),
    )
