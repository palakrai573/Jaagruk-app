"""FastAPI application factory and startup checks.

Startup is deliberately noisy about anything that would make the service quietly wrong: an unsafe
production secret, a database with no schema, SQLite in production. Refusing to boot, or logging a
loud warning with the exact remedy, beats serving requests that look fine and are not.
"""

from __future__ import annotations

import asyncio
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.exc import IntegrityError, SQLAlchemyError

from app import __version__
from app.api import health
from app.api.router import api_router
from app.core.config import Settings, get_settings
from app.db.session import (
    create_all_tables,
    database_is_reachable,
    required_tables_exist,
)
from app.services import events

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-7s %(name)-22s %(message)s",
)
logger = logging.getLogger("jaagruk")

DESCRIPTION = """
Backend for **Jaagruk**, the AR-based vocational safety training and certification platform for
Jharkhand's mining, steel and mica sectors.

This service is deliberately *not* on the critical path for training. Devices work fully offline:
they run drills, score them, issue Ed25519-signed certificates into a per-site hash chain, and
verify certificates from a QR code with no network at all. The backend exists to receive what
those devices produce when connectivity happens to be available, and to give compliance officers
and DGMS inspectors a view across sites.

Key behaviours worth knowing before integrating:

* **Sync is idempotent.** Replaying `(device_id, client_batch_id)` returns the stored response and
  ingests nothing.
* **Verification returns a status, not a boolean.** `signature_valid_chain_unknown` is a legitimate
  partial result, not a failure.
* **A certificate that fails chain verification is stored and flagged, never discarded.** Deleting
  the evidence of tampering would defeat the point of keeping a chain.
* **Statutory validity and operational readiness are separate numbers** and are never blended.
"""


def _log_startup_state(settings: Settings) -> None:
    logger.info("Jaagruk backend %s starting in %s mode", __version__, settings.environment.value)
    logger.info(
        "database: %s",
        "sqlite" if settings.is_sqlite else settings.database_url.split("@")[-1],
    )

    if not database_is_reachable():
        logger.error(
            "database is not reachable at startup. The service will boot so /readyz can report "
            "the problem, but every data endpoint will fail until it is."
        )
        return

    if settings.is_sqlite:
        # Development and test convenience: create the schema straight from the models so a fresh
        # checkout runs with no migration step. PostgreSQL goes through Alembic, so schema changes
        # stay reviewable and reversible.
        create_all_tables()
        logger.info("sqlite schema ensured from the model metadata")
        return

    applied, missing = required_tables_exist()
    if not applied:
        logger.error(
            "database schema is incomplete. Missing %d table(s): %s\n"
            "    Run migrations first:  cd backend && alembic upgrade head",
            len(missing),
            ", ".join(missing),
        )


@asynccontextmanager
async def lifespan(app: FastAPI):  # noqa: ANN201
    settings = get_settings()
    settings.validate_for_startup()

    settings.media_root.mkdir(parents=True, exist_ok=True)
    _log_startup_state(settings)

    # Recorded so synchronous handlers running in the thread pool can schedule WebSocket
    # broadcasts back onto the serving loop.
    events.bind_event_loop(asyncio.get_running_loop())

    yield

    logger.info("Jaagruk backend shutting down (%d live socket(s))", events.bus.subscriber_count)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()

    app = FastAPI(
        title="Jaagruk Safety Certification API",
        version=__version__,
        description=DESCRIPTION,
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_credentials=True,
        allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "If-None-Match"],
        expose_headers=["ETag", "Content-Disposition", "Retry-After"],
    )

    # Probes live outside the version prefix: an orchestrator should not have to know the API
    # version to check whether the process is alive.
    app.include_router(health.router)
    app.include_router(api_router, prefix=settings.api_prefix)

    _install_middleware(app)
    _install_exception_handlers(app)
    return app


def _install_middleware(app: FastAPI) -> None:
    @app.middleware("http")
    async def timing_and_security_headers(request: Request, call_next):  # noqa: ANN001, ANN202
        started = time.perf_counter()
        response = await call_next(request)
        elapsed_ms = (time.perf_counter() - started) * 1000.0

        response.headers["X-Response-Time-Ms"] = f"{elapsed_ms:.1f}"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        # The API serves JSON and CSV, never HTML that should execute anything.
        response.headers["Content-Security-Policy"] = "default-src 'none'; frame-ancestors 'none'"

        if elapsed_ms > 2_000:
            logger.warning(
                "slow request %s %s took %.0fms",
                request.method,
                request.url.path,
                elapsed_ms,
            )
        return response


def _install_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def validation_handler(  # noqa: ANN202
        request: Request, exc: RequestValidationError
    ):
        # FastAPI's default body is a nested structure that is awkward to surface in a UI. Flatten
        # it into "field: message" lines a dashboard or an Android client can display directly.
        problems = []
        for error in exc.errors():
            location = ".".join(str(part) for part in error.get("loc", ()) if part != "body")
            problems.append(f"{location or 'request'}: {error.get('msg', 'invalid')}")
        logger.info("validation failure on %s: %s", request.url.path, "; ".join(problems))
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={"detail": "; ".join(problems), "code": "validation_error"},
        )

    @app.exception_handler(IntegrityError)
    async def integrity_handler(request: Request, exc: IntegrityError):  # noqa: ANN202
        # A unique constraint firing is usually the system working: two devices racing for the
        # same chain slot, or a replayed batch. 409 with a stable code, not a 500.
        message = str(getattr(exc, "orig", exc))
        logger.warning("integrity violation on %s: %s", request.url.path, message)
        return JSONResponse(
            status_code=status.HTTP_409_CONFLICT,
            content={
                "detail": (
                    "This record conflicts with one already stored. If this was a retry, the "
                    "original was accepted and no action is needed."
                ),
                "code": "conflict",
                "hint": message[:300],
            },
        )

    @app.exception_handler(SQLAlchemyError)
    async def database_handler(request: Request, exc: SQLAlchemyError):  # noqa: ANN202
        logger.error("database error on %s: %s", request.url.path, exc)
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={
                "detail": (
                    "The database is temporarily unavailable. Devices should keep their sync "
                    "queue and retry with backoff."
                ),
                "code": "database_unavailable",
            },
        )


app = create_app()
