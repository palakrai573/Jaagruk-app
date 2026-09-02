"""Authentication and role-based access control.

The denial paths matter more than the happy path here. A site officer who can read another
company's compliance data is a data-protection incident, and "we check it in the handler" is how
that happens on the one endpoint someone forgot.
"""

from __future__ import annotations

import time

from fastapi.testclient import TestClient

from app.core.security import (
    Role,
    TokenType,
    hash_password,
    verify_password,
)
from tests.conftest import TEST_PASSWORD, SITE_A, SITE_B, auth_headers, login


class TestPasswordHashing:
    def test_round_trips(self) -> None:
        stored = hash_password("correct horse battery staple")
        assert verify_password("correct horse battery staple", stored)
        assert not verify_password("wrong password entirely", stored)

    def test_hash_is_salted(self) -> None:
        # Two hashes of the same password must differ, or a rainbow table covers the whole roster.
        assert hash_password("same") != hash_password("same")

    def test_hash_records_its_parameters(self) -> None:
        stored = hash_password("x")
        assert stored.startswith("scrypt$")
        assert len(stored.split("$")) == 6

    def test_malformed_stored_hash_fails_closed(self) -> None:
        for bad in ("", "not-a-hash", "scrypt$only$three", "bcrypt$1$2$3$4$5", "scrypt$a$b$c$d$e"):
            assert not verify_password("anything", bad)

    def test_empty_password_never_verifies(self) -> None:
        assert not verify_password("", hash_password("real"))


class TestLogin:
    def test_valid_credentials_return_both_tokens(self, client: TestClient, seeded: dict) -> None:
        body = login(client, "officer.a")
        assert body["access_token"]
        assert body["refresh_token"]
        assert body["role"] == Role.SITE_OFFICER.value
        assert body["site_id"] == SITE_A
        assert body["token_type"] == "bearer"

    def test_wrong_password_is_rejected(self, client: TestClient, seeded: dict) -> None:
        response = client.post(
            "/api/v1/auth/login", json={"username": "officer.a", "password": "WrongPassword1!"}
        )
        assert response.status_code == 401

    def test_unknown_and_wrong_password_are_indistinguishable(
        self, client: TestClient, seeded: dict
    ) -> None:
        # Identical responses, so the endpoint cannot be used to enumerate usernames.
        unknown = client.post(
            "/api/v1/auth/login",
            json={"username": "nobody.here", "password": "WrongPassword1!"},
        )
        wrong = client.post(
            "/api/v1/auth/login", json={"username": "officer.a", "password": "WrongPassword1!"}
        )
        assert unknown.status_code == wrong.status_code == 401
        assert unknown.json()["detail"] == wrong.json()["detail"]

    def test_repeated_failures_lock_the_account(self, client: TestClient, seeded: dict) -> None:
        for _ in range(5):
            client.post(
                "/api/v1/auth/login",
                json={"username": "officer.a", "password": "WrongPassword1!"},
            )
        response = client.post(
            "/api/v1/auth/login", json={"username": "officer.a", "password": TEST_PASSWORD}
        )
        assert response.status_code == 429
        assert "Retry-After" in response.headers

    def test_a_successful_login_clears_the_failure_count(
        self, client: TestClient, seeded: dict
    ) -> None:
        for _ in range(3):
            client.post(
                "/api/v1/auth/login",
                json={"username": "officer.a", "password": "WrongPassword1!"},
            )
        assert login(client, "officer.a")["access_token"]
        # Three more failures must not tip it over, because the counter was reset.
        for _ in range(3):
            client.post(
                "/api/v1/auth/login",
                json={"username": "officer.a", "password": "WrongPassword1!"},
            )
        assert (
            client.post(
                "/api/v1/auth/login",
                json={"username": "officer.a", "password": TEST_PASSWORD},
            ).status_code
            == 200
        )

    def test_short_password_is_a_validation_error(self, client: TestClient, seeded: dict) -> None:
        response = client.post(
            "/api/v1/auth/login", json={"username": "officer.a", "password": "short"}
        )
        assert response.status_code == 422


class TestTokens:
    def test_missing_header_is_rejected_with_a_useful_message(
        self, client: TestClient, seeded: dict
    ) -> None:
        response = client.get("/api/v1/auth/me")
        assert response.status_code == 401
        assert "bearer token" in response.json()["detail"].lower()

    def test_garbage_token_is_rejected(self, client: TestClient, seeded: dict) -> None:
        response = client.get(
            "/api/v1/auth/me", headers={"Authorization": "Bearer not.a.jwt"}
        )
        assert response.status_code == 401

    def test_a_refresh_token_is_not_accepted_as_an_access_token(
        self, client: TestClient, seeded: dict
    ) -> None:
        # Otherwise the refresh token's much longer lifetime silently becomes the session length.
        refresh = login(client, "officer.a")["refresh_token"]
        response = client.get(
            "/api/v1/auth/me", headers={"Authorization": f"Bearer {refresh}"}
        )
        assert response.status_code == 401
        assert "access token" in response.json()["detail"]

    def test_refresh_rotates_and_burns_the_old_token(
        self, client: TestClient, seeded: dict
    ) -> None:
        first = login(client, "officer.a")
        rotated = client.post(
            "/api/v1/auth/refresh", json={"refresh_token": first["refresh_token"]}
        )
        assert rotated.status_code == 200
        assert rotated.json()["refresh_token"] != first["refresh_token"]

        # Reusing the burned token must fail, so a stolen copy is good for at most one exchange.
        replay = client.post(
            "/api/v1/auth/refresh", json={"refresh_token": first["refresh_token"]}
        )
        assert replay.status_code == 401

    def test_logout_revokes_the_session(self, client: TestClient, seeded: dict) -> None:
        headers = auth_headers(client, "officer.a")
        assert client.get("/api/v1/auth/me", headers=headers).status_code == 200
        assert client.post("/api/v1/auth/logout", headers=headers).status_code == 204
        assert client.get("/api/v1/auth/me", headers=headers).status_code == 401

    def test_me_reports_role_derived_permissions(
        self, client: TestClient, seeded: dict
    ) -> None:
        inspector = client.get(
            "/api/v1/auth/me", headers=auth_headers(client, "inspector")
        ).json()
        assert "read_all_companies" in inspector["permissions"]
        assert "manage_workers" not in inspector["permissions"]

        supervisor = client.get(
            "/api/v1/auth/me", headers=auth_headers(client, "supervisor.a")
        ).json()
        assert "manage_workers" in supervisor["permissions"]
        assert "read_all_companies" not in supervisor["permissions"]
        assert "triage_hazards" not in supervisor["permissions"]

    def test_a_token_for_a_deactivated_account_stops_working(
        self, client: TestClient, seeded: dict, db
    ) -> None:  # noqa: ANN001
        from sqlalchemy import select

        from app.models import User

        headers = auth_headers(client, "officer.a")
        assert client.get("/api/v1/auth/me", headers=headers).status_code == 200

        user = db.scalar(select(User).where(User.username == "officer.a"))
        user.active = False
        db.commit()

        assert client.get("/api/v1/auth/me", headers=headers).status_code == 401


class TestScopeIsolation:
    def test_inspector_sees_every_site(self, client: TestClient, seeded: dict) -> None:
        sites = client.get(
            "/api/v1/sites", headers=auth_headers(client, "inspector")
        ).json()
        assert {site["id"] for site in sites} == {SITE_A, SITE_B}

    def test_company_admin_sees_only_their_own_company(
        self, client: TestClient, seeded: dict
    ) -> None:
        sites = client.get(
            "/api/v1/sites", headers=auth_headers(client, "admin.coal")
        ).json()
        assert {site["id"] for site in sites} == {SITE_A}

    def test_site_officer_sees_only_their_own_site(
        self, client: TestClient, seeded: dict
    ) -> None:
        sites = client.get(
            "/api/v1/sites", headers=auth_headers(client, "officer.a")
        ).json()
        assert {site["id"] for site in sites} == {SITE_A}

    def test_out_of_scope_site_is_404_not_403(self, client: TestClient, seeded: dict) -> None:
        # 403 would confirm the site exists, which is itself information the caller should not get.
        response = client.get(
            f"/api/v1/sites/{SITE_B}", headers=auth_headers(client, "officer.a")
        )
        assert response.status_code == 404

    def test_out_of_scope_worker_is_404(self, client: TestClient, seeded: dict) -> None:
        response = client.get(
            f"/api/v1/workers/{SITE_B}-W00004", headers=auth_headers(client, "officer.a")
        )
        assert response.status_code == 404

    def test_worker_listing_is_scoped(self, client: TestClient, seeded: dict) -> None:
        page = client.get(
            "/api/v1/workers", headers=auth_headers(client, "officer.a")
        ).json()
        assert page["total"] == 3
        assert all(item["site_id"] == SITE_A for item in page["items"])

    def test_inspector_sees_the_whole_roster(self, client: TestClient, seeded: dict) -> None:
        page = client.get(
            "/api/v1/workers", headers=auth_headers(client, "inspector")
        ).json()
        assert page["total"] == 4


class TestRoleGuards:
    def test_only_a_company_admin_can_create_a_site(
        self, client: TestClient, seeded: dict
    ) -> None:
        payload = {
            "id": "JH-GIR-055",
            "company_id": seeded["coal_company_id"],
            "name": "New Site",
            "district": "Giridih",
            "sector": "coal_mine",
        }
        for username, expected in (
            ("inspector", 403),
            ("officer.a", 403),
            ("supervisor.a", 403),
            ("admin.coal", 201),
        ):
            response = client.post(
                "/api/v1/sites", json=payload, headers=auth_headers(client, username)
            )
            assert response.status_code == expected, username

    def test_a_company_admin_cannot_create_a_site_in_another_company(
        self, client: TestClient, seeded: dict
    ) -> None:
        response = client.post(
            "/api/v1/sites",
            json={
                "id": "JH-GIR-056",
                "company_id": seeded["steel_company_id"],
                "name": "Cross-company Site",
                "district": "Giridih",
                "sector": "steel_plant",
            },
            headers=auth_headers(client, "admin.coal"),
        )
        assert response.status_code == 403

    def test_an_inspector_cannot_register_workers(
        self, client: TestClient, seeded: dict
    ) -> None:
        # A regulator inspects; it does not administer a company's roster.
        response = client.post(
            "/api/v1/workers",
            json={
                "id": f"{SITE_A}-W00099",
                "site_id": SITE_A,
                "full_name": "Should Not Work",
                "preferred_language": "hi",
            },
            headers=auth_headers(client, "inspector"),
        )
        assert response.status_code == 403

    def test_a_supervisor_cannot_triage_hazards(
        self, client: TestClient, seeded: dict
    ) -> None:
        created = client.post(
            "/api/v1/hazards",
            json={
                "site_id": SITE_A,
                "category": "blocked_exit",
                "severity": "high",
                "note": "Pallets across the exit.",
            },
            headers=auth_headers(client, "supervisor.a"),
        )
        assert created.status_code == 201

        response = client.patch(
            f"/api/v1/hazards/{created.json()['id']}",
            json={"status": "acknowledged"},
            headers=auth_headers(client, "supervisor.a"),
        )
        assert response.status_code == 403

    def test_only_permitted_roles_can_export_reports(
        self, client: TestClient, seeded: dict
    ) -> None:
        for username, expected in (
            ("inspector", 200),
            ("admin.coal", 200),
            ("officer.a", 200),
            ("supervisor.a", 403),
        ):
            response = client.get(
                "/api/v1/reports/statutory.csv", headers=auth_headers(client, username)
            )
            assert response.status_code == expected, username

    def test_site_id_must_match_the_documented_pattern(
        self, client: TestClient, seeded: dict
    ) -> None:
        response = client.post(
            "/api/v1/sites",
            json={
                "id": "not-a-site-id",
                "company_id": seeded["coal_company_id"],
                "name": "Bad Id",
                "district": "Giridih",
                "sector": "coal_mine",
            },
            headers=auth_headers(client, "admin.coal"),
        )
        assert response.status_code == 422

    def test_worker_id_must_belong_to_its_site(self, client: TestClient, seeded: dict) -> None:
        response = client.post(
            "/api/v1/workers",
            json={
                "id": f"{SITE_B}-W00050",
                "site_id": SITE_A,
                "full_name": "Mismatched Site",
                "preferred_language": "hi",
            },
            headers=auth_headers(client, "supervisor.a"),
        )
        assert response.status_code == 422


class TestPaginationGuards:
    def test_page_size_is_capped(self, client: TestClient, seeded: dict) -> None:
        # The cap is enforced server-side, so a rogue page_size cannot be a denial-of-service lever.
        response = client.get(
            "/api/v1/workers?page_size=999", headers=auth_headers(client, "inspector")
        )
        assert response.status_code == 200
        assert response.json()["page_size"] <= 200

    def test_invalid_pagination_is_rejected(self, client: TestClient, seeded: dict) -> None:
        headers = auth_headers(client, "inspector")
        assert client.get("/api/v1/workers?page=0", headers=headers).status_code == 422
        assert client.get("/api/v1/workers?page_size=0", headers=headers).status_code == 422


class TestSecurityHeaders:
    def test_responses_carry_hardening_headers(self, client: TestClient) -> None:
        response = client.get("/health")
        assert response.headers["X-Content-Type-Options"] == "nosniff"
        assert response.headers["Referrer-Policy"] == "no-referrer"
        assert "default-src 'none'" in response.headers["Content-Security-Policy"]
        assert "X-Response-Time-Ms" in response.headers


class TestUnknownFieldsRejected:
    def test_extra_fields_are_a_validation_error(
        self, client: TestClient, seeded: dict
    ) -> None:
        # Catches a client typo loudly instead of silently ignoring the field it meant to send.
        response = client.post(
            "/api/v1/auth/login",
            json={
                "username": "officer.a",
                "password": TEST_PASSWORD,
                "remember_me": True,
            },
        )
        assert response.status_code == 422
