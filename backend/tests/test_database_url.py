"""Database URLs as managed hosts hand them out.

Render, Railway and Heroku give PostgreSQL URLs as postgres:// or postgresql://. SQLAlchemy maps
both to psycopg2, which this project does not install; it ships psycopg 3. Without normalisation a
deployment boots and then fails on its first query.
"""

from __future__ import annotations

import pytest

from app.core.config import Settings


@pytest.mark.parametrize(
    ("given", "expected"),
    [
        ("postgres://u:p@db.example:5432/jaagruk", "postgresql+psycopg://u:p@db.example:5432/jaagruk"),
        ("postgresql://u:p@db.example/jaagruk", "postgresql+psycopg://u:p@db.example/jaagruk"),
        ("  postgresql://u:p@db.example/jaagruk  ", "postgresql+psycopg://u:p@db.example/jaagruk"),
        # Already explicit, or not PostgreSQL at all: left exactly as given.
        ("postgresql+psycopg://u:p@db.example/jaagruk", "postgresql+psycopg://u:p@db.example/jaagruk"),
        ("sqlite:///./jaagruk.db", "sqlite:///./jaagruk.db"),
        ("sqlite://", "sqlite://"),
    ],
)
def test_managed_postgres_urls_use_the_installed_driver(given: str, expected: str) -> None:
    assert Settings(database_url=given).database_url == expected


def test_an_empty_url_is_still_refused() -> None:
    with pytest.raises(ValueError, match="must not be empty"):
        Settings(database_url="   ")
