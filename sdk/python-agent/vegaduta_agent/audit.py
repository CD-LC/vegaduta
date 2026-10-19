"""Audit: one record per governed action, in a flat key=value format.

Mirrors ``io.vegaduta.agent.api.audit``. ``LoggingAuditSink`` writes one
``key=value`` line per event to the logger ``vegaduta.agent.audit`` — the same
shape the Java SDK writes to ``io.vegaduta.agent.audit`` — so a log pipeline
sees identical records whether the service is Java or Python.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Protocol

from .context import RunContext
from .risk import AsiRisk

AUDIT_LOGGER_NAME = "vegaduta.agent.audit"


class AuditOutcome(Enum):
    SUCCESS = "SUCCESS"
    FAILURE = "FAILURE"
    DENIED = "DENIED"
    PENDING_APPROVAL = "PENDING_APPROVAL"
    AUTO_APPROVED = "AUTO_APPROVED"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    EXPIRED = "EXPIRED"
    REPLAYED = "REPLAYED"
    BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    WARNING = "WARNING"


@dataclass(frozen=True)
class AuditEvent:
    action_type: str
    target_type: str
    target_id: str
    context: RunContext
    outcome: AuditOutcome = AuditOutcome.SUCCESS
    actor_id: str = "agent"
    detail: str | None = None
    risks: frozenset[AsiRisk] = frozenset()
    attributes: Mapping[str, str] = field(default_factory=dict)
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_flat_map(self) -> dict[str, str]:
        m: dict[str, str] = {
            "id": str(self.id),
            "createdAt": self.created_at.isoformat(),
            "actorId": self.actor_id,
            "actionType": self.action_type,
            "targetType": self.target_type,
            "targetId": self.target_id,
            "outcome": self.outcome.value,
            "runId": self.context.run_id,
        }
        if self.detail is not None:
            m["detail"] = self.detail
        for key, value in (
            ("tenantId", self.context.tenant_id),
            ("agentId", self.context.agent_id),
            ("sessionId", self.context.session_id),
            ("correlationId", self.context.correlation_id),
        ):
            if value is not None:
                m[key] = value
        if self.risks:
            m["risks"] = ",".join(sorted(r.id for r in self.risks))
        m.update(self.attributes)
        return m

    def to_line(self) -> str:
        """One ``key=value`` line; values are quoted when they contain spaces
        or quotes so the line stays machine-parseable."""
        parts = []
        for key, value in self.to_flat_map().items():
            text = str(value)
            if any(c in text for c in " =\"'\n\t"):
                text = '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'
            parts.append(f"{key}={text}")
        return " ".join(parts)


class AuditSink(Protocol):
    def record(self, event: AuditEvent) -> None: ...


class LoggingAuditSink:
    """Writes each event as one ``key=value`` line to the audit logger.
    Attach your own handler/formatter to ``vegaduta.agent.audit`` to route it
    (a file, a syslog drain, an OTel bridge)."""

    def __init__(self, logger: logging.Logger | None = None) -> None:
        self._log = logger or logging.getLogger(AUDIT_LOGGER_NAME)

    def record(self, event: AuditEvent) -> None:
        self._log.info(event.to_line())


class InMemoryAuditSink:
    """Collects events for tests and for callers who ship their own
    persistence."""

    def __init__(self) -> None:
        self.events: list[AuditEvent] = []

    def record(self, event: AuditEvent) -> None:
        self.events.append(event)

    def of_type(self, action_type: str) -> list[AuditEvent]:
        return [e for e in self.events if e.action_type == action_type]
