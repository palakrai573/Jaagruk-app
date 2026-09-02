"""Liveness and readiness probes.

Split deliberately. ``/health`` answers "is the process up" and must never touch the database, or
a database blip would make an orchestrator kill a perfectly healthy process. ``/readyz`` answers
"can this instance serve traffic" and does check the database and the schema.
"""

from __future__ import annotations

from fastapi import APIRouter

from app import __version__
from app.api.deps import SettingsDep
from app.db.session import database_is_reachable, required_tables_exist
from app.schemas import HealthResponse, ReadinessResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse, summary="Liveness probe")
def health(settings: SettingsDep) -> HealthResponse:
    return HealthResponse(
        status="ok", version=__version__, environment=settings.environment.value
    )


@router.get("/readyz", response_model=ReadinessResponse, summary="Readiness probe")
def readyz() -> ReadinessResponse:
    reachable = database_is_reachable()
    migrations_applied, missing = (True, []) if not reachable else required_tables_exist()
    ready = reachable and migrations_applied
    return ReadinessResponse(
        status="ready" if ready else "degraded",
        database=reachable,
        migrations_applied=migrations_applied,
        missing_tables=missing,
    )
