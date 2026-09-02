"""Login, refresh, logout, and the caller's own profile."""

from __future__ import annotations

import logging
from datetime import timedelta

from fastapi import APIRouter, HTTPException, Response, status
from sqlalchemy import select

from app.api.deps import ClaimsDep, ClientIpDep, DbDep, SettingsDep, UserDep
from app.core.security import (
    Role,
    TokenError,
    TokenType,
    create_token,
    decode_token,
    token_denylist,
    verify_password,
)
from app.db.base import as_utc, utcnow
from app.models import User
from app.schemas import LoginRequest, MeResponse, RefreshRequest, TokenResponse
from app.services import audit

logger = logging.getLogger("jaagruk.auth")

router = APIRouter(prefix="/auth", tags=["auth"])

#: Failed attempts before a temporary lockout.
MAX_FAILED_LOGINS = 5
#: Escalating lockouts, mirroring the on-device PIN policy.
LOCKOUT_SCHEDULE_SECONDS = (30, 60, 300, 900)


def _lockout_for(failed_count: int) -> int:
    over = max(0, failed_count - MAX_FAILED_LOGINS)
    index = min(over, len(LOCKOUT_SCHEDULE_SECONDS) - 1)
    return LOCKOUT_SCHEDULE_SECONDS[index]


@router.post("/login", response_model=TokenResponse, summary="Exchange credentials for tokens")
def login(
    payload: LoginRequest,
    db: DbDep,
    settings: SettingsDep,
    ip: ClientIpDep,
) -> TokenResponse:
    user = db.scalar(select(User).where(User.username == payload.username))

    # The same message and the same work for an unknown username as for a wrong password, so the
    # endpoint cannot be used to enumerate accounts. The password check runs even when the user is
    # missing, against a throwaway hash, to keep the timing comparable.
    generic_failure = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Incorrect username or password.",
    )

    if user is None:
        verify_password(payload.password, "scrypt$65536$8$1$00$00")
        audit.record(
            db,
            actor=payload.username,
            action=audit.ACTION_LOGIN_FAILED,
            outcome=audit.OUTCOME_FAILURE,
            detail="unknown username",
            ip_address=ip,
        )
        # Committed explicitly. The dependency rolls the session back when a handler raises, so an
        # audit row or a lockout counter written on the failure path would otherwise be discarded --
        # which would silently disable brute-force protection entirely.
        db.commit()
        raise generic_failure

    now = utcnow()
    locked_until = as_utc(user.locked_until)
    if locked_until is not None and locked_until > now:
        remaining = int((locked_until - now).total_seconds())
        audit.record(
            db,
            actor=user.username,
            action=audit.ACTION_LOGIN_FAILED,
            outcome=audit.OUTCOME_DENIED,
            detail=f"locked for a further {remaining}s",
            ip_address=ip,
            site_id=user.site_id,
        )
        db.commit()
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=f"Too many failed attempts. Try again in {remaining} seconds.",
            headers={"Retry-After": str(remaining)},
        )

    if not user.active:
        audit.record(
            db,
            actor=user.username,
            action=audit.ACTION_LOGIN_FAILED,
            outcome=audit.OUTCOME_DENIED,
            detail="account disabled",
            ip_address=ip,
            site_id=user.site_id,
        )
        db.commit()
        raise generic_failure

    if not verify_password(payload.password, user.password_hash):
        user.failed_login_count += 1
        if user.failed_login_count >= MAX_FAILED_LOGINS:
            lockout = _lockout_for(user.failed_login_count)
            user.locked_until = now + timedelta(seconds=lockout)
            logger.warning(
                "locking account %s for %ss after %d failed attempts",
                user.username,
                lockout,
                user.failed_login_count,
            )
        audit.record(
            db,
            actor=user.username,
            action=audit.ACTION_LOGIN_FAILED,
            outcome=audit.OUTCOME_FAILURE,
            detail=f"attempt {user.failed_login_count}",
            ip_address=ip,
            site_id=user.site_id,
        )
        db.commit()
        raise generic_failure

    user.failed_login_count = 0
    user.locked_until = None
    user.last_login_at = now

    role = Role(user.role)
    access, access_claims = create_token(
        settings,
        subject=user.id,
        role=role,
        token_type=TokenType.ACCESS,
        company_id=user.company_id,
        site_id=user.site_id,
    )
    refresh, _ = create_token(
        settings,
        subject=user.id,
        role=role,
        token_type=TokenType.REFRESH,
        company_id=user.company_id,
        site_id=user.site_id,
    )

    audit.record(
        db,
        actor=user.username,
        actor_role=user.role,
        action=audit.ACTION_LOGIN,
        target_type="user",
        target_id=user.id,
        ip_address=ip,
        site_id=user.site_id,
    )

    return TokenResponse(
        access_token=access,
        refresh_token=refresh,
        expires_in_seconds=settings.access_token_minutes * 60,
        role=user.role,
        company_id=user.company_id,
        site_id=user.site_id,
        full_name=user.full_name,
    )


@router.post("/refresh", response_model=TokenResponse, summary="Rotate an access token")
def refresh_tokens(
    payload: RefreshRequest,
    db: DbDep,
    settings: SettingsDep,
    ip: ClientIpDep,
) -> TokenResponse:
    try:
        claims = decode_token(settings, payload.refresh_token, TokenType.REFRESH)
    except TokenError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)
        ) from exc

    if token_denylist.is_revoked(claims.jti):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="This refresh token has been revoked. Log in again.",
        )

    user = db.get(User, claims.subject)
    if user is None or not user.active:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="This account is no longer active."
        )

    # Rotate: the presented refresh token is burned as it is used, so a stolen copy is good for at
    # most one exchange and reuse is detectable.
    token_denylist.revoke(claims.jti, claims.expires_at)

    role = Role(user.role)
    access, _ = create_token(
        settings,
        subject=user.id,
        role=role,
        token_type=TokenType.ACCESS,
        company_id=user.company_id,
        site_id=user.site_id,
    )
    new_refresh, _ = create_token(
        settings,
        subject=user.id,
        role=role,
        token_type=TokenType.REFRESH,
        company_id=user.company_id,
        site_id=user.site_id,
    )

    audit.record(
        db,
        actor=user.username,
        actor_role=user.role,
        action=audit.ACTION_TOKEN_REFRESH,
        target_type="user",
        target_id=user.id,
        ip_address=ip,
        site_id=user.site_id,
    )

    return TokenResponse(
        access_token=access,
        refresh_token=new_refresh,
        expires_in_seconds=settings.access_token_minutes * 60,
        role=user.role,
        company_id=user.company_id,
        site_id=user.site_id,
        full_name=user.full_name,
    )


@router.post(
    "/logout",
    status_code=status.HTTP_204_NO_CONTENT,
    response_class=Response,
    summary="Revoke the current session",
)
def logout(claims: ClaimsDep, user: UserDep, db: DbDep, ip: ClientIpDep) -> Response:
    token_denylist.revoke(claims.jti, claims.expires_at)
    audit.record(
        db,
        actor=user.username,
        actor_role=user.role,
        action=audit.ACTION_LOGOUT,
        target_type="user",
        target_id=user.id,
        ip_address=ip,
        site_id=user.site_id,
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/me", response_model=MeResponse, summary="The caller's own profile")
def me(user: UserDep) -> MeResponse:
    role = Role(user.role)
    permissions = [
        name
        for name, granted in (
            ("read_all_companies", role.sees_all_companies),
            ("manage_sites", role.can_manage_sites),
            ("manage_workers", role.can_manage_workers),
            ("triage_hazards", role.can_triage_hazards),
            ("export_reports", role.can_export_reports),
            ("upload_sync", role.can_upload_sync),
        )
        if granted
    ]
    return MeResponse(
        user_id=user.id,
        username=user.username,
        full_name=user.full_name,
        role=user.role,
        company_id=user.company_id,
        site_id=user.site_id,
        permissions=permissions,
    )
