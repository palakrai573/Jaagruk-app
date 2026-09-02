"""Certificate verification and hash-chain integrity, server side.

Mirrors ``core/src/main/kotlin/org/jaagruk/core/crypto/ChainVerifier.kt``, including its refusal to
collapse everything into a boolean. "Signature valid but I hold no chain copy" is the normal state
for a DGMS inspector visiting a site for the first time, and reporting that as "invalid" would
train inspectors to ignore the tool.

The other principle enforced here: a certificate that fails chain verification is **stored with a
quarantine flag**, not discarded. Deleting the evidence of tampering would defeat the entire point
of keeping a chain.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass, field
from enum import Enum

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.core import canonical, crypto
from app.db.base import utcnow
from app.models import Certificate, CertificateStatus, ChainHead, SiteKey

logger = logging.getLogger("jaagruk.chain")


class ChainStatus(str, Enum):
    VERIFIED = "verified"
    SIGNATURE_VALID_CHAIN_UNKNOWN = "signature_valid_chain_unknown"
    BROKEN_LINK = "broken_link"
    SEQUENCE_GAP = "sequence_gap"
    BAD_SIGNATURE = "bad_signature"
    UNKNOWN_SITE_KEY = "unknown_site_key"
    MALFORMED = "malformed"

    @property
    def is_trustworthy(self) -> bool:
        return self in (ChainStatus.VERIFIED, ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN)

    @property
    def indicates_tampering(self) -> bool:
        return self in (ChainStatus.BROKEN_LINK, ChainStatus.BAD_SIGNATURE)


@dataclass(slots=True)
class VerificationResult:
    status: ChainStatus
    signed: canonical.SignedAttestation | None = None
    reasons: list[str] = field(default_factory=list)
    matched_key_epoch: int | None = None


def load_site_public_keys(session: Session, site_id: str) -> list[SiteKey]:
    """All key epochs for a site, newest first.

    Every epoch is tried, not just the active one, because a certificate issued under a previous
    key must stay verifiable forever. Rotating a key is not a reason to invalidate history.
    """
    return list(
        session.scalars(
            select(SiteKey).where(SiteKey.site_id == site_id).order_by(SiteKey.epoch.desc())
        ).all()
    )


def verify_signature_any_epoch(
    session: Session,
    signed: canonical.SignedAttestation,
) -> tuple[bool, int | None, list[SiteKey]]:
    """Verify against every key epoch the site has ever had."""
    keys = load_site_public_keys(session, signed.attestation.site_id)
    if not keys:
        return False, None, []

    message = canonical.canonical_bytes(signed.attestation)
    expected_record_hash = canonical.record_hash(message, signed.signature)
    # Checking the record hash as well as the signature stops a caller carrying a doctored
    # record_hash and redirecting where the chain points.
    if not crypto.constant_time_equals(expected_record_hash, signed.record_hash):
        return False, None, keys

    for key in keys:
        if crypto.verify(key.public_key, message, signed.signature):
            return True, key.epoch, keys
    return False, None, keys


def verify_qr(session: Session, qr_text: str) -> VerificationResult:
    """Full offline-equivalent verification of a scanned certificate."""
    try:
        signed = canonical.decode_qr(qr_text)
    except canonical.CanonicalFormatError as exc:
        return VerificationResult(
            status=ChainStatus.MALFORMED,
            reasons=[str(exc)],
        )
    return verify_signed(session, signed)


def verify_signed(
    session: Session,
    signed: canonical.SignedAttestation,
) -> VerificationResult:
    attestation = signed.attestation
    site_id = attestation.site_id
    reasons: list[str] = []

    signature_ok, epoch, keys = verify_signature_any_epoch(session, signed)
    if not keys:
        return VerificationResult(
            status=ChainStatus.UNKNOWN_SITE_KEY,
            signed=signed,
            reasons=[
                f"no public key held for site {site_id}; register the site's supervisor device "
                "before verifying its certificates"
            ],
        )
    if not signature_ok:
        return VerificationResult(
            status=ChainStatus.BAD_SIGNATURE,
            signed=signed,
            reasons=[
                f"signature does not match any of site {site_id}'s "
                f"{len(keys)} key epoch(s): the certificate was altered or was not issued by "
                "this site"
            ],
        )
    reasons.append(f"Ed25519 signature valid for site {site_id} (key epoch {epoch})")

    head = session.get(ChainHead, site_id)
    if head is None or head.last_seq == 0:
        reasons.append(
            f"no chain records held for site {site_id}, so linkage was not cross-checked"
        )
        return VerificationResult(
            status=ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN,
            signed=signed,
            reasons=reasons,
            matched_key_epoch=epoch,
        )

    existing = session.scalar(
        select(Certificate).where(
            Certificate.site_id == site_id, Certificate.seq == attestation.seq
        )
    )
    if existing is not None and not crypto.constant_time_equals(
        existing.record_hash, signed.record_hash
    ):
        reasons.append(
            f"site {site_id} already holds a different record at seq {attestation.seq} "
            f"({existing.record_hash.hex()[:12]}.. vs {signed.record_hash.hex()[:12]}..)"
        )
        return VerificationResult(
            status=ChainStatus.BROKEN_LINK,
            signed=signed,
            reasons=reasons,
            matched_key_epoch=epoch,
        )

    if attestation.is_genesis:
        reasons.append(f"genesis record for site {site_id}")
        return VerificationResult(
            status=ChainStatus.VERIFIED,
            signed=signed,
            reasons=reasons,
            matched_key_epoch=epoch,
        )

    predecessor_seq = attestation.seq - 1
    predecessor = session.scalar(
        select(Certificate).where(
            Certificate.site_id == site_id, Certificate.seq == predecessor_seq
        )
    )

    if predecessor is None:
        if predecessor_seq > head.last_seq:
            reasons.append(
                f"certificate seq {attestation.seq} is ahead of the newest record held for "
                f"site {site_id} (seq {head.last_seq}); sync to cross-check linkage"
            )
            return VerificationResult(
                status=ChainStatus.SIGNATURE_VALID_CHAIN_UNKNOWN,
                signed=signed,
                reasons=reasons,
                matched_key_epoch=epoch,
            )
        reasons.append(
            f"record at seq {predecessor_seq} is missing from site {site_id}'s chain even "
            f"though seq {head.last_seq} is held"
        )
        return VerificationResult(
            status=ChainStatus.SEQUENCE_GAP,
            signed=signed,
            reasons=reasons,
            matched_key_epoch=epoch,
        )

    if not crypto.constant_time_equals(
        predecessor.record_hash, attestation.prev_record_hash
    ):
        reasons.append(
            f"certificate at seq {attestation.seq} points at "
            f"{attestation.prev_record_hash.hex()[:12]}.. but site {site_id}'s record at seq "
            f"{predecessor_seq} hashes to {predecessor.record_hash.hex()[:12]}.."
        )
        return VerificationResult(
            status=ChainStatus.BROKEN_LINK,
            signed=signed,
            reasons=reasons,
            matched_key_epoch=epoch,
        )

    reasons.append(f"links correctly to seq {predecessor_seq}")
    return VerificationResult(
        status=ChainStatus.VERIFIED,
        signed=signed,
        reasons=reasons,
        matched_key_epoch=epoch,
    )


# ---------------------------------------------------------------------------
# Chain head maintenance
# ---------------------------------------------------------------------------


def refresh_chain_head(session: Session, site_id: str) -> ChainHead:
    """Recompute a site's chain head from stored certificates.

    Derived rather than incremented so a head can never drift out of step with the rows it is
    meant to summarise. Quarantined records are excluded from the head — the tip must be the
    highest record actually trusted — but they stay counted so the dashboard can surface them.
    """
    verified_count = (
        session.scalar(
            select(func.count())
            .select_from(Certificate)
            .where(
                Certificate.site_id == site_id,
                Certificate.status == CertificateStatus.VERIFIED.value,
            )
        )
        or 0
    )
    quarantined_count = (
        session.scalar(
            select(func.count())
            .select_from(Certificate)
            .where(
                Certificate.site_id == site_id,
                Certificate.status == CertificateStatus.QUARANTINED.value,
            )
        )
        or 0
    )

    tip = session.scalar(
        select(Certificate)
        .where(
            Certificate.site_id == site_id,
            Certificate.status == CertificateStatus.VERIFIED.value,
        )
        .order_by(Certificate.seq.desc())
        .limit(1)
    )

    head = session.get(ChainHead, site_id)
    if head is None:
        head = ChainHead(
            site_id=site_id,
            last_seq=0,
            last_record_hash=canonical.ZERO_HASH,
            certificate_count=0,
            quarantined_count=0,
        )
        session.add(head)

    head.last_seq = tip.seq if tip is not None else 0
    head.last_record_hash = tip.record_hash if tip is not None else canonical.ZERO_HASH
    head.certificate_count = verified_count
    head.quarantined_count = quarantined_count
    head.updated_at = utcnow()
    return head


def missing_sequences(session: Session, site_id: str, limit: int = 200) -> list[int]:
    """Sequence numbers absent below the head.

    Benign while devices are still syncing, evidence of deletion when they are not, so the
    dashboard shows the list rather than a bare count and lets an officer judge.
    """
    head = session.get(ChainHead, site_id)
    if head is None or head.last_seq <= 0:
        return []

    present = set(
        session.scalars(
            select(Certificate.seq).where(Certificate.site_id == site_id)
        ).all()
    )
    gaps: list[int] = []
    for seq in range(1, head.last_seq + 1):
        if seq not in present:
            gaps.append(seq)
            if len(gaps) >= limit:
                break
    return gaps


@dataclass(slots=True)
class ChainAuditResult:
    site_id: str
    records_checked: int
    status: ChainStatus
    first_problem_seq: int | None
    reasons: list[str] = field(default_factory=list)
    quarantined_seqs: list[int] = field(default_factory=list)

    @property
    def clean(self) -> bool:
        return self.status is ChainStatus.VERIFIED and self.first_problem_seq is None


def audit_chain(session: Session, site_id: str) -> ChainAuditResult:
    """Walk a site's whole ledger end to end.

    Stops at the first break: everything after an unexplained break is untrustworthy anyway, so
    continuing would only produce noise around a single root cause.
    """
    keys = load_site_public_keys(session, site_id)
    if not keys:
        return ChainAuditResult(
            site_id=site_id,
            records_checked=0,
            status=ChainStatus.UNKNOWN_SITE_KEY,
            first_problem_seq=None,
            reasons=[f"no public key held for site {site_id}"],
        )

    records = list(
        session.scalars(
            select(Certificate)
            .where(Certificate.site_id == site_id)
            .order_by(Certificate.seq.asc())
        ).all()
    )
    quarantined = [r.seq for r in records if r.status == CertificateStatus.QUARANTINED.value]

    if not records:
        return ChainAuditResult(
            site_id=site_id,
            records_checked=0,
            status=ChainStatus.VERIFIED,
            first_problem_seq=None,
            reasons=[f"site {site_id} has issued no certificates yet"],
        )

    key_by_epoch = {key.epoch: key.public_key for key in keys}
    expected_seq = 1
    expected_prev = canonical.ZERO_HASH
    checked = 0

    for record in records:
        checked += 1

        if record.seq != expected_seq:
            return ChainAuditResult(
                site_id=site_id,
                records_checked=checked,
                status=ChainStatus.SEQUENCE_GAP,
                first_problem_seq=expected_seq,
                reasons=[
                    f"expected seq {expected_seq} but found {record.seq}: "
                    f"{record.seq - expected_seq} record(s) missing from site {site_id}"
                ],
                quarantined_seqs=quarantined,
            )

        attestation = canonical.Attestation(
            site_id=record.site_id,
            seq=record.seq,
            worker_id_hash=record.worker_id_hash,
            module_code=record.module_code,
            score_permille=record.score_permille,
            median_latency_ms=record.median_latency_ms,
            outcome_flags=record.outcome_flags,
            issued_at_epoch_min=record.issued_at_sec // 60,
            prev_record_hash=record.prev_record_hash,
        )
        message = canonical.canonical_bytes(attestation)
        public_key = key_by_epoch.get(record.key_epoch)

        signature_ok = public_key is not None and crypto.verify(
            public_key, message, record.signature
        )
        if not signature_ok:
            # Fall back to every epoch: key_epoch is device-reported metadata, and a
            # mislabelled epoch should not be reported as forgery.
            signature_ok = any(
                crypto.verify(key.public_key, message, record.signature) for key in keys
            )
        if not signature_ok:
            return ChainAuditResult(
                site_id=site_id,
                records_checked=checked,
                status=ChainStatus.BAD_SIGNATURE,
                first_problem_seq=record.seq,
                reasons=[f"signature invalid at seq {record.seq}"],
                quarantined_seqs=quarantined,
            )

        if not crypto.constant_time_equals(expected_prev, record.prev_record_hash):
            return ChainAuditResult(
                site_id=site_id,
                records_checked=checked,
                status=ChainStatus.BROKEN_LINK,
                first_problem_seq=record.seq,
                reasons=[
                    f"broken link at seq {record.seq}: points at "
                    f"{record.prev_record_hash.hex()[:12]}.. but the previous record hashes to "
                    f"{expected_prev.hex()[:12]}.."
                ],
                quarantined_seqs=quarantined,
            )

        expected_prev = record.record_hash
        expected_seq = record.seq + 1

    return ChainAuditResult(
        site_id=site_id,
        records_checked=checked,
        status=ChainStatus.VERIFIED,
        first_problem_seq=None,
        reasons=[f"all {checked} record(s) for site {site_id} verified end to end"],
        quarantined_seqs=quarantined,
    )


# ---------------------------------------------------------------------------
# Ingest
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class IngestOutcome:
    status: str  # accepted | duplicate | quarantined | rejected
    certificate: Certificate | None
    reason: str | None = None
    retryable: bool = False
    chain_status: ChainStatus | None = None


def ingest_certificate(
    session: Session,
    *,
    qr_text: str,
    worker_id: str,
    device_id: str | None,
    key_epoch: int,
    now_sec: int,
    max_clock_skew_seconds: int,
) -> IngestOutcome:
    """Verify and store one uploaded certificate.

    Ingest is additive only. There is no update or delete path from a client, so no device can
    rewrite history — the worst it can do is submit something that gets quarantined and raised.
    """
    try:
        signed = canonical.decode_qr(qr_text)
    except canonical.CanonicalFormatError as exc:
        return IngestOutcome(status="rejected", certificate=None, reason=f"malformed: {exc}")

    attestation = signed.attestation

    existing = session.scalar(
        select(Certificate).where(Certificate.record_hash == signed.record_hash)
    )
    if existing is not None:
        # Already ingested, whether by direct upload or relayed via a supervisor's phone. The
        # shared idempotency key collapses both paths onto this one row.
        if existing.worker_id is None and worker_id:
            existing.worker_id = worker_id
            existing.worker_resolved = True
        return IngestOutcome(
            status="duplicate", certificate=existing, reason="already ingested"
        )

    issued_at_sec = attestation.issued_at_epoch_sec
    if issued_at_sec > now_sec + max_clock_skew_seconds:
        return IngestOutcome(
            status="rejected",
            certificate=None,
            reason=(
                f"issued_at is {issued_at_sec - now_sec}s ahead of server time, beyond the "
                f"{max_clock_skew_seconds}s tolerance"
            ),
        )
    clock_skew_flagged = issued_at_sec > now_sec

    verification = verify_signed(session, signed)

    if verification.status is ChainStatus.UNKNOWN_SITE_KEY:
        # Retryable: the supervisor device may simply not have registered yet.
        return IngestOutcome(
            status="rejected",
            certificate=None,
            reason=f"no key registered for site {attestation.site_id}",
            retryable=True,
            chain_status=verification.status,
        )

    quarantine_reason: str | None = None
    if verification.status in (
        ChainStatus.BAD_SIGNATURE,
        ChainStatus.BROKEN_LINK,
        ChainStatus.SEQUENCE_GAP,
    ):
        # Stored, not dropped. A discarded broken link is a destroyed audit trail.
        quarantine_reason = "; ".join(verification.reasons)

    slot_taken = session.scalar(
        select(Certificate).where(
            Certificate.site_id == attestation.site_id, Certificate.seq == attestation.seq
        )
    )
    if slot_taken is not None:
        quarantine_reason = (
            f"sequence slot {attestation.seq} for site {attestation.site_id} is already "
            f"occupied by certificate {slot_taken.id}; two devices issued into the same slot"
        )
        # The slot is uniquely constrained, so this record cannot be stored at its claimed
        # position. Reject with the conflict named rather than corrupting the chain.
        return IngestOutcome(
            status="rejected",
            certificate=None,
            reason=quarantine_reason,
            chain_status=ChainStatus.BROKEN_LINK,
        )

    from app.models import Worker  # local import keeps the module import graph acyclic

    worker = session.get(Worker, worker_id) if worker_id else None

    certificate = Certificate(
        id=str(uuid.uuid4()),
        site_id=attestation.site_id,
        seq=attestation.seq,
        key_epoch=verification.matched_key_epoch or key_epoch,
        worker_id=worker_id or None,
        worker_id_hash=attestation.worker_id_hash,
        worker_resolved=worker is not None,
        module_code=attestation.module_code,
        score_permille=attestation.score_permille,
        median_latency_ms=attestation.median_latency_ms,
        outcome_flags=attestation.outcome_flags,
        issued_at_sec=issued_at_sec,
        prev_record_hash=attestation.prev_record_hash,
        record_hash=signed.record_hash,
        signature=signed.signature,
        qr_text=qr_text.strip(),
        device_id=device_id,
        status=(
            CertificateStatus.QUARANTINED.value
            if quarantine_reason
            else CertificateStatus.VERIFIED.value
        ),
        quarantine_reason=quarantine_reason,
        clock_skew_flagged=clock_skew_flagged,
    )
    session.add(certificate)
    session.flush()
    refresh_chain_head(session, attestation.site_id)

    if quarantine_reason:
        logger.warning(
            "quarantined certificate site=%s seq=%s reason=%s",
            attestation.site_id,
            attestation.seq,
            quarantine_reason,
        )
        return IngestOutcome(
            status="quarantined",
            certificate=certificate,
            reason=quarantine_reason,
            chain_status=verification.status,
        )

    return IngestOutcome(
        status="accepted", certificate=certificate, chain_status=verification.status
    )
