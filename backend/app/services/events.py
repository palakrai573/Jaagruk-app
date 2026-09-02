"""Live event fan-out for the admin dashboard.

In-process, and said plainly: this works for a single-instance pilot and is wrong behind more than
one worker process, where it needs Redis pub/sub or Postgres ``LISTEN/NOTIFY``. Documented in
``docs/API.md`` rather than left to be discovered when a second instance is added and half the
dashboards stop updating.

Events are RBAC-filtered at fan-out time, so a site officer's socket never receives another site's
traffic — filtering in the browser would mean the data had already left the server.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any

from fastapi import WebSocket

from app.core.security import Role

logger = logging.getLogger("jaagruk.events")

#: Dropped when a client cannot keep up. A dashboard that fell behind should reconnect and
#: refetch, not force the server to buffer without limit.
MAX_QUEUE_DEPTH = 256


@dataclass(slots=True, eq=False)
class Subscriber:
    """One connected dashboard socket.

    ``eq=False`` so the dataclass keeps identity-based equality and hashing. A generated
    ``__eq__`` sets ``__hash__`` to None, and these objects live in a set.
    """

    websocket: WebSocket
    role: Role
    company_id: str | None
    site_id: str | None
    queue: asyncio.Queue[dict[str, Any]] = field(
        default_factory=lambda: asyncio.Queue(maxsize=MAX_QUEUE_DEPTH)
    )

    def may_see(self, event_site_id: str | None, event_company_id: str | None) -> bool:
        if self.role.sees_all_companies:
            return True
        if self.site_id is not None:
            # Site-scoped users also see site-less announcements addressed to their company.
            return event_site_id == self.site_id or (
                event_site_id is None and event_company_id == self.company_id
            )
        if self.company_id is not None:
            return event_company_id == self.company_id or event_company_id is None
        return False


class EventBus:
    def __init__(self) -> None:
        self._subscribers: set[Subscriber] = set()
        self._lock = asyncio.Lock()

    async def subscribe(self, subscriber: Subscriber) -> None:
        async with self._lock:
            self._subscribers.add(subscriber)
        logger.info(
            "live subscriber joined role=%s site=%s (total=%d)",
            subscriber.role.value,
            subscriber.site_id,
            len(self._subscribers),
        )

    async def unsubscribe(self, subscriber: Subscriber) -> None:
        async with self._lock:
            self._subscribers.discard(subscriber)

    @property
    def subscriber_count(self) -> int:
        return len(self._subscribers)

    async def publish(
        self,
        event_type: str,
        *,
        site_id: str | None,
        company_id: str | None,
        at_epoch_sec: int,
        payload: dict[str, Any],
    ) -> int:
        """Queue an event for every entitled subscriber.

        :return: how many sockets it was queued for.
        """
        message = {
            "type": event_type,
            "site_id": site_id,
            "at_epoch_sec": at_epoch_sec,
            "payload": payload,
        }
        delivered = 0
        async with self._lock:
            targets = list(self._subscribers)

        for subscriber in targets:
            if not subscriber.may_see(site_id, company_id):
                continue
            try:
                subscriber.queue.put_nowait(message)
                delivered += 1
            except asyncio.QueueFull:
                logger.warning(
                    "dropping live event for a slow subscriber (site=%s); it should reconnect",
                    subscriber.site_id,
                )
        return delivered


bus = EventBus()


# ---------------------------------------------------------------------------
# Publishing from synchronous request handlers
# ---------------------------------------------------------------------------


def publish_threadsafe(
    event_type: str,
    *,
    site_id: str | None,
    company_id: str | None,
    at_epoch_sec: int,
    payload: dict[str, Any],
) -> None:
    """Publish from a synchronous endpoint running in FastAPI's thread pool.

    A live-update failure must never fail the request that produced the data. If no event loop is
    reachable, or the loop is shutting down, the event is dropped with a log line: the certificate
    is already committed, and the dashboard refetches on reconnect.
    """
    try:
        loop = _running_loop()
    except RuntimeError:
        logger.debug("no running event loop; skipping live event %s", event_type)
        return

    coroutine = bus.publish(
        event_type,
        site_id=site_id,
        company_id=company_id,
        at_epoch_sec=at_epoch_sec,
        payload=payload,
    )
    try:
        asyncio.run_coroutine_threadsafe(coroutine, loop)
    except RuntimeError as exc:
        logger.debug("could not schedule live event %s: %s", event_type, exc)


_main_loop: asyncio.AbstractEventLoop | None = None


def bind_event_loop(loop: asyncio.AbstractEventLoop) -> None:
    """Record the serving loop at startup so worker threads can reach it."""
    global _main_loop
    _main_loop = loop


def _running_loop() -> asyncio.AbstractEventLoop:
    if _main_loop is not None and not _main_loop.is_closed():
        return _main_loop
    return asyncio.get_running_loop()
