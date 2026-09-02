"""Cross-language parity for the canonical attestation encoding.

The single most important test in the backend suite.

The Android app signs certificates; this service verifies them. If the two implementations of the
canonical byte layout disagree by one byte, every certificate issued in the field verifies on the
phone and fails on the server — and the failure looks like widespread forgery rather than an
encoding bug. That is exactly the sort of defect that surfaces after a thousand workers have been
certified.

Both sides are pinned to one committed file. ``core``'s ``AttestationVectorsTest`` asserts the
Kotlin encoder against it; this module asserts the Python encoder against the same bytes. A change
to either without regenerating the fixtures turns a test red.

Regenerate deliberately with ``gradlew :core:generateFixtures``, and bump the format version at the
same time.
"""

from __future__ import annotations

import json

import pytest

from app.core import canonical, crypto
from tests.conftest import FIXTURE_PATH


@pytest.fixture(scope="module")
def vectors() -> dict:
    if not FIXTURE_PATH.is_file():
        pytest.fail(
            f"Cross-language fixtures are missing at {FIXTURE_PATH}.\n"
            "Generate them with:  gradlew :core:generateFixtures"
        )
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


def _attestation(vector: dict) -> canonical.Attestation:
    return canonical.Attestation(
        site_id=vector["siteId"],
        seq=vector["seq"],
        worker_id_hash=bytes.fromhex(vector["workerIdHashHex"]),
        module_code=vector["moduleCode"],
        score_permille=vector["scorePermille"],
        median_latency_ms=vector["medianLatencyMs"],
        outcome_flags=vector["outcomeFlags"],
        issued_at_epoch_min=vector["issuedAtEpochMin"],
        prev_record_hash=bytes.fromhex(vector["prevRecordHashHex"]),
    )


def test_fixture_metadata_matches_this_build(vectors: dict) -> None:
    assert vectors["formatVersion"] == canonical.FORMAT_VERSION
    assert vectors["magic"].encode("ascii") == canonical.MAGIC
    assert vectors["qrTextPrefix"] == canonical.QR_TEXT_PREFIX
    assert vectors["vectors"], "the fixture file contains no vectors"


def test_fixture_key_material_is_self_consistent(vectors: dict) -> None:
    private_key = bytes.fromhex(vectors["sitePrivateKeyHex"])
    public_key = bytes.fromhex(vectors["sitePublicKeyHex"])
    assert crypto.public_key_from_private(private_key) == public_key


def test_python_reproduces_every_canonical_byte_string(vectors: dict) -> None:
    private_key = bytes.fromhex(vectors["sitePrivateKeyHex"])

    for vector in vectors["vectors"]:
        name = vector["name"]
        attestation = _attestation(vector)

        produced = canonical.canonical_bytes(attestation)
        assert produced.hex() == vector["canonicalHex"], f"canonical bytes differ for '{name}'"

        signature = crypto.sign(private_key, produced)
        assert signature.hex() == vector["signatureHex"], f"signature differs for '{name}'"

        record = canonical.record_hash(produced, signature)
        assert record.hex() == vector["recordHashHex"], f"record hash differs for '{name}'"

        signed = canonical.SignedAttestation(attestation, signature, record)
        assert canonical.encode_qr(signed) == vector["qrText"], f"QR text differs for '{name}'"


def test_python_decodes_every_fixture_qr(vectors: dict) -> None:
    public_key = bytes.fromhex(vectors["sitePublicKeyHex"])

    for vector in vectors["vectors"]:
        decoded = canonical.decode_qr(vector["qrText"])
        assert decoded.attestation == _attestation(vector), vector["name"]
        assert decoded.record_hash.hex() == vector["recordHashHex"]

        message = canonical.canonical_bytes(decoded.attestation)
        assert crypto.verify(public_key, message, decoded.signature), vector["name"]


def test_worker_id_hashes_match_their_plaintext(vectors: dict) -> None:
    for vector in vectors["vectors"]:
        assert (
            canonical.worker_id_hash(vector["workerId"]).hex()
            == vector["workerIdHashHex"]
        ), vector["name"]


def test_canonical_round_trip_through_decode(vectors: dict) -> None:
    for vector in vectors["vectors"]:
        canonical_bytes = bytes.fromhex(vector["canonicalHex"])
        assert canonical.decode_canonical(canonical_bytes) == _attestation(vector)


def test_fixtures_cover_the_format_boundaries(vectors: dict) -> None:
    """Boundary coverage is the point of a parity suite.

    Two implementations rarely disagree on a typical value; they disagree at zero, at the maximum,
    and where a sign bit or a padding rule is involved.
    """
    names = {vector["name"] for vector in vectors["vectors"]}
    assert {"genesis", "min_field_values", "max_field_values"} <= names

    maximum = next(v for v in vectors["vectors"] if v["name"] == "max_field_values")
    assert maximum["seq"] == canonical.MAX_U32
    assert maximum["medianLatencyMs"] == canonical.MAX_U32
    assert maximum["issuedAtEpochMin"] == canonical.MAX_U32
    assert maximum["outcomeFlags"] == canonical.FLAG_ALL_MASK
    assert len(maximum["siteId"]) == canonical.MAX_SITE_ID_BYTES

    minimum = next(v for v in vectors["vectors"] if v["name"] == "min_field_values")
    assert minimum["medianLatencyMs"] == 0
    assert minimum["issuedAtEpochMin"] == 0
    assert minimum["outcomeFlags"] == 0


def test_every_fixture_qr_stays_inside_the_scannable_budget(vectors: dict) -> None:
    for vector in vectors["vectors"]:
        payload = vector["qrText"][len(canonical.QR_TEXT_PREFIX) :]
        decoded = canonical._b64url_decode(payload)  # noqa: SLF001 - checking the wire size
        assert len(decoded) <= canonical.QR_MAX_PAYLOAD_BYTES


def test_typical_certificate_is_the_documented_size(vectors: dict) -> None:
    """Pinned so a field addition cannot quietly push real QR codes past readable density."""
    genesis = next(v for v in vectors["vectors"] if v["name"] == "genesis")
    assert len(bytes.fromhex(genesis["canonicalHex"])) == 97
    assert len(genesis["qrText"]) == 216


class TestPythonEncoderValidation:
    """The Python side must reject exactly what Kotlin rejects."""

    def test_rejects_reserved_flag_bits(self) -> None:
        with pytest.raises(canonical.CanonicalFormatError, match="reserved"):
            canonical.Attestation(
                site_id="JH-DHN-001",
                seq=1,
                worker_id_hash=bytes(32),
                module_code=1,
                score_permille=800,
                median_latency_ms=1_000,
                outcome_flags=0xC1,
                issued_at_epoch_min=1,
                prev_record_hash=canonical.ZERO_HASH,
            )

    def test_rejects_genesis_with_a_non_zero_predecessor(self) -> None:
        with pytest.raises(canonical.CanonicalFormatError, match="genesis"):
            canonical.Attestation(
                site_id="JH-DHN-001",
                seq=1,
                worker_id_hash=bytes(32),
                module_code=1,
                score_permille=800,
                median_latency_ms=1_000,
                outcome_flags=1,
                issued_at_epoch_min=1,
                prev_record_hash=bytes([9]) * 32,
            )

    def test_rejects_non_genesis_with_a_zero_predecessor(self) -> None:
        with pytest.raises(canonical.CanonicalFormatError, match="non-genesis"):
            canonical.Attestation(
                site_id="JH-DHN-001",
                seq=2,
                worker_id_hash=bytes(32),
                module_code=1,
                score_permille=800,
                median_latency_ms=1_000,
                outcome_flags=1,
                issued_at_epoch_min=1,
                prev_record_hash=canonical.ZERO_HASH,
            )

    def test_rejects_an_oversized_site_id(self) -> None:
        with pytest.raises(canonical.CanonicalFormatError, match="QR budget"):
            canonical.Attestation(
                site_id="JH-DHANBAD-BLOCK-2-SEAM-7",
                seq=1,
                worker_id_hash=bytes(32),
                module_code=1,
                score_permille=800,
                median_latency_ms=1_000,
                outcome_flags=1,
                issued_at_epoch_min=1,
                prev_record_hash=canonical.ZERO_HASH,
            )

    def test_rejects_module_code_zero(self) -> None:
        with pytest.raises(canonical.CanonicalFormatError, match="module_code"):
            canonical.Attestation(
                site_id="JH-DHN-001",
                seq=1,
                worker_id_hash=bytes(32),
                module_code=0,
                score_permille=800,
                median_latency_ms=1_000,
                outcome_flags=1,
                issued_at_epoch_min=1,
                prev_record_hash=canonical.ZERO_HASH,
            )

    def test_rejects_a_score_above_one_thousand(self) -> None:
        with pytest.raises(canonical.CanonicalFormatError, match="score_permille"):
            canonical.Attestation(
                site_id="JH-DHN-001",
                seq=1,
                worker_id_hash=bytes(32),
                module_code=1,
                score_permille=1_001,
                median_latency_ms=1_000,
                outcome_flags=1,
                issued_at_epoch_min=1,
                prev_record_hash=canonical.ZERO_HASH,
            )

    def test_rejects_a_qr_from_another_app(self) -> None:
        with pytest.raises(canonical.CanonicalFormatError, match="not a Jaagruk certificate"):
            canonical.decode_qr("WIFI:S:MineNetwork;T:WPA;P:secret;;")
        assert canonical.decode_qr_or_none("WIFI:S:x;;") is None

    def test_rejects_a_truncated_payload(self, vectors: dict) -> None:
        text = vectors["vectors"][0]["qrText"]
        for cut in (10, 40, 100, 180, 210):
            with pytest.raises(canonical.CanonicalFormatError):
                canonical.decode_qr(text[:cut])

    def test_accepts_the_printable_url_form(self, vectors: dict) -> None:
        signed = canonical.decode_qr(vectors["vectors"][0]["qrText"])
        url = canonical.encode_qr_url(signed, "https://jaagruk.jharkhand.gov.in")

        assert url.startswith("https://jaagruk.jharkhand.gov.in/v/")
        assert canonical.decode_qr(url).attestation == signed.attestation
        # A scanner or shortener appending tracking parameters must not break verification.
        assert canonical.decode_qr(f"{url}?utm_source=camera").attestation == signed.attestation
        assert canonical.decode_qr(f"{url}#top").attestation == signed.attestation

    def test_detects_every_single_bit_flip(self, vectors: dict) -> None:
        """Either the decoder or the signature must catch every one-bit mutation."""
        public_key = bytes.fromhex(vectors["sitePublicKeyHex"])
        text = vectors["vectors"][0]["qrText"]
        payload = canonical._b64url_decode(text[len(canonical.QR_TEXT_PREFIX) :])  # noqa: SLF001

        for index in range(len(payload)):
            mutated = bytearray(payload)
            mutated[index] ^= 0x01
            candidate = canonical.QR_TEXT_PREFIX + canonical._b64url_encode(bytes(mutated))  # noqa: SLF001

            decoded = canonical.decode_qr_or_none(candidate)
            trusted = decoded is not None and crypto.verify(
                public_key,
                canonical.canonical_bytes(decoded.attestation),
                decoded.signature,
            )
            assert not trusted, f"a flipped bit at byte {index} was not detected"

    def test_flag_descriptions_are_stable(self) -> None:
        flags = (
            canonical.FLAG_PASSED
            | canonical.FLAG_HESITATION
            | canonical.FLAG_BUDDY_DRILL
            | canonical.FLAG_SITE_SCANNED_AR
        )
        assert canonical.describe_flags(flags) == [
            "passed",
            "hesitation",
            "buddy_drill",
            "site_scanned_ar",
        ]
        assert canonical.describe_flags(0) == []
