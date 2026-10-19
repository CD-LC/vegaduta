"""The kill switch: stop an agent, a tool, or everything, now.

Mirrors ``agent-core``'s ``halt`` package. A halt is a record — scope, reason,
who engaged it, when — kept in a store and consulted by a ``HaltGate`` at the
front of the gate chain. Engaging and releasing are themselves audited
(``halt.engaged`` / ``halt.released``) so the trail shows who flipped the
switch. ``halt_drill`` proves the wiring works: engage, confirm a call is
denied, release — the sort of check an incident reviewer asks for.
"""

from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
from typing import Protocol

from .audit import AuditEvent, AuditOutcome, AuditSink
from .context import RunContext
from .gate import Verdict
from .risk import ToolClassification
from .tool_call import ToolCall


class HaltScopeKind(Enum):
    ALL = "ALL"  # every call, every agent
    AGENT = "AGENT"  # one agent_id
    TOOL = "TOOL"  # one tool name, any agent


@dataclass(frozen=True)
class HaltScope:
    kind: HaltScopeKind
    value: str | None = None

    @classmethod
    def all(cls) -> HaltScope:
        return cls(HaltScopeKind.ALL)

    @classmethod
    def agent(cls, agent_id: str) -> HaltScope:
        return cls(HaltScopeKind.AGENT, agent_id)

    @classmethod
    def tool(cls, tool_name: str) -> HaltScope:
        return cls(HaltScopeKind.TOOL, tool_name)

    @property
    def specificity(self) -> int:
        return {HaltScopeKind.ALL: 0, HaltScopeKind.AGENT: 1, HaltScopeKind.TOOL: 2}[self.kind]

    def matches(self, call: ToolCall, context: RunContext) -> bool:
        if self.kind is HaltScopeKind.ALL:
            return True
        if self.kind is HaltScopeKind.AGENT:
            return context.agent_id == self.value
        return call.tool_name == self.value


@dataclass(frozen=True)
class HaltRecord:
    id: str
    scope: HaltScope
    reason: str
    engaged_by: str
    engaged_at: datetime


class HaltStore(Protocol):
    def engage(self, record: HaltRecord) -> None: ...

    def release(self, halt_id: str) -> HaltRecord | None: ...

    def active(self) -> list[HaltRecord]: ...


class InMemoryHaltStore:
    def __init__(self) -> None:
        self._records: dict[str, HaltRecord] = {}
        self._lock = threading.Lock()

    def engage(self, record: HaltRecord) -> None:
        with self._lock:
            self._records[record.id] = record

    def release(self, halt_id: str) -> HaltRecord | None:
        with self._lock:
            return self._records.pop(halt_id, None)

    def active(self) -> list[HaltRecord]:
        with self._lock:
            return list(self._records.values())


class HaltSwitch:
    """Engage and release halts; every change is audited."""

    def __init__(self, audit: AuditSink, store: HaltStore | None = None) -> None:
        self._audit = audit
        self._store = store or InMemoryHaltStore()

    @property
    def store(self) -> HaltStore:
        return self._store

    def engage(self, scope: HaltScope, reason: str, engaged_by: str) -> HaltRecord:
        record = HaltRecord(
            id=str(uuid.uuid4()),
            scope=scope,
            reason=reason,
            engaged_by=engaged_by,
            engaged_at=datetime.now(timezone.utc),
        )
        self._store.engage(record)
        self._switch_audit("halt.engaged", record)
        return record

    def release(self, halt_id: str, released_by: str) -> HaltRecord | None:
        record = self._store.release(halt_id)
        if record is not None:
            self._switch_audit("halt.released", record, actor=released_by)
        return record

    def active(self) -> list[HaltRecord]:
        """Engaged halts, most specific scope first (the precedence the gate
        reports)."""
        return sorted(
            self._store.active(),
            key=lambda r: (-r.scope.specificity, r.engaged_at, r.id),
        )

    def _switch_audit(self, action: str, record: HaltRecord, actor: str | None = None) -> None:
        self._audit.record(
            AuditEvent(
                action_type=action,
                target_type="halt",
                target_id=record.id,
                context=RunContext.anonymous(),
                outcome=AuditOutcome.WARNING,
                actor_id=actor or record.engaged_by,
                detail=record.reason,
                attributes={
                    "scope": record.scope.kind.value,
                    "scopeValue": record.scope.value or "",
                },
            )
        )


class HaltGate:
    """A ``ToolDispatchGate`` that denies when an engaged halt covers the call.
    Put it at the front of a gate chain so a halt beats every other policy."""

    def __init__(self, switch: HaltSwitch, delegate=None) -> None:
        self._switch = switch
        self._delegate = delegate

    def evaluate(
        self, call: ToolCall, classification: ToolClassification, context: RunContext
    ) -> Verdict:
        for record in self._switch.active():
            if record.scope.matches(call, context):
                return Verdict.deny(f"halted:{record.scope.kind.value}:{record.reason}")
        if self._delegate is not None:
            return self._delegate.evaluate(call, classification, context)
        return Verdict.allow()


@dataclass(frozen=True)
class DrillReport:
    """The result of a halt drill: engage, probe, release."""

    tool_name: str
    denied_while_engaged: bool
    allowed_after_release: bool
    detail: str


def halt_drill(switch: HaltSwitch, tool_name: str, engaged_by: str = "halt-drill") -> DrillReport:
    """Proves the kill switch works end to end without running a real tool:
    engages a tool-scoped halt, evaluates a call through ``HaltGate``, then
    releases and re-checks."""
    record = switch.engage(HaltScope.tool(tool_name), "halt drill", engaged_by)
    gate = HaltGate(switch)
    try:
        call = ToolCall.of(tool_name)
        denied = not gate.evaluate(call, ToolClassification(), RunContext.anonymous()).allowed
        switch.release(record.id, engaged_by)
        allowed = gate.evaluate(call, ToolClassification(), RunContext.anonymous()).allowed
        detail = (
            "ok" if denied and allowed
            else "halt did not deny" if not denied
            else "release did not clear the halt"
        )
        return DrillReport(tool_name, denied, allowed, detail)
    finally:
        switch.release(record.id, engaged_by)
