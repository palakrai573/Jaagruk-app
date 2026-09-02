"""Compliance aggregation, report exports, health probes and the live socket."""

from __future__ import annotations

import uuid

from fastapi.testclient import TestClient

from app.db.base import utcnow
from app.models import AssessmentRun, TrainingProgress
from tests.conftest import (
    DEVICE_A,
    SITE_A,
    SITE_B,
    auth_headers,
    build_chain,
    certificate_upload,
)

DAY = 86_400


def _add_progress(
    db,  # noqa: ANN001
    worker_id: str,
    *,
    site_id: str = SITE_A,
    module_id: str = "fire-evacuation",
    module_code: int = 1,
    base_score: int = 900,
    days_ago: int = 1,
    certified_days_ago: int | None = None,
    stage: int = 0,
    hesitation: bool = False,
    failures: int = 0,
) -> TrainingProgress:
    now = int(utcnow().timestamp())
    certified = now - (certified_days_ago if certified_days_ago is not None else days_ago) * DAY
    row = TrainingProgress(
        worker_id=worker_id,
        module_id=module_id,
        site_id=site_id,
        module_code=module_code,
        base_score=base_score,
        last_pass_at_sec=now - days_ago * DAY,
        certified_at_sec=certified,
        refresher_stage=stage,
        next_due_at_sec=now - days_ago * DAY + 2 * DAY,
        consecutive_failures=failures,
        attempts=1,
        best_score_permille=base_score,
        last_hesitation_flag=hesitation,
    )
    db.add(row)
    db.commit()
    return row


def _add_run(
    db,  # noqa: ANN001
    worker_id: str,
    *,
    site_id: str = SITE_A,
    module_id: str = "fire-evacuation",
    hesitation: bool = True,
    median_latency_ms: int = 7_200,
    days_ago: int = 1,
) -> AssessmentRun:
    now = int(utcnow().timestamp())
    run = AssessmentRun(
        id=str(uuid.uuid4()),
        worker_id=worker_id,
        site_id=site_id,
        module_id=module_id,
        module_code=1,
        scenario_id="fire-evac-full",
        catalog_version=1,
        device_id=DEVICE_A,
        mode="initial",
        presentation="site_scanned",
        completion="completed",
        score_permille=760,
        passed=True,
        hesitation_flag=hesitation,
        hesitation_ratio=0.66 if hesitation else 0.0,
        median_latency_ms=median_latency_ms,
        started_at_sec=now - days_ago * DAY - 600,
        finished_at_sec=now - days_ago * DAY,
        total_duration_ms=520_000,
        steps_json=(
            '[{"step_id":"s1","outcome":"CORRECT_SLOW","latency_ms":7200,"expert_ms":3000},'
            '{"step_id":"s2","outcome":"CORRECT_FAST","latency_ms":1900,"expert_ms":3000},'
            '{"step_id":"s3","outcome":"CORRECT_SLOW","latency_ms":8100,"expert_ms":3000}]'
        ),
        failed_critical_steps_json="[]",
    )
    db.add(run)
    db.commit()
    return run


class TestComplianceOverview:
    def test_an_empty_deployment_reports_zeroes_not_errors(
        self, client: TestClient, seeded: dict, inspector_headers: dict
    ) -> None:
        # A site with no workers must render as an empty state, not a NaN that blanks a chart.
        body = client.get("/api/v1/compliance/overview", headers=inspector_headers).json()
        assert body["site_count"] == 2
        assert body["worker_count"] == 4
        assert body["certified_worker_percent"] == 0.0
        assert body["mean_readiness_permille"] == 0
        assert body["workers_never_certified"] == 4

    def test_a_fresh_pass_counts_as_ready_and_certified(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        _add_progress(db, f"{SITE_A}-W00001", base_score=950, days_ago=1)

        body = client.get("/api/v1/compliance/overview", headers=inspector_headers).json()
        assert body["workers_ready"] == 1
        assert body["certified_worker_percent"] == 25.0
        assert body["mean_readiness_permille"] > 900

    def test_the_statutorily_valid_but_stale_cohort_is_surfaced(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        """Legally clear to work, practically unprepared: the group to act on first."""
        _add_progress(db, f"{SITE_A}-W00001", base_score=1_000, days_ago=150)

        body = client.get("/api/v1/compliance/overview", headers=inspector_headers).json()
        assert body["statutorily_valid_but_stale"] == 1
        assert body["workers_stale"] + body["workers_expired"] >= 1

    def test_hesitation_risk_is_counted(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        _add_progress(db, f"{SITE_A}-W00001", hesitation=True)

        body = client.get("/api/v1/compliance/overview", headers=inspector_headers).json()
        assert body["hesitation_risk_count"] == 1

    def test_the_overview_is_scoped(self, client: TestClient, seeded: dict, db) -> None:  # noqa: ANN001
        _add_progress(db, f"{SITE_A}-W00001")
        _add_progress(db, f"{SITE_B}-W00004", site_id=SITE_B)

        officer = client.get(
            "/api/v1/compliance/overview", headers=auth_headers(client, "officer.a")
        ).json()
        assert officer["site_count"] == 1
        assert officer["worker_count"] == 3

        inspector = client.get(
            "/api/v1/compliance/overview", headers=auth_headers(client, "inspector")
        ).json()
        assert inspector["site_count"] == 2
        assert inspector["worker_count"] == 4

    def test_open_hazards_are_counted(
        self, client: TestClient, seeded: dict, inspector_headers: dict
    ) -> None:
        client.post(
            "/api/v1/hazards",
            json={
                "site_id": SITE_A,
                "category": "gas_smell",
                "severity": "critical",
                "zone_label": "Sealed district",
            },
            headers=auth_headers(client, "officer.a"),
        )
        body = client.get("/api/v1/compliance/overview", headers=inspector_headers).json()
        assert body["open_hazard_count"] == 1
        assert body["critical_hazard_count"] == 1


class TestSiteBreakdown:
    def test_per_site_rows_are_returned(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        _add_progress(db, f"{SITE_A}-W00001", base_score=920)

        rows = client.get("/api/v1/compliance/by-site", headers=inspector_headers).json()
        assert {row["site_id"] for row in rows} == {SITE_A, SITE_B}

        site_a = next(row for row in rows if row["site_id"] == SITE_A)
        assert site_a["worker_count"] == 3
        assert site_a["ar_scanned"] is True
        assert site_a["certified_worker_percent"] > 0

        # A site with no training yet must be 0.0, never NaN.
        site_b = next(row for row in rows if row["site_id"] == SITE_B)
        assert site_b["certified_worker_percent"] == 0.0
        assert site_b["mean_readiness_permille"] == 0

    def test_the_breakdown_is_scoped(self, client: TestClient, seeded: dict) -> None:
        rows = client.get(
            "/api/v1/compliance/by-site", headers=auth_headers(client, "officer.a")
        ).json()
        assert [row["site_id"] for row in rows] == [SITE_A]


class TestHesitationRisk:
    def test_the_cohort_lists_slow_but_correct_workers(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        worker_id = f"{SITE_A}-W00001"
        _add_progress(db, worker_id, hesitation=True)
        _add_run(db, worker_id, hesitation=True)

        page = client.get(
            "/api/v1/compliance/hesitation-risk", headers=inspector_headers
        ).json()
        assert page["total"] == 1
        entry = page["items"][0]
        assert entry["worker_id"] == worker_id
        assert entry["worker_full_name"] == "Birsa Munda"
        assert entry["module_title_en"] == "Fire & Explosion Response"
        assert entry["hesitant_step_count"] == 2
        assert entry["total_step_count"] == 3
        assert entry["pace_multiple"] > 1.0
        # These workers passed. That is exactly what makes them worth listing.
        assert entry["score_permille"] >= 700

    def test_confident_runs_are_excluded(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        _add_run(db, f"{SITE_A}-W00001", hesitation=False)
        page = client.get(
            "/api/v1/compliance/hesitation-risk", headers=inspector_headers
        ).json()
        assert page["total"] == 0

    def test_failed_runs_are_excluded_even_when_hesitant(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        """A failed run belongs in remediation, not in this cohort.

        The list exists to isolate workers who would pass any conventional quiz and still decide too
        slowly. Including score failures would dilute that signal and point at the wrong
        intervention: re-teaching the material rather than drilling under time pressure.
        """
        run = _add_run(db, f"{SITE_A}-W00001", hesitation=True)
        run.passed = False
        run.score_permille = 640
        db.commit()

        page = client.get(
            "/api/v1/compliance/hesitation-risk", headers=inspector_headers
        ).json()
        assert page["total"] == 0

    def test_the_cohort_is_scoped(self, client: TestClient, seeded: dict, db) -> None:  # noqa: ANN001
        _add_run(db, f"{SITE_A}-W00001")
        _add_run(db, f"{SITE_B}-W00004", site_id=SITE_B)

        officer = client.get(
            "/api/v1/compliance/hesitation-risk", headers=auth_headers(client, "officer.a")
        ).json()
        assert officer["total"] == 1

    def test_a_run_with_unparseable_step_detail_does_not_break_the_page(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        run = _add_run(db, f"{SITE_A}-W00001")
        run.steps_json = "{not valid json at all"
        db.commit()

        response = client.get(
            "/api/v1/compliance/hesitation-risk", headers=inspector_headers
        )
        assert response.status_code == 200
        assert response.json()["items"][0]["total_step_count"] == 0


class TestReadinessTrend:
    def test_the_series_covers_the_requested_window(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        _add_progress(db, f"{SITE_A}-W00001", base_score=1_000, days_ago=20)

        body = client.get(
            "/api/v1/compliance/readiness-trend?days=30", headers=inspector_headers
        ).json()
        assert 29 <= len(body["points"]) <= 32
        assert body["points"][0]["day_epoch_sec"] < body["points"][-1]["day_epoch_sec"]

    def test_readiness_declines_across_the_window(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        # Evaluated as of each day, so a decline is visible rather than smoothed away.
        _add_progress(db, f"{SITE_A}-W00001", base_score=1_000, days_ago=60, certified_days_ago=60)

        points = client.get(
            "/api/v1/compliance/readiness-trend?days=45", headers=inspector_headers
        ).json()["points"]
        readings = [p["mean_readiness_permille"] for p in points if p["mean_readiness_permille"]]
        assert readings
        assert readings[0] > readings[-1]

    def test_an_excessive_window_is_rejected(
        self, client: TestClient, seeded: dict, inspector_headers: dict
    ) -> None:
        response = client.get(
            "/api/v1/compliance/readiness-trend?days=99999", headers=inspector_headers
        )
        assert response.status_code == 422


class TestWorkerDetail:
    def test_per_module_readiness_is_returned(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        worker_id = f"{SITE_A}-W00001"
        _add_progress(db, worker_id, module_id="fire-evacuation", module_code=1, base_score=950)
        _add_progress(
            db,
            worker_id,
            module_id="gas-confined-space",
            module_code=2,
            base_score=1_000,
            days_ago=200,
            certified_days_ago=200,
        )

        body = client.get(f"/api/v1/workers/{worker_id}", headers=inspector_headers).json()
        assert body["full_name"] == "Birsa Munda"
        assert len(body["modules"]) == 2

        fire = next(m for m in body["modules"] if m["module_code"] == 1)
        assert fire["readiness_band"] == "ready"
        assert fire["statutory_valid"] is True
        assert fire["required_action"] == "none"

        gas = next(m for m in body["modules"] if m["module_code"] == 2)
        # Still statutorily valid at 200 days, but operationally decayed.
        assert gas["statutory_valid"] is True
        assert gas["readiness_band"] in ("stale", "expired")
        assert gas["required_action"] in ("refresher_due", "full_rerun_required")

    def test_readiness_filter_narrows_the_roster(
        self, client: TestClient, seeded: dict, inspector_headers: dict, db
    ) -> None:  # noqa: ANN001
        _add_progress(db, f"{SITE_A}-W00001", base_score=980, days_ago=1)
        _add_progress(db, f"{SITE_A}-W00002", base_score=1_000, days_ago=200)

        page = client.get(
            "/api/v1/workers?readiness_below=500", headers=inspector_headers
        ).json()
        ids = {item["id"] for item in page["items"]}
        assert f"{SITE_A}-W00002" in ids
        assert f"{SITE_A}-W00001" not in ids

    def test_search_matches_name_and_id(
        self, client: TestClient, seeded: dict, inspector_headers: dict
    ) -> None:
        by_name = client.get("/api/v1/workers?q=Birsa", headers=inspector_headers).json()
        assert by_name["total"] == 1

        by_id = client.get("/api/v1/workers?q=W00002", headers=inspector_headers).json()
        assert by_id["total"] == 1

    def test_registering_over_a_provisional_worker_keeps_their_history(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        from app.models import Worker

        from app.core import canonical

        provisional_id = f"{SITE_A}-W00777"
        db.add(
            Worker(
                id=provisional_id,
                site_id=SITE_A,
                full_name=f"Unregistered ({provisional_id})",
                worker_id_hash=canonical.worker_id_hash(provisional_id),
                provisional=True,
            )
        )
        db.commit()
        _add_progress(db, provisional_id, base_score=880)

        response = client.post(
            "/api/v1/workers",
            json={
                "id": provisional_id,
                "site_id": SITE_A,
                "full_name": "Phulmani Tudu",
                "preferred_language": "sat",
                "pictogram_mode": True,
            },
            headers=supervisor_a_headers,
        )
        assert response.status_code == 201
        assert response.json()["provisional"] is False

        detail = client.get(
            f"/api/v1/workers/{provisional_id}", headers=supervisor_a_headers
        ).json()
        assert detail["full_name"] == "Phulmani Tudu"
        # The training history attached to that id must survive registration.
        assert len(detail["modules"]) == 1

    def test_registering_an_already_registered_worker_is_a_conflict(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict
    ) -> None:
        response = client.post(
            "/api/v1/workers",
            json={
                "id": f"{SITE_A}-W00001",
                "site_id": SITE_A,
                "full_name": "Duplicate",
                "preferred_language": "hi",
            },
            headers=supervisor_a_headers,
        )
        assert response.status_code == 409


class TestReportExports:
    def test_the_statutory_report_is_traceable_to_the_ledger(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, db
    ) -> None:  # noqa: ANN001
        worker_id = f"{SITE_A}-W00001"
        records = build_chain(1, worker_ids=[worker_id])
        signed, qr = records[0]
        client.post(
            "/api/v1/sync/batch",
            json={
                "device_id": DEVICE_A,
                "client_batch_id": f"batch-{uuid.uuid4().hex[:12]}",
                "certificates": [certificate_upload(signed, qr, worker_id)],
                "assessments": [],
                "hazards": [],
                "progress": [],
            },
            headers=supervisor_a_headers,
        )
        _add_progress(db, worker_id, base_score=880, days_ago=2)

        response = client.get(
            "/api/v1/reports/statutory.csv", headers=auth_headers(client, "officer.a")
        )
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/csv")
        assert "attachment;" in response.headers["content-disposition"]

        body = response.text
        assert "# Jaagruk statutory certification report" in body
        assert "certificate_record_hash_prefix" in body
        assert worker_id in body
        # A compliance line has to be tied back to the evidence behind it.
        assert signed.record_hash.hex()[:16] in body

    def test_the_report_states_that_readiness_and_statutory_validity_differ(
        self, client: TestClient, seeded: dict, officer_a_headers: dict
    ) -> None:
        body = client.get(
            "/api/v1/reports/statutory.csv", headers=officer_a_headers
        ).text
        assert "separate from" in body

    def test_an_empty_report_says_so_rather_than_returning_a_bare_header(
        self, client: TestClient, seeded: dict, officer_a_headers: dict
    ) -> None:
        body = client.get(
            "/api/v1/reports/statutory.csv", headers=officer_a_headers
        ).text
        assert "No certification records matched" in body

    def test_the_hazard_report_exports(
        self, client: TestClient, seeded: dict, officer_a_headers: dict
    ) -> None:
        client.post(
            "/api/v1/hazards",
            json={
                "site_id": SITE_A,
                "category": "blocked_exit",
                "severity": "critical",
                "note": "Timber across the escape route.",
                "zone_label": "Level 3",
            },
            headers=officer_a_headers,
        )
        body = client.get("/api/v1/reports/hazards.csv", headers=officer_a_headers).text
        assert "# Jaagruk hazard and near-miss report" in body
        assert "blocked_exit" in body
        assert "Timber across the escape route." in body

    def test_the_ledger_export_includes_the_verification_recipe(
        self, client: TestClient, seeded: dict, supervisor_a_headers: dict, officer_a_headers: dict
    ) -> None:
        """An auditor must be able to re-verify with just this file and the public key."""
        worker_id = f"{SITE_A}-W00001"
        signed, qr = build_chain(1, worker_ids=[worker_id])[0]
        client.post(
            "/api/v1/sync/batch",
            json={
                "device_id": DEVICE_A,
                "client_batch_id": f"batch-{uuid.uuid4().hex[:12]}",
                "certificates": [certificate_upload(signed, qr, worker_id)],
                "assessments": [],
                "hazards": [],
                "progress": [],
            },
            headers=supervisor_a_headers,
        )

        body = client.get(
            f"/api/v1/reports/chain/{SITE_A}.csv", headers=officer_a_headers
        ).text
        assert "record_hash = SHA-256(canonical_bytes || signature)" in body
        assert signed.record_hash.hex() in body
        assert signed.signature.hex() in body

    def test_the_ledger_export_is_scoped_without_confirming_existence(
        self, client: TestClient, seeded: dict
    ) -> None:
        response = client.get(
            f"/api/v1/reports/chain/{SITE_B}.csv", headers=auth_headers(client, "officer.a")
        )
        assert response.status_code == 404


class TestHealthProbes:
    def test_health_never_touches_the_database(self, client: TestClient) -> None:
        body = client.get("/health").json()
        assert body["status"] == "ok"
        assert body["environment"] == "test"
        assert body["version"]

    def test_readyz_reports_the_schema_state(self, client: TestClient) -> None:
        body = client.get("/readyz").json()
        assert body["status"] == "ready"
        assert body["database"] is True
        assert body["migrations_applied"] is True
        assert body["missing_tables"] == []

    def test_openapi_is_served(self, client: TestClient) -> None:
        body = client.get("/openapi.json").json()
        assert body["info"]["title"] == "Jaagruk Safety Certification API"
        assert "/api/v1/sync/batch" in body["paths"]
        assert "/api/v1/certificates/verify" in body["paths"]


class TestLiveSocket:
    def test_a_bad_token_is_closed_with_4401(self, client: TestClient, seeded: dict) -> None:
        from starlette.websockets import WebSocketDisconnect

        try:
            with client.websocket_connect("/api/v1/ws/live?token=not-a-token") as socket:
                socket.receive_json()
            raise AssertionError("the socket should have been closed")
        except WebSocketDisconnect as exc:
            assert exc.code == 4401

    def test_a_valid_token_receives_the_connected_frame(
        self, client: TestClient, seeded: dict
    ) -> None:
        from tests.conftest import login

        token = login(client, "officer.a")["access_token"]
        with client.websocket_connect(f"/api/v1/ws/live?token={token}") as socket:
            frame = socket.receive_json()
            assert frame["type"] == "connected"
            assert frame["site_id"] == SITE_A
            assert frame["payload"]["role"] == "site_officer"
            assert frame["payload"]["heartbeat_seconds"] > 0

    def test_the_subscriber_scope_filters_events(self, seeded: dict) -> None:
        """Filtering happens server-side; a browser filter would mean the data already left."""
        from app.core.security import Role
        from app.services.events import Subscriber

        officer = Subscriber(
            websocket=None,  # type: ignore[arg-type]
            role=Role.SITE_OFFICER,
            company_id="coal",
            site_id=SITE_A,
        )
        assert officer.may_see(SITE_A, "coal")
        assert not officer.may_see(SITE_B, "steel")

        inspector = Subscriber(
            websocket=None,  # type: ignore[arg-type]
            role=Role.DGMS_INSPECTOR,
            company_id=None,
            site_id=None,
        )
        assert inspector.may_see(SITE_A, "coal")
        assert inspector.may_see(SITE_B, "steel")

        admin = Subscriber(
            websocket=None,  # type: ignore[arg-type]
            role=Role.COMPANY_ADMIN,
            company_id="coal",
            site_id=None,
        )
        assert admin.may_see(SITE_A, "coal")
        assert not admin.may_see(SITE_B, "steel")
