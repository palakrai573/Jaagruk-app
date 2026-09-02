"""Authentication primitives: password hashing, JWT issue and verification.

Password hashing uses :func:`hashlib.scrypt` from the standard library rather than
``passlib[bcrypt]``. That is a deliberate choice: scrypt is memory-hard, well specified, needs no
third-party dependency, and cannot break on a backend deployment because a bcrypt wheel and a
passlib release disagreed about a version attribute. The parameters below give roughly 64 MB of
memory cost per verification, which is a real deterrent to offline cracking while staying
comfortable for a login endpoint.
"""

from __future__ import annotations

import hmac
import secrets
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from enum import Enum
from hashlib import scrypt

import jwt

from app.core.config import Settings

# --- scrypt parameters -----------------------------------------------------
_SCRYPT_N = 2**16  # CPU/memory cost
_SCRYPT_R = 8  # block size
_SCRYPT_P = 1  # parallelisation
_SCRYPT_DKLEN = 32
_SALT_BYTES = 16
_HASH_PREFIX = "scrypt"

# scrypt with n=2**16, r=8 needs ~64 MB. Python's default maxmem is 0 (which means a low
# internal cap), so it has to be raised explicitly or the call fails.
_SCRYPT_MAXMEM = 132 * 1024 * 1024


class Role(str, Enum):
    """Roles, ordered from broadest read scope to narrowest.

    ``supervisor`` is the role the Android app authenticates as. It can register devices and
    workers for its own site and upload sync batches, and nothing else.
    """

    DGMS_INSPECTOR = "dgms_inspector"
    COMPANY_ADMIN = "company_admin"
    SITE_OFFICER = "site_officer"
    SUPERVISOR = "supervisor"

    @property
    def sees_all_companies(self) -> bool:
        return self is Role.DGMS_INSPECTOR

    @property
    def can_manage_workers(self) -> bool:
        return self in (Role.COMPANY_ADMIN, Role.SITE_OFFICER, Role.SUPERVISOR)

    @property
    def can_triage_hazards(self) -> bool:
        return self in (Role.COMPANY_ADMIN, Role.SITE_OFFICER)

    @property
    def can_export_reports(self) -> bool:
        return self in (Role.DGMS_INSPECTOR, Role.COMPANY_ADMIN, Role.SITE_OFFICER)

    @property
    def can_upload_sync(self) -> bool:
        return self in (Role.SUPERVISOR, Role.SITE_OFFICER, Role.COMPANY_ADMIN)

    @property
    def can_manage_sites(self) -> bool:
        return self is Role.COMPANY_ADMIN


class TokenType(str, Enum):
    ACCESS = "access"
    REFRESH = "refresh"


@dataclass(frozen=True, slots=True)
class TokenClaims:
    subject: str
    role: Role
    company_id: str | None
    site_id: str | None
    token_type: TokenType
    jti: str
    expires_at: datetime


class TokenError(Exception):
    """Raised when a token is absent, malformed, expired or revoked."""


# ---------------------------------------------------------------------------
# Passwords
# ---------------------------------------------------------------------------


def hash_password(password: str) -> str:
    """Return ``scrypt$n$r$p$salt_hex$hash_hex``.

    Parameters are stored alongside the hash so they can be raised later without invalidating
    every existing password.
    """
    if not password:
        raise ValueError("password must not be empty")
    salt = secrets.token_bytes(_SALT_BYTES)
    derived = scrypt(
        password.encode("utf-8"),
        salt=salt,
        n=_SCRYPT_N,
        r=_SCRYPT_R,
        p=_SCRYPT_P,
        dklen=_SCRYPT_DKLEN,
        maxmem=_SCRYPT_MAXMEM,
    )
    return f"{_HASH_PREFIX}${_SCRYPT_N}${_SCRYPT_R}${_SCRYPT_P}${salt.hex()}${derived.hex()}"


def verify_password(password: str, stored: str) -> bool:
    """Constant-time password check. Returns ``False`` for a malformed stored hash."""
    if not password or not stored:
        return False
    parts = stored.split("$")
    if len(parts) != 6 or parts[0] != _HASH_PREFIX:
        return False
    try:
        n, r, p = int(parts[1]), int(parts[2]), int(parts[3])
        salt = bytes.fromhex(parts[4])
        expected = bytes.fromhex(parts[5])
    except ValueError:
        return False

    try:
        derived = scrypt(
            password.encode("utf-8"),
            salt=salt,
            n=n,
            r=r,
            p=p,
            dklen=len(expected),
            maxmem=_SCRYPT_MAXMEM,
        )
    except ValueError:
        # Stored parameters that this interpreter refuses (for example a memory cost above
        # maxmem). Refusing to authenticate is the only safe answer.
        return False
    return hmac.compare_digest(derived, expected)


# ---------------------------------------------------------------------------
# Tokens
# ---------------------------------------------------------------------------


def _now() -> datetime:
    return datetime.now(tz=timezone.utc)


def create_token(
    settings: Settings,
    subject: str,
    role: Role,
    token_type: TokenType,
    company_id: str | None = None,
    site_id: str | None = None,
) -> tuple[str, TokenClaims]:
    lifetime = (
        timedelta(minutes=settings.access_token_minutes)
        if token_type is TokenType.ACCESS
        else timedelta(days=settings.refresh_token_days)
    )
    issued_at = _now()
    expires_at = issued_at + lifetime
    jti = uuid.uuid4().hex

    payload = {
        "sub": subject,
        "role": role.value,
        "company_id": company_id,
        "site_id": site_id,
        "typ": token_type.value,
        "jti": jti,
        "iat": int(issued_at.timestamp()),
        "exp": int(expires_at.timestamp()),
        "iss": "jaagruk",
    }
    encoded = jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)
    claims = TokenClaims(
        subject=subject,
        role=role,
        company_id=company_id,
        site_id=site_id,
        token_type=token_type,
        jti=jti,
        expires_at=expires_at,
    )
    return encoded, claims


def decode_token(
    settings: Settings,
    token: str,
    expected_type: TokenType,
) -> TokenClaims:
    """Decode and validate a JWT.

    :raises TokenError: expired, wrong signature, wrong issuer, or the wrong token type. The
        type check matters: a refresh token must never be accepted as an access token, or its
        much longer lifetime becomes the effective session length.
    """
    if not token:
        raise TokenError("no token supplied")
    try:
        payload = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
            issuer="jaagruk",
            options={"require": ["exp", "iat", "sub", "jti"]},
        )
    except jwt.ExpiredSignatureError as exc:
        raise TokenError("token has expired") from exc
    except jwt.InvalidTokenError as exc:
        raise TokenError(f"invalid token: {exc}") from exc

    raw_type = payload.get("typ")
    if raw_type != expected_type.value:
        raise TokenError(
            f"expected a {expected_type.value} token but received {raw_type!r}"
        )

    raw_role = payload.get("role")
    try:
        role = Role(raw_role)
    except ValueError as exc:
        raise TokenError(f"unknown role {raw_role!r}") from exc

    return TokenClaims(
        subject=str(payload["sub"]),
        role=role,
        company_id=payload.get("company_id"),
        site_id=payload.get("site_id"),
        token_type=expected_type,
        jti=str(payload["jti"]),
        expires_at=datetime.fromtimestamp(payload["exp"], tz=timezone.utc),
    )


class TokenDenylist:
    """In-memory denylist of revoked token ids.

    Deliberately in-process and deliberately called out as such: it works for a single-instance
    pilot and is wrong for a multi-instance deployment, where it needs to be Redis or a database
    table. Documented in ``docs/API.md`` rather than left to be discovered when horizontal
    scaling silently stops logout from working.
    """

    def __init__(self) -> None:
        self._revoked: dict[str, datetime] = {}

    def revoke(self, jti: str, expires_at: datetime) -> None:
        self._prune()
        self._revoked[jti] = expires_at

    def is_revoked(self, jti: str) -> bool:
        self._prune()
        return jti in self._revoked

    def clear(self) -> None:
        self._revoked.clear()

    def _prune(self) -> None:
        now = _now()
        expired = [jti for jti, exp in self._revoked.items() if exp <= now]
        for jti in expired:
            del self._revoked[jti]


token_denylist = TokenDenylist()
