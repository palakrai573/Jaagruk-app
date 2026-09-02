"""End-to-end smoke test against a running backend.

    cd backend && .venv\\Scripts\\python.exe ..\\tools\\smoke_test.py

Exercises the routes the dashboard and the Android app actually call, against the seeded demo data,
and checks the *content* of the answers rather than only the status codes. A 200 that returns the
wrong number is the failure mode that matters here.

Exits non-zero on the first failed expectation, so it is usable as a CI gate.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

DEFAULT_BASE = "http://127.0.0.1:8000"
DEFAULT_USER = "inspector.dgms"
DEFAULT_PASSWORD = "JaagrukDemo2026!"

PASS = "  ok  "
FAIL = " FAIL "

failures: list[str] = []
checks = 0


def check(condition: bool, description: str, detail: str = "") -> None:
    global checks
    checks += 1
    if condition:
        print(f"[{PASS}] {description}")
    else:
        print(f"[{FAIL}] {description}" + (f"\n         {detail}" if detail else ""))
        failures.append(description)


def request(
    base: str,
    path: str,
    *,
    method: str = "GET",
    token: str | None = None,
    body: dict | None = None,
    raw: bool = False,
):
    url = f"{base}{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            payload = response.read()
            if raw:
                return response.status, payload.decode("utf-8", errors="replace")
            return response.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        try:
            return error.code, json.loads(detail)
        except json.JSONDecodeError:
            return error.code, {"detail": detail}
    except urllib.error.URLError as error:
        print(f"\nCould not reach {url}: {error.reason}")
        print("Start the backend first:")
        print("  cd backend && .venv\\Scripts\\python.exe -m uvicorn app.main:app --port 8000")
        sys.exit(2)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument("--username", default=DEFAULT_USER)
    parser.add_argument("--password", default=DEFAULT_PASSWORD)
    args = parser.parse_args()
    base = args.base.rstrip("/")

    print(f"Jaagruk smoke test against {base}\n")

    # --- probes ------------------------------------------------------------
    status, health = request(base, "/health")
    check(status == 200 and health["status"] == "ok", "GET /health reports ok", str(health))

    status, ready = request(base, "/readyz")
    check(
        status == 200 and ready["database"] and ready["migrations_applied"],
        "GET /readyz reports a usable schema",
        str(ready),
    )

    # --- auth --------------------------------------------------------------
    status, login = request(
        base,
        "/api/v1/auth/login",
        method="POST",
        body={"username": args.username, "password": args.password},
    )
    if status != 200:
        print(f"\nLogin failed ({status}): {login}")
        print("Seed the database first:  cd backend && python -m app.seed --reset")
        return 2
    token = login["access_token"]
    check(bool(token) and bool(login["refresh_token"]), "POST /auth/login returns both tokens")

    status, me = request(base, "/api/v1/auth/me", token=token)
    check(status == 200 and me["username"] == args.username, "GET /auth/me returns the caller")

    status, bad = request(
        base,
        "/api/v1/auth/login",
        method="POST",
        body={"username": args.username, "password": "definitely-not-the-password"},
    )
    check(bad is not None and status == 401, "a wrong password is refused", str(bad))

    status, unauth = request(base, "/api/v1/compliance/overview")
    check(status == 401, "an unauthenticated request is refused")

    # --- compliance --------------------------------------------------------
    status, overview = request(base, "/api/v1/compliance/overview", token=token)
    check(status == 200, "GET /compliance/overview")
    check(overview["site_count"] > 0, "the overview reports sites", str(overview))
    check(overview["worker_count"] > 0, "the overview reports workers")
    check(overview["certificate_count"] > 0, "the overview reports certificates")
    check(
        overview["workers_ready"]
        + overview["workers_due"]
        + overview["workers_stale"]
        + overview["workers_expired"]
        == overview["worker_count"],
        "readiness bands account for every worker",
        f"bands sum to "
        f"{overview['workers_ready'] + overview['workers_due'] + overview['workers_stale'] + overview['workers_expired']}"
        f" but there are {overview['worker_count']} workers",
    )
    check(
        0.0 <= overview["certified_worker_percent"] <= 100.0,
        "certified percentage is a percentage",
        str(overview["certified_worker_percent"]),
    )

    status, by_site = request(base, "/api/v1/compliance/by-site", token=token)
    check(status == 200 and len(by_site) > 0, "GET /compliance/by-site returns rows")

    status, hesitation = request(
        base, "/api/v1/compliance/hesitation-risk?page_size=5", token=token
    )
    check(status == 200, "GET /compliance/hesitation-risk")
    check(
        all(entry["score_permille"] >= 700 for entry in hesitation["items"]),
        "everyone on the hesitation list actually passed",
        "that is the point of the cohort: competent on knowledge, slow under pressure",
    )

    status, trend = request(base, "/api/v1/compliance/readiness-trend?days=30", token=token)
    check(status == 200 and len(trend["points"]) >= 28, "GET /compliance/readiness-trend")

    # --- catalog -----------------------------------------------------------
    status, sites = request(base, "/api/v1/sites", token=token)
    check(status == 200 and len(sites) > 0, "GET /sites")
    site_ids = [site["id"] for site in sites]

    status, modules = request(base, "/api/v1/modules", token=token)
    check(status == 200 and len(modules) == 5, "GET /modules returns the five safety domains")
    check(
        {module["module_code"] for module in modules} == {1, 2, 3, 4, 5},
        "module codes are the frozen 1..5",
    )

    status, devices = request(base, "/api/v1/devices", token=token)
    check(status == 200, "GET /devices")

    first_site = site_ids[0]
    status, keys = request(base, f"/api/v1/sites/{first_site}/public-keys", token=token)
    check(
        status == 200 and len(keys["keys"]) > 0 and len(keys["keys"][0]["public_key_hex"]) == 64,
        "GET /sites/{id}/public-keys returns a 32-byte Ed25519 key",
    )

    # --- workers -----------------------------------------------------------
    status, workers = request(base, "/api/v1/workers?page_size=5", token=token)
    check(status == 200 and workers["total"] > 0, "GET /workers")
    worker_id = workers["items"][0]["id"]

    status, detail = request(base, f"/api/v1/workers/{worker_id}", token=token)
    check(status == 200 and detail["id"] == worker_id, "GET /workers/{id}")
    check(
        all(
            0 <= module["readiness_permille"] <= 1000 for module in detail["modules"]
        ),
        "per-module readiness is in permille range",
    )

    status, missing = request(base, "/api/v1/workers/JH-XXX-999-W00001", token=token)
    check(status == 404, "an unknown worker is 404")

    # --- certificates and chain -------------------------------------------
    status, certificates = request(base, "/api/v1/certificates?page_size=5", token=token)
    check(status == 200 and certificates["total"] > 0, "GET /certificates")

    certificate = certificates["items"][0]
    check(
        certificate["qr_text"].startswith("JGK1:"),
        "stored QR text carries the Jaagruk prefix",
        certificate["qr_text"][:16],
    )
    check(
        len(certificate["record_hash_hex"]) == 64,
        "record hash is 32 bytes",
    )

    status, verified = request(
        base,
        "/api/v1/certificates/verify",
        method="POST",
        token=token,
        body={"qr_text": certificate["qr_text"]},
    )
    check(status == 200, "POST /certificates/verify")
    check(
        verified["status"] == "verified" and verified["trustworthy"],
        "a genuine certificate verifies",
        str(verified.get("reasons")),
    )
    check(
        verified["score_permille"] == certificate["score_permille"],
        "the verified score matches the stored score",
        "if these differ, the signed payload and the database disagree",
    )
    check(
        verified["statutory_valid"] is not None
        and verified["readiness_permille"] is not None,
        "verification reports statutory validity and readiness separately",
    )

    # Tamper with one character of the payload: it must not verify.
    tampered = certificate["qr_text"]
    index = len("JGK1:") + 20
    original_char = tampered[index]
    replacement = "A" if original_char != "A" else "B"
    tampered = tampered[:index] + replacement + tampered[index + 1 :]
    status, tampered_result = request(
        base,
        "/api/v1/certificates/verify",
        method="POST",
        token=token,
        body={"qr_text": tampered},
    )
    check(
        status == 200 and not tampered_result["trustworthy"],
        "a single altered character is detected",
        str(tampered_result.get("status")),
    )

    status, junk = request(
        base,
        "/api/v1/certificates/verify",
        method="POST",
        token=token,
        body={"qr_text": "WIFI:S:MineNet;T:WPA;P:secret;;"},
    )
    check(
        status == 200 and junk["status"] == "malformed",
        "a QR from another app is reported as malformed",
    )

    status, worker_match = request(
        base,
        "/api/v1/certificates/verify",
        method="POST",
        token=token,
        body={
            "qr_text": certificate["qr_text"],
            "candidate_worker_id": certificate["worker_id"],
        },
    )
    check(
        worker_match.get("worker_id_matches") is True,
        "the worker id on the card is confirmed against the hash in the QR",
    )
    check(
        certificate["worker_id"] not in certificate["qr_text"],
        "the QR never carries a plaintext worker id",
    )

    status, head = request(base, f"/api/v1/chains/{first_site}", token=token)
    check(status == 200 and head["last_seq"] >= 0, "GET /chains/{siteId}")

    status, audit = request(base, f"/api/v1/chains/{first_site}/verify", method="POST", token=token)
    check(status == 200, "POST /chains/{siteId}/verify")
    check(
        audit["records_checked"] > 0,
        "the chain audit actually walked records",
        str(audit),
    )

    # The seed plants a deliberate break so tamper detection is demonstrable rather than claimed.
    if "JH-JAM-021" in site_ids:
        status, broken = request(
            base, "/api/v1/chains/JH-JAM-021/verify", method="POST", token=token
        )
        check(
            status == 200 and len(broken["quarantined_seqs"]) > 0,
            "the seeded chain break is visible at JH-JAM-021",
            str(broken),
        )

    # --- hazards -----------------------------------------------------------
    status, hazards = request(base, "/api/v1/hazards?page_size=5", token=token)
    check(status == 200, "GET /hazards")

    status, unplotted = request(base, "/api/v1/hazards/without-coordinates", token=token)
    check(status == 200, "GET /hazards/without-coordinates")
    check(
        all(item["latitude"] is None for item in unplotted["items"]),
        "the no-coordinates listing contains only reports without a fix",
    )

    status, bad_bbox = request(base, "/api/v1/hazards?bbox=not,a,bbox", token=token)
    check(status == 422, "a malformed bounding box is a validation error")

    # --- reports -----------------------------------------------------------
    status, csv_text = request(
        base, "/api/v1/reports/statutory.csv?days=365", token=token, raw=True
    )
    check(status == 200, "GET /reports/statutory.csv")
    check(
        "# Jaagruk statutory certification report" in csv_text,
        "the certification export carries its provenance header",
    )
    check(
        "certificate_record_hash_prefix" in csv_text,
        "certification rows trace back to a ledger entry",
    )

    status, hazard_csv = request(
        base, "/api/v1/reports/hazards.csv?days=365", token=token, raw=True
    )
    check(
        status == 200 and "# Jaagruk hazard and near-miss report" in hazard_csv,
        "GET /reports/hazards.csv",
    )

    status, ledger_csv = request(
        base, f"/api/v1/reports/chain/{first_site}.csv", token=token, raw=True
    )
    check(status == 200, "GET /reports/chain/{siteId}.csv")
    check(
        "record_hash = SHA-256(canonical_bytes || signature)" in ledger_csv,
        "the ledger export states how to verify it independently",
    )

    # --- bootstrap (the Android app's down-sync) ---------------------------
    status, bootstrap = request(
        base, f"/api/v1/sync/bootstrap?site_id={first_site}", token=token
    )
    check(status == 200, "GET /sync/bootstrap")
    check(
        len(bootstrap["site_keys"]) > 0 and len(bootstrap["modules"]) == 5,
        "bootstrap carries site keys and the module catalog",
    )
    check(
        bootstrap["server_time_sec"] > 0 and len(bootstrap["chain_head_hash_hex"]) == 64,
        "bootstrap carries server time and the chain head",
    )

    # --- RBAC --------------------------------------------------------------
    status, officer_login = request(
        base,
        "/api/v1/auth/login",
        method="POST",
        body={"username": "officer.dhanbad", "password": args.password},
    )
    if status == 200:
        officer_token = officer_login["access_token"]
        status, officer_sites = request(base, "/api/v1/sites", token=officer_token)
        check(
            status == 200 and len(officer_sites) == 1,
            "a site officer sees only their own site",
            f"saw {len(officer_sites)} site(s)",
        )
        other = next((s for s in site_ids if s != officer_sites[0]["id"]), None)
        if other:
            status, denied = request(base, f"/api/v1/sites/{other}", token=officer_token)
            check(
                status == 404,
                "an out-of-scope site is 404, not 403",
                "403 would confirm the site exists",
            )

        status, supervisor_login = request(
            base,
            "/api/v1/auth/login",
            method="POST",
            body={"username": "supervisor.dhanbad", "password": args.password},
        )
        if status == 200:
            status, report_denied = request(
                base,
                "/api/v1/reports/statutory.csv",
                token=supervisor_login["access_token"],
            )
            check(status == 403, "a supervisor cannot export statutory reports")

    # --- summary -----------------------------------------------------------
    print(f"\n{checks - len(failures)}/{checks} checks passed")
    if failures:
        print("\nFailed:")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("All good.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
