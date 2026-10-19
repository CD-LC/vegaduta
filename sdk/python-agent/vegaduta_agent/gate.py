"""The dispatch gate: the policy point that decides whether a tool call runs.

Mirrors ``io.vegaduta.agent.api.gate``. A gate sees the call, its
classification and the run context and answers ALLOW, DENY or
REQUIRE_APPROVAL. Install one to enforce org-wide rules — forbidden tools,
allow-lists, environment scoping — without editing each tool.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Protocol

from .context import RunContext
from .risk import ToolClassification
from .tool_call import ToolCall


class Outcome(Enum):
    ALLOW = "ALLOW"
    DENY = "DENY"
    REQUIRE_APPROVAL = "REQUIRE_APPROVAL"


@dataclass(frozen=True)
class Verdict:
    outcome: Outcome
    reason: str = ""

    @property
    def allowed(self) -> bool:
        return self.outcome is Outcome.ALLOW

    @classmethod
    def allow(cls) -> Verdict:
        return cls(Outcome.ALLOW)

    @classmethod
    def deny(cls, reason: str) -> Verdict:
        return cls(Outcome.DENY, reason)

    @classmethod
    def require_approval(cls, reason: str) -> Verdict:
        return cls(Outcome.REQUIRE_APPROVAL, reason)


class ToolDispatchGate(Protocol):
    """Decides the fate of one classified tool call. Implementations must be
    side-effect free and fast — they run on every call."""

    def evaluate(
        self, call: ToolCall, classification: ToolClassification, context: RunContext
    ) -> Verdict: ...


class _AllowAll:
    def evaluate(self, call: ToolCall, classification: ToolClassification, context: RunContext) -> Verdict:
        return Verdict.allow()


def allow_all() -> ToolDispatchGate:
    """The default gate: policy is expressed by ``@governed`` attributes and
    the approval threshold, not by a central rule set."""
    return _AllowAll()
