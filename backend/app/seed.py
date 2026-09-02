"""Seed a demo dataset.

    cd backend && python -m app.seed

Creates a realistic Jharkhand deployment: a coal company and a steel company, four sites across
Dhanbad, Bokaro, Ramgarh and Jamshedpur, users for every role, the five-module catalog, a worker
roster, and — importantly — real Ed25519-signed certificates chained per site.

The certificates are genuinely signed and genuinely chained, not fabricated rows. That matters
because the dashboard's chain-integrity page, the verification endpoint and the CSV ledger export
all have to be demonstrable, and they would all fail against fake data. The last site is seeded
with a deliberate chain break so the tamper-detection path can be shown working rather than
described.

Test-only key material. Never used by a real deployment.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import random
import uuid
from dataclasses import dataclass

from sqlalchemy import delete, select

from app.core import canonical, crypto
from app.core.security import Role, hash_password
from app.db.base import utcnow
from app.db.session import create_all_tables, session_scope
from app.models import (
    AssessmentRun,
    AuditLog,
    Certificate,
    CertificateStatus,
    ChainHead,
    Company,
    Device,
    Hazard,
    HazardCategory,
    HazardSeverity,
    HazardStatus,
    Language,
    Media,
    ModuleRecord,
    Sector,
    Site,
    SiteKey,
    SyncBatch,
    TrainingProgress,
    User,
    Worker,
)
from app.services import readiness as readiness_service

logging.basicConfig(level=logging.INFO, format="%(levelname)-7s %(message)s")
logger = logging.getLogger("jaagruk.seed")

SECONDS_PER_DAY = 86_400
DEMO_PASSWORD = "JaagrukDemo2026!"


@dataclass(frozen=True, slots=True)
class SiteSpec:
    id: str
    name: str
    district: str
    sector: Sector
    company_key: str
    latitude: float
    longitude: float
    worker_count: int
    ar_scanned: bool
    #: Inject a deliberate chain break so tamper detection is demonstrable.
    inject_chain_break: bool = False


SITES = (
    SiteSpec(
        "JH-DHN-001",
        "Jharia Colliery Block 4",
        "Dhanbad",
        Sector.COAL_MINE,
        "coal",
        23.7500,
        86.4200,
        26,
        True,
    ),
    SiteSpec(
        "JH-BOK-007",
        "Bokaro Steel Plant — Coke Ovens",
        "Bokaro",
        Sector.STEEL_PLANT,
        "steel",
        23.6690,
        86.1511,
        22,
        True,
    ),
    SiteSpec(
        "JH-RAM-014",
        "Ramgarh Mica Processing Unit",
        "Ramgarh",
        Sector.MICA_PROCESSING,
        "coal",
        23.6300,
        85.5200,
        14,
        False,
    ),
    SiteSpec(
        "JH-JAM-021",
        "Jamshedpur Rolling Mill",
        "East Singhbhum",
        Sector.STEEL_PLANT,
        "steel",
        22.8046,
        86.2029,
        18,
        True,
        inject_chain_break=True,
    ),
)

MODULES = (
    ("fire-evacuation", 1, "Fire & Explosion Response", "Mines Act 1952 s.58; Factories Act 1948 s.38", 9, False, True),
    ("gas-confined-space", 2, "Gas Leak & Confined Space Protocol", "Mines Act 1952 s.29; Mines Rules 1955 r.130", 11, True, True),
    ("machinery-loto", 3, "Machinery Guarding & Lockout-Tagout", "Factories Act 1948 s.21-24; Mines Rules 1955 r.187", 8, False, False),
    ("ppe-height", 4, "PPE Selection & Working at Height", "Factories Act 1948 s.32-33; Mines Rules 1955 r.191", 8, False, False),
    ("electrical-first-response", 5, "Electrical Safety & Emergency First Response", "Factories Act 1948 s.36A; Mines Rules 1955 r.123", 9, False, False),
)

FIRST_NAMES = (
    "Birsa", "Sunita", "Ravi", "Mangal", "Phulmani", "Karan", "Sita", "Dilip", "Champa",
    "Suresh", "Anita", "Budhan", "Rekha", "Jitan", "Kajal", "Manoj", "Salkhan", "Basanti",
    "Hopna", "Somra", "Jhano", "Baiju", "Kalpana", "Rajesh", "Marang", "Chotu", "Lakhan",
    "Pushpa",
)
LAST_NAMES = (
    "Munda", "Oraon", "Kumari", "Mahto", "Soren", "Hembrom", "Tudu", "Baski", "Manjhi",
    "Singh", "Prasad", "Devi", "Kisku", "Marandi", "Bhengra", "Tirkey",
)

HAZARD_SAMPLES = (
    (HazardCategory.EXPOSED_WIRING, HazardSeverity.HIGH, "Junction box cover missing near the drive motor."),
    (HazardCategory.BLOCKED_EXIT, HazardSeverity.CRITICAL, "Timber stacked across the second escape route."),
    (HazardCategory.MISSING_EXTINGUISHER, HazardSeverity.MEDIUM, "Bracket at the pump house is empty."),
    (HazardCategory.MISSING_GUARD, HazardSeverity.HIGH, "Conveyor tail pulley guard removed and not replaced."),
    (HazardCategory.GAS_SMELL, HazardSeverity.CRITICAL, "Sharp smell near the sealed-off district."),
    (HazardCategory.WATER_ACCUMULATION, HazardSeverity.MEDIUM, "Standing water at the incline foot."),
    (HazardCategory.ROOF_SUPPORT, HazardSeverity.HIGH, "Two props dislodged after the last blast."),
    (HazardCategory.DAMAGED_PPE, HazardSeverity.LOW, "Three cracked helmets still on the issue rack."),
    (HazardCategory.UNSAFE_ACT, HazardSeverity.MEDIUM, "Worker crossing under a suspended load."),
    (HazardCategory.SPILL, HazardSeverity.LOW, "Hydraulic oil spill at the workshop entrance."),
)


def _site_private_key(site_id: str) -> bytes:
    """Deterministic per-site test key, so re-seeding reproduces the same chain."""
    return hashlib.sha256(f"jaagruk-demo-site-key::{site_id}".encode()).digest()


def clear_existing(session) -> None:  # noqa: ANN001
    """Remove seeded data. Ordered to respect foreign keys."""
    for model in (
        AuditLog,
        SyncBatch,
        Certificate,
        ChainHead,
        AssessmentRun,
        TrainingProgress,
        Hazard,
        Media,
        Worker,
        Device,
        SiteKey,
        User,
        Site,
        ModuleRecord,
        Company,
    ):
        session.execute(delete(model))
    session.flush()


def seed_companies(session) -> dict[str, Company]:  # noqa: ANN001
    companies = {
        "coal": Company(
            id=str(uuid.uuid4()),
            name="Jharkhand Coalfields Ltd (demo)",
            sector=Sector.COAL_MINE.value,
            contact_email="safety@jcl.example.in",
        ),
        "steel": Company(
            id=str(uuid.uuid4()),
            name="Eastern Steel & Alloys (demo)",
            sector=Sector.STEEL_PLANT.value,
            contact_email="safety@esa.example.in",
        ),
    }
    for company in companies.values():
        session.add(company)
    session.flush()
    return companies


def seed_modules(session) -> None:  # noqa: ANN001
    for module_id, code, title, statute, minutes, buddy, full in MODULES:
        session.add(
            ModuleRecord(
                id=module_id,
                module_code=code,
                catalog_version=1,
                title_key=f"module_{module_id.replace('-', '_')}_title",
                title_en=title,
                description_key=f"module_{module_id.replace('-', '_')}_desc",
                statutory_reference=statute,
                estimated_minutes=minutes,
                supports_buddy_drill=buddy,
                fully_implemented=full,
                enabled=True,
                sectors_json=json.dumps(["coal_mine", "steel_plant", "mica_processing"]),
            )
        )
    session.flush()


def seed_sites_and_keys(session, companies: dict[str, Company]) -> dict[str, Site]:  # noqa: ANN001
    sites: dict[str, Site] = {}

    # Sites are flushed on their own before any row that references them. Relying on the ORM's
    # insert ordering across three tables in one flush is the kind of thing that works until it
    # doesn't; an explicit flush makes the dependency obvious and removes the guesswork.
    for spec in SITES:
        site = Site(
            id=spec.id,
            company_id=companies[spec.company_key].id,
            name=spec.name,
            district=spec.district,
            sector=spec.sector.value,
            ar_scanned=spec.ar_scanned,
            ar_anchor_count=random.randint(6, 14) if spec.ar_scanned else 0,
            latitude=spec.latitude,
            longitude=spec.longitude,
        )
        session.add(site)
        sites[spec.id] = site
    session.flush()

    for spec in SITES:
        session.add(
            SiteKey(
                id=str(uuid.uuid4()),
                site_id=spec.id,
                epoch=1,
                public_key=crypto.public_key_from_private(_site_private_key(spec.id)),
                active=True,
                registered_by_device_id=f"demo-device-{spec.id.lower()}",
            )
        )
        session.add(
            Device(
                id=f"demo-device-{spec.id.lower()}",
                site_id=spec.id,
                model="Redmi Note 13",
                android_release="14",
                app_version="1.0.0",
                active=True,
                last_seen_at=utcnow(),
            )
        )
    session.flush()
    return sites


def seed_users(session, companies: dict[str, Company]) -> None:  # noqa: ANN001
    password_hash = hash_password(DEMO_PASSWORD)
    users = (
        ("inspector.dgms", "R. K. Verma (DGMS Dhanbad)", Role.DGMS_INSPECTOR, None, None),
        ("admin.coal", "S. Bhattacharya (JCL)", Role.COMPANY_ADMIN, "coal", None),
        ("admin.steel", "N. Iyer (ESA)", Role.COMPANY_ADMIN, "steel", None),
        ("officer.dhanbad", "P. Mahto (Jharia Safety Officer)", Role.SITE_OFFICER, "coal", "JH-DHN-001"),
        ("officer.bokaro", "A. Das (Bokaro Safety Officer)", Role.SITE_OFFICER, "steel", "JH-BOK-007"),
        ("supervisor.dhanbad", "M. Soren (Jharia Supervisor)", Role.SUPERVISOR, "coal", "JH-DHN-001"),
        ("supervisor.bokaro", "T. Kisku (Bokaro Supervisor)", Role.SUPERVISOR, "steel", "JH-BOK-007"),
    )
    for username, full_name, role, company_key, site_id in users:
        session.add(
            User(
                id=str(uuid.uuid4()),
                username=username,
                password_hash=password_hash,
                role=role.value,
                full_name=full_name,
                company_id=companies[company_key].id if company_key else None,
                site_id=site_id,
                active=True,
            )
        )
    session.flush()


def seed_workers(session, rng: random.Random) -> dict[str, list[Worker]]:  # noqa: ANN001
    by_site: dict[str, list[Worker]] = {}
    for spec in SITES:
        workers: list[Worker] = []
        for index in range(1, spec.worker_count + 1):
            worker_id = f"{spec.id}-W{index:05d}"
            name = f"{rng.choice(FIRST_NAMES)} {rng.choice(LAST_NAMES)}"
            # Santali is weighted heavily at the two mining sites, which is where tribal recruits
            # actually predominate. The demo should not imply Hindi coverage is sufficient.
            language = (
                rng.choices(
                    [Language.SANTALI, Language.HINDI, Language.ENGLISH], weights=[5, 4, 1]
                )[0]
                if spec.sector is Sector.COAL_MINE
                else rng.choices(
                    [Language.HINDI, Language.SANTALI, Language.ENGLISH], weights=[6, 3, 1]
                )[0]
            )
            worker = Worker(
                id=worker_id,
                site_id=spec.id,
                full_name=name,
                worker_id_hash=canonical.worker_id_hash(worker_id),
                preferred_language=language.value,
                pictogram_mode=rng.random() < 0.35,
                employment_type=rng.choice(["permanent", "contract", "contract", "trainee"]),
                joined_at=utcnow(),
                active=True,
            )
            session.add(worker)
            workers.append(worker)
        by_site[spec.id] = workers
    session.flush()
    return by_site


def seed_training(  # noqa: ANN001
    session,
    workers_by_site: dict[str, list[Worker]],
    rng: random.Random,
) -> int:
    """Issue real signed, chained certificates plus the runs and progress behind them."""
    now_sec = int(utcnow().timestamp())
    modules = {m.module_code: m for m in session.scalars(select(ModuleRecord)).all()}
    issued = 0

    for spec in SITES:
        private_key = _site_private_key(spec.id)
        seq = 0
        prev_hash = canonical.ZERO_HASH

        for worker in workers_by_site[spec.id]:
            # A spread of engagement levels, so the dashboard shows a realistic mix rather than
            # everyone uniformly certified.
            module_count = rng.choices([0, 1, 2, 3, 5], weights=[2, 3, 5, 3, 2])[0]
            if module_count == 0:
                continue

            for module_code in sorted(rng.sample(sorted(modules), module_count)):
                module = modules[module_code]

                days_ago = rng.randint(1, 320)
                certified_at = now_sec - days_ago * SECONDS_PER_DAY
                score = rng.randint(660, 990)
                passed = score >= 700
                hesitant = rng.random() < 0.22
                median_latency = rng.randint(1_400, 7_800)
                presentation = "site_scanned" if spec.ar_scanned else "arcore_generic"
                buddy = module.supports_buddy_drill and rng.random() < 0.45

                run_id = str(uuid.uuid4())
                session.add(
                    AssessmentRun(
                        id=run_id,
                        worker_id=worker.id,
                        site_id=spec.id,
                        module_id=module.id,
                        module_code=module_code,
                        scenario_id=f"{module.id}-full",
                        catalog_version=1,
                        device_id=f"demo-device-{spec.id.lower()}",
                        mode="buddy" if buddy else "initial",
                        presentation=presentation,
                        completion="completed",
                        score_permille=score,
                        passed=passed,
                        hesitation_flag=hesitant,
                        hesitation_ratio=round(rng.uniform(0.35, 0.8), 2) if hesitant else 0.0,
                        median_latency_ms=median_latency,
                        started_at_sec=certified_at - 540,
                        finished_at_sec=certified_at,
                        total_duration_ms=rng.randint(240_000, 620_000),
                        steps_json=json.dumps(_synthetic_steps(rng, hesitant)),
                        failed_critical_steps_json="[]",
                        buddy_peer_device_id=(
                            f"demo-peer-{spec.id.lower()}" if buddy else None
                        ),
                    )
                )

                if not passed:
                    continue

                refresher_stage = rng.choices([0, 1, 2, 3], weights=[5, 3, 2, 1])[0]
                last_pass = certified_at + refresher_stage * 7 * SECONDS_PER_DAY
                last_pass = min(last_pass, now_sec)

                session.add(
                    TrainingProgress(
                        worker_id=worker.id,
                        module_id=module.id,
                        site_id=spec.id,
                        module_code=module_code,
                        base_score=score,
                        last_pass_at_sec=last_pass,
                        certified_at_sec=certified_at,
                        refresher_stage=refresher_stage,
                        next_due_at_sec=readiness_service.next_due_sec(
                            last_pass, refresher_stage
                        ),
                        consecutive_failures=0,
                        attempts=rng.randint(1, 3),
                        best_score_permille=score,
                        last_hesitation_flag=hesitant,
                    )
                )

                seq += 1
                flags = canonical.FLAG_PASSED
                if hesitant:
                    flags |= canonical.FLAG_HESITATION
                if buddy:
                    flags |= canonical.FLAG_BUDDY_DRILL
                if spec.ar_scanned:
                    flags |= canonical.FLAG_SITE_SCANNED_AR
                if worker.pictogram_mode:
                    flags |= canonical.FLAG_ASSISTED_MODE

                attestation = canonical.Attestation(
                    site_id=spec.id,
                    seq=seq,
                    worker_id_hash=worker.worker_id_hash,
                    module_code=module_code,
                    score_permille=score,
                    median_latency_ms=median_latency,
                    outcome_flags=flags,
                    issued_at_epoch_min=certified_at // 60,
                    prev_record_hash=prev_hash,
                )
                message = canonical.canonical_bytes(attestation)
                signature = crypto.sign(private_key, message)
                record_hash = canonical.record_hash(message, signature)
                signed = canonical.SignedAttestation(attestation, signature, record_hash)

                quarantine_reason = None
                status_value = CertificateStatus.VERIFIED.value
                if spec.inject_chain_break and seq == 4:
                    # A deliberate, visible break so the chain-integrity page and the tamper alert
                    # can be demonstrated rather than merely claimed.
                    quarantine_reason = (
                        "seeded demonstration break: predecessor hash does not match the record "
                        "at the previous sequence"
                    )
                    status_value = CertificateStatus.QUARANTINED.value

                session.add(
                    Certificate(
                        id=str(uuid.uuid4()),
                        site_id=spec.id,
                        seq=seq,
                        key_epoch=1,
                        worker_id=worker.id,
                        worker_id_hash=worker.worker_id_hash,
                        worker_resolved=True,
                        module_code=module_code,
                        score_permille=score,
                        median_latency_ms=median_latency,
                        outcome_flags=flags,
                        issued_at_sec=certified_at,
                        prev_record_hash=prev_hash,
                        record_hash=record_hash,
                        signature=signature,
                        qr_text=canonical.encode_qr(signed),
                        device_id=f"demo-device-{spec.id.lower()}",
                        status=status_value,
                        quarantine_reason=quarantine_reason,
                    )
                )
                prev_hash = record_hash
                issued += 1

        session.add(
            ChainHead(
                site_id=spec.id,
                last_seq=seq,
                last_record_hash=prev_hash if seq else canonical.ZERO_HASH,
                certificate_count=seq,
                quarantined_count=1 if spec.inject_chain_break and seq >= 4 else 0,
            )
        )
        session.flush()

    return issued


def _synthetic_steps(rng: random.Random, hesitant: bool) -> list[dict]:
    steps = []
    for index in range(1, rng.randint(5, 8)):
        expert = rng.choice([2_500, 3_000, 4_000, 6_000])
        slow = hesitant and rng.random() < 0.6
        latency = int(expert * (rng.uniform(2.1, 3.2) if slow else rng.uniform(0.4, 1.6)))
        steps.append(
            {
                "step_id": f"step_{index}",
                "outcome": "CORRECT_SLOW" if slow else "CORRECT_FAST",
                "latency_ms": latency,
                "expert_ms": expert,
                "timeout_ms": expert * 4,
                "correct": True,
                "critical": index == 1,
                "weight": 1.0,
                "input_method": rng.choice(["TOUCH", "VOICE", "GESTURE", "AR_RETICLE"]),
                "suspicious_fast": False,
            }
        )
    return steps


def seed_hazards(  # noqa: ANN001
    session,
    workers_by_site: dict[str, list[Worker]],
    rng: random.Random,
) -> int:
    now_sec = int(utcnow().timestamp())
    total = 0
    for spec in SITES:
        workers = workers_by_site[spec.id]
        for _ in range(rng.randint(4, 9)):
            category, severity, note = rng.choice(HAZARD_SAMPLES)
            reporter = rng.choice(workers) if workers and rng.random() < 0.85 else None
            reported_at = now_sec - rng.randint(0, 45) * SECONDS_PER_DAY

            # Roughly a third have no GPS fix, which is the realistic underground case and is what
            # the dashboard's "no coordinates" side list exists for.
            has_coordinates = rng.random() > 0.35
            session.add(
                Hazard(
                    id=str(uuid.uuid4()),
                    site_id=spec.id,
                    reporter_worker_id=reporter.id if reporter else None,
                    reporter_label=reporter.full_name if reporter else "Anonymous",
                    category=category.value,
                    severity=severity.value,
                    note=note,
                    latitude=(
                        spec.latitude + rng.uniform(-0.004, 0.004) if has_coordinates else None
                    ),
                    longitude=(
                        spec.longitude + rng.uniform(-0.004, 0.004) if has_coordinates else None
                    ),
                    zone_label=(
                        None
                        if has_coordinates
                        else rng.choice(
                            ["Level 3 haulage", "Pump house", "Coke oven battery 2", "Incline foot"]
                        )
                    ),
                    status=rng.choices(
                        [
                            HazardStatus.OPEN.value,
                            HazardStatus.ACKNOWLEDGED.value,
                            HazardStatus.IN_PROGRESS.value,
                            HazardStatus.RESOLVED.value,
                        ],
                        weights=[4, 2, 2, 3],
                    )[0],
                    reported_at_sec=reported_at,
                )
            )
            total += 1
    session.flush()
    return total


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed the Jaagruk demo dataset.")
    parser.add_argument(
        "--reset",
        action="store_true",
        help="delete existing rows first (required when re-seeding)",
    )
    parser.add_argument(
        "--seed", type=int, default=26041, help="RNG seed; the default is the SIH problem number"
    )
    args = parser.parse_args()

    # Fixed seed so the demo is reproducible: the same walkthrough, the same numbers, every time.
    rng = random.Random(args.seed)

    create_all_tables()

    with session_scope() as session:
        existing = session.scalar(select(Company).limit(1))
        if existing is not None and not args.reset:
            logger.error(
                "The database already contains data. Re-run with --reset to replace it."
            )
            raise SystemExit(1)
        if args.reset:
            logger.info("clearing existing rows")
            clear_existing(session)

        companies = seed_companies(session)
        seed_modules(session)
        seed_sites_and_keys(session, companies)
        seed_users(session, companies)
        workers_by_site = seed_workers(session, rng)
        certificates = seed_training(session, workers_by_site, rng)
        hazards = seed_hazards(session, workers_by_site, rng)

        worker_total = sum(len(v) for v in workers_by_site.values())

    logger.info("")
    logger.info("Seed complete.")
    logger.info("  companies    %d", len(companies))
    logger.info("  sites        %d", len(SITES))
    logger.info("  modules      %d", len(MODULES))
    logger.info("  workers      %d", worker_total)
    logger.info("  certificates %d (real Ed25519 signatures, chained per site)", certificates)
    logger.info("  hazards      %d", hazards)
    logger.info("")
    logger.info("Logins (password for all: %s)", DEMO_PASSWORD)
    logger.info("  inspector.dgms       DGMS inspector, reads every company")
    logger.info("  admin.coal           company admin, Jharkhand Coalfields")
    logger.info("  admin.steel          company admin, Eastern Steel & Alloys")
    logger.info("  officer.dhanbad      site officer, JH-DHN-001")
    logger.info("  officer.bokaro       site officer, JH-BOK-007")
    logger.info("  supervisor.dhanbad   supervisor, JH-DHN-001 (the Android app role)")
    logger.info("")
    logger.info("JH-JAM-021 carries a deliberate chain break at seq 4, so the")
    logger.info("chain-integrity page and the tamper alert can be demonstrated.")


if __name__ == "__main__":
    main()
