"""Python mirror of the Kotlin canonical attestation encoding.

This module and ``core/src/main/kotlin/org/jaagruk/core/cert/AttestationCodec.kt`` must produce
byte-identical output. If they diverge by a single byte, every certificate the app issues will
verify on the phone and fail on the server, and the failure will look like widespread forgery
rather than an encoding bug.

That risk is retired by ``backend/tests/test_canonical_parity.py``, which asserts this
implementation against the very same committed fixture file the Kotlin test uses:
``core/src/test/resources/fixtures/attestation_vectors.json``. Neither side can drift without
turning a test red.

Specification: ``docs/ARCHITECTURE.md`` section 2.
"""

from __future__ import annotations

import base64
import hashlib
import struct
from dataclasses import dataclass

MAGIC = b"JGKA"
FORMAT_VERSION = 1
HEADER_SIZE = 5  # magic(4) + formatVersion(1)

QR_TEXT_PREFIX = "JGK1:"
QR_MAGIC = b"J"
QR_VERIFY_URL_MARKER = "/v/"
QR_MAX_PAYLOAD_BYTES = 512
# magic(1) + version(1) + smallest possible body + signature(64)
QR_MIN_PAYLOAD_BYTES = 2 + 84 + 64

HASH_SIZE = 32
SIGNATURE_SIZE = 64
ZERO_HASH = bytes(HASH_SIZE)

MAX_SITE_ID_BYTES = 16
MIN_SEQ = 1
MAX_U32 = 0xFFFF_FFFF
MAX_SCORE_PERMILLE = 1000

# outcomeFlags bit layout — mirrors OutcomeFlags.kt
FLAG_PASSED = 1 << 0
FLAG_HESITATION = 1 << 1
FLAG_BUDDY_DRILL = 1 << 2
FLAG_SITE_SCANNED_AR = 1 << 3
FLAG_REFRESHER = 1 << 4
FLAG_ASSISTED_MODE = 1 << 5
FLAG_RESERVED_MASK = (1 << 6) | (1 << 7)
FLAG_ALL_MASK = 0x3F


class CanonicalFormatError(ValueError):
    """Raised when bytes do not conform to the canonical encoding.

    Decoding never returns a partially populated object and never guesses. A malformed
    certificate must surface as "malformed", not as one that merely fails verification.
    """


@dataclass(frozen=True, slots=True)
class Attestation:
    """The one and only signed object in the certification scheme.

    Carries ``worker_id_hash``, never a plaintext worker id, so a lost certificate card
    discloses no identity.
    """

    site_id: str
    seq: int
    worker_id_hash: bytes
    module_code: int
    score_permille: int
    median_latency_ms: int
    outcome_flags: int
    issued_at_epoch_min: int
    prev_record_hash: bytes
    format_version: int = FORMAT_VERSION

    def __post_init__(self) -> None:
        _validate(self)

    @property
    def is_genesis(self) -> bool:
        return self.seq == MIN_SEQ

    @property
    def passed(self) -> bool:
        return bool(self.outcome_flags & FLAG_PASSED)

    @property
    def hesitation_flagged(self) -> bool:
        return bool(self.outcome_flags & FLAG_HESITATION)

    @property
    def buddy_drill(self) -> bool:
        return bool(self.outcome_flags & FLAG_BUDDY_DRILL)

    @property
    def site_scanned_ar(self) -> bool:
        return bool(self.outcome_flags & FLAG_SITE_SCANNED_AR)

    @property
    def refresher(self) -> bool:
        return bool(self.outcome_flags & FLAG_REFRESHER)

    @property
    def assisted_mode(self) -> bool:
        return bool(self.outcome_flags & FLAG_ASSISTED_MODE)

    @property
    def issued_at_epoch_sec(self) -> int:
        return self.issued_at_epoch_min * 60


@dataclass(frozen=True, slots=True)
class SignedAttestation:
    attestation: Attestation
    signature: bytes
    record_hash: bytes

    def __post_init__(self) -> None:
        if len(self.signature) != SIGNATURE_SIZE:
            raise CanonicalFormatError(
                f"signature must be {SIGNATURE_SIZE} bytes, got {len(self.signature)}"
            )
        if len(self.record_hash) != HASH_SIZE:
            raise CanonicalFormatError(
                f"record_hash must be {HASH_SIZE} bytes, got {len(self.record_hash)}"
            )


def _validate(a: Attestation) -> None:
    if a.format_version != FORMAT_VERSION:
        raise CanonicalFormatError(
            f"unsupported attestation format version {a.format_version} "
            f"(this build speaks {FORMAT_VERSION})"
        )
    if not a.site_id or not a.site_id.strip():
        raise CanonicalFormatError("site_id must not be blank")
    site_id_bytes = len(a.site_id.encode("utf-8"))
    if site_id_bytes > MAX_SITE_ID_BYTES:
        raise CanonicalFormatError(
            f"site_id is {site_id_bytes} bytes; the QR budget allows at most {MAX_SITE_ID_BYTES}"
        )
    if not MIN_SEQ <= a.seq <= MAX_U32:
        raise CanonicalFormatError(f"seq must be in {MIN_SEQ}..{MAX_U32}, got {a.seq}")
    if len(a.worker_id_hash) != HASH_SIZE:
        raise CanonicalFormatError(
            f"worker_id_hash must be {HASH_SIZE} bytes, got {len(a.worker_id_hash)}"
        )
    if not 1 <= a.module_code <= 255:
        raise CanonicalFormatError(
            f"module_code must be 1..255 (0 is reserved), got {a.module_code}"
        )
    if not 0 <= a.score_permille <= MAX_SCORE_PERMILLE:
        raise CanonicalFormatError(
            f"score_permille must be 0..{MAX_SCORE_PERMILLE}, got {a.score_permille}"
        )
    if not 0 <= a.median_latency_ms <= MAX_U32:
        raise CanonicalFormatError(
            f"median_latency_ms must fit in u32, got {a.median_latency_ms}"
        )
    if not 0 <= a.outcome_flags <= 0xFF:
        raise CanonicalFormatError(f"outcome_flags out of u8 range: {a.outcome_flags}")
    if a.outcome_flags & FLAG_RESERVED_MASK:
        raise CanonicalFormatError(
            f"reserved outcome_flags bits set (0x{a.outcome_flags:02X}); "
            "payload targets a newer format"
        )
    if not 0 <= a.issued_at_epoch_min <= MAX_U32:
        raise CanonicalFormatError(
            f"issued_at_epoch_min must fit in u32, got {a.issued_at_epoch_min}"
        )
    if len(a.prev_record_hash) != HASH_SIZE:
        raise CanonicalFormatError(
            f"prev_record_hash must be {HASH_SIZE} bytes, got {len(a.prev_record_hash)}"
        )
    if a.seq == MIN_SEQ and a.prev_record_hash != ZERO_HASH:
        raise CanonicalFormatError(
            f"the genesis certificate (seq={MIN_SEQ}) must carry a zero prev_record_hash"
        )
    if a.seq != MIN_SEQ and a.prev_record_hash == ZERO_HASH:
        raise CanonicalFormatError(
            f"a non-genesis certificate (seq={a.seq}) must carry a non-zero prev_record_hash"
        )


# ---------------------------------------------------------------------------
# Encoding
# ---------------------------------------------------------------------------


def canonical_bytes(a: Attestation) -> bytes:
    """The exact bytes that get signed.

    Big-endian throughout, and every variable-length field length-prefixed, so no value can be
    shifted into an adjacent field.
    """
    site_id = a.site_id.encode("utf-8")
    if len(site_id) > MAX_SITE_ID_BYTES:
        raise CanonicalFormatError(f"site_id exceeds {MAX_SITE_ID_BYTES} bytes")

    return b"".join(
        (
            MAGIC,
            struct.pack(">B", a.format_version),
            struct.pack(">H", len(site_id)),
            site_id,
            struct.pack(">I", a.seq),
            a.worker_id_hash,
            struct.pack(">B", a.module_code),
            struct.pack(">H", a.score_permille),
            struct.pack(">I", a.median_latency_ms),
            struct.pack(">B", a.outcome_flags),
            struct.pack(">I", a.issued_at_epoch_min),
            a.prev_record_hash,
        )
    )


def body_bytes(a: Attestation) -> bytes:
    """Canonical bytes without the fixed header, for embedding in a QR payload."""
    return canonical_bytes(a)[HEADER_SIZE:]


def canonical_from_body(format_version: int, body: bytes) -> bytes:
    """Rebuild the signable canonical bytes from a header-less body."""
    if not 0 <= format_version <= 0xFF:
        raise CanonicalFormatError(f"format_version out of u8 range: {format_version}")
    return MAGIC + struct.pack(">B", format_version) + body


def record_hash(canonical: bytes, signature: bytes) -> bytes:
    """``SHA-256(canonical || signature)`` — the chain link a successor points back to.

    Hashing the signature as well as the payload means re-signing an identical payload with a
    different key produces a different link, so a record cannot be re-signed and spliced into an
    existing chain without breaking it.
    """
    if len(signature) != SIGNATURE_SIZE:
        raise CanonicalFormatError(
            f"signature must be {SIGNATURE_SIZE} bytes, got {len(signature)}"
        )
    return hashlib.sha256(canonical + signature).digest()


def worker_id_hash(worker_id: str) -> bytes:
    if not worker_id or not worker_id.strip():
        raise CanonicalFormatError("worker_id must not be blank")
    return hashlib.sha256(worker_id.encode("utf-8")).digest()


# ---------------------------------------------------------------------------
# Decoding
# ---------------------------------------------------------------------------


def decode_canonical(canonical: bytes) -> Attestation:
    """Parse signable canonical bytes.

    :raises CanonicalFormatError: bad magic, unsupported version, reserved flag bits,
        out-of-range values, truncation, or trailing bytes.
    """
    expected_length = HEADER_SIZE + 2 + 4 + HASH_SIZE + 1 + 2 + 4 + 1 + 4 + HASH_SIZE
    if len(canonical) < expected_length:
        raise CanonicalFormatError(
            f"canonical payload is {len(canonical)} bytes, too short to be an attestation"
        )
    if canonical[:4] != MAGIC:
        raise CanonicalFormatError(
            f"bad magic: expected {MAGIC!r}, got {canonical[:4]!r}"
        )

    offset = 4
    (format_version,) = struct.unpack_from(">B", canonical, offset)
    offset += 1
    if format_version != FORMAT_VERSION:
        raise CanonicalFormatError(
            f"attestation format version {format_version} is not supported by this build "
            f"(expected {FORMAT_VERSION}); update the server"
        )

    (site_id_len,) = struct.unpack_from(">H", canonical, offset)
    offset += 2
    if site_id_len > MAX_SITE_ID_BYTES:
        raise CanonicalFormatError(
            f"site_id length {site_id_len} exceeds cap {MAX_SITE_ID_BYTES}"
        )
    if offset + site_id_len > len(canonical):
        raise CanonicalFormatError("truncated site_id")
    try:
        site_id = canonical[offset : offset + site_id_len].decode("utf-8")
    except UnicodeDecodeError as exc:
        raise CanonicalFormatError(f"site_id is not valid UTF-8: {exc}") from exc
    offset += site_id_len

    try:
        (seq,) = struct.unpack_from(">I", canonical, offset)
        offset += 4
        worker_hash = canonical[offset : offset + HASH_SIZE]
        offset += HASH_SIZE
        (module_code,) = struct.unpack_from(">B", canonical, offset)
        offset += 1
        (score_permille,) = struct.unpack_from(">H", canonical, offset)
        offset += 2
        (median_latency_ms,) = struct.unpack_from(">I", canonical, offset)
        offset += 4
        (outcome_flags,) = struct.unpack_from(">B", canonical, offset)
        offset += 1
        (issued_at_epoch_min,) = struct.unpack_from(">I", canonical, offset)
        offset += 4
        prev_hash = canonical[offset : offset + HASH_SIZE]
        offset += HASH_SIZE
    except struct.error as exc:
        raise CanonicalFormatError(f"truncated attestation: {exc}") from exc

    if len(worker_hash) != HASH_SIZE or len(prev_hash) != HASH_SIZE:
        raise CanonicalFormatError("truncated hash field")
    if offset != len(canonical):
        raise CanonicalFormatError(
            f"{len(canonical) - offset} unexpected trailing byte(s) at offset {offset}"
        )

    return Attestation(
        format_version=format_version,
        site_id=site_id,
        seq=seq,
        worker_id_hash=worker_hash,
        module_code=module_code,
        score_permille=score_permille,
        median_latency_ms=median_latency_ms,
        outcome_flags=outcome_flags,
        issued_at_epoch_min=issued_at_epoch_min,
        prev_record_hash=prev_hash,
    )


# ---------------------------------------------------------------------------
# QR
# ---------------------------------------------------------------------------


def _b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _b64url_decode(text: str) -> bytes:
    if not text:
        raise CanonicalFormatError("empty base64url payload")
    stripped = text.rstrip("=")
    allowed = set(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    )
    for index, char in enumerate(stripped):
        if char not in allowed:
            raise CanonicalFormatError(
                f"illegal base64url character {char!r} at index {index}"
            )
    padding = "=" * (-len(stripped) % 4)
    try:
        return base64.urlsafe_b64decode(stripped + padding)
    except (ValueError, TypeError) as exc:
        raise CanonicalFormatError(f"malformed base64url payload: {exc}") from exc


def encode_qr(signed: SignedAttestation) -> str:
    body = body_bytes(signed.attestation)
    binary = (
        QR_MAGIC
        + struct.pack(">B", signed.attestation.format_version)
        + body
        + signed.signature
    )
    if len(binary) > QR_MAX_PAYLOAD_BYTES:
        raise CanonicalFormatError(
            f"QR payload grew to {len(binary)} bytes, over the {QR_MAX_PAYLOAD_BYTES} limit"
        )
    return QR_TEXT_PREFIX + _b64url_encode(binary)


def encode_qr_url(signed: SignedAttestation, base_url: str) -> str:
    if not base_url.strip():
        raise CanonicalFormatError("base_url must not be blank")
    payload = encode_qr(signed)[len(QR_TEXT_PREFIX) :]
    return base_url.rstrip("/") + QR_VERIFY_URL_MARKER + payload


def looks_like_certificate(qr_text: str) -> bool:
    trimmed = qr_text.strip()
    return trimmed.startswith(QR_TEXT_PREFIX) or QR_VERIFY_URL_MARKER in trimmed


def _extract_payload(qr_text: str) -> str:
    trimmed = qr_text.strip()
    if not trimmed:
        raise CanonicalFormatError("empty QR content")

    if trimmed.startswith(QR_TEXT_PREFIX):
        raw = trimmed[len(QR_TEXT_PREFIX) :]
    elif QR_VERIFY_URL_MARKER in trimmed:
        raw = trimmed.rsplit(QR_VERIFY_URL_MARKER, 1)[1]
    else:
        raise CanonicalFormatError(
            "this QR code is not a Jaagruk certificate (expected a "
            f"{QR_TEXT_PREFIX!r} prefix or a {QR_VERIFY_URL_MARKER!r} verification link)"
        )

    payload = raw.split("?", 1)[0].split("#", 1)[0].strip()
    if not payload:
        raise CanonicalFormatError("QR code carries a Jaagruk prefix but no payload")
    return payload


def decode_qr(qr_text: str) -> SignedAttestation:
    """Decode a QR string into a signed attestation.

    :raises CanonicalFormatError: for anything that is not a well-formed Jaagruk certificate.
        A damaged scan, a QR from another app and a truncated payload all land here — never as
        a silent partial decode and never as a false "valid".
    """
    binary = _b64url_decode(_extract_payload(qr_text))

    if len(binary) < QR_MIN_PAYLOAD_BYTES:
        raise CanonicalFormatError(
            f"certificate payload is only {len(binary)} bytes; at least "
            f"{QR_MIN_PAYLOAD_BYTES} are required (damaged or partial scan?)"
        )
    if len(binary) > QR_MAX_PAYLOAD_BYTES:
        raise CanonicalFormatError(
            f"certificate payload is {len(binary)} bytes, over the "
            f"{QR_MAX_PAYLOAD_BYTES} limit"
        )
    if binary[:1] != QR_MAGIC:
        raise CanonicalFormatError(
            f"bad QR magic: expected {QR_MAGIC!r}, got {binary[:1]!r}"
        )

    format_version = binary[1]
    body = binary[2 : len(binary) - SIGNATURE_SIZE]
    signature = binary[len(binary) - SIGNATURE_SIZE :]
    if not body:
        raise CanonicalFormatError("certificate payload has no body before its signature")

    canonical = canonical_from_body(format_version, body)
    attestation = decode_canonical(canonical)
    return SignedAttestation(
        attestation=attestation,
        signature=signature,
        record_hash=record_hash(canonical, signature),
    )


def decode_qr_or_none(qr_text: str) -> SignedAttestation | None:
    try:
        return decode_qr(qr_text)
    except CanonicalFormatError:
        return None


def describe_flags(outcome_flags: int) -> list[str]:
    """Human-readable flag names, for API responses and CSV exports."""
    names: list[str] = []
    if outcome_flags & FLAG_PASSED:
        names.append("passed")
    if outcome_flags & FLAG_HESITATION:
        names.append("hesitation")
    if outcome_flags & FLAG_BUDDY_DRILL:
        names.append("buddy_drill")
    if outcome_flags & FLAG_SITE_SCANNED_AR:
        names.append("site_scanned_ar")
    if outcome_flags & FLAG_REFRESHER:
        names.append("refresher")
    if outcome_flags & FLAG_ASSISTED_MODE:
        names.append("assisted_mode")
    return names
