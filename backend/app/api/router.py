"""Aggregate router.

Order matters in two places and both are deliberate:

* ``certificates`` is mounted before ``workers`` so ``/certificates/verify`` is matched as a
  literal before any ``/{id}`` pattern could shadow it.
* ``health`` is mounted outside the versioned prefix in ``main.py``, because an orchestrator's
  probe should not have to know the API version.
"""

from __future__ import annotations

from fastapi import APIRouter

from app.api import (
    auth,
    catalog,
    certificates,
    compliance,
    hazards,
    media,
    reports,
    sync_api,
    workers,
    ws,
)

api_router = APIRouter()

api_router.include_router(auth.router)
api_router.include_router(catalog.router)
api_router.include_router(workers.router)
api_router.include_router(sync_api.router)
api_router.include_router(certificates.router)
api_router.include_router(compliance.router)
api_router.include_router(hazards.router)
api_router.include_router(media.router)
api_router.include_router(reports.router)
api_router.include_router(ws.router)
