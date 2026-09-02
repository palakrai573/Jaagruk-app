"""Engine and session factory.

Synchronous SQLAlchemy, on purpose. FastAPI runs ``def`` (non-async) endpoints in a worker
thread pool, so blocking database calls never touch the event loop. An async stack would add
asyncpg plus aiosqlite and a second set of driver behaviours to reason about, for no benefit at
this scale. The one genuinely async surface, the live-updates WebSocket, does its single database
read through ``run_in_threadpool``.
"""

from __future__ import annotations

import logging
from collections.abc import Generator, Iterator
from contextlib import contextmanager

from sqlalchemy import create_engine, event, make_url, text
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app.core.config import Settings, get_settings

logger = logging.getLogger("jaagruk.db")

_engine: Engine | None = None
_session_factory: sessionmaker[Session] | None = None


def is_memory_sqlite(database_url: str) -> bool:
    """True for every spelling of an in-memory SQLite URL.

    ``sqlite://``, ``sqlite:///`` and ``sqlite:///:memory:`` are all in-memory, and only the last
    one contains the substring ``:memory:``. Matching on the substring alone silently gives every
    connection its own empty database, which presents as "no such table" on the first query.
    """
    try:
        url = make_url(database_url)
    except Exception:  # noqa: BLE001 - an unparseable URL is not our problem to classify here
        return False
    return url.get_backend_name() == "sqlite" and (
        url.database is None or url.database in ("", ":memory:")
    )


def build_engine(settings: Settings) -> Engine:
    """Create an engine tuned for the configured backend."""
    if settings.is_sqlite:
        is_memory = is_memory_sqlite(settings.database_url)
        engine = create_engine(
            settings.database_url,
            # SQLite ties a connection to the thread that made it, and FastAPI serves sync
            # endpoints from a thread pool.
            connect_args={"check_same_thread": False},
            # An in-memory database lives only as long as its connection, so tests need every
            # session to share one.
            poolclass=StaticPool if is_memory else None,
            echo=False,
            future=True,
        )

        @event.listens_for(engine, "connect")
        def _configure_sqlite(dbapi_connection, _record) -> None:  # noqa: ANN001
            cursor = dbapi_connection.cursor()
            # Off by default in SQLite, which would silently let a hazard outlive a deleted
            # site and leave orphan rows in compliance reports.
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.execute("PRAGMA journal_mode=WAL")
            cursor.execute("PRAGMA busy_timeout=5000")
            cursor.close()

        return engine

    return create_engine(
        settings.database_url,
        pool_pre_ping=True,  # a mine-site link drops connections; fail fast and reconnect
        pool_size=10,
        max_overflow=20,
        pool_recycle=1_800,
        echo=False,
        future=True,
    )


def get_engine() -> Engine:
    global _engine
    if _engine is None:
        _engine = build_engine(get_settings())
    return _engine


def get_session_factory() -> sessionmaker[Session]:
    global _session_factory
    if _session_factory is None:
        _session_factory = sessionmaker(
            bind=get_engine(),
            autoflush=False,
            autocommit=False,
            expire_on_commit=False,
            future=True,
        )
    return _session_factory


def reset_engine() -> None:
    """Drop the cached engine and session factory. Used by tests between fixtures."""
    global _engine, _session_factory
    if _engine is not None:
        _engine.dispose()
    _engine = None
    _session_factory = None


def get_db() -> Generator[Session, None, None]:
    """FastAPI dependency.

    Commits on success and rolls back on any exception, so a handler that raises can never leave
    a half-written batch behind.
    """
    session = get_session_factory()()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


@contextmanager
def session_scope() -> Iterator[Session]:
    """Standalone transactional scope for scripts and background work."""
    session = get_session_factory()()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def database_is_reachable() -> bool:
    """Used by ``/readyz``. Never raises, so the probe itself cannot 500."""
    try:
        with get_engine().connect() as connection:
            connection.execute(text("SELECT 1"))
        return True
    except Exception as exc:  # noqa: BLE001 - a readiness probe must report, not propagate
        logger.warning("database readiness check failed: %s", exc)
        return False


def required_tables_exist() -> tuple[bool, list[str]]:
    """Report any model tables missing from the database.

    Called at startup so a backend booted against an unmigrated database says exactly which
    tables are missing and which command to run, instead of failing on the first request.
    """
    from sqlalchemy import inspect

    from app.db.base import Base
    from app import models  # noqa: F401  (import registers the mappings)

    inspector = inspect(get_engine())
    existing = set(inspector.get_table_names())
    expected = set(Base.metadata.tables.keys())
    missing = sorted(expected - existing)
    return (not missing), missing


def create_all_tables() -> None:
    """Create the full schema directly from the models.

    Used for SQLite development and for the test suite. PostgreSQL deployments go through
    Alembic instead, so schema changes are reviewable and reversible.
    """
    from app.db.base import Base
    from app import models  # noqa: F401

    Base.metadata.create_all(bind=get_engine())


def drop_all_tables() -> None:
    """Test teardown only."""
    from app.db.base import Base
    from app import models  # noqa: F401

    Base.metadata.drop_all(bind=get_engine())
