"""The effect journal: what makes a retried tool call safe.

Mirrors ``io.vegaduta.agent.api.journal.EffectJournal``. When the model (or a
retry, or a crashed run resumed) asks for a call already recorded, the
journaled result is replayed instead of running the effect twice — this is the
answer to "does the payment happen twice?".

Keys are supplied by the caller; ``effect_key`` builds the standard one:
``sha256(tool_name + "\\0" + canonical_json(arguments))`` under the run id, so
replay is scoped to one run.
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
from enum import Enum
from typing import Protocol


def effect_key(run_id: str, tool_name: str, arguments_json: str) -> str:
    """Stable journal key for one call inside one run. The arguments are
    re-serialised canonically (sorted keys) so ``{"a":1,"b":2}`` and
    ``{"b":2,"a":1}`` hit the same entry."""
    try:
        canonical = json.dumps(json.loads(arguments_json), sort_keys=True, separators=(",", ":"))
    except (ValueError, TypeError):
        canonical = arguments_json
    digest = hashlib.sha256(f"{tool_name}\0{canonical}".encode()).hexdigest()
    return f"{run_id}:{digest}"


class EffectJournal(Protocol):
    def lookup(self, key: str) -> str | None:
        """A previously recorded result for the key, if any."""
        ...

    def begin(self, key: str) -> None:
        """Marks a key as in-flight. Implementations may use this to detect a
        call that started but never recorded (a crash mid-effect)."""
        ...

    def record(self, key: str, result: str) -> None:
        """Records the result of a completed call."""
        ...


class _Disabled:
    """A journal that remembers nothing; disables replay."""

    def lookup(self, key: str) -> str | None:
        return None

    def begin(self, key: str) -> None:
        pass

    def record(self, key: str, result: str) -> None:
        pass


def disabled() -> EffectJournal:
    return _Disabled()


class InMemoryEffectJournal:
    """Process-local journal. The right default for a single-process agent and
    for tests; use ``SqliteEffectJournal`` (or your own) to survive restarts."""

    def __init__(self) -> None:
        self._done: dict[str, str] = {}
        self._in_flight: set[str] = set()
        self._lock = threading.Lock()

    def lookup(self, key: str) -> str | None:
        with self._lock:
            return self._done.get(key)

    def begin(self, key: str) -> None:
        with self._lock:
            self._in_flight.add(key)

    def record(self, key: str, result: str) -> None:
        with self._lock:
            self._in_flight.discard(key)
            self._done[key] = result

    def pending_keys(self) -> list[str]:
        """Begun but never recorded — in-flight calls. On a process-local
        journal this is "running right now"; on a durable one it is "may
        have crashed mid-effect"."""
        with self._lock:
            return sorted(self._in_flight)


class SqliteEffectJournal:
    """Durable journal on the standard-library sqlite3 driver — one table, one
    file, no dependency. Safe across threads; each call uses a short-lived
    transaction."""

    def __init__(self, path: str = ":memory:") -> None:
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._lock = threading.Lock()
        with self._lock, self._conn:
            self._conn.execute(
                "CREATE TABLE IF NOT EXISTS effect_journal ("
                "key TEXT PRIMARY KEY, result TEXT NOT NULL, "
                "status TEXT NOT NULL DEFAULT 'done', "
                "recorded_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')))"
            )

    def lookup(self, key: str) -> str | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT result FROM effect_journal WHERE key = ? AND status = 'done'", (key,)
            ).fetchone()
        return row[0] if row else None

    def begin(self, key: str) -> None:
        with self._lock, self._conn:
            self._conn.execute(
                "INSERT OR IGNORE INTO effect_journal (key, result, status) VALUES (?, '', 'in_flight')",
                (key,),
            )

    def record(self, key: str, result: str) -> None:
        with self._lock, self._conn:
            self._conn.execute(
                "INSERT INTO effect_journal (key, result, status) VALUES (?, ?, 'done') "
                "ON CONFLICT(key) DO UPDATE SET result = excluded.result, status = 'done'",
                (key, result),
            )

    def pending_keys(self) -> list[str]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT key FROM effect_journal WHERE status = 'in_flight' ORDER BY recorded_at"
            ).fetchall()
        return [r[0] for r in rows]

    def close(self) -> None:
        self._conn.close()


def interrupted_keys(journal) -> list[str]:
    """The keys a journal knows began but never recorded — calls that were
    running when the process died. ``SqliteEffectJournal`` and
    ``InMemoryEffectJournal`` answer it; a foreign journal can opt in by
    exposing the same attribute/method."""
    pending = getattr(journal, "pending_keys", None)
    return pending() if callable(pending) else []


class UnknownEffectPolicy(Enum):
    """What a caller should do about a call that may or may not have run."""

    ALERT = "ALERT"  # tell a human; the effect may have happened
    IGNORE = "IGNORE"  # acceptable for read-only effects
