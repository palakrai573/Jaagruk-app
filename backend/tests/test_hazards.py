"""Hazard reporting, clustering, rate limiting and triage."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.services.hazards import haversine_metres
from tests.conftest import SITE_A, SITE_B, auth_headers


def _create(client: TestClient, headers: dict, **overrides) -> dict:  # noqa: ANN003
    payload = {
        "site_id": SITE_A,
        "category": "exposed_wiring",
        "severity": "high",
        "note": "Junction box cover missing near the drive motor.",
    }
    payload.update(overrides)
    response = client.post("/api/v1/hazards", json=payload, headers=headers)
    assert response.status_code == 201, response.text
    return response.json()


class TestHaversine:
    def test_zero_distance(self) -> None:
        assert haversine_metres(23.75, 86.42, 23.75, 86.42) == 0.0

    def test_a_short_distance_is_plausible(self) -> None:
        # ~0.0001 degrees of latitude is roughly 11 metres.
        distance = haversine_metres(23.75, 86.42, 23.7501, 86.42)
        assert 10.0 < distance < 12.5

    def test_antipodal_points_do_not_raise(self) -> None:
        # Floating-point error can push the asin argument above 1.0; the clamp must hold.
        distance = haversine_metres(0.0, 0.0, 0.0, 180.0)
        assert distance > 20_000_000


class TestHazardCreation:
    def test_any_authenticated_role_can_file_a_report(
        self, client: TestClient, seeded: dict
    ) -> None:
        # Safety reporting is never gated on seniority. An uncertified worker noticing exposed
        # wiring is exactly who should be able to say so.
        for username in ("supervisor.a", "officer.a", "admin.coal"):
            hazard = _create(client, auth_headers(client, username))
            assert hazard["status"] == "open"

    def test_a_report_records_its_reporter(self, client: TestClient, seeded: dict) -> None:
        hazard = _create(
            client,
            auth_headers(client, "supervisor.a"),
            reporter_worker_id=f"{SITE_A}-W00001",
        )
        assert hazard["reporter_worker_id"] == f"{SITE_A}-W00001"
        assert hazard["reporter_label"] == "Birsa Munda"

    def test_an_anonymous_report_is_labelled_as_such(
        self, client: TestClient, seeded: dict
    ) -> None:
        hazard = _create(client, auth_headers(client, "supervisor.a"))
        assert hazard["reporter_label"] == "Anonymous"

    def test_a_report_can_be_located_by_zone_label_alone(
        self, client: TestClient, seeded: dict
    ) -> None:
        # GPS is unreliable underground, which is precisely why the zone label exists.
        hazard = _create(
            client,
            auth_headers(client, "supervisor.a"),
            zone_label="Level 3 haulage, second crosscut",
        )
        assert hazard["latitude"] is None
        assert hazard["zone_label"] == "Level 3 haulage, second crosscut"

    def test_reporting_to_another_site_is_refused(
        self, client: TestClient, seeded: dict
    ) -> None:
        response = client.post(
            "/api/v1/hazards",
            json={"site_id": SITE_B, "category": "spill", "severity": "low"},
            headers=auth_headers(client, "supervisor.a"),
        )
        assert response.status_code == 404

    def test_an_invalid_category_is_a_validation_error(
        self, client: TestClient, seeded: dict
    ) -> None:
        response = client.post(
            "/api/v1/hazards",
            json={"site_id": SITE_A, "category": "aliens", "severity": "low"},
            headers=auth_headers(client, "supervisor.a"),
        )
        assert response.status_code == 422

    def test_out_of_range_coordinates_are_rejected(
        self, client: TestClient, seeded: dict
    ) -> None:
        response = client.post(
            "/api/v1/hazards",
            json={
                "site_id": SITE_A,
                "category": "spill",
                "severity": "low",
                "latitude": 200.0,
                "longitude": 86.4,
            },
            headers=auth_headers(client, "supervisor.a"),
        )
        assert response.status_code == 422


class TestRateLimiting:
    def test_a_worker_is_capped_per_hour(self, client: TestClient, seeded: dict) -> None:
        headers = auth_headers(client, "supervisor.a")
        worker_id = f"{SITE_A}-W00001"

        for index in range(10):
            _create(
                client,
                headers,
                reporter_worker_id=worker_id,
                zone_label=f"Distinct zone {index}",
            )

        response = client.post(
            "/api/v1/hazards",
            json={
                "site_id": SITE_A,
                "category": "spill",
                "severity": "low",
                "reporter_worker_id": worker_id,
                "zone_label": "One too many",
            },
            headers=headers,
        )
        assert response.status_code == 429
        assert "Retry-After" in response.headers

    def test_anonymous_reports_are_not_capped(self, client: TestClient, seeded: dict) -> None:
        # An unattributed report is better than a suppressed one.
        headers = auth_headers(client, "supervisor.a")
        for index in range(15):
            _create(client, headers, zone_label=f"Anonymous zone {index}")


class TestClustering:
    def test_nearby_reports_of_the_same_category_are_clustered(
        self, client: TestClient, seeded: dict
    ) -> None:
        headers = auth_headers(client, "supervisor.a")
        first = _create(
            client, headers, category="blocked_exit", latitude=23.7500, longitude=86.4200
        )
        second = _create(
            client, headers, category="blocked_exit", latitude=23.75001, longitude=86.42001
        )
        assert second["duplicate_of_id"] == first["id"]

        reloaded = client.get(f"/api/v1/hazards/{first['id']}", headers=headers).json()
        assert reloaded["duplicate_count"] == 1

    def test_corroboration_raises_the_severity(self, client: TestClient, seeded: dict) -> None:
        # Several people flagging the same thing is a signal, not noise.
        headers = auth_headers(client, "supervisor.a")
        first = _create(
            client,
            headers,
            category="blocked_exit",
            severity="low",
            latitude=23.7500,
            longitude=86.4200,
        )
        _create(
            client,
            headers,
            category="blocked_exit",
            severity="critical",
            latitude=23.75001,
            longitude=86.42001,
        )
        reloaded = client.get(f"/api/v1/hazards/{first['id']}", headers=headers).json()
        assert reloaded["severity"] == "critical"

    def test_a_different_category_is_not_clustered(
        self, client: TestClient, seeded: dict
    ) -> None:
        headers = auth_headers(client, "supervisor.a")
        _create(client, headers, category="blocked_exit", latitude=23.75, longitude=86.42)
        second = _create(client, headers, category="spill", latitude=23.75, longitude=86.42)
        assert second["duplicate_of_id"] is None

    def test_a_distant_report_is_not_clustered(self, client: TestClient, seeded: dict) -> None:
        headers = auth_headers(client, "supervisor.a")
        _create(client, headers, category="blocked_exit", latitude=23.75, longitude=86.42)
        second = _create(
            client, headers, category="blocked_exit", latitude=23.80, longitude=86.50
        )
        assert second["duplicate_of_id"] is None

    def test_matching_zone_labels_cluster_without_coordinates(
        self, client: TestClient, seeded: dict
    ) -> None:
        headers = auth_headers(client, "supervisor.a")
        first = _create(client, headers, category="roof_support", zone_label="Level 3 haulage")
        second = _create(client, headers, category="roof_support", zone_label="Level 3 haulage")
        assert second["duplicate_of_id"] == first["id"]

    def test_duplicates_are_hidden_from_the_default_listing(
        self, client: TestClient, seeded: dict
    ) -> None:
        headers = auth_headers(client, "supervisor.a")
        _create(client, headers, category="blocked_exit", latitude=23.75, longitude=86.42)
        _create(client, headers, category="blocked_exit", latitude=23.75001, longitude=86.42001)

        default = client.get("/api/v1/hazards", headers=headers).json()
        assert default["total"] == 1

        with_duplicates = client.get(
            "/api/v1/hazards?include_duplicates=true", headers=headers
        ).json()
        assert with_duplicates["total"] == 2


class TestTriage:
    def test_the_legal_transition_path_works(self, client: TestClient, seeded: dict) -> None:
        headers = auth_headers(client, "officer.a")
        hazard = _create(client, headers)

        for target in ("acknowledged", "in_progress", "resolved"):
            response = client.patch(
                f"/api/v1/hazards/{hazard['id']}",
                json={"status": target, "resolution_note": "Guard refitted and signed off."},
                headers=headers,
            )
            assert response.status_code == 200, response.text
            assert response.json()["status"] == target

    def test_an_illegal_transition_returns_the_allowed_set(
        self, client: TestClient, seeded: dict
    ) -> None:
        headers = auth_headers(client, "officer.a")
        hazard = _create(client, headers)

        response = client.patch(
            f"/api/v1/hazards/{hazard['id']}",
            json={"status": "resolved"},
            headers=headers,
        )
        assert response.status_code == 409
        detail = response.json()["detail"]
        assert "acknowledged" in detail

    def test_resolved_is_terminal(self, client: TestClient, seeded: dict) -> None:
        # Reopening would let a closed hazard be quietly relitigated. A fresh report is required
        # and the original stays on record.
        headers = auth_headers(client, "officer.a")
        hazard = _create(client, headers)
        client.patch(
            f"/api/v1/hazards/{hazard['id']}", json={"status": "acknowledged"}, headers=headers
        )
        client.patch(
            f"/api/v1/hazards/{hazard['id']}", json={"status": "resolved"}, headers=headers
        )

        response = client.patch(
            f"/api/v1/hazards/{hazard['id']}", json={"status": "open"}, headers=headers
        )
        assert response.status_code == 409
        assert "terminal" in response.json()["detail"]

    def test_a_report_can_be_marked_invalid_from_open(
        self, client: TestClient, seeded: dict
    ) -> None:
        headers = auth_headers(client, "officer.a")
        hazard = _create(client, headers)
        response = client.patch(
            f"/api/v1/hazards/{hazard['id']}",
            json={"status": "invalid", "resolution_note": "Duplicate of an existing work order."},
            headers=headers,
        )
        assert response.status_code == 200

    def test_setting_the_same_status_is_a_no_op(
        self, client: TestClient, seeded: dict
    ) -> None:
        headers = auth_headers(client, "officer.a")
        hazard = _create(client, headers)
        response = client.patch(
            f"/api/v1/hazards/{hazard['id']}", json={"status": "open"}, headers=headers
        )
        assert response.status_code == 200
        assert response.json()["status"] == "open"

    def test_concurrent_triage_gives_the_loser_a_409(
        self, client: TestClient, seeded: dict
    ) -> None:
        """Two officers on the same hazard: the second refreshes rather than overwriting."""
        headers = auth_headers(client, "officer.a")
        hazard = _create(client, headers)
        stale_timestamp = hazard["updated_at_iso"]

        first = client.patch(
            f"/api/v1/hazards/{hazard['id']}",
            json={"status": "acknowledged", "expected_updated_at_iso": stale_timestamp},
            headers=headers,
        )
        assert first.status_code == 200

        second = client.patch(
            f"/api/v1/hazards/{hazard['id']}",
            json={"status": "invalid", "expected_updated_at_iso": stale_timestamp},
            headers=headers,
        )
        assert second.status_code == 409
        assert "changed by someone else" in second.json()["detail"]

    def test_the_allowed_next_statuses_are_advertised(
        self, client: TestClient, seeded: dict
    ) -> None:
        headers = auth_headers(client, "officer.a")
        hazard = _create(client, headers)
        assert set(hazard["allowed_next_statuses"]) == {"acknowledged", "invalid"}

    def test_triage_is_scoped(self, client: TestClient, seeded: dict) -> None:
        hazard = _create(client, auth_headers(client, "supervisor.b"), site_id=SITE_B)
        response = client.patch(
            f"/api/v1/hazards/{hazard['id']}",
            json={"status": "acknowledged"},
            headers=auth_headers(client, "officer.a"),
        )
        assert response.status_code == 404


class TestListingAndFiltering:
    def test_filters_narrow_the_result(self, client: TestClient, seeded: dict) -> None:
        headers = auth_headers(client, "officer.a")
        _create(client, headers, category="spill", severity="low", zone_label="A")
        _create(client, headers, category="blocked_exit", severity="critical", zone_label="B")

        by_category = client.get("/api/v1/hazards?category=spill", headers=headers).json()
        assert by_category["total"] == 1

        by_severity = client.get("/api/v1/hazards?severity=critical", headers=headers).json()
        assert by_severity["total"] == 1

        by_status = client.get("/api/v1/hazards?status=resolved", headers=headers).json()
        assert by_status["total"] == 0

    def test_a_bounding_box_filters_to_the_viewport(
        self, client: TestClient, seeded: dict
    ) -> None:
        headers = auth_headers(client, "officer.a")
        _create(client, headers, category="spill", latitude=23.75, longitude=86.42)
        _create(client, headers, category="roof_support", latitude=25.00, longitude=88.00)

        inside = client.get(
            "/api/v1/hazards?bbox=86.40,23.70,86.45,23.80", headers=headers
        ).json()
        assert inside["total"] == 1

    def test_a_malformed_bounding_box_is_a_validation_error(
        self, client: TestClient, seeded: dict
    ) -> None:
        response = client.get(
            "/api/v1/hazards?bbox=not,a,bbox", headers=auth_headers(client, "officer.a")
        )
        assert response.status_code == 422

    def test_reports_without_coordinates_have_their_own_listing(
        self, client: TestClient, seeded: dict
    ) -> None:
        # A hazard that cannot be plotted still has to be visible.
        headers = auth_headers(client, "officer.a")
        _create(client, headers, category="spill", latitude=23.75, longitude=86.42)
        _create(client, headers, category="roof_support", zone_label="Level 3 haulage")

        body = client.get("/api/v1/hazards/without-coordinates", headers=headers).json()
        assert body["total"] == 1
        assert body["items"][0]["zone_label"] == "Level 3 haulage"

    def test_listing_is_scoped(self, client: TestClient, seeded: dict) -> None:
        _create(client, auth_headers(client, "supervisor.a"), site_id=SITE_A, zone_label="A")
        _create(client, auth_headers(client, "supervisor.b"), site_id=SITE_B, zone_label="B")

        officer_view = client.get(
            "/api/v1/hazards", headers=auth_headers(client, "officer.a")
        ).json()
        assert officer_view["total"] == 1
        assert officer_view["items"][0]["site_id"] == SITE_A

        inspector_view = client.get(
            "/api/v1/hazards", headers=auth_headers(client, "inspector")
        ).json()
        assert inspector_view["total"] == 2

    def test_an_unknown_hazard_is_404(self, client: TestClient, seeded: dict) -> None:
        import uuid

        response = client.get(
            f"/api/v1/hazards/{uuid.uuid4()}", headers=auth_headers(client, "officer.a")
        )
        assert response.status_code == 404
