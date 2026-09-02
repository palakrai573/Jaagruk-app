"""Live-updates WebSocket for the admin dashboard."""

from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from starlette.concurrency import run_in_threadpool

from app.core.config import get_settings
from app.core.security import Role, TokenError, TokenType, decode_token, token_denylist
from app.db.base import utcnow
from app.db.session import session_scope
from app.models import User
from app.services import events

logger = logging.getLogger("jaagruk.ws")

router = APIRouter(tags=["live"])

#: Application-level close codes. 4401 mirrors HTTP 401 so the client knows to refresh and retry
#: rather than back off indefinitely.
CLOSE_UNAUTHORISED = 4401
CLOSE_FORBIDDEN = 4403

HEARTBEAT_SECONDS = 25.0


def _load_user(user_id: str) -> User | None:
    """Blocking database read, called through ``run_in_threadpool``.

    The rest of the backend is synchronous by design; this is the one place that has to bridge
    into async, and it does so explicitly rather than blocking the event loop.
    """
    with session_scope() as session:
        user = session.get(User, user_id)
        if user is None or not user.active:
            return None
        session.expunge(user)
        return user


@router.websocket("/ws/live")
async def live_events(
    websocket: WebSocket,
    token: str = Query(description="Access token; browsers cannot set WebSocket headers"),
) -> None:
    """Push certificate, hazard and sync events as they happen.

    The token arrives as a query parameter because the browser WebSocket API cannot set an
    ``Authorization`` header. That is a real trade-off: the token appears in server access logs.
    It is mitigated by a short access-token lifetime and by never accepting a refresh token here,
    and it is called out in ``docs/API.md`` rather than glossed over.

    Events are filtered server-side by the subscriber's scope, so a site officer's socket never
    carries another site's traffic — filtering in the browser would mean the data had already left
    the server.
    """
    settings = get_settings()

    try:
        claims = decode_token(settings, token, TokenType.ACCESS)
    except TokenError as exc:
        await websocket.close(code=CLOSE_UNAUTHORISED, reason=str(exc)[:120])
        return

    if token_denylist.is_revoked(claims.jti):
        await websocket.close(code=CLOSE_UNAUTHORISED, reason="session signed out")
        return

    user = await run_in_threadpool(_load_user, claims.subject)
    if user is None:
        await websocket.close(code=CLOSE_FORBIDDEN, reason="account inactive")
        return

    await websocket.accept()
    subscriber = events.Subscriber(
        websocket=websocket,
        role=Role(user.role),
        company_id=user.company_id,
        site_id=user.site_id,
    )
    await events.bus.subscribe(subscriber)

    try:
        await websocket.send_json(
            {
                "type": "connected",
                "site_id": user.site_id,
                "at_epoch_sec": int(utcnow().timestamp()),
                "payload": {
                    "role": user.role,
                    "scope": user.site_id or user.company_id or "all",
                    "heartbeat_seconds": HEARTBEAT_SECONDS,
                    "token_expires_at_epoch_sec": int(claims.expires_at.timestamp()),
                },
            }
        )

        # Two concurrent concerns: pushing queued events out, and noticing that the client has
        # gone. A single loop reading only from the queue would keep a dead socket open until the
        # next event, which on a quiet site could be hours.
        pump = asyncio.create_task(_pump(websocket, subscriber))
        reader = asyncio.create_task(_drain_client(websocket))

        done, pending = await asyncio.wait(
            {pump, reader}, return_when=asyncio.FIRST_COMPLETED
        )
        for task in pending:
            task.cancel()
        for task in done:
            exception = task.exception()
            if exception is not None and not isinstance(exception, WebSocketDisconnect):
                logger.info("live socket ended: %s", exception)

    except WebSocketDisconnect:
        pass
    except Exception as exc:  # noqa: BLE001 - a dashboard socket must never take the server down
        logger.warning("live socket error: %s", exc)
    finally:
        await events.bus.unsubscribe(subscriber)


async def _pump(websocket: WebSocket, subscriber: events.Subscriber) -> None:
    """Forward queued events, with a periodic heartbeat.

    The heartbeat keeps intermediate proxies from silently dropping an idle connection, which is
    otherwise indistinguishable from a quiet site.
    """
    while True:
        try:
            message = await asyncio.wait_for(
                subscriber.queue.get(), timeout=HEARTBEAT_SECONDS
            )
        except asyncio.TimeoutError:
            await websocket.send_json(
                {
                    "type": "heartbeat",
                    "site_id": subscriber.site_id,
                    "at_epoch_sec": int(utcnow().timestamp()),
                    "payload": {},
                }
            )
            continue
        await websocket.send_json(message)


async def _drain_client(websocket: WebSocket) -> None:
    """Read and discard client frames so a disconnect is detected promptly.

    The protocol is server-to-client only. Anything the client sends is ignored rather than parsed,
    which keeps the socket from being an input surface at all.
    """
    while True:
        await websocket.receive_text()
