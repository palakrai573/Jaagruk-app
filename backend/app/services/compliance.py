"""Compliance aggregation for the admin dashboard.

Readiness decays exponentially, so it is computed in Python from stored ``TrainingProgress`` rows
rather than in SQL. Two reasons: the arithmetic then provably matches the on-device model
(``app/services/readiness.py`` mirrors the Kotlin implementation and both are unit tested), and a
worker seeing a different number on their phone than an officer sees on the dashboard would destroy
confidence in the figure the whole platform is built around.

At pilot scale — a few thousand workers per company — this is a single indexed read plus arithmetic
over the result set. Beyond roughly 100k progress rows it wants a materialised readiness snapshot
refreshed on a schedule, and that is named here rather than discovered later.
"""

from __future__ import annotations

import json
import logging
from collections import defaultdict
from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.db.base import utcnow
from app.models import (
    AssessmentRun,
    Certificate,
    CertificateStatus,
    Hazard,
    HazardSeverity,
    HazardStatus,
    ModuleRecord,
    Site,
    TrainingProgress,
    Worker,
)
from app.schemas import (
    ComplianceOverviewOut,
    HesitationRiskOut,
    ReadinessTrendOut,
    ReadinessTrendPoint,
    SiteComplianceOut,
)
from app.services import readiness as readiness_service
from app.services.readiness import ReadinessBand, RequiredAction
from app.services.scope import AccessScope

logger = logging.getLogger("jaagruk.compliance")

SECONDS_PER_DAY = 86_400


def _now_sec() -> int:
    return int(utcnow().timestamp())


def _percent(numerator: int, denominator: int) -> float:
    """Percentage that never divides by zero.

    A site with no workers yet reports 0.0, not NaN. NaN reaching a dashboard chart renders as a
    blank panel, which reads as a broken tool rather than an empty site.
    """
    if denominator <= 0:
        return 0.0
    return round(numerator * 100.0 / denominator, 1)


@dataclass(slots=True)
class _WorkerRollup:
    worker_id: str
    site_id: str
    readiness_values: list[int]
    modules_certified: int
    modules_due: int
    has_valid_certificate: bool
    statutorily_valid_but_stale: bool
    hesitation_flagged: bool
    worst_band: ReadinessBand

    @property
    def mean_readiness(self) -> int:
        if not self.readiness_values:
            return 0
        return round(sum(self.readiness_values) / len(self.readiness_values))


def _rollup_workers(
    session: Session, scope: AccessScope, now_sec: int
) -> dict[str, _WorkerRollup]:
    """Per-worker readiness rollup across every module they have attempted."""
    site_ids = scope.visible_site_ids(session)

    worker_query = select(Worker).where(Worker.active.is_(True))
    if site_ids is not None:
        if not site_ids:
            return {}
        worker_query = worker_query.where(Worker.site_id.in_(site_ids))
    workers = list(session.scalars(worker_query).all())

    progress_query = select(TrainingProgress)
    if site_ids is not None:
        progress_query = progress_query.where(TrainingProgress.site_id.in_(site_ids))
    progress_by_worker: dict[str, list[TrainingProgress]] = defaultdict(list)
    for row in session.scalars(progress_query).all():
        progress_by_worker[row.worker_id].append(row)

    rollups: dict[str, _WorkerRollup] = {}
    for worker in workers:
        rows = progress_by_worker.get(worker.id, [])
        if not rows:
            rollups[worker.id] = _WorkerRollup(
                worker_id=worker.id,
                site_id=worker.site_id,
                readiness_values=[],
                modules_certified=0,
                modules_due=0,
                has_valid_certificate=False,
                statutorily_valid_but_stale=False,
                hesitation_flagged=False,
                worst_band=ReadinessBand.EXPIRED,
            )
            continue

        readiness_values: list[int] = []
        certified = 0
        due = 0
        valid = False
        stale_but_valid = False
        hesitant = False
        worst = ReadinessBand.READY

        for row in rows:
            assessment = readiness_service.evaluate_progress(row, now_sec)
            readiness_values.append(assessment.readiness_permille)
            if assessment.statutory_valid:
                certified += 1
                valid = True
            if assessment.required_action is RequiredAction.REFRESHER_DUE:
                due += 1
            if assessment.statutorily_valid_but_stale:
                stale_but_valid = True
            if row.last_hesitation_flag:
                hesitant = True
            if _band_rank(assessment.band) > _band_rank(worst):
                worst = assessment.band

        rollups[worker.id] = _WorkerRollup(
            worker_id=worker.id,
            site_id=worker.site_id,
            readiness_values=readiness_values,
            modules_certified=certified,
            modules_due=due,
            has_valid_certificate=valid,
            statutorily_valid_but_stale=stale_but_valid,
            hesitation_flagged=hesitant,
            worst_band=worst,
        )
    return rollups


_BAND_RANK = {
    ReadinessBand.READY: 0,
    ReadinessBand.DUE: 1,
    ReadinessBand.STALE: 2,
    ReadinessBand.EXPIRED: 3,
}


def _band_rank(band: ReadinessBand) -> int:
    return _BAND_RANK[band]


def overview(session: Session, scope: AccessScope) -> ComplianceOverviewOut:
    now_sec = _now_sec()
    site_ids = scope.visible_site_ids(session)

    site_query = select(func.count()).select_from(Site).where(Site.active.is_(True))
    if site_ids is not None:
        site_query = (
            site_query.where(Site.id.in_(site_ids)) if site_ids else site_query.where(False)
        )
    site_count = session.scalar(site_query) or 0

    rollups = _rollup_workers(session, scope, now_sec)
    worker_count = len(rollups)

    band_counts = {band: 0 for band in ReadinessBand}
    never_certified = 0
    stale_but_valid = 0
    readiness_total = 0
    workers_with_valid_cert = 0

    for rollup in rollups.values():
        if not rollup.readiness_values:
            never_certified += 1
            band_counts[ReadinessBand.EXPIRED] += 1
            continue
        band_counts[rollup.worst_band] += 1
        readiness_total += rollup.mean_readiness
        if rollup.has_valid_certificate:
            workers_with_valid_cert += 1
        if rollup.statutorily_valid_but_stale:
            stale_but_valid += 1

    assessed = worker_count - never_certified
    mean_readiness = round(readiness_total / assessed) if assessed > 0 else 0

    cert_query = select(func.count()).select_from(Certificate)
    quarantined_query = select(func.count()).select_from(Certificate).where(
        Certificate.status == CertificateStatus.QUARANTINED.value
    )
    hazard_query = select(func.count()).select_from(Hazard).where(
        Hazard.status.in_(
            [
                HazardStatus.OPEN.value,
                HazardStatus.ACKNOWLEDGED.value,
                HazardStatus.IN_PROGRESS.value,
            ]
        ),
        Hazard.duplicate_of_id.is_(None),
    )
    critical_query = select(func.count()).select_from(Hazard).where(
        Hazard.severity == HazardSeverity.CRITICAL.value,
        Hazard.status != HazardStatus.RESOLVED.value,
        Hazard.status != HazardStatus.INVALID.value,
        Hazard.duplicate_of_id.is_(None),
    )
    due_query = select(func.count()).select_from(TrainingProgress).where(
        TrainingProgress.next_due_at_sec <= now_sec,
        TrainingProgress.base_score > 0,
    )
    hesitation_query = select(func.count()).select_from(TrainingProgress).where(
        TrainingProgress.last_hesitation_flag.is_(True)
    )

    if site_ids is not None:
        if not site_ids:
            cert_query = cert_query.where(False)
            quarantined_query = quarantined_query.where(False)
            hazard_query = hazard_query.where(False)
            critical_query = critical_query.where(False)
            due_query = due_query.where(False)
            hesitation_query = hesitation_query.where(False)
        else:
            cert_query = cert_query.where(Certificate.site_id.in_(site_ids))
            quarantined_query = quarantined_query.where(Certificate.site_id.in_(site_ids))
            hazard_query = hazard_query.where(Hazard.site_id.in_(site_ids))
            critical_query = critical_query.where(Hazard.site_id.in_(site_ids))
            due_query = due_query.where(TrainingProgress.site_id.in_(site_ids))
            hesitation_query = hesitation_query.where(
                TrainingProgress.site_id.in_(site_ids)
            )

    return ComplianceOverviewOut(
        site_count=site_count,
        worker_count=worker_count,
        certificate_count=session.scalar(cert_query) or 0,
        quarantined_certificate_count=session.scalar(quarantined_query) or 0,
        certified_worker_percent=_percent(workers_with_valid_cert, worker_count),
        mean_readiness_permille=mean_readiness,
        workers_ready=band_counts[ReadinessBand.READY],
        workers_due=band_counts[ReadinessBand.DUE],
        workers_stale=band_counts[ReadinessBand.STALE],
        workers_expired=band_counts[ReadinessBand.EXPIRED],
        workers_never_certified=never_certified,
        statutorily_valid_but_stale=stale_but_valid,
        hesitation_risk_count=session.scalar(hesitation_query) or 0,
        open_hazard_count=session.scalar(hazard_query) or 0,
        critical_hazard_count=session.scalar(critical_query) or 0,
        refreshers_due_count=session.scalar(due_query) or 0,
        generated_at_sec=now_sec,
    )


def by_site(session: Session, scope: AccessScope) -> list[SiteComplianceOut]:
    now_sec = _now_sec()
    site_ids = scope.visible_site_ids(session)

    site_query = select(Site).where(Site.active.is_(True)).order_by(Site.id)
    if site_ids is not None:
        if not site_ids:
            return []
        site_query = site_query.where(Site.id.in_(site_ids))
    sites = list(session.scalars(site_query).all())
    if not sites:
        return []

    rollups = _rollup_workers(session, scope, now_sec)
    by_site_rollups: dict[str, list[_WorkerRollup]] = defaultdict(list)
    for rollup in rollups.values():
        by_site_rollups[rollup.site_id].append(rollup)

    ids = [site.id for site in sites]

    hazard_counts = dict(
        session.execute(
            select(Hazard.site_id, func.count())
            .where(
                Hazard.site_id.in_(ids),
                Hazard.duplicate_of_id.is_(None),
                Hazard.status.in_(
                    [
                        HazardStatus.OPEN.value,
                        HazardStatus.ACKNOWLEDGED.value,
                        HazardStatus.IN_PROGRESS.value,
                    ]
                ),
            )
            .group_by(Hazard.site_id)
        ).all()
    )
    quarantined_counts = dict(
        session.execute(
            select(Certificate.site_id, func.count())
            .where(
                Certificate.site_id.in_(ids),
                Certificate.status == CertificateStatus.QUARANTINED.value,
            )
            .group_by(Certificate.site_id)
        ).all()
    )
    hesitation_counts = dict(
        session.execute(
            select(TrainingProgress.site_id, func.count())
            .where(
                TrainingProgress.site_id.in_(ids),
                TrainingProgress.last_hesitation_flag.is_(True),
            )
            .group_by(TrainingProgress.site_id)
        ).all()
    )
    due_counts = dict(
        session.execute(
            select(TrainingProgress.site_id, func.count())
            .where(
                TrainingProgress.site_id.in_(ids),
                TrainingProgress.next_due_at_sec <= now_sec,
                TrainingProgress.base_score > 0,
            )
            .group_by(TrainingProgress.site_id)
        ).all()
    )

    results: list[SiteComplianceOut] = []
    for site in sites:
        site_rollups = by_site_rollups.get(site.id, [])
        assessed = [r for r in site_rollups if r.readiness_values]
        certified = sum(1 for r in site_rollups if r.has_valid_certificate)
        mean_readiness = (
            round(sum(r.mean_readiness for r in assessed) / len(assessed)) if assessed else 0
        )
        results.append(
            SiteComplianceOut(
                site_id=site.id,
                site_name=site.name,
                district=site.district,
                sector=site.sector,
                ar_scanned=site.ar_scanned,
                worker_count=len(site_rollups),
                certified_worker_percent=_percent(certified, len(site_rollups)),
                mean_readiness_permille=mean_readiness,
                hesitation_risk_count=hesitation_counts.get(site.id, 0),
                open_hazard_count=hazard_counts.get(site.id, 0),
                quarantined_certificate_count=quarantined_counts.get(site.id, 0),
                refreshers_due_count=due_counts.get(site.id, 0),
            )
        )
    return results


def hesitation_risk(
    session: Session,
    scope: AccessScope,
    *,
    page: int,
    page_size: int,
    site_id: str | None = None,
) -> tuple[list[HesitationRiskOut], int]:
    """Workers who answer correctly but slowly.

    The cohort this platform exists to surface. A conventional quiz scores these people as
    competent; a real evacuation would not. They are listed separately from failures because the
    intervention is different — targeted drill repetition, not re-teaching the material.
    """
    now_sec = _now_sec()
    site_ids = scope.visible_site_ids(session)

    # Restricted to runs that PASSED, deliberately.
    #
    # The cohort exists to isolate one specific problem: a worker who knows the material, would pass
    # any conventional quiz, and still decides too slowly to act in time. Mixing in runs that failed
    # on score would dilute exactly that signal and send the wrong intervention — a failed run needs
    # the material re-taught, a hesitant pass needs repeated drilling under time pressure.
    query = (
        select(AssessmentRun, Worker, Site, ModuleRecord)
        .join(Worker, Worker.id == AssessmentRun.worker_id)
        .join(Site, Site.id == AssessmentRun.site_id)
        .join(ModuleRecord, ModuleRecord.id == AssessmentRun.module_id)
        .where(
            AssessmentRun.hesitation_flag.is_(True),
            AssessmentRun.passed.is_(True),
        )
        .order_by(AssessmentRun.finished_at_sec.desc())
    )
    if site_ids is not None:
        if not site_ids:
            return [], 0
        query = query.where(AssessmentRun.site_id.in_(site_ids))
    if site_id:
        query = query.where(AssessmentRun.site_id == site_id)

    count_query = (
        select(func.count())
        .select_from(AssessmentRun)
        .where(
            AssessmentRun.hesitation_flag.is_(True),
            AssessmentRun.passed.is_(True),
        )
    )
    if site_ids is not None:
        count_query = count_query.where(AssessmentRun.site_id.in_(site_ids))
    if site_id:
        count_query = count_query.where(AssessmentRun.site_id == site_id)
    total = session.scalar(count_query) or 0

    rows = session.execute(query.offset((page - 1) * page_size).limit(page_size)).all()

    # One worker can appear for several modules; keep the most recent run per pairing so the list
    # is a list of people to retrain, not a log.
    seen: set[tuple[str, str]] = set()
    results: list[HesitationRiskOut] = []

    for run, worker, site, module in rows:
        key = (run.worker_id, run.module_id)
        if key in seen:
            continue
        seen.add(key)

        steps = _parse_steps(run.steps_json)
        hesitant_steps = sum(1 for step in steps if step.get("outcome") == "CORRECT_SLOW")
        expert_total = sum(int(step.get("expert_ms", 0)) for step in steps)
        pace = (
            round(run.median_latency_ms / (expert_total / len(steps)), 2)
            if steps and expert_total > 0
            else 0.0
        )

        progress = session.get(TrainingProgress, (run.worker_id, run.module_id))
        assessment = readiness_service.evaluate_progress(progress, now_sec)

        results.append(
            HesitationRiskOut(
                worker_id=worker.id,
                worker_full_name=worker.full_name,
                site_id=site.id,
                site_name=site.name,
                module_id=module.id,
                module_title_en=module.title_en,
                score_permille=run.score_permille,
                median_latency_ms=run.median_latency_ms,
                pace_multiple=pace,
                hesitant_step_count=hesitant_steps,
                total_step_count=len(steps),
                last_attempt_at_sec=run.finished_at_sec,
                readiness_permille=assessment.readiness_permille,
                statutory_valid=assessment.statutory_valid,
            )
        )
    return results, total


def _parse_steps(steps_json: str) -> list[dict]:
    try:
        parsed = json.loads(steps_json)
    except (json.JSONDecodeError, TypeError):
        logger.warning("could not parse steps_json; treating the run as having no step detail")
        return []
    return parsed if isinstance(parsed, list) else []


def readiness_trend(
    session: Session,
    scope: AccessScope,
    *,
    from_sec: int,
    to_sec: int,
    site_id: str | None = None,
) -> ReadinessTrendOut:
    """Daily activity and mean readiness over a window.

    Readiness is evaluated **as of each day**, not as of today, so the line shows how prepared the
    workforce actually was at that point rather than back-projecting the current state.
    """
    if to_sec < from_sec:
        from_sec, to_sec = to_sec, from_sec
    site_ids = scope.visible_site_ids(session)
    if site_ids is not None and not site_ids:
        return ReadinessTrendOut(
            site_id=site_id, from_epoch_sec=from_sec, to_epoch_sec=to_sec, points=[]
        )

    progress_query = select(TrainingProgress)
    cert_query = select(Certificate.issued_at_sec).where(
        Certificate.issued_at_sec >= from_sec, Certificate.issued_at_sec <= to_sec
    )
    run_query = select(AssessmentRun.finished_at_sec, AssessmentRun.hesitation_flag).where(
        AssessmentRun.finished_at_sec >= from_sec, AssessmentRun.finished_at_sec <= to_sec
    )

    if site_ids is not None:
        progress_query = progress_query.where(TrainingProgress.site_id.in_(site_ids))
        cert_query = cert_query.where(Certificate.site_id.in_(site_ids))
        run_query = run_query.where(AssessmentRun.site_id.in_(site_ids))
    if site_id:
        progress_query = progress_query.where(TrainingProgress.site_id == site_id)
        cert_query = cert_query.where(Certificate.site_id == site_id)
        run_query = run_query.where(AssessmentRun.site_id == site_id)

    progress_rows = list(session.scalars(progress_query).all())

    certs_per_day: dict[int, int] = defaultdict(int)
    for (issued_at,) in session.execute(cert_query).all():
        certs_per_day[_day_bucket(issued_at)] += 1

    runs_per_day: dict[int, int] = defaultdict(int)
    hesitant_per_day: dict[int, int] = defaultdict(int)
    for finished_at, hesitation in session.execute(run_query).all():
        bucket = _day_bucket(finished_at)
        runs_per_day[bucket] += 1
        if hesitation:
            hesitant_per_day[bucket] += 1

    points: list[ReadinessTrendPoint] = []
    day = _day_bucket(from_sec)
    last_day = _day_bucket(to_sec)
    # 400 days keeps a mistyped range from generating an unbounded series.
    guard = 0

    while day <= last_day and guard < 400:
        guard += 1
        evaluated = [
            readiness_service.evaluate_progress(row, day).readiness_permille
            for row in progress_rows
            if row.base_score > 0 and row.certified_at_sec <= day
        ]
        points.append(
            ReadinessTrendPoint(
                day_epoch_sec=day,
                mean_readiness_permille=(
                    round(sum(evaluated) / len(evaluated)) if evaluated else 0
                ),
                certificates_issued=certs_per_day.get(day, 0),
                assessments_run=runs_per_day.get(day, 0),
                hesitation_flagged=hesitant_per_day.get(day, 0),
            )
        )
        day += SECONDS_PER_DAY

    return ReadinessTrendOut(
        site_id=site_id, from_epoch_sec=from_sec, to_epoch_sec=to_sec, points=points
    )


def _day_bucket(epoch_sec: int) -> int:
    return (epoch_sec // SECONDS_PER_DAY) * SECONDS_PER_DAY
