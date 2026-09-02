"""Statutory report export.

Formatted for the two Acts the problem statement names, because an inspector's question is not
"what is the mean readiness" but "show me, per worker, that periodic certification happened inside
the last twelve months, and prove the record has not been altered".

Streamed row by row so a large export keeps memory flat, and hard-capped with the cap declared in
the file's own header — a truncated export must never be mistakable for a complete one.
"""

from __future__ import annotations

import csv
import io
from collections.abc import Iterator
from datetime import datetime, timezone
from enum import Enum

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core import canonical
from app.db.base import utcnow
from app.models import (
    Certificate,
    CertificateStatus,
    Hazard,
    ModuleRecord,
    Site,
    TrainingProgress,
    Worker,
)
from app.services import readiness as readiness_service
from app.services.scope import AccessScope


class StatutoryAct(str, Enum):
    MINES = "mines"
    FACTORIES = "factories"
    BOTH = "both"

    @property
    def title(self) -> str:
        return {
            StatutoryAct.MINES: "Mines Act 1952",
            StatutoryAct.FACTORIES: "Factories Act 1948",
            StatutoryAct.BOTH: "Mines Act 1952 and Factories Act 1948",
        }[self]


def _iso(epoch_sec: int) -> str:
    if epoch_sec <= 0:
        return ""
    return datetime.fromtimestamp(epoch_sec, tz=timezone.utc).strftime("%Y-%m-%d")


def _writer() -> tuple[io.StringIO, csv.writer]:
    buffer = io.StringIO()
    return buffer, csv.writer(buffer, lineterminator="\n")


def _flush(buffer: io.StringIO) -> str:
    value = buffer.getvalue()
    buffer.seek(0)
    buffer.truncate(0)
    return value


def stream_certification_report(
    session: Session,
    scope: AccessScope,
    *,
    site_id: str | None,
    from_sec: int,
    to_sec: int,
    act: StatutoryAct,
    max_rows: int,
) -> Iterator[str]:
    """Per-worker, per-module certification status.

    Every row carries the certificate's ``record_hash`` prefix so an inspector can tie the line
    back to a specific ledger entry and re-verify it independently. A compliance report that cannot
    be traced to the evidence behind it is just a claim.
    """
    now_sec = int(utcnow().timestamp())
    site_ids = scope.visible_site_ids(session)

    buffer, writer = _writer()

    writer.writerow(["# Jaagruk statutory certification report"])
    writer.writerow(["# Act", act.title])
    writer.writerow(["# Generated (UTC)", utcnow().strftime("%Y-%m-%d %H:%M:%S")])
    writer.writerow(["# Period", f"{_iso(from_sec)} to {_iso(to_sec)}"])
    writer.writerow(["# Requested by", f"{scope.username} ({scope.role.value})"])
    writer.writerow(["# Row cap", str(max_rows)])
    writer.writerow(
        [
            "# Note",
            "readiness_permille is an operational retention measure and is separate from "
            "statutory validity; a certificate can be statutorily valid while operationally stale",
        ]
    )
    writer.writerow([])
    writer.writerow(
        [
            "site_id",
            "site_name",
            "district",
            "worker_id",
            "worker_name",
            "module_id",
            "module_title",
            "statutory_reference",
            "certified_on",
            "statutory_expiry",
            "statutory_valid",
            "days_to_expiry",
            "best_score_percent",
            "readiness_percent",
            "readiness_band",
            "required_action",
            "attempts",
            "hesitation_flagged",
            "certificate_seq",
            "certificate_record_hash_prefix",
            "certificate_status",
            "conducted_in_site_scanned_ar",
            "buddy_drill",
        ]
    )
    yield _flush(buffer)

    query = (
        select(TrainingProgress, Worker, Site, ModuleRecord)
        .join(Worker, Worker.id == TrainingProgress.worker_id)
        .join(Site, Site.id == TrainingProgress.site_id)
        .join(ModuleRecord, ModuleRecord.id == TrainingProgress.module_id)
        .order_by(TrainingProgress.site_id, TrainingProgress.worker_id, ModuleRecord.module_code)
    )
    if site_ids is not None:
        if not site_ids:
            yield _flush(buffer)
            return
        query = query.where(TrainingProgress.site_id.in_(site_ids))
    if site_id:
        query = query.where(TrainingProgress.site_id == site_id)
    if act is StatutoryAct.MINES:
        query = query.where(Site.sector == "coal_mine")
    elif act is StatutoryAct.FACTORIES:
        query = query.where(Site.sector.in_(["steel_plant", "mica_processing"]))

    emitted = 0
    for progress, worker, site, module in session.execute(query).all():
        if emitted >= max_rows:
            writer.writerow(
                [
                    f"# TRUNCATED at the {max_rows}-row cap. Narrow the date range or filter by "
                    "site to export the remainder."
                ]
            )
            yield _flush(buffer)
            return

        if progress.certified_at_sec and not (
            from_sec <= progress.certified_at_sec <= to_sec
        ):
            continue

        assessment = readiness_service.evaluate_progress(progress, now_sec)
        certificate = session.scalar(
            select(Certificate)
            .where(
                Certificate.worker_id == worker.id,
                Certificate.module_code == progress.module_code,
            )
            .order_by(Certificate.seq.desc())
            .limit(1)
        )

        writer.writerow(
            [
                site.id,
                site.name,
                site.district,
                worker.id,
                worker.full_name,
                module.id,
                module.title_en,
                module.statutory_reference,
                _iso(progress.certified_at_sec),
                _iso(assessment.statutory_expiry_sec),
                "yes" if assessment.statutory_valid else "no",
                assessment.days_until_statutory_expiry,
                round(progress.best_score_permille / 10.0, 1),
                round(assessment.readiness_permille / 10.0, 1),
                assessment.band.value,
                assessment.required_action.value,
                progress.attempts,
                "yes" if progress.last_hesitation_flag else "no",
                certificate.seq if certificate else "",
                certificate.record_hash.hex()[:16] if certificate else "",
                certificate.status if certificate else "not_issued",
                (
                    "yes"
                    if certificate
                    and certificate.outcome_flags & canonical.FLAG_SITE_SCANNED_AR
                    else "no"
                ),
                (
                    "yes"
                    if certificate and certificate.outcome_flags & canonical.FLAG_BUDDY_DRILL
                    else "no"
                ),
            ]
        )
        emitted += 1
        if emitted % 200 == 0:
            yield _flush(buffer)

    if emitted == 0:
        writer.writerow(["# No certification records matched the requested filters."])
    yield _flush(buffer)


def stream_hazard_report(
    session: Session,
    scope: AccessScope,
    *,
    site_id: str | None,
    from_sec: int,
    to_sec: int,
    max_rows: int,
) -> Iterator[str]:
    """Near-miss and unsafe-condition log.

    Gives DGMS something it currently has no source for: ground-level hazard reporting from the
    workforce, rather than only after-the-fact accident investigation.
    """
    site_ids = scope.visible_site_ids(session)
    buffer, writer = _writer()

    writer.writerow(["# Jaagruk hazard and near-miss report"])
    writer.writerow(["# Generated (UTC)", utcnow().strftime("%Y-%m-%d %H:%M:%S")])
    writer.writerow(["# Period", f"{_iso(from_sec)} to {_iso(to_sec)}"])
    writer.writerow(["# Requested by", f"{scope.username} ({scope.role.value})"])
    writer.writerow(["# Row cap", str(max_rows)])
    writer.writerow([])
    writer.writerow(
        [
            "hazard_id",
            "site_id",
            "site_name",
            "reported_on",
            "category",
            "severity",
            "status",
            "reporter",
            "zone_label",
            "latitude",
            "longitude",
            "corroborating_reports",
            "has_photo",
            "has_voice_note",
            "resolution_note",
            "note",
        ]
    )
    yield _flush(buffer)

    query = (
        select(Hazard, Site)
        .join(Site, Site.id == Hazard.site_id)
        .where(
            Hazard.reported_at_sec >= from_sec,
            Hazard.reported_at_sec <= to_sec,
            Hazard.duplicate_of_id.is_(None),
        )
        .order_by(Hazard.reported_at_sec.desc())
    )
    if site_ids is not None:
        if not site_ids:
            yield _flush(buffer)
            return
        query = query.where(Hazard.site_id.in_(site_ids))
    if site_id:
        query = query.where(Hazard.site_id == site_id)

    emitted = 0
    for hazard, site in session.execute(query).all():
        if emitted >= max_rows:
            writer.writerow([f"# TRUNCATED at the {max_rows}-row cap."])
            yield _flush(buffer)
            return
        writer.writerow(
            [
                hazard.id,
                site.id,
                site.name,
                _iso(hazard.reported_at_sec),
                hazard.category,
                hazard.severity,
                hazard.status,
                hazard.reporter_label,
                hazard.zone_label or "",
                hazard.latitude if hazard.latitude is not None else "",
                hazard.longitude if hazard.longitude is not None else "",
                hazard.duplicate_count,
                "yes" if hazard.photo_media_id else "no",
                "yes" if hazard.voice_media_id else "no",
                (hazard.resolution_note or "").replace("\n", " "),
                (hazard.note or "").replace("\n", " "),
            ]
        )
        emitted += 1
        if emitted % 200 == 0:
            yield _flush(buffer)

    if emitted == 0:
        writer.writerow(["# No hazard reports matched the requested filters."])
    yield _flush(buffer)


def stream_chain_report(
    session: Session,
    scope: AccessScope,
    *,
    site_id: str,
    max_rows: int,
) -> Iterator[str]:
    """The raw certificate ledger for one site, in sequence order.

    Exists so an auditor can re-verify the chain themselves with nothing but this file and the
    site's public key. A tamper-evidence claim that only our own software can check is not worth
    much.
    """
    buffer, writer = _writer()

    writer.writerow(["# Jaagruk certificate ledger"])
    writer.writerow(["# Site", site_id])
    writer.writerow(["# Generated (UTC)", utcnow().strftime("%Y-%m-%d %H:%M:%S")])
    writer.writerow(["# Requested by", f"{scope.username} ({scope.role.value})"])
    writer.writerow(
        [
            "# Verification",
            "record_hash = SHA-256(canonical_bytes || signature); each row's prev_record_hash "
            "must equal the previous row's record_hash, and row 1 must be all zeroes",
        ]
    )
    writer.writerow([])
    writer.writerow(
        [
            "seq",
            "key_epoch",
            "worker_id",
            "worker_id_hash",
            "module_code",
            "score_permille",
            "median_latency_ms",
            "outcome_flags",
            "flag_names",
            "issued_at_utc",
            "prev_record_hash",
            "record_hash",
            "signature",
            "status",
            "quarantine_reason",
            "device_id",
        ]
    )
    yield _flush(buffer)

    visible = scope.visible_site_ids(session)
    if visible is not None and site_id not in visible:
        # Reported as empty rather than as a 403, so the export cannot be used to discover which
        # site ids exist.
        writer.writerow(["# No ledger available for the requested site."])
        yield _flush(buffer)
        return

    records = session.scalars(
        select(Certificate)
        .where(Certificate.site_id == site_id)
        .order_by(Certificate.seq.asc())
        .limit(max_rows)
    ).all()

    emitted = 0
    for record in records:
        writer.writerow(
            [
                record.seq,
                record.key_epoch,
                record.worker_id or "",
                record.worker_id_hash.hex(),
                record.module_code,
                record.score_permille,
                record.median_latency_ms,
                record.outcome_flags,
                "|".join(canonical.describe_flags(record.outcome_flags)),
                datetime.fromtimestamp(record.issued_at_sec, tz=timezone.utc).isoformat(),
                record.prev_record_hash.hex(),
                record.record_hash.hex(),
                record.signature.hex(),
                record.status,
                record.quarantine_reason or "",
                record.device_id or "",
            ]
        )
        emitted += 1
        if emitted % 200 == 0:
            yield _flush(buffer)

    if emitted == 0:
        writer.writerow(["# This site has issued no certificates yet."])
    elif emitted >= max_rows:
        writer.writerow([f"# TRUNCATED at the {max_rows}-row cap."])
    yield _flush(buffer)


def filename_for(kind: str, site_id: str | None, from_sec: int, to_sec: int) -> str:
    scope_part = site_id or "all-sites"
    return f"jaagruk-{kind}-{scope_part}-{_iso(from_sec)}-to-{_iso(to_sec)}.csv"
