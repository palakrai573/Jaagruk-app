"""Declarative base and shared column conventions."""

from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import DateTime, MetaData
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# Explicit naming conventions so Alembic autogenerate produces stable, comparable names for
# indexes and constraints instead of database-specific defaults. Without this, a migration
# generated on SQLite and applied to PostgreSQL can disagree about what a constraint is called.
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)


def utcnow() -> datetime:
    """Timezone-aware UTC now.

    Everything the server stores as a timestamp is UTC. Timezone-naive datetimes are the usual
    route to a certificate that appears to expire five and a half hours early.
    """
    return datetime.now(tz=timezone.utc)


def as_utc(value: datetime | None) -> datetime | None:
    """Force a timestamp read back from the database into UTC-aware form.

    Necessary because SQLite has no timestamp type and hands back naive datetimes even for a
    ``DateTime(timezone=True)`` column, while PostgreSQL returns aware ones. Mixing the two raises
    ``can't subtract offset-naive and offset-aware datetimes`` — and worse, a naive value
    interpreted as local time silently shifts every comparison by the machine's UTC offset, which
    for IST is five and a half hours.

    Everything the server writes is UTC, so a naive value can safely be *labelled* UTC rather than
    converted.
    """
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def to_utc_iso(value: datetime | None) -> str | None:
    """UTC ISO-8601 with an explicit offset, so API output does not depend on the backend."""
    normalised = as_utc(value)
    return normalised.isoformat() if normalised is not None else None


class TimestampMixin:
    """``created_at`` for rows where creation time is part of the audit trail."""

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, nullable=False, index=True
    )
