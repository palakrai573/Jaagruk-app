"""Certificate verification, listing, and chain integrity."""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import func, select

from app.api.deps import (
    ClientIpDep,
    DbDep,
    PaginationDep,
    ScopeDep,
    require_site_access,
)
from app.api.serializers import certificate_out, chain_head_out, empty_chain_head_out
from app.core import canonical, crypto
from app.db.base import utcnow
from app.models import (
    Certificate,
    CertificateStatus,
    ChainHead,
    ModuleRecord,
    TrainingProgress,
    Worker,
)
from app.schemas import (
    CertificateOut,
    ChainAuditResponse,
    ChainHeadOut,
    Page,
    VerifyRequest,
    VerifyResponse,
)
from app.services import audit, chain as chain_service
from app.services import readiness as readiness_service

logger = logging.getLogger("jaagruk.certificates")

router = APIRouter(tags=["certificates"])


@router.post(
    "/certificates/verify",
    response_model=VerifyResponse,
    summary="Verify a scanned certificate QR",
)
def verify_certificate(
    payload: VerifyRequest,
    db: DbDep,
    scope: ScopeDep,
    ip: ClientIpDep,
) -> VerifyResponse:
    """Verify exactly what a physical scanner would see.

    The server takes the raw QR text and runs the same checks the app performs offline — decode,
    signature against every key epoch the site has ever had, then chain linkage. It returns a
    status rather than a boolean, because "signature valid but I hold no chain copy for this site"
    is a legitimate partial result and reporting it as "invalid" would train inspectors to ignore
    the tool.

    Also returns current readiness alongside statutory validity, so an inspector sees both "this
    certification is legally current" and "this worker's retention has decayed" as separate facts.
    """
    result = chain_service.verify_qr(db, payload.qr_text)

    audit.record(
        db,
        actor=scope.username,
        actor_role=scope.role.value,
        action=audit.ACTION_CERT_VERIFY,
        outcome=(
            audit.OUTCOME_SUCCESS
            if result.status.is_trustworthy
            else audit.OUTCOME_FLAGGED
        ),
        target_type="certificate",
        target_id=(
            f"{result.signed.attestation.site_id}#{result.signed.attestation.seq}"
            if result.signed
            else None
        ),
        detail=f"status={result.status.value}",
        ip_address=ip,
        site_id=result.signed.attestation.site_id if result.signed else None,
    )

    if result.signed is None:
        return VerifyResponse(
            status=result.status.value,
            trustworthy=False,
            indicates_tampering=result.status.indicates_tampering,
            reasons=result.reasons,
        )

    attestation = result.signed.attestation
    module = db.scalar(
        select(ModuleRecord).where(ModuleRecord.module_code == attestation.module_code)
    )

    worker = db.scalar(
        select(Worker).where(Worker.worker_id_hash == attestation.worker_id_hash)
    )
    worker_matches: bool | None = None
    if payload.candidate_worker_id:
        worker_matches = crypto.constant_time_equals(
            attestation.worker_id_hash,
            canonical.worker_id_hash(payload.candidate_worker_id),
        )

    now_sec = int(utcnow().timestamp())
    statutory_expiry = readiness_service.statutory_expiry_sec(attestation.issued_at_epoch_sec)
    statutory_valid = now_sec < statutory_expiry

    readiness = None
    band = None
    if worker is not None and module is not None:
        progress = db.get(TrainingProgress, (worker.id, module.id))
        if progress is not None:
            assessment = readiness_service.evaluate_progress(progress, now_sec)
            readiness = assessment.readiness_permille
            band = assessment.band.value

    return VerifyResponse(
        status=result.status.value,
        trustworthy=result.status.is_trustworthy,
        indicates_tampering=result.status.indicates_tampering,
        reasons=result.reasons,
        site_id=attestation.site_id,
        seq=attestation.seq,
        module_code=attestation.module_code,
        module_title_en=module.title_en if module else None,
        score_permille=attestation.score_permille,
        median_latency_ms=attestation.median_latency_ms,
        outcome_flags=attestation.outcome_flags,
        flag_names=canonical.describe_flags(attestation.outcome_flags),
        issued_at_sec=attestation.issued_at_epoch_sec,
        statutory_valid=statutory_valid,
        statutory_expiry_sec=statutory_expiry,
        readiness_permille=readiness,
        readiness_band=band,
        worker_id_matches=worker_matches,
        worker_full_name=worker.full_name if worker is not None else None,
        record_hash_hex=result.signed.record_hash.hex(),
        prev_record_hash_hex=attestation.prev_record_hash.hex(),
    )


@router.get(
    "/certificates", response_model=Page[CertificateOut], summary="Paginated certificate ledger"
)
def list_certificates(
    db: DbDep,
    scope: ScopeDep,
    page: PaginationDep,
    site_id: Annotated[str | None, Query()] = None,
    worker_id: Annotated[str | None, Query()] = None,
    module_code: Annotated[int | None, Query(ge=1, le=255)] = None,
    only_quarantined: Annotated[bool, Query()] = False,
) -> Page[CertificateOut]:
    statement = select(Certificate)
    statement = scope.restrict(statement, Certificate.site_id)
    count_statement = select(func.count()).select_from(Certificate)
    count_statement = scope.restrict(count_statement, Certificate.site_id)

    if site_id:
        statement = statement.where(Certificate.site_id == site_id)
        count_statement = count_statement.where(Certificate.site_id == site_id)
    if worker_id:
        statement = statement.where(Certificate.worker_id == worker_id)
        count_statement = count_statement.where(Certificate.worker_id == worker_id)
    if module_code is not None:
        statement = statement.where(Certificate.module_code == module_code)
        count_statement = count_statement.where(Certificate.module_code == module_code)
    if only_quarantined:
        statement = statement.where(
            Certificate.status == CertificateStatus.QUARANTINED.value
        )
        count_statement = count_statement.where(
            Certificate.status == CertificateStatus.QUARANTINED.value
        )

    total = db.scalar(count_statement) or 0
    rows = list(
        db.scalars(
            statement.order_by(Certificate.site_id, Certificate.seq.desc())
            .offset(page.offset)
            .limit(page.page_size)
        ).all()
    )

    workers = {
        worker.id: worker
        for worker in db.scalars(
            select(Worker).where(
                Worker.id.in_([r.worker_id for r in rows if r.worker_id])
            )
        ).all()
    } if rows else {}
    modules = {
        module.module_code: module
        for module in db.scalars(
            select(ModuleRecord).where(
                ModuleRecord.module_code.in_([r.module_code for r in rows])
            )
        ).all()
    } if rows else {}

    return Page[CertificateOut](
        items=[
            certificate_out(
                row,
                workers.get(row.worker_id) if row.worker_id else None,
                modules.get(row.module_code),
            )
            for row in rows
        ],
        total=total,
        page=page.page,
        page_size=page.page_size,
    )


@router.get(
    "/chains/{site_id}", response_model=ChainHeadOut, summary="A site's chain head and any gaps"
)
def get_chain_head(site_id: str, db: DbDep, scope: ScopeDep) -> ChainHeadOut:
    site = require_site_access(db, scope, site_id)
    head = db.get(ChainHead, site.id)
    if head is None:
        return empty_chain_head_out(site.id)
    return chain_head_out(head, chain_service.missing_sequences(db, site.id))


@router.post(
    "/chains/{site_id}/verify",
    response_model=ChainAuditResponse,
    summary="Walk a site's whole ledger end to end",
)
def audit_chain(
    site_id: str,
    db: DbDep,
    scope: ScopeDep,
    ip: ClientIpDep,
) -> ChainAuditResponse:
    """Verify every record for a site in sequence.

    Stops at the first break, because everything after an unexplained break is untrustworthy
    anyway and continuing would only produce noise around one root cause.
    """
    site = require_site_access(db, scope, site_id)
    result = chain_service.audit_chain(db, site.id)

    audit.record(
        db,
        actor=scope.username,
        actor_role=scope.role.value,
        action=audit.ACTION_CHAIN_AUDIT,
        outcome=audit.OUTCOME_SUCCESS if result.clean else audit.OUTCOME_FLAGGED,
        target_type="site",
        target_id=site.id,
        detail=f"status={result.status.value} checked={result.records_checked}",
        ip_address=ip,
        site_id=site.id,
    )

    return ChainAuditResponse(
        site_id=result.site_id,
        records_checked=result.records_checked,
        status=result.status.value,
        clean=result.clean,
        first_problem_seq=result.first_problem_seq,
        reasons=result.reasons,
        quarantined_seqs=result.quarantined_seqs,
    )


@router.get(
    "/certificates/{certificate_id}",
    response_model=CertificateOut,
    summary="One certificate",
)
def get_certificate(certificate_id: str, db: DbDep, scope: ScopeDep) -> CertificateOut:
    certificate = db.get(Certificate, certificate_id)
    if certificate is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Certificate '{certificate_id}' was not found.",
        )
    visible = scope.visible_site_ids(db)
    if visible is not None and certificate.site_id not in visible:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Certificate '{certificate_id}' was not found.",
        )

    worker = db.get(Worker, certificate.worker_id) if certificate.worker_id else None
    module = db.scalar(
        select(ModuleRecord).where(ModuleRecord.module_code == certificate.module_code)
    )
    return certificate_out(certificate, worker, module)
