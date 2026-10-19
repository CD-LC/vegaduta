"""Fixed-window rate limiting for the data API (see dataapi.py)."""

from __future__ import annotations

import threading
import time


class RateLimiter:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._w: dict[str, tuple[int, int]] = {}

    def hit(self, bucket: str, limit: int, window: int = 60) -> int | None:
        """Count one request; None if allowed, else seconds until reset."""
        if limit <= 0:
            return None
        now = int(time.time())
        start = now - now % window
        with self._lock:
            ws, n = self._w.get(bucket, (start, 0))
            if ws != start:
                ws, n = start, 0
            n += 1
            self._w[bucket] = (ws, n)
            if len(self._w) > 50_000:  # bound memory under key/IP churn
                self._w = {k: v for k, v in self._w.items() if v[0] == start}
            return None if n <= limit else (ws + window - now)


LIMITER = RateLimiter()


def client_ip(headers: dict, fallback: str) -> str:
    for h in ("cf-connecting-ip", "x-forwarded-for"):
        v = headers.get(h, "")
        if v:
            return v.split(",")[0].strip()[:64]
    return fallback
