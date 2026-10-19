"""Governed long-term memory: what the agent is allowed to remember.

Mirrors ``agent-core``'s ``memory`` package. An agent that persists memories
across runs owns an exfiltration path and a poisoning surface — a web page it
read can become an instruction it later executes. ``GovernedMemoryStore``
wraps any ``MemoryStore`` and runs every write through the pipeline's
guardrails and a provenance label (``USER`` said it, ``TOOL_OUTPUT`` read it,
``MODEL_INFERENCE`` concluded it); tool-output memories are tainted and
content like instructions gets a probe before it is stored. A write cap keeps
a runaway loop from filling the store.
"""

from __future__ import annotations

import re
import threading
import uuid
from collections.abc import Iterable
from dataclasses import dataclass
from enum import Enum
from typing import Protocol

from .errors import GovernanceException


class Provenance(Enum):
    """Where a memory came from — carried on the entry so readers can weigh
    untrusted text accordingly."""

    USER = "USER"
    TOOL_OUTPUT = "TOOL_OUTPUT"
    MODEL_INFERENCE = "MODEL_INFERENCE"


@dataclass(frozen=True)
class MemoryEntry:
    id: str
    content: str
    provenance: Provenance
    created_at: str  # ISO-8601
    tags: tuple[str, ...] = ()


class MemoryStore(Protocol):
    def put(self, entry: MemoryEntry) -> None: ...

    def get(self, entry_id: str) -> MemoryEntry | None: ...

    def search(self, query: str, limit: int = 10) -> list[MemoryEntry]: ...

    def delete(self, entry_id: str) -> bool: ...


class InMemoryMemoryStore:
    def __init__(self) -> None:
        self._entries: dict[str, MemoryEntry] = {}
        self._lock = threading.Lock()

    def put(self, entry: MemoryEntry) -> None:
        with self._lock:
            self._entries[entry.id] = entry

    def get(self, entry_id: str) -> MemoryEntry | None:
        return self._entries.get(entry_id)

    def search(self, query: str, limit: int = 10) -> list[MemoryEntry]:
        words = [w.lower() for w in query.split() if w]
        with self._lock:
            hits = [
                e
                for e in self._entries.values()
                if all(w in e.content.lower() for w in words)
            ]
        return hits[:limit]

    def delete(self, entry_id: str) -> bool:
        with self._lock:
            return self._entries.pop(entry_id, None) is not None


class MemoryWriteBlockedException(GovernanceException):
    """A write was refused by a guardrail or the poison probe."""


class MemoryWriteCapExceededException(GovernanceException):
    """The store's write cap was hit for this run."""


# A small built-in probe: the patterns a tool output most often uses to try to
# become a stored instruction. Not a classifier — a tripwire.
_POISON_PATTERNS = (
    re.compile(r"\b(ignore|disregard|forget)\s+(all\s+)?(previous|prior|earlier)\s+", re.I),
    re.compile(r"\b(new|updated?)\s+(system\s+)?instructions?\b", re.I),
    re.compile(r"\bfrom now on\b.*\b(always|never)\b", re.I),
    re.compile(r"\b(you are|act as)\b.*\b(assistant|agent)\b", re.I),
)


@dataclass(frozen=True)
class ProbeReport:
    suspicious: bool
    matched_patterns: tuple[str, ...]


def poison_probe(content: str, extra_patterns: Iterable[re.Pattern] = ()) -> ProbeReport:
    """Screens memory content for instruction-shaped text. Returns a report;
    the caller decides whether to refuse. False positives are expected — this
    is a tripwire, not a verdict."""
    matched = tuple(
        p.pattern for p in (*_POISON_PATTERNS, *extra_patterns) if p.search(content)
    )
    return ProbeReport(bool(matched), matched)


class GovernedMemoryStore:
    """Wraps a ``MemoryStore`` so writes pass the same guardrails as tool
    output, carry provenance, and are probed when they come from untrusted
    text. ``writes_left`` bounds how many entries one run may create."""

    def __init__(
        self,
        store: MemoryStore | None = None,
        *,
        guardrails: Iterable = (),
        write_cap: int = 1000,
        refuse_suspicious_tool_output: bool = True,
    ) -> None:
        from .guardrail import GuardrailStage, run_guardrails

        self._store = store or InMemoryMemoryStore()
        self._guardrails = list(guardrails)
        self._write_cap = write_cap
        self._refuse_probe = refuse_suspicious_tool_output
        self._writes = 0
        self._lock = threading.Lock()
        self._stage = GuardrailStage.TOOL_OUTPUT
        self._run_guardrails = run_guardrails

    def put(
        self,
        content: str,
        provenance: Provenance = Provenance.USER,
        tags: Iterable[str] = (),
    ) -> MemoryEntry:
        from datetime import datetime, timezone

        with self._lock:
            if self._writes >= self._write_cap:
                raise MemoryWriteCapExceededException(
                    f"memory write cap {self._write_cap} reached"
                )
            self._writes += 1
        checked = self._run_guardrails(content, self._stage, self._guardrails)
        content = checked.content
        if provenance is Provenance.TOOL_OUTPUT and self._refuse_probe:
            report = poison_probe(content)
            if report.suspicious:
                raise MemoryWriteBlockedException(
                    "memory refused: tool output looks like an instruction "
                    f"({len(report.matched_patterns)} pattern(s))"
                )
        entry = MemoryEntry(
            id=str(uuid.uuid4()),
            content=content,
            provenance=provenance,
            created_at=datetime.now(timezone.utc).isoformat(),
            tags=tuple(tags),
        )
        self._store.put(entry)
        return entry

    def get(self, entry_id: str) -> MemoryEntry | None:
        return self._store.get(entry_id)

    def search(self, query: str, limit: int = 10) -> list[MemoryEntry]:
        return self._store.search(query, limit)

    def delete(self, entry_id: str) -> bool:
        return self._store.delete(entry_id)
