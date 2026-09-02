"""Batch sync ingest: idempotency, per-item results, and the bootstrap down-sync.

Idempotency is the property that makes this platform usable on a mine-site uplink. Devices retry;
replies get lost; a supervisor's phone relays records that were also uploaded directly. Every one of
those must converge on exactly one stored row.
"""

from __future__ import annotations

import uuid

from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app.core import canonical
from app.db.base import utcnow
from app.models import AssessmentRun, Certificate, CertificateStatus, Hazard, TrainingProgress
from tests.conftest import (
    DEVICE_A,
    DEVICE_B,
    SITE_A,
    SITE_B,
    auth_headers,
    build_certificate,
    build_chain,
    certificate_upload,
)


def _batch(**overrides) -> dict:  # noqa: ANN003
    payload = {
        "device_id": DEVICE_A,
        "client_batch_id": f"batch-{uuid.uuid4().hex[:16]}",
        "certificates": [],
        "assessments": [],
        "hazards": [],
        "progress": [],
    }
    payload.update(overrides)
    return payload


def _assessment(worker_id: str, run_id: str | None = None, **overrides) -> dict:  # noqa: ANN003
    now = int(utcnow().timestamp())
    payload = {
        "idempotency_key": f"run-{uuid.uuid4().hex[:16]}",
        "run_id": run_id or str(uuid.uuid4()),
        "worker_id": worker_id,
        "site_id": SITE_A,
        "module_id": "fire-evacuation",
        "module_code": 1,
        "scenario_id": "fire-evac-full",
        "catalog_version": 1,
        "mode": "initial",
        "presentation": "site_scanned",
        "completion": "completed",
        "score_permille": 842,
        "passed": True,
        "hesitation_flag": False,
        "hesitation_ratio": 0.0,
        "median_latency_ms": 2_400,
        "started_at_sec": now - 600,
        "finished_at_sec": now,
        "total_duration_ms": 480_000,
        "steps": [],
        "failed_critical_step_ids": [],
    }
    payload.update(overrides)
    return payload


def _hazard(**overrides) -> dict:  # noqa: ANN003
    payload = {
        "idempotency_key": f"haz-{uuid.uuid4().hex[:16]}",
        "hazard_id": str(uuid.uuid4()),
        "site_id": SITE_A,
        "category": "exposed_wiring",
        "severity": "high",
        "note": "Junction box cover missing.",
        "reported_at_sec": int(utcnow().timestamp()) - 120,
        "zone_label": "Level 3 haulage",
    }
    payload.update(overrides)
    return payload


def _progress(worker_id: str, **overrides) -> dict:  # noqa: ANN003
    now = int(utcnow().timestamp())
    payload = {
        "idempotency_key": f"prog-{uuid.uuid4().hex[:16]}",
        "worker_id": worker_id,
        "site_id": SITE_A,
        "module_id": "fire-evacuation",
        "module_code": 1,
        "base_score": 842,
        "last_pass_at_sec": now - 3_600,
        "certified_at_sec": now - 3_600,
        "refresher_stage": 0,
        "next_due_at_sec": now + 2 * 86_400,
        "consecutive_failures": 0,
        "attempts": 1,
        "best_score_permille": 842,
        "last_hesitation_flag": False,
    }
    payload.update(overrides)
    return payload


class TestBatchIdempotency:
    def test_a_clean_batch_is_accepted(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        worker_id = f"{SITE_A}-W00001"
        signed, qr = build_certificate(worker_id=worker_id)

        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(
                certificates=[certificate_upload(signed, qr, worker_id)],
                assessments=[_assessment(worker_id)],
                hazards=[_hazard()],
                progress=[_progress(worker_id)],
            ),
            headers=supervisor_a_headers,
        )
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["accepted"] == 4
        assert body["rejected"] == 0
        assert not body["replayed"]
        assert {r["kind"] for r in body["results"]} == {
            "certificate",
            "assessment",
            "hazard",
            "progress",
        }

    def test_replaying_a_batch_returns_the_stored_response(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        """The property that makes retrying safe after a lost reply."""
        worker_id = f"{SITE_A}-W00001"
        signed, qr = build_certificate(worker_id=worker_id)
        payload = _batch(certificates=[certificate_upload(signed, qr, worker_id)])

        first = client.post("/api/v1/sync/batch", json=payload, headers=supervisor_a_headers)
        second = client.post("/api/v1/sync/batch", json=payload, headers=supervisor_a_headers)

        assert first.status_code == second.status_code == 200
        assert not first.json()["replayed"]
        assert second.json()["replayed"] is True
        assert second.json()["batch_id"] == first.json()["batch_id"]
        assert second.json()["accepted"] == first.json()["accepted"]

        # Exactly one certificate, however many times the batch arrived.
        assert db.scalar(select(func.count()).select_from(Certificate)) == 1

    def test_the_same_certificate_in_a_different_batch_is_a_duplicate(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        # This is the gossip-relay case: the same record arrives by direct upload and via a
        # supervisor's phone, in two different batches.
        worker_id = f"{SITE_A}-W00001"
        signed, qr = build_certificate(worker_id=worker_id)
        upload = certificate_upload(signed, qr, worker_id)

        first = client.post(
            "/api/v1/sync/batch",
            json=_batch(certificates=[upload]),
            headers=supervisor_a_headers,
        )
        second = client.post(
            "/api/v1/sync/batch",
            json=_batch(certificates=[upload]),
            headers=supervisor_a_headers,
        )
        assert first.json()["accepted"] == 1
        assert second.json()["duplicates"] == 1
        assert db.scalar(select(func.count()).select_from(Certificate)) == 1

    def test_a_duplicate_assessment_run_is_reported_not_duplicated(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        worker_id = f"{SITE_A}-W00001"
        run = _assessment(worker_id)

        client.post(
            "/api/v1/sync/batch", json=_batch(assessments=[run]), headers=supervisor_a_headers
        )
        second = client.post(
            "/api/v1/sync/batch", json=_batch(assessments=[run]), headers=supervisor_a_headers
        )
        assert second.json()["duplicates"] == 1
        assert db.scalar(select(func.count()).select_from(AssessmentRun)) == 1


class TestPerItemResults:
    def test_one_bad_item_does_not_reject_the_batch(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        """A phone back from six weeks offline must not lose good records to one bad one."""
        worker_id = f"{SITE_A}-W00001"
        good, good_qr = build_certificate(worker_id=worker_id)

        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(
                certificates=[
                    certificate_upload(good, good_qr, worker_id),
                    {
                        "idempotency_key": "cert-broken-000000001",
                        "qr_text": "JGK1:this-is-not-a-valid-payload-at-all-really",
                        "worker_id": worker_id,
                        "module_id": "fire-evacuation",
                        "key_epoch": 1,
                    },
                ],
                assessments=[_assessment(worker_id)],
            ),
            headers=supervisor_a_headers,
        )
        assert response.status_code == 200
        body = response.json()
        assert body["accepted"] == 2
        assert body["rejected"] == 1
        broken = next(r for r in body["results"] if r["status"] == "rejected")
        assert "malformed" in (broken["reason"] or "")

    def test_an_assessment_for_another_site_is_rejected(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(assessments=[_assessment(f"{SITE_B}-W00004", site_id=SITE_B)]),
            headers=supervisor_a_headers,
        )
        result = response.json()["results"][0]
        assert result["status"] == "rejected"
        assert "registered to site" in result["reason"]

    def test_an_unknown_module_is_rejected_but_retryable(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        # Retryable because the catalog may simply not have been seeded yet: the device should keep
        # the record queued rather than discard real training history.
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(
                assessments=[
                    _assessment(f"{SITE_A}-W00001", module_id="module-that-does-not-exist")
                ]
            ),
            headers=supervisor_a_headers,
        )
        result = response.json()["results"][0]
        assert result["status"] == "rejected"
        assert result["retryable"] is True

    def test_a_run_finishing_before_it_started_is_rejected(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        now = int(utcnow().timestamp())
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(
                assessments=[
                    _assessment(
                        f"{SITE_A}-W00001", started_at_sec=now, finished_at_sec=now - 100
                    )
                ]
            ),
            headers=supervisor_a_headers,
        )
        assert response.json()["results"][0]["status"] == "rejected"

    def test_a_far_future_certificate_is_rejected(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        worker_id = f"{SITE_A}-W00001"
        far_future = int(utcnow().timestamp()) + 10 * 86_400
        signed, qr = build_certificate(worker_id=worker_id, issued_at_sec=far_future)

        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(certificates=[certificate_upload(signed, qr, worker_id)]),
            headers=supervisor_a_headers,
        )
        result = response.json()["results"][0]
        assert result["status"] == "rejected"
        assert "ahead of server time" in result["reason"]

    def test_a_slightly_future_certificate_is_accepted_and_flagged(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        # A phone whose clock is an hour fast is routine. Accept, flag, and let an officer judge.
        worker_id = f"{SITE_A}-W00001"
        slightly_ahead = int(utcnow().timestamp()) + 3_600
        signed, qr = build_certificate(worker_id=worker_id, issued_at_sec=slightly_ahead)

        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(certificates=[certificate_upload(signed, qr, worker_id)]),
            headers=supervisor_a_headers,
        )
        assert response.json()["accepted"] == 1
        stored = db.scalar(select(Certificate))
        assert stored.clock_skew_flagged is True

    def test_progress_creates_a_provisional_worker_when_the_roster_is_behind(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        from app.models import Worker

        unknown = f"{SITE_A}-W09999"
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(progress=[_progress(unknown)]),
            headers=supervisor_a_headers,
        )
        assert response.json()["accepted"] == 1

        worker = db.get(Worker, unknown)
        assert worker is not None
        assert worker.provisional is True
        assert db.get(TrainingProgress, (unknown, "fire-evacuation")) is not None

    def test_older_progress_never_rolls_a_worker_backwards(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        worker_id = f"{SITE_A}-W00001"
        now = int(utcnow().timestamp())

        client.post(
            "/api/v1/sync/batch",
            json=_batch(
                progress=[
                    _progress(worker_id, last_pass_at_sec=now, base_score=900, refresher_stage=2)
                ]
            ),
            headers=supervisor_a_headers,
        )
        stale = client.post(
            "/api/v1/sync/batch",
            json=_batch(
                progress=[
                    _progress(
                        worker_id,
                        last_pass_at_sec=now - 100_000,
                        base_score=500,
                        refresher_stage=0,
                    )
                ]
            ),
            headers=supervisor_a_headers,
        )
        assert stale.json()["results"][0]["status"] == "duplicate"

        row = db.get(TrainingProgress, (worker_id, "fire-evacuation"))
        assert row.base_score == 900
        assert row.refresher_stage == 2


class TestBatchGuards:
    def test_an_unregistered_device_is_refused_with_an_instruction(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(device_id="device-nobody-registered"),
            headers=supervisor_a_headers,
        )
        assert response.status_code == 403
        # The device must be told to keep its queue, not to give up.
        assert "keep the queue" in response.json()["detail"].lower()

    def test_an_oversized_batch_is_refused_with_a_split_instruction(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(
                progress=[_progress(f"{SITE_A}-W00001") for _ in range(101)]
            ),
            headers=supervisor_a_headers,
        )
        assert response.status_code == 413
        assert "split it" in response.json()["detail"].lower()

    def test_sync_requires_an_upload_capable_role(
        self, client: TestClient, seeded: dict
    ) -> None:
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(),
            headers=auth_headers(client, "inspector"),
        )
        assert response.status_code == 403

    def test_an_empty_batch_is_accepted(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        # A device with nothing queued still checks in, and that must not be an error.
        response = client.post(
            "/api/v1/sync/batch", json=_batch(), headers=supervisor_a_headers
        )
        assert response.status_code == 200
        assert response.json()["accepted"] == 0


class TestChainIngest:
    def test_a_full_chain_ingests_cleanly(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        worker_ids = [f"{SITE_A}-W{index:05d}" for index in (1, 2, 3)]
        records = build_chain(6, worker_ids=worker_ids)

        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(
                certificates=[
                    certificate_upload(signed, qr, signed_worker)
                    for (signed, qr), signed_worker in zip(
                        records, [worker_ids[i % 3] for i in range(6)], strict=True
                    )
                ]
            ),
            headers=supervisor_a_headers,
        )
        assert response.status_code == 200
        assert response.json()["accepted"] == 6
        assert response.json()["quarantined"] == 0

        head = client.get(
            f"/api/v1/chains/{SITE_A}", headers=supervisor_a_headers
        ).json()
        assert head["last_seq"] == 6
        assert head["certificate_count"] == 6
        assert head["missing_sequences"] == []

    def test_a_broken_link_is_quarantined_not_discarded(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        """Destroying the evidence of tampering would defeat the point of keeping a chain."""
        worker_id = f"{SITE_A}-W00001"
        first, first_qr = build_certificate(seq=1, worker_id=worker_id)
        client.post(
            "/api/v1/sync/batch",
            json=_batch(certificates=[certificate_upload(first, first_qr, worker_id)]),
            headers=supervisor_a_headers,
        )

        # Correctly signed, but pointing at a predecessor that does not exist.
        spliced, spliced_qr = build_certificate(
            seq=2, worker_id=worker_id, prev_record_hash=bytes([0x33]) * 32
        )
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(certificates=[certificate_upload(spliced, spliced_qr, worker_id)]),
            headers=supervisor_a_headers,
        )
        assert response.json()["quarantined"] == 1

        stored = db.scalars(
            select(Certificate).where(Certificate.seq == 2)
        ).one()
        assert stored.status == CertificateStatus.QUARANTINED.value
        assert stored.quarantine_reason

    def test_a_certificate_signed_by_the_wrong_key_is_quarantined(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        from tests.conftest import SITE_B_PRIVATE_KEY

        worker_id = f"{SITE_A}-W00001"
        forged, forged_qr = build_certificate(
            site_id=SITE_A, worker_id=worker_id, private_key=SITE_B_PRIVATE_KEY
        )
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(certificates=[certificate_upload(forged, forged_qr, worker_id)]),
            headers=supervisor_a_headers,
        )
        assert response.json()["quarantined"] == 1

    def test_a_second_device_claiming_an_occupied_slot_is_rejected(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        worker_a = f"{SITE_A}-W00001"
        worker_b = f"{SITE_A}-W00002"

        first, first_qr = build_certificate(seq=1, worker_id=worker_a)
        client.post(
            "/api/v1/sync/batch",
            json=_batch(certificates=[certificate_upload(first, first_qr, worker_a)]),
            headers=supervisor_a_headers,
        )

        # A different certificate claiming the same sequence slot.
        conflicting, conflicting_qr = build_certificate(
            seq=1, worker_id=worker_b, score_permille=999
        )
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(
                certificates=[certificate_upload(conflicting, conflicting_qr, worker_b)]
            ),
            headers=supervisor_a_headers,
        )
        result = response.json()["results"][0]
        assert result["status"] == "rejected"
        assert "already occupied" in result["reason"]
        assert db.scalar(select(func.count()).select_from(Certificate)) == 1

    def test_a_gap_is_reported_by_the_chain_head(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        records = build_chain(4)
        # Upload 1, 2 and 4; hold back 3.
        for index in (0, 1, 3):
            signed, qr = records[index]
            client.post(
                "/api/v1/sync/batch",
                json=_batch(
                    certificates=[
                        certificate_upload(signed, qr, f"{SITE_A}-W0000{index + 1}")
                    ]
                ),
                headers=supervisor_a_headers,
            )
        head = client.get(f"/api/v1/chains/{SITE_A}", headers=supervisor_a_headers).json()
        assert 3 in head["missing_sequences"]


class TestBootstrap:
    def test_bootstrap_returns_everything_needed_offline(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        response = client.get(
            f"/api/v1/sync/bootstrap?site_id={SITE_A}", headers=supervisor_a_headers
        )
        assert response.status_code == 200
        body = response.json()

        assert body["site"]["id"] == SITE_A
        assert len(body["site_keys"]) == 1
        assert body["site_keys"][0]["public_key_hex"]
        assert len(body["modules"]) == 5
        assert len(body["workers"]) == 3
        assert body["chain_head_seq"] == 0
        assert body["chain_head_hash_hex"] == canonical.ZERO_HASH.hex()
        assert body["server_time_sec"] > 0

    def test_bootstrap_is_scoped(self, client: TestClient, seeded: dict) -> None:
        response = client.get(
            f"/api/v1/sync/bootstrap?site_id={SITE_B}",
            headers=auth_headers(client, "supervisor.a"),
        )
        assert response.status_code == 404

    def test_bootstrap_can_skip_the_roster(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        body = client.get(
            f"/api/v1/sync/bootstrap?site_id={SITE_A}&include_roster=false",
            headers=supervisor_a_headers,
        ).json()
        assert body["workers"] == []

    def test_bootstrap_links_certificates_that_arrived_before_the_roster(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        """Matching is by worker-id hash, which is all the QR ever carried."""
        from app.models import Worker

        late_worker = f"{SITE_A}-W00042"
        signed, qr = build_certificate(worker_id=late_worker)

        client.post(
            "/api/v1/sync/batch",
            json=_batch(certificates=[certificate_upload(signed, qr, late_worker)]),
            headers=supervisor_a_headers,
        )
        stored = db.scalars(select(Certificate)).one()
        assert stored.worker_resolved is False

        db.add(
            Worker(
                id=late_worker,
                site_id=SITE_A,
                full_name="Arrived Later",
                worker_id_hash=canonical.worker_id_hash(late_worker),
            )
        )
        db.commit()

        client.get(
            f"/api/v1/sync/bootstrap?site_id={SITE_A}", headers=supervisor_a_headers
        )
        db.expire_all()
        assert db.scalars(select(Certificate)).one().worker_resolved is True


class TestHazardSyncPath:
    def test_hazard_rate_limits_are_not_enforced_on_sync(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        # A phone offline for a week legitimately arrives with a burst. Dropping real hazards to
        # satisfy a rate limit would be exactly the wrong trade.
        worker_id = f"{SITE_A}-W00001"
        hazards = [
            _hazard(
                reporter_worker_id=worker_id,
                zone_label=f"Zone {index}",
                reported_at_sec=int(utcnow().timestamp()) - index * 10_000,
            )
            for index in range(25)
        ]
        response = client.post(
            "/api/v1/sync/batch", json=_batch(hazards=hazards), headers=supervisor_a_headers
        )
        assert response.status_code == 200
        assert response.json()["accepted"] == 25
        assert db.scalar(select(func.count()).select_from(Hazard)) == 25

    def test_a_hazard_for_an_unknown_site_is_retryable(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        response = client.post(
            "/api/v1/sync/batch",
            json=_batch(hazards=[_hazard(site_id="JH-XXX-999")]),
            headers=supervisor_a_headers,
        )
        result = response.json()["results"][0]
        assert result["status"] == "rejected"
        assert result["retryable"] is True

    def test_nearby_reports_of_the_same_hazard_are_clustered(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        # Five workers passing one blocked exit should be one pin with a count of five, not five
        # pins an officer has to reconcile by hand.
        now = int(utcnow().timestamp())
        base = _hazard(
            category="blocked_exit",
            latitude=23.7500,
            longitude=86.4200,
            zone_label=None,
            reported_at_sec=now,
        )
        nearby = _hazard(
            category="blocked_exit",
            latitude=23.75001,
            longitude=86.42001,
            zone_label=None,
            reported_at_sec=now + 60,
        )
        client.post(
            "/api/v1/sync/batch", json=_batch(hazards=[base]), headers=supervisor_a_headers
        )
        response = client.post(
            "/api/v1/sync/batch", json=_batch(hazards=[nearby]), headers=supervisor_a_headers
        )
        assert "clustered with existing report" in (response.json()["results"][0]["reason"] or "")

        original = db.get(Hazard, base["hazard_id"])
        assert original.duplicate_count == 1
