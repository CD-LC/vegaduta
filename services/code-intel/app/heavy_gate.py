"""Admission control for heavy code-intel jobs (clone / index / graph build)."""

from __future__ import annotations

import threading
import time

from .service_errors import HeavyJobBusyError


class HeavyGate:
    """Admission control for heavy ops (clone / index / graph build).

    A global cap plus a per-tenant cap, both per process. Counters sit under
    one Condition so background callers can wait for a slot. Waiters are
    served FIFO: a later arrival (waiting or fail-fast) never takes a slot
    that an earlier admissible waiter could use. Per-tenant entries are
    removed when they reach zero, so memory is bounded by in-flight jobs.
    Waiters are in-process only: a restart drops them (callers poll /status
    or retry)."""

    def __init__(self, max_jobs: int, max_per_tenant: int,
                 max_queued: int, retry_after: int):
        self._max = max_jobs
        self._max_tenant = max_per_tenant
        self._max_queued = max_queued
        self._retry_after = retry_after
        self._cond = threading.Condition()
        self._running = 0
        self._queue: list[tuple[object, str]] = []   # FIFO (token, tenant)
        self._by_tenant: dict[str, int] = {}

    @property
    def _queued(self) -> int:
        return len(self._queue)

    def _free(self, tenant_id: str) -> bool:
        return (self._running < self._max
                and self._by_tenant.get(tenant_id, 0) < self._max_tenant)

    def _blocked_by_earlier(self, token) -> bool:
        """True if an earlier queued waiter could run right now (it must go
        first). `token` None = a new arrival, everyone queued is earlier."""
        for tok, ten in self._queue:
            if tok is token:
                return False
            if self._free(ten):
                return True
        return False

    def acquire(self, tenant_id: str, wait: float = 0) -> None:
        deadline = time.monotonic() + wait
        with self._cond:
            if not (self._free(tenant_id)
                    and not self._blocked_by_earlier(None)):
                if wait <= 0 or self._queued >= self._max_queued:
                    raise HeavyJobBusyError(
                        "too many concurrent indexing jobs; retry later",
                        self._retry_after)
                token = object()
                self._queue.append((token, tenant_id))
                try:
                    while not (self._free(tenant_id)
                               and not self._blocked_by_earlier(token)):
                        left = deadline - time.monotonic()
                        if left <= 0:
                            raise HeavyJobBusyError(
                                "timed out waiting for an indexing slot",
                                self._retry_after)
                        self._cond.wait(left)
                finally:
                    self._queue.remove((token, tenant_id))
                    self._cond.notify_all()
            self._running += 1
            self._by_tenant[tenant_id] = self._by_tenant.get(tenant_id, 0) + 1

    def release(self, tenant_id: str) -> None:
        with self._cond:
            self._running -= 1
            n = self._by_tenant.get(tenant_id, 0) - 1
            if n > 0:
                self._by_tenant[tenant_id] = n
            else:
                self._by_tenant.pop(tenant_id, None)
            self._cond.notify_all()
