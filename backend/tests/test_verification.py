"""Certificate verification and full-chain audit through the HTTP surface."""

from __future__ import annotations

import uuid

from fastapi.testclient import TestClient
from sqlalchemy import select

from app.core import canonical
from app.db.base import utcnow
from app.models import Certificate, CertificateStatus, SiteKey
from tests.conftest import (
    DEVICE_A,
    SITE_A,
    SITE_A_PRIVATE_KEY,
    SITE_B,
    SITE_B_PRIVATE_KEY,
    auth_headers,
    build_certificate,
    build_chain,
    certificate_upload,
)


def _upload(client: TestClient, headers: dict, uploads: list[dict]) -> dict:
    response = client.post(
        "/api/v1/sync/batch",
        json={
            "device_id": DEVICE_A,
            "client_batch_id": f"batch-{uuid.uuid4().hex[:16]}",
            "certificates": uploads,
            "assessments": [],
            "hazards": [],
            "progress": [],
        },
        headers=headers,
    )
    assert response.status_code == 200, response.text
    return response.json()


def _verify(client: TestClient, headers: dict, qr_text: str, **extra) -> dict:  # noqa: ANN003
    payload = {"qr_text": qr_text}
    payload.update(extra)
    response = client.post("/api/v1/certificates/verify", json=payload, headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


class TestVerificationStatuses:
    def test_a_linked_certificate_verifies(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        worker_id = f"{SITE_A}-W00001"
        signed, qr = build_certificate(worker_id=worker_id)
        _upload(client, supervisor_a_headers, [certificate_upload(signed, qr, worker_id)])

        body = _verify(client, supervisor_a_headers, qr)
        assert body["status"] == "verified"
        assert body["trustworthy"] is True
        assert body["indicates_tampering"] is False
        assert body["site_id"] == SITE_A
        assert body["seq"] == 1
        assert body["score_permille"] == 842
        assert "passed" in body["flag_names"]
        assert body["module_title_en"] == "Fire & Explosion Response"

    def test_partial_trust_when_the_chain_is_not_held(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        """An inspector's first visit to a site is legitimate, not a failure.

        Reporting it as "invalid" would train inspectors to ignore the tool.
        """
        signed, qr = build_certificate(worker_id=f"{SITE_A}-W00001")

        body = _verify(client, supervisor_a_headers, qr)
        assert body["status"] == "signature_valid_chain_unknown"
        assert body["trustworthy"] is True
        assert body["indicates_tampering"] is False
        assert any("no chain records" in reason for reason in body["reasons"])

    def test_a_certificate_newer_than_the_local_copy_is_partial_trust(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        records = build_chain(5)
        # Hold only the first two, then verify the fifth.
        for index in (0, 1):
            signed, qr = records[index]
            _upload(
                client,
                supervisor_a_headers,
                [certificate_upload(signed, qr, f"{SITE_A}-W0000{index + 1}")],
            )

        body = _verify(client, supervisor_a_headers, records[4][1])
        assert body["status"] == "signature_valid_chain_unknown"
        assert any("ahead of the newest record" in reason for reason in body["reasons"])

    def test_an_unknown_site_key_is_reported_as_such(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        db.execute(SiteKey.__table__.delete().where(SiteKey.site_id == SITE_A))
        db.commit()

        signed, qr = build_certificate(worker_id=f"{SITE_A}-W00001")
        body = _verify(client, supervisor_a_headers, qr)
        assert body["status"] == "unknown_site_key"
        assert body["trustworthy"] is False
        assert body["indicates_tampering"] is False

    def test_a_tampered_score_fails_the_signature(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        signed, qr = build_certificate(worker_id=f"{SITE_A}-W00001", score_permille=705)

        # Re-encode with a raised score but the original signature, exactly what a forger would try.
        forged = canonical.Attestation(
            site_id=signed.attestation.site_id,
            seq=signed.attestation.seq,
            worker_id_hash=signed.attestation.worker_id_hash,
            module_code=signed.attestation.module_code,
            score_permille=995,
            median_latency_ms=signed.attestation.median_latency_ms,
            outcome_flags=signed.attestation.outcome_flags,
            issued_at_epoch_min=signed.attestation.issued_at_epoch_min,
            prev_record_hash=signed.attestation.prev_record_hash,
        )
        forged_signed = canonical.SignedAttestation(
            attestation=forged,
            signature=signed.signature,
            record_hash=canonical.record_hash(
                canonical.canonical_bytes(forged), signed.signature
            ),
        )
        body = _verify(client, supervisor_a_headers, canonical.encode_qr(forged_signed))
        assert body["status"] == "bad_signature"
        assert body["indicates_tampering"] is True

    def test_a_certificate_from_another_site_key_fails(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        signed, qr = build_certificate(
            site_id=SITE_A, worker_id=f"{SITE_A}-W00001", private_key=SITE_B_PRIVATE_KEY
        )
        assert _verify(client, supervisor_a_headers, qr)["status"] == "bad_signature"

    def test_an_inserted_record_breaks_the_link(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        records = build_chain(2)
        for index, (signed, qr) in enumerate(records, start=1):
            _upload(
                client,
                supervisor_a_headers,
                [certificate_upload(signed, qr, f"{SITE_A}-W0000{index}")],
            )

        spliced, spliced_qr = build_certificate(
            seq=3, worker_id=f"{SITE_A}-W00003", prev_record_hash=bytes([0x33]) * 32
        )
        body = _verify(client, supervisor_a_headers, spliced_qr)
        assert body["status"] == "broken_link"
        assert body["indicates_tampering"] is True

    def test_a_deleted_record_shows_as_a_sequence_gap(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        records = build_chain(4)
        for index, (signed, qr) in enumerate(records, start=1):
            _upload(
                client,
                supervisor_a_headers,
                [certificate_upload(signed, qr, f"{SITE_A}-W0000{min(index, 3)}")],
            )

        # Remove the record at seq 3, simulating someone deleting an inconvenient certificate.
        db.execute(
            Certificate.__table__.delete().where(
                Certificate.site_id == SITE_A, Certificate.seq == 3
            )
        )
        db.commit()

        body = _verify(client, supervisor_a_headers, records[3][1])
        assert body["status"] == "sequence_gap"

    def test_malformed_qr_is_reported_without_a_record(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        body = _verify(client, supervisor_a_headers, "WIFI:S:MineNet;T:WPA;P:x;;")
        assert body["status"] == "malformed"
        assert body["trustworthy"] is False
        assert body["site_id"] is None

    def test_verification_works_from_the_printable_url_form(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        worker_id = f"{SITE_A}-W00001"
        signed, qr = build_certificate(worker_id=worker_id)
        _upload(client, supervisor_a_headers, [certificate_upload(signed, qr, worker_id)])

        url = canonical.encode_qr_url(signed, "https://jaagruk.jharkhand.gov.in")
        assert _verify(client, supervisor_a_headers, url)["status"] == "verified"


class TestWorkerIdentityConfirmation:
    def test_a_matching_worker_id_is_confirmed(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        worker_id = f"{SITE_A}-W00001"
        signed, qr = build_certificate(worker_id=worker_id)

        body = _verify(client, supervisor_a_headers, qr, candidate_worker_id=worker_id)
        assert body["worker_id_matches"] is True
        assert body["worker_full_name"] == "Birsa Munda"

    def test_a_mismatched_worker_id_is_reported(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        signed, qr = build_certificate(worker_id=f"{SITE_A}-W00001")
        body = _verify(
            client, supervisor_a_headers, qr, candidate_worker_id=f"{SITE_A}-W00002"
        )
        assert body["worker_id_matches"] is False

    def test_the_qr_never_carries_a_plaintext_worker_id(self) -> None:
        """A dropped certificate card must disclose no identity."""
        worker_id = f"{SITE_A}-W00001"
        _, qr = build_certificate(worker_id=worker_id)
        assert worker_id not in qr
        assert "W00001" not in qr


class TestStatutoryAndReadinessSeparation:
    def test_verification_reports_statutory_validity(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        recent = int(utcnow().timestamp()) - 30 * 86_400
        signed, qr = build_certificate(worker_id=f"{SITE_A}-W00001", issued_at_sec=recent)

        body = _verify(client, supervisor_a_headers, qr)
        assert body["statutory_valid"] is True
        assert body["statutory_expiry_sec"] > int(utcnow().timestamp())

    def test_an_old_certificate_is_statutorily_expired(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        old = int(utcnow().timestamp()) - 400 * 86_400
        signed, qr = build_certificate(worker_id=f"{SITE_A}-W00001", issued_at_sec=old)

        body = _verify(client, supervisor_a_headers, qr)
        assert body["statutory_valid"] is False


class TestChainAudit:
    def test_an_empty_chain_audits_clean(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        response = client.post(
            f"/api/v1/chains/{SITE_A}/verify", headers=supervisor_a_headers
        )
        assert response.status_code == 200
        body = response.json()
        assert body["clean"] is True
        assert body["records_checked"] == 0

    def test_a_full_valid_chain_audits_clean(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        records = build_chain(8)
        for index, (signed, qr) in enumerate(records, start=1):
            _upload(
                client,
                supervisor_a_headers,
                [certificate_upload(signed, qr, f"{SITE_A}-W0000{min(index, 3)}")],
            )

        body = client.post(
            f"/api/v1/chains/{SITE_A}/verify", headers=supervisor_a_headers
        ).json()
        assert body["clean"] is True
        assert body["status"] == "verified"
        assert body["records_checked"] == 8
        assert body["first_problem_seq"] is None

    def test_the_audit_finds_a_missing_record_and_names_the_sequence(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        records = build_chain(6)
        for index, (signed, qr) in enumerate(records, start=1):
            _upload(
                client,
                supervisor_a_headers,
                [certificate_upload(signed, qr, f"{SITE_A}-W0000{min(index, 3)}")],
            )
        db.execute(
            Certificate.__table__.delete().where(
                Certificate.site_id == SITE_A, Certificate.seq == 4
            )
        )
        db.commit()

        body = client.post(
            f"/api/v1/chains/{SITE_A}/verify", headers=supervisor_a_headers
        ).json()
        assert body["status"] == "sequence_gap"
        assert body["first_problem_seq"] == 4
        assert body["clean"] is False

    def test_the_audit_finds_an_altered_score(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        records = build_chain(4)
        for index, (signed, qr) in enumerate(records, start=1):
            _upload(
                client,
                supervisor_a_headers,
                [certificate_upload(signed, qr, f"{SITE_A}-W0000{min(index, 3)}")],
            )

        # Alter a stored score directly, as a database-level tamper would.
        stored = db.scalars(
            select(Certificate).where(
                Certificate.site_id == SITE_A, Certificate.seq == 2
            )
        ).one()
        stored.score_permille = 1_000
        db.commit()

        body = client.post(
            f"/api/v1/chains/{SITE_A}/verify", headers=supervisor_a_headers
        ).json()
        assert body["status"] == "bad_signature"
        assert body["first_problem_seq"] == 2

    def test_the_audit_stops_at_the_first_problem(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        records = build_chain(10)
        for index, (signed, qr) in enumerate(records, start=1):
            _upload(
                client,
                supervisor_a_headers,
                [certificate_upload(signed, qr, f"{SITE_A}-W0000{min(index, 3)}")],
            )
        stored = db.scalars(
            select(Certificate).where(
                Certificate.site_id == SITE_A, Certificate.seq == 3
            )
        ).one()
        stored.score_permille = 999
        db.commit()

        body = client.post(
            f"/api/v1/chains/{SITE_A}/verify", headers=supervisor_a_headers
        ).json()
        # Everything past an unexplained break is untrustworthy anyway.
        assert body["records_checked"] == 3

    def test_the_audit_reports_quarantined_sequences(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        worker_id = f"{SITE_A}-W00001"
        first, first_qr = build_certificate(seq=1, worker_id=worker_id)
        _upload(client, supervisor_a_headers, [certificate_upload(first, first_qr, worker_id)])

        spliced, spliced_qr = build_certificate(
            seq=2, worker_id=worker_id, prev_record_hash=bytes([0x55]) * 32
        )
        _upload(
            client, supervisor_a_headers, [certificate_upload(spliced, spliced_qr, worker_id)]
        )

        body = client.post(
            f"/api/v1/chains/{SITE_A}/verify", headers=supervisor_a_headers
        ).json()
        assert 2 in body["quarantined_seqs"]

    def test_the_audit_is_scoped(self, client: TestClient, seeded: dict) -> None:
        response = client.post(
            f"/api/v1/chains/{SITE_B}/verify", headers=auth_headers(client, "officer.a")
        )
        assert response.status_code == 404


class TestKeyEpochs:
    def test_a_certificate_from_a_previous_epoch_still_verifies(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        """Losing a supervisor phone must not invalidate the certificates already issued."""
        worker_id = f"{SITE_A}-W00001"
        signed, qr = build_certificate(worker_id=worker_id, private_key=SITE_A_PRIVATE_KEY)
        _upload(client, supervisor_a_headers, [certificate_upload(signed, qr, worker_id)])

        # Roll to a new epoch: the old key is retired but archived, not deleted.
        old = db.scalars(select(SiteKey).where(SiteKey.site_id == SITE_A)).one()
        old.active = False
        old.revoked_at = utcnow()
        old.revocation_reason = "supervisor handset lost"

        new_private = bytes(range(100, 132))
        from app.core import crypto

        db.add(
            SiteKey(
                id=str(uuid.uuid4()),
                site_id=SITE_A,
                epoch=2,
                public_key=crypto.public_key_from_private(new_private),
                active=True,
            )
        )
        db.commit()

        assert _verify(client, supervisor_a_headers, qr)["status"] == "verified"

    def test_registering_a_conflicting_key_at_the_same_epoch_is_refused(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        # Two different keys at one epoch would make verification ambiguous.
        response = client.post(
            "/api/v1/devices/register",
            json={
                "device_id": "new-device-0001",
                "site_id": SITE_A,
                "site_public_key_hex": bytes(range(200, 232)).hex(),
                "key_epoch": 1,
            },
            headers=supervisor_a_headers,
        )
        assert response.status_code == 409
        assert "next epoch" in response.json()["detail"]

    def test_re_registering_the_same_device_and_key_is_idempotent(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        from app.core import crypto

        payload = {
            "device_id": DEVICE_A,
            "site_id": SITE_A,
            "site_public_key_hex": crypto.public_key_from_private(SITE_A_PRIVATE_KEY).hex(),
            "key_epoch": 1,
            "model": "Redmi Note 13",
        }
        first = client.post(
            "/api/v1/devices/register", json=payload, headers=supervisor_a_headers
        )
        second = client.post(
            "/api/v1/devices/register", json=payload, headers=supervisor_a_headers
        )
        # A reinstall must not lock a supervisor out of their own site.
        assert first.status_code == second.status_code == 201

    def test_a_device_cannot_be_moved_between_sites_silently(
        self, client: TestClient, seeded: dict
    ) -> None:
        from app.core import crypto

        response = client.post(
            "/api/v1/devices/register",
            json={
                "device_id": DEVICE_A,
                "site_id": SITE_B,
                "site_public_key_hex": crypto.public_key_from_private(
                    SITE_B_PRIVATE_KEY
                ).hex(),
                "key_epoch": 1,
            },
            headers=auth_headers(client, "supervisor.b"),
        )
        assert response.status_code == 409

    def test_public_keys_endpoint_returns_every_epoch(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        body = client.get(
            f"/api/v1/sites/{SITE_A}/public-keys", headers=supervisor_a_headers
        ).json()
        assert body["site_id"] == SITE_A
        assert len(body["keys"]) == 1
        assert len(bytes.fromhex(body["keys"][0]["public_key_hex"])) == 32


class TestCertificateListing:
    def test_listing_is_scoped_and_paginated(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        records = build_chain(5)
        for index, (signed, qr) in enumerate(records, start=1):
            _upload(
                client,
                supervisor_a_headers,
                [certificate_upload(signed, qr, f"{SITE_A}-W0000{min(index, 3)}")],
            )

        page = client.get(
            "/api/v1/certificates?page_size=2", headers=supervisor_a_headers
        ).json()
        assert page["total"] == 5
        assert len(page["items"]) == 2
        assert all(item["site_id"] == SITE_A for item in page["items"])

    def test_quarantined_filter_isolates_the_alerts(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        worker_id = f"{SITE_A}-W00001"
        good, good_qr = build_certificate(seq=1, worker_id=worker_id)
        _upload(client, supervisor_a_headers, [certificate_upload(good, good_qr, worker_id)])

        bad, bad_qr = build_certificate(
            seq=2, worker_id=worker_id, prev_record_hash=bytes([0x77]) * 32
        )
        _upload(client, supervisor_a_headers, [certificate_upload(bad, bad_qr, worker_id)])

        page = client.get(
            "/api/v1/certificates?only_quarantined=true", headers=supervisor_a_headers
        ).json()
        assert page["total"] == 1
        assert page["items"][0]["status"] == CertificateStatus.QUARANTINED.value
        assert page["items"][0]["quarantine_reason"]

    def test_an_unknown_certificate_is_404(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        response = client.get(
            f"/api/v1/certificates/{uuid.uuid4()}", headers=supervisor_a_headers
        )
        assert response.status_code == 404
