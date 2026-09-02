"""Request and response schemas.

Two conventions worth stating up front:

* Binary values cross the wire as lower-case hex, never base64. Hex is greppable in a log, and a
  chain hash is something people actually read out to each other while diagnosing a break.
* A certificate is uploaded as its **QR text** plus the plaintext worker id, rather than as
  pre-parsed fields. The server then decodes and verifies exactly the bytes a physical scanner
  would see, so there is no second parsing path that could accept something the offline verifier
  would reject.
"""

from __future__ import annotations

import re
from typing import Any, Generic, Literal, TypeVar

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.models import (
    ArPresentation,
    AssessmentMode,
    CertificateStatus,
    Completion,
    HazardCategory,
    HazardSeverity,
    HazardStatus,
    Language,
    MediaKind,
    Sector,
)

T = TypeVar("T")

SITE_ID_PATTERN = re.compile(r"^[A-Z]{2}-[A-Z0-9]{2,6}-[0-9]{3}$")
WORKER_ID_PATTERN = re.compile(r"^[A-Z]{2}-[A-Z0-9]{2,6}-[0-9]{3}-W[0-9]{5}$")
HEX_32 = re.compile(r"^[0-9a-f]{64}$")


class ApiModel(BaseModel):
    """Base for every schema. Rejects unknown fields so a client typo is a 422, not silence."""

    model_config = ConfigDict(extra="forbid", from_attributes=True)


class OrmModel(BaseModel):
    """Base for response models mapped straight off ORM rows."""

    model_config = ConfigDict(from_attributes=True)


class Page(OrmModel, Generic[T]):
    items: list[T]
    total: int
    page: int
    page_size: int

    @property
    def has_more(self) -> bool:
        return self.page * self.page_size < self.total


class ErrorDetail(OrmModel):
    detail: str
    code: str | None = None
    hint: str | None = None


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------


class LoginRequest(ApiModel):
    username: str = Field(min_length=3, max_length=100)
    password: str = Field(min_length=8, max_length=256)


class TokenResponse(OrmModel):
    access_token: str
    refresh_token: str
    token_type: Literal["bearer"] = "bearer"
    expires_in_seconds: int
    role: str
    company_id: str | None
    site_id: str | None
    full_name: str


class RefreshRequest(ApiModel):
    refresh_token: str = Field(min_length=16)


class MeResponse(OrmModel):
    user_id: str
    username: str
    full_name: str
    role: str
    company_id: str | None
    site_id: str | None
    permissions: list[str]


# ---------------------------------------------------------------------------
# Sites, devices, modules
# ---------------------------------------------------------------------------


class SiteCreate(ApiModel):
    id: str = Field(min_length=6, max_length=16)
    company_id: str
    name: str = Field(min_length=2, max_length=200)
    district: str = Field(min_length=2, max_length=100)
    sector: Sector = Sector.COAL_MINE
    latitude: float | None = Field(default=None, ge=-90.0, le=90.0)
    longitude: float | None = Field(default=None, ge=-180.0, le=180.0)

    @field_validator("id")
    @classmethod
    def _validate_site_id(cls, value: str) -> str:
        upper = value.strip().upper()
        if not SITE_ID_PATTERN.match(upper):
            raise ValueError(
                "site id must look like 'JH-DHN-001' (state, district code, three-digit serial)"
            )
        # Re-checked because the id travels inside every signed certificate and the QR budget
        # allows no more than 16 bytes.
        if len(upper.encode("utf-8")) > 16:
            raise ValueError("site id must be at most 16 bytes to fit the certificate QR")
        return upper


class SiteOut(OrmModel):
    id: str
    company_id: str
    name: str
    district: str
    sector: str
    ar_scanned: bool
    ar_anchor_count: int
    latitude: float | None
    longitude: float | None
    active: bool


class SiteKeyOut(OrmModel):
    epoch: int
    public_key_hex: str
    active: bool
    registered_at_iso: str
    revoked_at_iso: str | None = None
    revocation_reason: str | None = None


class SitePublicKeysResponse(OrmModel):
    site_id: str
    keys: list[SiteKeyOut]


class DeviceRegisterRequest(ApiModel):
    device_id: str = Field(min_length=8, max_length=64)
    site_id: str = Field(min_length=6, max_length=16)
    #: Ed25519 public key of the site signing identity held on this device, hex encoded.
    site_public_key_hex: str = Field(min_length=64, max_length=64)
    #: Hardware-backed EC P-256 attestation key from the Android Keystore, hex encoded DER.
    attest_public_key_hex: str | None = Field(default=None, max_length=512)
    key_epoch: int = Field(default=1, ge=1)
    model: str | None = Field(default=None, max_length=120)
    android_release: str | None = Field(default=None, max_length=32)
    app_version: str | None = Field(default=None, max_length=32)

    @field_validator("site_public_key_hex")
    @classmethod
    def _validate_public_key(cls, value: str) -> str:
        lowered = value.strip().lower()
        if not HEX_32.match(lowered):
            raise ValueError("site_public_key_hex must be 64 lower-case hex characters (32 bytes)")
        return lowered


class DeviceOut(OrmModel):
    id: str
    site_id: str
    model: str | None
    android_release: str | None
    app_version: str | None
    active: bool
    last_seen_at_iso: str | None
    last_sync_at_iso: str | None


class ModuleOut(OrmModel):
    id: str
    module_code: int
    catalog_version: int
    title_key: str
    title_en: str
    description_key: str
    statutory_reference: str
    estimated_minutes: int
    supports_buddy_drill: bool
    fully_implemented: bool
    enabled: bool
    sectors: list[str]


# ---------------------------------------------------------------------------
# Workers
# ---------------------------------------------------------------------------


class WorkerCreate(ApiModel):
    id: str = Field(min_length=8, max_length=48)
    site_id: str = Field(min_length=6, max_length=16)
    full_name: str = Field(min_length=2, max_length=200)
    preferred_language: Language = Language.HINDI
    pictogram_mode: bool = False
    phone_number: str | None = Field(default=None, max_length=20)
    employment_type: str | None = Field(default=None, max_length=32)

    @field_validator("id")
    @classmethod
    def _validate_worker_id(cls, value: str) -> str:
        upper = value.strip().upper()
        if not WORKER_ID_PATTERN.match(upper):
            raise ValueError("worker id must look like 'JH-DHN-001-W00042'")
        return upper

    @field_validator("phone_number")
    @classmethod
    def _validate_phone(cls, value: str | None) -> str | None:
        if value is None:
            return None
        digits = re.sub(r"[^0-9]", "", value)
        if len(digits) not in (10, 12):
            raise ValueError("phone number must have 10 digits, or 12 with a country code")
        return digits


class ModuleReadinessOut(OrmModel):
    module_id: str
    module_code: int
    module_title_en: str
    attempts: int
    best_score_permille: int
    base_score_permille: int
    readiness_permille: int
    readiness_band: str
    statutory_valid: bool
    days_until_statutory_expiry: int
    required_action: str
    refresher_due: bool
    next_due_at_sec: int
    last_pass_at_sec: int
    certified_at_sec: int
    hesitation_flagged: bool


class WorkerOut(OrmModel):
    id: str
    site_id: str
    full_name: str
    preferred_language: str
    pictogram_mode: bool
    active: bool
    provisional: bool
    #: Mean readiness across every module this worker has attempted.
    overall_readiness_permille: int
    modules_certified: int
    modules_due: int
    hesitation_flagged: bool


class WorkerDetailOut(WorkerOut):
    phone_number: str | None
    employment_type: str | None
    joined_at_iso: str | None
    modules: list[ModuleReadinessOut]
    certificate_count: int
    hazard_reports_filed: int


# ---------------------------------------------------------------------------
# Sync
# ---------------------------------------------------------------------------


class CertificateUpload(ApiModel):
    """A certificate exactly as the device holds it.

    Only ``qr_text`` is trusted. Everything else is context the server uses to resolve names and
    is re-derived from the signed payload where it matters, so a device cannot claim a score its
    signature does not support.
    """

    idempotency_key: str = Field(min_length=8, max_length=64)
    qr_text: str = Field(min_length=32, max_length=1024)
    worker_id: str = Field(min_length=8, max_length=48)
    module_id: str = Field(min_length=2, max_length=48)
    key_epoch: int = Field(default=1, ge=1)
    run_id: str | None = Field(default=None, max_length=36)


class StepResultUpload(ApiModel):
    step_id: str = Field(max_length=64)
    outcome: str = Field(max_length=24)
    latency_ms: int = Field(ge=0)
    expert_ms: int = Field(ge=0)
    timeout_ms: int = Field(ge=0)
    correct: bool
    critical: bool
    weight: float = Field(gt=0.0)
    input_method: str = Field(max_length=24)
    suspicious_fast: bool = False


class AssessmentUpload(ApiModel):
    idempotency_key: str = Field(min_length=8, max_length=64)
    run_id: str = Field(min_length=8, max_length=36)
    worker_id: str = Field(min_length=8, max_length=48)
    site_id: str = Field(min_length=6, max_length=16)
    module_id: str = Field(min_length=2, max_length=48)
    module_code: int = Field(ge=1, le=255)
    scenario_id: str = Field(min_length=2, max_length=64)
    catalog_version: int = Field(default=1, ge=1)
    mode: AssessmentMode
    presentation: ArPresentation
    completion: Completion
    score_permille: int = Field(ge=0, le=1000)
    passed: bool
    hesitation_flag: bool
    hesitation_ratio: float = Field(ge=0.0, le=1.0)
    median_latency_ms: int = Field(ge=0)
    started_at_sec: int = Field(ge=0)
    finished_at_sec: int = Field(ge=0)
    total_duration_ms: int = Field(ge=0)
    steps: list[StepResultUpload] = Field(default_factory=list, max_length=64)
    failed_critical_step_ids: list[str] = Field(default_factory=list, max_length=64)
    void_reason: str | None = Field(default=None, max_length=48)
    abort_reason: str | None = Field(default=None, max_length=48)
    buddy_peer_device_id: str | None = Field(default=None, max_length=64)


class HazardUpload(ApiModel):
    idempotency_key: str = Field(min_length=8, max_length=64)
    hazard_id: str = Field(min_length=8, max_length=36)
    site_id: str = Field(min_length=6, max_length=16)
    reporter_worker_id: str | None = Field(default=None, max_length=48)
    category: HazardCategory
    severity: HazardSeverity
    note: str | None = Field(default=None, max_length=2000)
    latitude: float | None = Field(default=None, ge=-90.0, le=90.0)
    longitude: float | None = Field(default=None, ge=-180.0, le=180.0)
    zone_label: str | None = Field(default=None, max_length=120)
    ar_anchor_id: str | None = Field(default=None, max_length=120)
    photo_media_id: str | None = Field(default=None, max_length=36)
    voice_media_id: str | None = Field(default=None, max_length=36)
    reported_at_sec: int = Field(ge=0)


class ProgressUpload(ApiModel):
    idempotency_key: str = Field(min_length=8, max_length=64)
    worker_id: str = Field(min_length=8, max_length=48)
    site_id: str = Field(min_length=6, max_length=16)
    module_id: str = Field(min_length=2, max_length=48)
    module_code: int = Field(ge=1, le=255)
    base_score: int = Field(ge=0, le=1000)
    last_pass_at_sec: int = Field(ge=0)
    certified_at_sec: int = Field(ge=0)
    refresher_stage: int = Field(ge=0, le=32)
    next_due_at_sec: int = Field(ge=0)
    consecutive_failures: int = Field(ge=0, le=1000)
    attempts: int = Field(ge=0, le=100_000)
    best_score_permille: int = Field(ge=0, le=1000)
    last_hesitation_flag: bool = False


class SyncBatchRequest(ApiModel):
    device_id: str = Field(min_length=8, max_length=64)
    #: Stable per batch on the device. Replaying the same id returns the stored response.
    client_batch_id: str = Field(min_length=8, max_length=64)
    #: Signature over the batch by the device's Keystore key, hex encoded. Optional while a
    #: pilot fleet is still being enrolled; recorded and checked once present.
    device_signature_hex: str | None = Field(default=None, max_length=512)
    certificates: list[CertificateUpload] = Field(default_factory=list)
    assessments: list[AssessmentUpload] = Field(default_factory=list)
    hazards: list[HazardUpload] = Field(default_factory=list)
    progress: list[ProgressUpload] = Field(default_factory=list)

    @property
    def item_count(self) -> int:
        return (
            len(self.certificates)
            + len(self.assessments)
            + len(self.hazards)
            + len(self.progress)
        )


class SyncItemResult(OrmModel):
    kind: Literal["certificate", "assessment", "hazard", "progress"]
    ref_id: str
    idempotency_key: str
    status: Literal["accepted", "duplicate", "quarantined", "rejected"]
    reason: str | None = None
    #: True when a retryable server-side condition caused the rejection, so the device should
    #: keep the item queued rather than discard it.
    retryable: bool = False


class SyncBatchResponse(OrmModel):
    batch_id: str
    accepted: int
    rejected: int
    quarantined: int
    duplicates: int
    results: list[SyncItemResult]
    server_time_sec: int
    #: True when this exact batch was already ingested and the stored response is being replayed.
    replayed: bool = False


class BootstrapResponse(OrmModel):
    """Everything a device needs to work offline for the next stretch."""

    site: SiteOut
    site_keys: list[SiteKeyOut]
    modules: list[ModuleOut]
    workers: list[WorkerOut]
    chain_head_seq: int
    chain_head_hash_hex: str
    catalog_version: int
    server_time_sec: int


# ---------------------------------------------------------------------------
# Certificates and chains
# ---------------------------------------------------------------------------


class VerifyRequest(ApiModel):
    qr_text: str = Field(min_length=8, max_length=2048)
    #: Optional. When supplied, the server confirms the hash in the certificate matches the id
    #: printed on the worker's physical card.
    candidate_worker_id: str | None = Field(default=None, max_length=48)


class VerifyResponse(OrmModel):
    status: str
    trustworthy: bool
    indicates_tampering: bool
    reasons: list[str]
    site_id: str | None = None
    seq: int | None = None
    module_code: int | None = None
    module_title_en: str | None = None
    score_permille: int | None = None
    median_latency_ms: int | None = None
    outcome_flags: int | None = None
    flag_names: list[str] = Field(default_factory=list)
    issued_at_sec: int | None = None
    statutory_valid: bool | None = None
    statutory_expiry_sec: int | None = None
    readiness_permille: int | None = None
    readiness_band: str | None = None
    worker_id_matches: bool | None = None
    worker_full_name: str | None = None
    record_hash_hex: str | None = None
    prev_record_hash_hex: str | None = None


class CertificateOut(OrmModel):
    id: str
    site_id: str
    seq: int
    key_epoch: int
    worker_id: str | None
    worker_full_name: str | None = None
    module_code: int
    module_title_en: str | None = None
    score_permille: int
    median_latency_ms: int
    outcome_flags: int
    flag_names: list[str] = Field(default_factory=list)
    issued_at_sec: int
    status: str
    quarantine_reason: str | None
    clock_skew_flagged: bool
    record_hash_hex: str
    prev_record_hash_hex: str
    qr_text: str
    device_id: str | None


class ChainHeadOut(OrmModel):
    site_id: str
    last_seq: int
    last_record_hash_hex: str
    certificate_count: int
    quarantined_count: int
    updated_at_iso: str | None
    #: Missing sequence numbers below the head. Benign when devices are still syncing;
    #: evidence of deletion when they are not.
    missing_sequences: list[int] = Field(default_factory=list)


class ChainAuditResponse(OrmModel):
    site_id: str
    records_checked: int
    status: str
    clean: bool
    first_problem_seq: int | None
    reasons: list[str]
    quarantined_seqs: list[int] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Compliance
# ---------------------------------------------------------------------------


class ComplianceOverviewOut(OrmModel):
    site_count: int
    worker_count: int
    certificate_count: int
    quarantined_certificate_count: int
    #: Workers with at least one statutorily valid certificate, as a percentage of all workers.
    certified_worker_percent: float
    mean_readiness_permille: int
    workers_ready: int
    workers_due: int
    workers_stale: int
    workers_expired: int
    workers_never_certified: int
    #: Statutorily valid but operationally stale. The cohort a site officer should look at first.
    statutorily_valid_but_stale: int
    hesitation_risk_count: int
    open_hazard_count: int
    critical_hazard_count: int
    refreshers_due_count: int
    generated_at_sec: int


class SiteComplianceOut(OrmModel):
    site_id: str
    site_name: str
    district: str
    sector: str
    ar_scanned: bool
    worker_count: int
    certified_worker_percent: float
    mean_readiness_permille: int
    hesitation_risk_count: int
    open_hazard_count: int
    quarantined_certificate_count: int
    refreshers_due_count: int


class HesitationRiskOut(OrmModel):
    """A worker who is technically certified but decides slowly under pressure."""

    worker_id: str
    worker_full_name: str
    site_id: str
    site_name: str
    module_id: str
    module_title_en: str
    score_permille: int
    median_latency_ms: int
    #: Median decision time as a multiple of the expert baseline for that scenario.
    pace_multiple: float
    hesitant_step_count: int
    total_step_count: int
    last_attempt_at_sec: int
    readiness_permille: int
    statutory_valid: bool


class ReadinessTrendPoint(OrmModel):
    day_epoch_sec: int
    mean_readiness_permille: int
    certificates_issued: int
    assessments_run: int
    hesitation_flagged: int


class ReadinessTrendOut(OrmModel):
    site_id: str | None
    from_epoch_sec: int
    to_epoch_sec: int
    points: list[ReadinessTrendPoint]


# ---------------------------------------------------------------------------
# Hazards
# ---------------------------------------------------------------------------


class HazardCreate(ApiModel):
    site_id: str = Field(min_length=6, max_length=16)
    category: HazardCategory
    severity: HazardSeverity
    note: str | None = Field(default=None, max_length=2000)
    reporter_worker_id: str | None = Field(default=None, max_length=48)
    latitude: float | None = Field(default=None, ge=-90.0, le=90.0)
    longitude: float | None = Field(default=None, ge=-180.0, le=180.0)
    zone_label: str | None = Field(default=None, max_length=120)
    ar_anchor_id: str | None = Field(default=None, max_length=120)
    photo_media_id: str | None = Field(default=None, max_length=36)
    voice_media_id: str | None = Field(default=None, max_length=36)


class HazardPatch(ApiModel):
    status: HazardStatus
    resolution_note: str | None = Field(default=None, max_length=2000)
    #: Optimistic concurrency. When two officers triage the same hazard, the second gets a 409
    #: rather than silently overwriting the first.
    expected_updated_at_iso: str | None = None


class HazardOut(OrmModel):
    id: str
    site_id: str
    site_name: str | None = None
    reporter_worker_id: str | None
    reporter_label: str
    category: str
    severity: str
    note: str | None
    latitude: float | None
    longitude: float | None
    zone_label: str | None
    ar_anchor_id: str | None
    photo_media_id: str | None
    voice_media_id: str | None
    status: str
    duplicate_of_id: str | None
    duplicate_count: int
    reported_at_sec: int
    created_at_iso: str
    updated_at_iso: str
    resolved_at_iso: str | None
    resolution_note: str | None
    allowed_next_statuses: list[str] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Media
# ---------------------------------------------------------------------------


class MediaOut(OrmModel):
    id: str
    kind: MediaKind
    content_type: str
    byte_size: int
    sha256_hex: str
    url: str


# ---------------------------------------------------------------------------
# Health
# ---------------------------------------------------------------------------


class HealthResponse(OrmModel):
    status: Literal["ok"]
    version: str
    environment: str


class ReadinessResponse(OrmModel):
    status: Literal["ready", "degraded"]
    database: bool
    migrations_applied: bool
    missing_tables: list[str] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# WebSocket
# ---------------------------------------------------------------------------


class LiveEvent(OrmModel):
    """Pushed over ``/ws/live``. ``type`` is stable; ``payload`` is event specific."""

    type: Literal[
        "cert.issued",
        "cert.quarantined",
        "hazard.created",
        "hazard.updated",
        "sync.batch",
        "chain.break",
    ]
    site_id: str | None
    at_epoch_sec: int
    payload: dict[str, Any]
