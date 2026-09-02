"""Shared FastAPI dependencies.

Authorisation is a dependency, never a check inside a handler body. Every protected route declares
``Depends(require_roles(...))`` or ``Depends(current_scope)``, and the row-level filter travels with
the scope object. A new endpoint that forgets an explicit check still cannot read another site's
rows, because the scope is what builds the query.
"""

from __future__ import annotations

import logging
from collections.abc import Generator
from typing import Annotated

from fastapi import Depends, HTTPException, Query, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.core.config import Settings, get_settings
from app.core.security import (
    Role,
    TokenClaims,
    TokenError,
    TokenType,
    decode_token,
    token_denylist,
)
from app.db.session import get_db
from app.models import Site, User
from app.services.scope import AccessScope

logger = logging.getLogger("jaagruk.api")

# auto_error=False so a missing header produces our own 401 with a useful message rather than
# FastAPI's bare "Not authenticated".
bearer_scheme = HTTPBearer(auto_error=False, scheme_name="JaagrukBearer")

SettingsDep = Annotated[Settings, Depends(get_settings)]
DbDep = Annotated[Session, Depends(get_db)]


def client_ip(request: Request) -> str | None:
    """Best-effort client address for the audit trail.

    ``X-Forwarded-For`` is honoured because the service runs behind a reverse proxy in every
    realistic deployment. It is attacker-controllable, so it is recorded as evidence and never used
    for an access decision.
    """
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()[:64]
    return request.client.host[:64] if request.client else None


ClientIpDep = Annotated[str | None, Depends(client_ip)]


def current_claims(
    settings: SettingsDep,
    credentials: Annotated[
        HTTPAuthorizationCredentials | None, Depends(bearer_scheme)
    ] = None,
) -> TokenClaims:
    if credentials is None or not credentials.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authorization header with a bearer token is required.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    try:
        claims = decode_token(settings, credentials.credentials, TokenType.ACCESS)
    except TokenError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=str(exc),
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc

    if token_denylist.is_revoked(claims.jti):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="This session has been signed out. Log in again.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return claims


ClaimsDep = Annotated[TokenClaims, Depends(current_claims)]


def current_user(claims: ClaimsDep, db: DbDep) -> User:
    user = db.get(User, claims.subject)
    if user is None or not user.active:
        # The token is cryptographically fine but the account is gone or disabled. 401 rather than
        # 403: the correct client action is to discard the token and re-authenticate.
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="This account is no longer active.",
        )
    return user


UserDep = Annotated[User, Depends(current_user)]


def current_scope(user: UserDep) -> AccessScope:
    return AccessScope(
        role=Role(user.role),
        user_id=user.id,
        username=user.username,
        company_id=user.company_id,
        site_id=user.site_id,
    )


ScopeDep = Annotated[AccessScope, Depends(current_scope)]


def require_roles(*roles: Role):
    """Dependency factory restricting a route to specific roles."""
    allowed = set(roles)

    def _guard(scope: ScopeDep) -> AccessScope:
        if scope.role not in allowed:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=(
                    f"Role '{scope.role.value}' cannot perform this action. "
                    f"Required: {', '.join(sorted(r.value for r in allowed))}."
                ),
            )
        return scope

    return _guard


def require_site_access(db: DbDep, scope: ScopeDep, site_id: str) -> Site:
    """Load a site the caller is entitled to see.

    Returns 404, not 403, for a site outside the caller's scope. A 403 would confirm the site
    exists, which is itself information a competitor or an unauthorised user should not get.
    """
    site = db.get(Site, site_id)
    if site is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=f"Site '{site_id}' was not found."
        )
    visible = scope.visible_site_ids(db)
    if visible is not None and site_id not in visible:
        logger.info(
            "scope denied site access user=%s role=%s site=%s",
            scope.username,
            scope.role.value,
            site_id,
        )
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=f"Site '{site_id}' was not found."
        )
    return site


class Pagination:
    """Validated, capped pagination.

    The cap is enforced here rather than trusted from the client, so a rogue ``page_size=100000``
    cannot turn a listing endpoint into a denial-of-service vector.
    """

    def __init__(self, page: int, page_size: int) -> None:
        self.page = page
        self.page_size = page_size

    @property
    def offset(self) -> int:
        return (self.page - 1) * self.page_size


def pagination(
    settings: SettingsDep,
    page: Annotated[int, Query(ge=1, le=10_000, description="1-based page number")] = 1,
    page_size: Annotated[int | None, Query(ge=1, le=1_000)] = None,
) -> Pagination:
    effective = page_size or settings.default_page_size
    return Pagination(page=page, page_size=min(effective, settings.max_page_size))


PaginationDep = Annotated[Pagination, Depends(pagination)]


def db_session_for_scripts() -> Generator[Session, None, None]:
    """Non-FastAPI session, for the seed script and management commands."""
    yield from get_db()
