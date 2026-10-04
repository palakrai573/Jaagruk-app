"""Serving the built compliance dashboard from the API's own origin.

Off by default. When JAAGRUK_DASHBOARD_DIR points at a built dashboard, the same process serves it
with a single-page-app fallback, and each response carries the CSP for what it is: the API keeps
its lock-everything policy, dashboard pages get a same-origin one.
"""

from __future__ import annotations

from collections.abc import Generator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import API_CSP, DASHBOARD_CSP, create_app

INDEX_MARKER = "<!-- jaagruk dashboard index -->"
OUTSIDE_SECRET = "this file sits outside the dashboard directory"


@pytest.fixture
def dashboard_dir(tmp_path: Path) -> Path:
    dist = tmp_path / "dist"
    (dist / "assets").mkdir(parents=True)
    (dist / "index.html").write_text(f"<!doctype html>{INDEX_MARKER}<div id=root></div>", "utf-8")
    (dist / "assets" / "app.js").write_text("console.log('dashboard')", "utf-8")
    # A sibling of dist/, to prove a traversal attempt cannot reach it.
    (tmp_path / "secret.txt").write_text(OUTSIDE_SECRET, "utf-8")
    return dist


def _client(dashboard: Path | None) -> TestClient:
    settings = get_settings().model_copy(update={"dashboard_dir": dashboard})
    return TestClient(create_app(settings))


@pytest.fixture
def served(dashboard_dir: Path) -> Generator[TestClient, None, None]:
    with _client(dashboard_dir) as client:
        yield client


def test_api_only_by_default(client: TestClient) -> None:
    assert client.get("/").status_code == 404
    response = client.get("/health")
    assert response.headers["content-security-policy"] == API_CSP


def test_index_and_assets_are_served(served: TestClient) -> None:
    index = served.get("/")
    assert index.status_code == 200
    assert INDEX_MARKER in index.text
    assert index.headers["content-type"].startswith("text/html")
    assert index.headers["content-security-policy"] == DASHBOARD_CSP

    asset = served.get("/assets/app.js")
    assert asset.status_code == 200
    assert "dashboard" in asset.text


def test_client_side_route_falls_back_to_index(served: TestClient) -> None:
    # A refresh on a dashboard route asks the server for a path with no file behind it.
    response = served.get("/workers/42")
    assert response.status_code == 200
    assert INDEX_MARKER in response.text


def test_a_missing_asset_is_a_404_not_index_html(served: TestClient) -> None:
    # A stale page asking for a renamed chunk after a redeploy must not get HTML back.
    response = served.get("/assets/index-renamed.js")
    assert response.status_code == 404
    assert INDEX_MARKER not in response.text


def test_unknown_api_path_stays_a_json_404(served: TestClient) -> None:
    # A mistyped endpoint must not come back as a 200 of HTML the client then fails to parse.
    response = served.get("/api/v1/definitely-not-a-route")
    assert response.status_code == 404
    assert INDEX_MARKER not in response.text
    assert response.headers["content-type"].startswith("application/json")
    assert response.headers["content-security-policy"] == API_CSP


def test_api_routes_keep_the_strict_policy_when_the_dashboard_is_mounted(served: TestClient) -> None:
    response = served.get("/health")
    assert response.status_code == 200
    assert response.headers["content-security-policy"] == API_CSP


@pytest.mark.parametrize(
    "path",
    ["/../secret.txt", "/%2e%2e/secret.txt", "/assets/%2e%2e/%2e%2e/secret.txt", "/..%2fsecret.txt"],
)
def test_traversal_cannot_leave_the_dashboard_directory(served: TestClient, path: str) -> None:
    response = served.get(path)
    assert OUTSIDE_SECRET not in response.text


def test_a_directory_without_index_html_serves_the_api_only(tmp_path: Path) -> None:
    empty = tmp_path / "not-built"
    empty.mkdir()
    with _client(empty) as client:
        assert client.get("/").status_code == 404
        assert client.get("/health").headers["content-security-policy"] == API_CSP
