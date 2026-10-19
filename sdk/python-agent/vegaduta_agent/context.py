"""The run context carried through one agent run.

Mirrors ``io.vegaduta.agent.api.RunContext``. One ``RunContext`` identifies a
single agent run (``run_id`` is required); tenant, principal, agent, session
and correlation ids are optional but should be set in real deployments — they
are what the audit record and approval queues scope on.
"""

from __future__ import annotations

import uuid
from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from types import MappingProxyType


@dataclass(frozen=True)
class RunContext:
    run_id: str
    tenant_id: str | None = None
    principal: str | None = None
    agent_id: str | None = None
    session_id: str | None = None
    correlation_id: str | None = None
    attributes: Mapping[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.run_id:
            raise ValueError("run_id is required")
        object.__setattr__(self, "attributes", MappingProxyType(dict(self.attributes)))

    @classmethod
    def anonymous(cls) -> RunContext:
        """A context with a fresh random run id and nothing else; for tests and
        single-user tools."""
        return cls(run_id=str(uuid.uuid4()))

    @classmethod
    def of_run(cls, run_id: str) -> RunContext:
        return cls(run_id=run_id)

    def with_attrs(self, **changes) -> RunContext:
        return replace(self, **changes)
