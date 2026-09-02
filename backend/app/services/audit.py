"""Append-only audit trail.

Written for every verification attempt, batch ingest, hazard transition and administrative change.
Two reasons: a statutory audit under the Mines Act needs it, and the first time a chain break
appears someone will ask which device uploaded it and when.

Writing an audit row must never fail the operation being audited, so failures here are logged and
swallowed.
"""

from __future__ import annotations

import logging

from sqlalchemy.orm import Session

from app.models import AuditLog

logger = logging.getLogger("jaagruk.audit")

# Actions, listed so a grep finds every one of them.
ACTION_LOGIN = "auth.login"
ACTION_LOGIN_FAILED = "auth.login_failed"
ACTION_LOGOUT = "auth.logout"
ACTION_TOKEN_REFRESH = "auth.refresh"
ACTION_DEVICE_REGISTER = "device.register"
ACTION_SITE_CREATE = "site.create"
ACTION_WORKER_CREATE = "worker.create"
ACTION_SYNC_BATCH = "sync.batch"
ACTION_SYNC_REPLAY = "sync.batch_replay"
ACTION_CERT_INGEST = "certificate.ingest"
ACTION_CERT_QUARANTINE = "certificate.quarantine"
ACTION_CERT_VERIFY = "certificate.verify"
ACTION_CHAIN_AUDIT = "chain.audit"
ACTION_HAZARD_CREATE = "hazard.create"
ACTION_HAZARD_TRANSITION = "hazard.transition"
ACTION_MEDIA_UPLOAD = "media.upload"
ACTION_REPORT_EXPORT = "report.export"

OUTCOME_SUCCESS = "success"
OUTCOME_FAILURE = "failure"
OUTCOME_DENIED = "denied"
OUTCOME_FLAGGED = "flagged"


def record(
    session: Session,
    *,
    actor: str,
    action: str,
    outcome: str = OUTCOME_SUCCESS,
    actor_role: str | None = None,
    target_type: str | None = None,
    target_id: str | None = None,
    detail: str | None = None,
    ip_address: str | None = None,
    site_id: str | None = None,
) -> None:
    try:
        session.add(
            AuditLog(
                actor=actor[:120],
                actor_role=actor_role,
                action=action[:64],
                target_type=target_type,
                target_id=target_id[:120] if target_id else None,
                outcome=outcome,
                detail=detail[:4000] if detail else None,
                ip_address=ip_address,
                site_id=site_id,
            )
        )
    except Exception as exc:  # noqa: BLE001 - auditing must not break the audited operation
        logger.error("failed to write audit row action=%s: %s", action, exc)
