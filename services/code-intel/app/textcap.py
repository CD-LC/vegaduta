"""Output capping shared by everything that returns process output."""

from __future__ import annotations

MAX_OUTPUT_CHARS = 20_000


def tail(s: str, limit: int = MAX_OUTPUT_CHARS) -> str:
    """The last `limit` characters of `s`, marked when anything was cut."""
    return s if len(s) <= limit else "...[truncated]...\n" + s[-limit:]
