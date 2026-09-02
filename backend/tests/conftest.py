"""Test fixtures.

Every test runs against a fresh in-memory SQLite database. ``reset_engine`` disposes the engine,
which for an in-memory database destroys it outright, so isolation is total rather than dependent
on remembering to clean up.

The whole suite is offline and deterministic: no PostgreSQL, no network, no fixed ports.
"""

from __future__ import annotations

import os
import uuid
from collections.abc import Generator
from pathlib import Path

import pytest

# Set before any application module is imported, so cached settings pick these up.
os.environ["JAAGRUK_ENVIRONMENT"] = "test"
os.environ["JAAGRUK_DATABASE_URL"] = "sqlite://"
os.environ["JAAGRUK_JWT_SECRET"] = "test-only-secret-value-that-is-comfortably-long-enough"
os.environ["JAAGRUK_ACCESS_TOKEN_MINUTES"] = "30"
os.environ["JAAGRUK_CORS_ORIGINS"] = "http://localhost:5173"

from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy.orm import Session  # noqa: E402

from app.core import canonical, crypto  # noqa: E402
from app.core.config import get_settings  # noqa: E402
from app.core.security import Role, hash_password, token_denylist  # noqa: E402
from app.db.base import utcnow  # noqa: E402
from app.db.session import (  # noqa: E402
    create_all_tables,
    reset_engine,
    session_scope,
)
from app.main import create_app  # noqa: E402
from app.models import (  # noqa: E402
    ChainHead,
    Company,
    Device,
    Language,
    ModuleRecord,
    Sector,
    Site,
    SiteKey,
    User,
    Worker,
)

TEST_PASSWORD = "TestPassword123!"

SITE_A = "JH-DHN-001"
SITE_B = "JH-BOK-007"
DEVICE_A = "test-device-aaaa-0001"
DEVICE_B = "test-device-bbbb-0002"

#: Deterministic, non-secret signing identities for each test site.
SITE_A_PRIVATE_KEY = bytes(range(1, 33))
SITE_B_PRIVATE_KEY = bytes(range(33, 65))

MODULES = (
    ("fire-evacuation", 1, "Fire & Explosion Response", 9, False, True),
    ("gas-confined-space", 2, "Gas Leak & Confined Space Protocol", 11, True, True),
    ("machinery-loto", 3, "Machinery Guarding & Lockout-Tagout", 8, False, False),
    ("ppe-height", 4, "PPE Selection & Working at Height", 8, False, False),
    ("electrical-first-response", 5, "Electrical Safety & First Response", 9, False, False),
)


@pytest.fixture(scope="session", autouse=True)
def _media_root(tmp_path_factory: pytest.TempPathFactory) -> Generator[Path, None, None]:
    root = tmp_path_factory.mktemp("jaagruk-media")
    os.environ["JAAGRUK_MEDIA_ROOT"] = str(root)
    get_settings.cache_clear()
    yield root


@pytest.fixture(autouse=True)
def _fresh_database(_media_root: Path) -> Generator[None, None, None]:
    get_settings.cache_clear()
    token_denylist.clear()
    reset_engine()
    create_all_tables()
    yield
    reset_engine()


@pytest.fixture()
def db() -> Generator[Session, None, None]:
    with session_scope() as session:
        yield session


@pytest.fixture()
def client() -> Generator[TestClient, None, None]:
    app = create_app(get_settings())
    with TestClient(app) as test_client:
        yield test_client


# ---------------------------------------------------------------------------
# Seeding
# ---------------------------------------------------------------------------


@pytest.fixture()
def seeded(db: Session) -> dict[str, object]:
    """Two companies, two sites, every role, five modules, four workers.

    Small enough to reason about in an assertion, complete enough that RBAC scoping has something
    real to exclude.
    """
    coal = Company(id=str(uuid.uuid4()), name="Coal Co (test)", sector=Sector.COAL_MINE.value)
    steel = Company(
        id=str(uuid.uuid4()), name="Steel Co (test)", sector=Sector.STEEL_PLANT.value
    )
    db.add_all([coal, steel])
    db.flush()

    site_a = Site(
        id=SITE_A,
        company_id=coal.id,
        name="Jharia Block 4",
        district="Dhanbad",
        sector=Sector.COAL_MINE.value,
        ar_scanned=True,
        ar_anchor_count=8,
        latitude=23.75,
        longitude=86.42,
    )
    site_b = Site(
        id=SITE_B,
        company_id=steel.id,
        name="Bokaro Coke Ovens",
        district="Bokaro",
        sector=Sector.STEEL_PLANT.value,
        latitude=23.669,
        longitude=86.1511,
    )
    db.add_all([site_a, site_b])
    db.flush()

    db.add_all(
        [
            SiteKey(
                id=str(uuid.uuid4()),
                site_id=SITE_A,
                epoch=1,
                public_key=crypto.public_key_from_private(SITE_A_PRIVATE_KEY),
                active=True,
            ),
            SiteKey(
                id=str(uuid.uuid4()),
                site_id=SITE_B,
                epoch=1,
                public_key=crypto.public_key_from_private(SITE_B_PRIVATE_KEY),
                active=True,
            ),
        ]
    )
    db.add_all(
        [
            Device(id=DEVICE_A, site_id=SITE_A, model="Redmi Note 13", active=True),
            Device(id=DEVICE_B, site_id=SITE_B, model="Galaxy M14", active=True),
        ]
    )

    for module_id, code, title, minutes, buddy, full in MODULES:
        db.add(
            ModuleRecord(
                id=module_id,
                module_code=code,
                catalog_version=1,
                title_key=f"module_{code}_title",
                title_en=title,
                description_key=f"module_{code}_desc",
                statutory_reference="Mines Act 1952 s.58",
                estimated_minutes=minutes,
                supports_buddy_drill=buddy,
                fully_implemented=full,
                enabled=True,
                sectors_json='["coal_mine", "steel_plant"]',
            )
        )

    password_hash = hash_password(TEST_PASSWORD)
    users = {
        "inspector": User(
            id=str(uuid.uuid4()),
            username="inspector",
            password_hash=password_hash,
            role=Role.DGMS_INSPECTOR.value,
            full_name="DGMS Inspector",
        ),
        "admin_coal": User(
            id=str(uuid.uuid4()),
            username="admin.coal",
            password_hash=password_hash,
            role=Role.COMPANY_ADMIN.value,
            full_name="Coal Admin",
            company_id=coal.id,
        ),
        "admin_steel": User(
            id=str(uuid.uuid4()),
            username="admin.steel",
            password_hash=password_hash,
            role=Role.COMPANY_ADMIN.value,
            full_name="Steel Admin",
            company_id=steel.id,
        ),
        "officer_a": User(
            id=str(uuid.uuid4()),
            username="officer.a",
            password_hash=password_hash,
            role=Role.SITE_OFFICER.value,
            full_name="Site A Officer",
            company_id=coal.id,
            site_id=SITE_A,
        ),
        "supervisor_a": User(
            id=str(uuid.uuid4()),
            username="supervisor.a",
            password_hash=password_hash,
            role=Role.SUPERVISOR.value,
            full_name="Site A Supervisor",
            company_id=coal.id,
            site_id=SITE_A,
        ),
        "supervisor_b": User(
            id=str(uuid.uuid4()),
            username="supervisor.b",
            password_hash=password_hash,
            role=Role.SUPERVISOR.value,
            full_name="Site B Supervisor",
            company_id=steel.id,
            site_id=SITE_B,
        ),
    }
    db.add_all(list(users.values()))

    workers = {}
    for index, (site_id, name) in enumerate(
        (
            (SITE_A, "Birsa Munda"),
            (SITE_A, "Sunita Kumari"),
            (SITE_A, "Ravi Mahto"),
            (SITE_B, "Anita Das"),
        ),
        start=1,
    ):
        worker_id = f"{site_id}-W{index:05d}"
        worker = Worker(
            id=worker_id,
            site_id=site_id,
            full_name=name,
            worker_id_hash=canonical.worker_id_hash(worker_id),
            preferred_language=Language.SANTALI.value if index % 2 else Language.HINDI.value,
            pictogram_mode=index % 2 == 0,
            joined_at=utcnow(),
        )
        db.add(worker)
        workers[worker_id] = worker

    db.add_all(
        [
            ChainHead(site_id=SITE_A, last_seq=0, last_record_hash=canonical.ZERO_HASH),
            ChainHead(site_id=SITE_B, last_seq=0, last_record_hash=canonical.ZERO_HASH),
        ]
    )
    db.commit()

    return {
        "coal_company_id": coal.id,
        "steel_company_id": steel.id,
        "users": {key: user.username for key, user in users.items()},
        "user_ids": {key: user.id for key, user in users.items()},
        "worker_ids": list(workers.keys()),
    }


# ---------------------------------------------------------------------------
# Auth helpers
# ---------------------------------------------------------------------------


def login(client: TestClient, username: str, password: str = TEST_PASSWORD) -> dict:
    response = client.post(
        "/api/v1/auth/login", json={"username": username, "password": password}
    )
    assert response.status_code == 200, response.text
    return response.json()


def auth_headers(client: TestClient, username: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {login(client, username)['access_token']}"}


@pytest.fixture()
def inspector_headers(client: TestClient, seeded: dict) -> dict[str, str]:
    return auth_headers(client, "inspector")


@pytest.fixture()
def officer_a_headers(client: TestClient, seeded: dict) -> dict[str, str]:
    return auth_headers(client, "officer.a")


@pytest.fixture()
def supervisor_a_headers(client: TestClient, seeded: dict) -> dict[str, str]:
    return auth_headers(client, "supervisor.a")


@pytest.fixture()
def admin_coal_headers(client: TestClient, seeded: dict) -> dict[str, str]:
    return auth_headers(client, "admin.coal")


# ---------------------------------------------------------------------------
# Certificate helpers
# ---------------------------------------------------------------------------


def build_certificate(
    *,
    site_id: str = SITE_A,
    seq: int = 1,
    worker_id: str | None = None,
    module_code: int = 1,
    score_permille: int = 842,
    median_latency_ms: int = 2_400,
    flags: int = canonical.FLAG_PASSED | canonical.FLAG_SITE_SCANNED_AR,
    issued_at_sec: int | None = None,
    prev_record_hash: bytes = canonical.ZERO_HASH,
    private_key: bytes | None = None,
) -> tuple[canonical.SignedAttestation, str]:
    """Build a genuinely signed certificate, returning it and its QR text."""
    worker_id = worker_id or f"{site_id}-W00001"
    issued_at_sec = issued_at_sec or int(utcnow().timestamp()) - 3_600
    key = private_key or (
        SITE_A_PRIVATE_KEY if site_id == SITE_A else SITE_B_PRIVATE_KEY
    )

    attestation = canonical.Attestation(
        site_id=site_id,
        seq=seq,
        worker_id_hash=canonical.worker_id_hash(worker_id),
        module_code=module_code,
        score_permille=score_permille,
        median_latency_ms=median_latency_ms,
        outcome_flags=flags,
        issued_at_epoch_min=issued_at_sec // 60,
        prev_record_hash=prev_record_hash,
    )
    message = canonical.canonical_bytes(attestation)
    signature = crypto.sign(key, message)
    signed = canonical.SignedAttestation(
        attestation=attestation,
        signature=signature,
        record_hash=canonical.record_hash(message, signature),
    )
    return signed, canonical.encode_qr(signed)


def certificate_upload(
    signed: canonical.SignedAttestation,
    qr_text: str,
    worker_id: str,
    module_id: str = "fire-evacuation",
    idempotency_key: str | None = None,
) -> dict:
    return {
        "idempotency_key": idempotency_key or f"cert-{signed.record_hash.hex()[:24]}",
        "qr_text": qr_text,
        "worker_id": worker_id,
        "module_id": module_id,
        "key_epoch": 1,
    }


def build_chain(
    length: int,
    *,
    site_id: str = SITE_A,
    worker_ids: list[str] | None = None,
) -> list[tuple[canonical.SignedAttestation, str]]:
    """A correctly linked run of certificates for one site."""
    records: list[tuple[canonical.SignedAttestation, str]] = []
    previous = canonical.ZERO_HASH
    for index in range(1, length + 1):
        worker_id = (
            worker_ids[(index - 1) % len(worker_ids)]
            if worker_ids
            else f"{site_id}-W{index:05d}"
        )
        signed, qr = build_certificate(
            site_id=site_id,
            seq=index,
            worker_id=worker_id,
            module_code=((index - 1) % 5) + 1,
            score_permille=700 + (index * 13) % 300,
            prev_record_hash=previous,
        )
        records.append((signed, qr))
        previous = signed.record_hash
    return records


PROJECT_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_PATH = (
    PROJECT_ROOT / "core" / "src" / "test" / "resources" / "fixtures"
    / "attestation_vectors.json"
)
