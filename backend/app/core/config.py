"""Application settings.

Every value is overridable by a ``JAAGRUK_``-prefixed environment variable, and the unsafe
defaults are the ones that get checked at startup rather than the ones that get shipped.
"""

from __future__ import annotations

import logging
from enum import Enum
from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

logger = logging.getLogger("jaagruk.config")

# Sentinel value. Present in .env.example so a fresh checkout runs immediately, and refused
# outright when ENVIRONMENT is production.
INSECURE_DEFAULT_SECRET = (
    "change-me-this-value-is-not-secret-and-the-app-will-refuse-to-start-in-prod"
)


class Environment(str, Enum):
    DEVELOPMENT = "development"
    TEST = "test"
    PRODUCTION = "production"

    @property
    def is_production(self) -> bool:
        return self is Environment.PRODUCTION


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="JAAGRUK_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    environment: Environment = Environment.DEVELOPMENT

    # --- database ---------------------------------------------------------
    database_url: str = "sqlite:///./jaagruk.db"

    # --- security ---------------------------------------------------------
    jwt_secret: str = INSECURE_DEFAULT_SECRET
    jwt_algorithm: str = "HS256"
    access_token_minutes: int = Field(default=30, ge=1, le=1440)
    refresh_token_days: int = Field(default=30, ge=1, le=365)

    # --- http -------------------------------------------------------------
    cors_origins: str = "http://localhost:5173,http://127.0.0.1:5173"
    api_prefix: str = "/api/v1"

    # --- media ------------------------------------------------------------
    media_root: Path = Path("./media_store")
    max_media_bytes: int = Field(default=8 * 1024 * 1024, ge=1024)

    # --- sync -------------------------------------------------------------
    max_batch_items: int = Field(default=100, ge=1, le=1000)
    max_clock_skew_seconds: int = Field(default=86_400, ge=0)

    # --- pagination -------------------------------------------------------
    default_page_size: int = Field(default=50, ge=1, le=200)
    max_page_size: int = Field(default=200, ge=1, le=1000)

    # --- reports ----------------------------------------------------------
    # Streaming keeps memory flat, but an unbounded export can still tie up a connection for
    # minutes. The cap is stated in the CSV header so a truncated export is never mistaken for
    # a complete one.
    max_report_rows: int = Field(default=50_000, ge=100)

    # --- hazards ----------------------------------------------------------
    hazard_reports_per_worker_per_hour: int = Field(default=10, ge=1)
    hazard_duplicate_window_seconds: int = Field(default=3_600, ge=0)
    hazard_duplicate_radius_metres: float = Field(default=5.0, ge=0.0)

    # --- public -----------------------------------------------------------
    verify_base_url: str = "https://jaagruk.jharkhand.gov.in"

    @field_validator("database_url")
    @classmethod
    def _validate_database_url(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("JAAGRUK_DATABASE_URL must not be empty")
        return value.strip()

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")

    @property
    def jwt_secret_is_insecure(self) -> bool:
        return self.jwt_secret == INSECURE_DEFAULT_SECRET or len(self.jwt_secret) < 32

    def validate_for_startup(self) -> None:
        """Fail loudly on an unsafe production configuration.

        Refusing to boot is the correct behaviour here. A safety-certification backend running
        on a publicly known signing secret is worse than one that is down, because it looks
        like it is working.
        """
        problems: list[str] = []

        if self.environment.is_production:
            if self.jwt_secret_is_insecure:
                problems.append(
                    "JAAGRUK_JWT_SECRET is the example value or shorter than 32 characters. "
                    'Generate one with: python -c "import secrets; '
                    'print(secrets.token_urlsafe(64))"'
                )
            if self.is_sqlite:
                problems.append(
                    "JAAGRUK_DATABASE_URL points at SQLite. Production needs PostgreSQL: "
                    "pip install -r requirements-postgres.txt and set a postgresql+psycopg:// URL."
                )
            if any(origin == "*" for origin in self.cors_origin_list):
                problems.append("JAAGRUK_CORS_ORIGINS must not be '*' in production.")

        if problems:
            raise RuntimeError(
                "Refusing to start with an unsafe configuration:\n  - "
                + "\n  - ".join(problems)
            )

        if self.jwt_secret_is_insecure:
            logger.warning(
                "JAAGRUK_JWT_SECRET is the insecure example value. Acceptable for local "
                "development only; production startup will refuse it."
            )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Cached settings accessor. Call ``get_settings.cache_clear()`` in tests."""
    return Settings()
