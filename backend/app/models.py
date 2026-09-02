"""SQLAlchemy models.

Constraints are expressed in the schema rather than left to application code wherever the
database can enforce them. Two of them carry most of the platform's integrity guarantee:

* ``uq_certificates_site_id_seq`` — one certificate per chain slot per site. Two coroutines, two
  devices or two replayed batches cannot mint conflicting records into the same slot, because the
  database refuses the second write regardless of which code path produced it.
* ``uq_sync_batches_device_id_client_batch_id`` — a replayed upload returns the stored response
  instead of re-ingesting.

Enumerated columns are stored as strings with an explicit ``CheckConstraint`` rather than as
native database enums. Native enums make Alembic migrations painful on PostgreSQL for no
functional gain here.
"""

from __future__ import annotations

from datetime import datetime
from enum import Enum

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, TimestampMixin, utcnow

# ---------------------------------------------------------------------------
# Enumerations (values are persisted; renaming one is a data migration)
# ---------------------------------------------------------------------------


class Sector(str, Enum):
    COAL_MINE = "coal_mine"
    STEEL_PLANT = "steel_plant"
    MICA_PROCESSING = "mica_processing"
    OTHER = "other"


class Language(str, Enum):
    HINDI = "hi"
    SANTALI = "sat"
    ENGLISH = "en"


class CertificateStatus(str, Enum):
    VERIFIED = "verified"
    #: Signature or chain linkage failed on ingest. The record is kept, not discarded:
    #: destroying the evidence of tampering would defeat the point of having a chain.
    QUARANTINED = "quarantined"
    #: Superseded by a later re-issue after a key-epoch rollover.
    SUPERSEDED = "superseded"


class HazardStatus(str, Enum):
    OPEN = "open"
    ACKNOWLEDGED = "acknowledged"
    IN_PROGRESS = "in_progress"
    RESOLVED = "resolved"
    INVALID = "invalid"


#: Allowed hazard status transitions. Anything else is a 409 with the permitted set attached.
HAZARD_TRANSITIONS: dict[HazardStatus, set[HazardStatus]] = {
    HazardStatus.OPEN: {
        HazardStatus.ACKNOWLEDGED,
        HazardStatus.INVALID,
    },
    HazardStatus.ACKNOWLEDGED: {
        HazardStatus.IN_PROGRESS,
        HazardStatus.RESOLVED,
        HazardStatus.INVALID,
    },
    HazardStatus.IN_PROGRESS: {
        HazardStatus.RESOLVED,
        HazardStatus.INVALID,
    },
    # Terminal. Reopening would let a resolved hazard be quietly relitigated, so a new report
    # is required instead and the original stays on the record.
    HazardStatus.RESOLVED: set(),
    HazardStatus.INVALID: set(),
}


class HazardCategory(str, Enum):
    EXPOSED_WIRING = "exposed_wiring"
    BLOCKED_EXIT = "blocked_exit"
    MISSING_EXTINGUISHER = "missing_extinguisher"
    MISSING_GUARD = "missing_guard"
    GAS_SMELL = "gas_smell"
    WATER_ACCUMULATION = "water_accumulation"
    ROOF_SUPPORT = "roof_support"
    SPILL = "spill"
    DAMAGED_PPE = "damaged_ppe"
    UNSAFE_ACT = "unsafe_act"
    OTHER = "other"


class HazardSeverity(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class MediaKind(str, Enum):
    PHOTO = "photo"
    VOICE = "voice"


class AssessmentMode(str, Enum):
    INITIAL = "initial"
    REFRESHER = "refresher"
    BUDDY = "buddy"
    PRACTICE = "practice"


class ArPresentation(str, Enum):
    SITE_SCANNED = "site_scanned"
    ARCORE_GENERIC = "arcore_generic"
    SENSOR_FALLBACK = "sensor_fallback"
    PICTOGRAM_2D = "pictogram_2d"


class Completion(str, Enum):
    COMPLETED = "completed"
    ABORTED = "aborted"
    INCOMPLETE = "incomplete"
    VOIDED = "voided"


def _check(column: str, enum_type: type[Enum], name: str) -> CheckConstraint:
    values = ", ".join(f"'{member.value}'" for member in enum_type)
    return CheckConstraint(f"{column} IN ({values})", name=name)


# ---------------------------------------------------------------------------
# Organisation
# ---------------------------------------------------------------------------


class Company(Base, TimestampMixin):
    __tablename__ = "companies"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False, unique=True)
    sector: Mapped[str] = mapped_column(String(32), nullable=False, default=Sector.OTHER.value)
    contact_email: Mapped[str | None] = mapped_column(String(200))
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)

    sites: Mapped[list[Site]] = relationship(back_populates="company")

    __table_args__ = (_check("sector", Sector, "sector_valid"),)


class Site(Base, TimestampMixin):
    """A mine, plant or processing unit.

    ``id`` is the human-readable ``JH-<district>-<serial>`` used everywhere, including inside
    signed certificates, so it is capped at 16 bytes by the QR budget and validated on creation.
    """

    __tablename__ = "sites"

    id: Mapped[str] = mapped_column(String(16), primary_key=True)
    company_id: Mapped[str] = mapped_column(
        ForeignKey("companies.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    district: Mapped[str] = mapped_column(String(100), nullable=False)
    sector: Mapped[str] = mapped_column(String(32), nullable=False, default=Sector.COAL_MINE.value)
    #: True once a supervisor has walked the site placing Cloud Anchors.
    ar_scanned: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    ar_anchor_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    latitude: Mapped[float | None] = mapped_column(Float)
    longitude: Mapped[float | None] = mapped_column(Float)
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)

    company: Mapped[Company] = relationship(back_populates="sites")
    keys: Mapped[list[SiteKey]] = relationship(back_populates="site")

    __table_args__ = (_check("sector", Sector, "sector_valid"),)


class SiteKey(Base, TimestampMixin):
    """One Ed25519 public key epoch for a site.

    Epochs exist because supervisor phones get lost, stolen and destroyed. A new epoch starts a
    fresh key without invalidating anything already issued: certificates from earlier epochs stay
    verifiable against the archived public key, which is the whole point of keeping history rather
    than rotating in place.
    """

    __tablename__ = "site_keys"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    site_id: Mapped[str] = mapped_column(
        ForeignKey("sites.id", ondelete="CASCADE"), nullable=False, index=True
    )
    epoch: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    public_key: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    registered_by_device_id: Mapped[str | None] = mapped_column(String(64))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revocation_reason: Mapped[str | None] = mapped_column(String(300))

    site: Mapped[Site] = relationship(back_populates="keys")

    __table_args__ = (
        UniqueConstraint("site_id", "epoch", name="uq_site_keys_site_id_epoch"),
        CheckConstraint("epoch >= 1", name="ck_site_keys_epoch_positive"),
    )


class User(Base, TimestampMixin):
    """A dashboard or supervisor login.

    ``company_id`` and ``site_id`` are the row-level scope: every query filters on them, so a
    site officer physically cannot read another site's data even if a handler forgets to check.
    """

    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    username: Mapped[str] = mapped_column(String(100), nullable=False, unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(300), nullable=False)
    role: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    full_name: Mapped[str] = mapped_column(String(200), nullable=False)
    company_id: Mapped[str | None] = mapped_column(
        ForeignKey("companies.id", ondelete="SET NULL"), index=True
    )
    site_id: Mapped[str | None] = mapped_column(
        ForeignKey("sites.id", ondelete="SET NULL"), index=True
    )
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    failed_login_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (
        CheckConstraint(
            "role IN ('dgms_inspector', 'company_admin', 'site_officer', 'supervisor')",
            name="ck_users_role_valid",
        ),
    )


class Device(Base, TimestampMixin):
    """A registered handset.

    Separate from :class:`User` on purpose. ``attest_public_key`` is a hardware-backed EC P-256
    key generated in the Android Keystore, and it is what signs sync uploads. Keeping device trust
    apart from login credentials means a stolen password alone cannot forge certificates: it also
    takes that specific handset.
    """

    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    site_id: Mapped[str] = mapped_column(
        ForeignKey("sites.id", ondelete="CASCADE"), nullable=False, index=True
    )
    attest_public_key: Mapped[bytes | None] = mapped_column(LargeBinary(256))
    model: Mapped[str | None] = mapped_column(String(120))
    android_release: Mapped[str | None] = mapped_column(String(32))
    app_version: Mapped[str | None] = mapped_column(String(32))
    registered_by_user_id: Mapped[str | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_sync_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------


class Worker(Base, TimestampMixin):
    __tablename__ = "workers"

    id: Mapped[str] = mapped_column(String(48), primary_key=True)
    site_id: Mapped[str] = mapped_column(
        ForeignKey("sites.id", ondelete="CASCADE"), nullable=False, index=True
    )
    full_name: Mapped[str] = mapped_column(String(200), nullable=False)
    #: SHA-256 of the worker id. Indexed so a scanned certificate resolves to a person without
    #: the QR ever having to carry a plaintext identity.
    worker_id_hash: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False, index=True)
    preferred_language: Mapped[str] = mapped_column(
        String(8), nullable=False, default=Language.HINDI.value
    )
    pictogram_mode: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    phone_number: Mapped[str | None] = mapped_column(String(20))
    employment_type: Mapped[str | None] = mapped_column(String(32))
    joined_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    #: False when a certificate arrived before the roster synced. Resolved at next bootstrap.
    provisional: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    __table_args__ = (_check("preferred_language", Language, "language_valid"),)


class ModuleRecord(Base, TimestampMixin):
    """Server-side mirror of the on-device module catalog.

    The app ships the catalog in its APK so it works offline from first launch. This table exists
    so the dashboard can name modules, exports can cite the statutory reference, and an operator
    can disable a module for a site without shipping a new build.
    """

    __tablename__ = "modules"

    id: Mapped[str] = mapped_column(String(48), primary_key=True)
    module_code: Mapped[int] = mapped_column(Integer, nullable=False, unique=True)
    catalog_version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    title_key: Mapped[str] = mapped_column(String(80), nullable=False)
    title_en: Mapped[str] = mapped_column(String(200), nullable=False)
    description_key: Mapped[str] = mapped_column(String(80), nullable=False)
    statutory_reference: Mapped[str] = mapped_column(String(200), nullable=False)
    estimated_minutes: Mapped[int] = mapped_column(Integer, nullable=False)
    supports_buddy_drill: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    fully_implemented: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    sectors_json: Mapped[str] = mapped_column(Text, nullable=False, default="[]")

    __table_args__ = (
        CheckConstraint(
            "module_code >= 1 AND module_code <= 255", name="ck_modules_code_range"
        ),
        CheckConstraint("estimated_minutes > 0", name="ck_modules_minutes_positive"),
    )


class AssessmentRun(Base, TimestampMixin):
    """One drill attempt, with its per-step detail retained.

    ``steps_json`` is kept so a disputed certificate can be recomputed from the stored step
    results years later and produce the identical score.
    """

    __tablename__ = "assessment_runs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    worker_id: Mapped[str] = mapped_column(String(48), nullable=False, index=True)
    site_id: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    module_id: Mapped[str] = mapped_column(String(48), nullable=False, index=True)
    module_code: Mapped[int] = mapped_column(Integer, nullable=False)
    scenario_id: Mapped[str] = mapped_column(String(64), nullable=False)
    catalog_version: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    device_id: Mapped[str | None] = mapped_column(String(64), index=True)

    mode: Mapped[str] = mapped_column(String(16), nullable=False)
    presentation: Mapped[str] = mapped_column(String(24), nullable=False)
    completion: Mapped[str] = mapped_column(String(16), nullable=False, index=True)

    score_permille: Mapped[int] = mapped_column(Integer, nullable=False)
    passed: Mapped[bool] = mapped_column(Boolean, nullable=False, index=True)
    hesitation_flag: Mapped[bool] = mapped_column(Boolean, nullable=False, index=True)
    hesitation_ratio: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    median_latency_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    started_at_sec: Mapped[int] = mapped_column(Integer, nullable=False)
    finished_at_sec: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    total_duration_ms: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    steps_json: Mapped[str] = mapped_column(Text, nullable=False, default="[]")
    failed_critical_steps_json: Mapped[str] = mapped_column(Text, nullable=False, default="[]")
    void_reason: Mapped[str | None] = mapped_column(String(48))
    abort_reason: Mapped[str | None] = mapped_column(String(48))
    buddy_peer_device_id: Mapped[str | None] = mapped_column(String(64))

    __table_args__ = (
        _check("mode", AssessmentMode, "mode_valid"),
        _check("presentation", ArPresentation, "presentation_valid"),
        _check("completion", Completion, "completion_valid"),
        CheckConstraint(
            "score_permille >= 0 AND score_permille <= 1000", name="ck_assessment_runs_score_range"
        ),
        CheckConstraint(
            "hesitation_ratio >= 0.0 AND hesitation_ratio <= 1.0",
            name="ck_assessment_runs_hesitation_range",
        ),
        Index("ix_assessment_runs_site_finished", "site_id", "finished_at_sec"),
        Index("ix_assessment_runs_worker_module", "worker_id", "module_id"),
    )


class Certificate(Base):
    """An ingested certificate, exactly as it was signed on the device.

    Every signed field is stored verbatim so verification can be repeated independently at any
    time; nothing here is derived or reformatted on the way in.
    """

    __tablename__ = "certificates"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    site_id: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    seq: Mapped[int] = mapped_column(Integer, nullable=False)
    key_epoch: Mapped[int] = mapped_column(Integer, nullable=False, default=1)

    worker_id: Mapped[str | None] = mapped_column(String(48), index=True)
    worker_id_hash: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False, index=True)
    #: False when the certificate arrived before the worker roster. Resolved on later sync.
    worker_resolved: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    module_code: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    score_permille: Mapped[int] = mapped_column(Integer, nullable=False)
    median_latency_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    outcome_flags: Mapped[int] = mapped_column(Integer, nullable=False)
    issued_at_sec: Mapped[int] = mapped_column(Integer, nullable=False, index=True)

    prev_record_hash: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    record_hash: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False, unique=True)
    signature: Mapped[bytes] = mapped_column(LargeBinary(64), nullable=False)
    qr_text: Mapped[str] = mapped_column(String(1024), nullable=False)

    device_id: Mapped[str | None] = mapped_column(String(64), index=True)
    status: Mapped[str] = mapped_column(
        String(16), nullable=False, default=CertificateStatus.VERIFIED.value, index=True
    )
    quarantine_reason: Mapped[str | None] = mapped_column(String(500))
    #: Set when issued_at is ahead of server time but inside the tolerated skew window.
    clock_skew_flagged: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    ingested_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow, index=True
    )

    __table_args__ = (
        # The single most important constraint in the schema: one certificate per chain slot.
        UniqueConstraint("site_id", "seq", name="uq_certificates_site_id_seq"),
        _check("status", CertificateStatus, "status_valid"),
        CheckConstraint(
            "score_permille >= 0 AND score_permille <= 1000", name="ck_certificates_score_range"
        ),
        CheckConstraint("seq >= 1", name="ck_certificates_seq_positive"),
        CheckConstraint(
            "module_code >= 1 AND module_code <= 255", name="ck_certificates_module_range"
        ),
        Index("ix_certificates_site_seq_lookup", "site_id", "seq"),
        Index("ix_certificates_worker_module", "worker_id", "module_code"),
    )


class ChainHead(Base):
    """Highest verified slot per site, so ingest does not re-walk the whole chain."""

    __tablename__ = "chain_heads"

    site_id: Mapped[str] = mapped_column(String(16), primary_key=True)
    last_seq: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    last_record_hash: Mapped[bytes] = mapped_column(LargeBinary(32), nullable=False)
    certificate_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    quarantined_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )

    __table_args__ = (CheckConstraint("last_seq >= 0", name="ck_chain_heads_seq_non_negative"),)


class TrainingProgress(Base):
    """Server-side mirror of a worker's retention state for one module.

    Readiness is always recomputed from these fields on read, never stored, so a value that
    synced weeks ago still reports correctly today.
    """

    __tablename__ = "training_progress"

    worker_id: Mapped[str] = mapped_column(String(48), primary_key=True)
    module_id: Mapped[str] = mapped_column(String(48), primary_key=True)
    site_id: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    module_code: Mapped[int] = mapped_column(Integer, nullable=False)

    base_score: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    last_pass_at_sec: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    certified_at_sec: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    refresher_stage: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    next_due_at_sec: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    consecutive_failures: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    best_score_permille: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    last_hesitation_flag: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )

    __table_args__ = (
        CheckConstraint(
            "base_score >= 0 AND base_score <= 1000", name="ck_training_progress_base_range"
        ),
        CheckConstraint("refresher_stage >= 0", name="ck_training_progress_stage_non_negative"),
        Index("ix_training_progress_site_due", "site_id", "next_due_at_sec"),
    )


# ---------------------------------------------------------------------------
# Operations
# ---------------------------------------------------------------------------


class Media(Base, TimestampMixin):
    __tablename__ = "media"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    content_type: Mapped[str] = mapped_column(String(100), nullable=False)
    byte_size: Mapped[int] = mapped_column(Integer, nullable=False)
    sha256_hex: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    stored_path: Mapped[str] = mapped_column(String(500), nullable=False)
    site_id: Mapped[str | None] = mapped_column(String(16), index=True)
    uploaded_by: Mapped[str | None] = mapped_column(String(100))

    __table_args__ = (
        _check("kind", MediaKind, "kind_valid"),
        CheckConstraint("byte_size > 0", name="ck_media_size_positive"),
    )


class Hazard(Base):
    """A crowdsourced near-miss or unsafe condition.

    ``reporter_label`` is denormalised on purpose: safety evidence must survive a worker record
    being deactivated or deleted, so the report never depends on a join that might disappear.
    """

    __tablename__ = "hazards"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    site_id: Mapped[str] = mapped_column(
        ForeignKey("sites.id", ondelete="CASCADE"), nullable=False, index=True
    )
    reporter_worker_id: Mapped[str | None] = mapped_column(String(48), index=True)
    reporter_label: Mapped[str] = mapped_column(String(200), nullable=False)

    category: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    severity: Mapped[str] = mapped_column(String(16), nullable=False, index=True)
    note: Mapped[str | None] = mapped_column(Text)

    #: GPS is unreliable underground, so coordinates are optional and a supervisor-defined zone
    #: label or an AR anchor id carries the location instead.
    latitude: Mapped[float | None] = mapped_column(Float)
    longitude: Mapped[float | None] = mapped_column(Float)
    zone_label: Mapped[str | None] = mapped_column(String(120))
    ar_anchor_id: Mapped[str | None] = mapped_column(String(120))

    photo_media_id: Mapped[str | None] = mapped_column(
        ForeignKey("media.id", ondelete="SET NULL")
    )
    voice_media_id: Mapped[str | None] = mapped_column(
        ForeignKey("media.id", ondelete="SET NULL")
    )

    status: Mapped[str] = mapped_column(
        String(16), nullable=False, default=HazardStatus.OPEN.value, index=True
    )
    duplicate_of_id: Mapped[str | None] = mapped_column(String(36), index=True)
    duplicate_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    reported_at_sec: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow, index=True
    )
    #: Optimistic-concurrency token. Two officers triaging at once: the loser gets a 409.
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )
    acknowledged_by_user_id: Mapped[str | None] = mapped_column(String(36))
    acknowledged_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    resolution_note: Mapped[str | None] = mapped_column(Text)
    device_id: Mapped[str | None] = mapped_column(String(64))

    __table_args__ = (
        _check("category", HazardCategory, "category_valid"),
        _check("severity", HazardSeverity, "severity_valid"),
        _check("status", HazardStatus, "status_valid"),
        Index("ix_hazards_site_status", "site_id", "status"),
        Index("ix_hazards_site_reported", "site_id", "reported_at_sec"),
    )


class SyncBatch(Base):
    """Record of an ingested upload, keyed for idempotent replay.

    ``response_json`` holds the original response so a retry after a lost reply returns exactly
    what the device would have received the first time, rather than re-ingesting or reporting a
    conflict for records that were already accepted.
    """

    __tablename__ = "sync_batches"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    device_id: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    client_batch_id: Mapped[str] = mapped_column(String(64), nullable=False)
    item_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    accepted_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    rejected_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    response_json: Mapped[str] = mapped_column(Text, nullable=False, default="{}")
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow, index=True
    )
    #: Number of times the same batch id was replayed. A high count means a flaky uplink.
    replay_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    __table_args__ = (
        UniqueConstraint(
            "device_id", "client_batch_id", name="uq_sync_batches_device_id_client_batch_id"
        ),
    )


class AuditLog(Base):
    """Append-only trail.

    A statutory audit needs this, and so do we the first time a chain break shows up and someone
    asks which device uploaded it and when.
    """

    __tablename__ = "audit_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    actor: Mapped[str] = mapped_column(String(120), nullable=False, index=True)
    actor_role: Mapped[str | None] = mapped_column(String(32))
    action: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    target_type: Mapped[str | None] = mapped_column(String(48))
    target_id: Mapped[str | None] = mapped_column(String(120), index=True)
    outcome: Mapped[str] = mapped_column(String(24), nullable=False)
    detail: Mapped[str | None] = mapped_column(Text)
    ip_address: Mapped[str | None] = mapped_column(String(64))
    site_id: Mapped[str | None] = mapped_column(String(16), index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow, index=True
    )
