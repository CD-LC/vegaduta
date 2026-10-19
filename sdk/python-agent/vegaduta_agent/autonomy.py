"""Tiered autonomy: how much an agent may do without a human in the loop.

Mirrors ``io.vegaduta.agent.api.autonomy`` and ``agent-core``'s
``AutonomyGate`` / ``AutonomyResolver`` / ``AutonomyRegistry``. Uniform
governance across every agent fails in practice: a research assistant and a
payments agent need different leashes, and an agent that misbehaves must be
reined in now, not at the next release. The level is a run-time property
(the registry, or the ``vegaduta.autonomy`` run-context attribute), not a
compile-time annotation, and every tool call is checked against it.

Level semantics (the same as the Java gate):

- non-side-effecting tools need L1; ``requires_approval`` still applies
- LOW/MEDIUM side-effecting tools need L3
- HIGH side-effecting tools need L3 plus an approval; at L2 every
  side-effecting call becomes an approval request
- CRITICAL always needs L4 and a human approval
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from enum import IntEnum

from .context import RunContext
from .gate import Outcome, ToolDispatchGate, Verdict
from .risk import RiskLabel, ToolClassification
from .tool_call import ToolCall

log = logging.getLogger(__name__)

AUTONOMY_ATTRIBUTE = "vegaduta.autonomy"


class AutonomyLevel(IntEnum):
    """Ordered from least to most autonomous; ``at_least`` compares."""

    #: Read-only tools; outputs go to the requesting user.
    L1_OBSERVE = 1
    #: May draft or suggest side effects; a human executes or approves.
    L2_RECOMMEND = 2
    #: Executes low/medium-risk side effects; high risk needs approval.
    L3_ACT_BOUNDED = 3
    #: Executes within guardrails; humans review after the fact.
    L4_ACT_AUTONOMOUS = 4

    def at_least(self, other: AutonomyLevel) -> bool:
        return self >= other

    @classmethod
    def parse(cls, name: str) -> AutonomyLevel:
        return cls[name.strip().upper()]


@dataclass(frozen=True)
class AutonomyRequirement:
    """The minimum autonomy level a classification needs, and whether a human
    approves each call regardless of level. Pure; mirrors
    ``AutonomyRequirement.forClassification``."""

    minimum_level: AutonomyLevel
    approval_required: bool

    @classmethod
    def for_classification(cls, classification: ToolClassification) -> AutonomyRequirement:
        approval = classification.requires_approval
        if not classification.side_effecting:
            return cls(AutonomyLevel.L1_OBSERVE, approval)
        if classification.risk.at_least(RiskLabel.HIGH):
            if approval:
                return cls(AutonomyLevel.L3_ACT_BOUNDED, True)
            # CRITICAL is always approval-gated (RiskLabel contract).
            return cls(
                AutonomyLevel.L4_ACT_AUTONOMOUS,
                classification.risk is RiskLabel.CRITICAL,
            )
        return cls(AutonomyLevel.L3_ACT_BOUNDED, approval)

    def satisfied_by(self, level: AutonomyLevel) -> bool:
        return level.at_least(self.minimum_level)


class AutonomyRegistry:
    """Per-agent levels plus a default. ``set`` may only be called by trusted
    code (your admin surface); a demotion takes effect on the next call."""

    def __init__(self, default_level: AutonomyLevel = AutonomyLevel.L4_ACT_AUTONOMOUS) -> None:
        self._default = default_level
        self._levels: dict[str, AutonomyLevel] = {}

    def set(self, agent_id: str, level: AutonomyLevel) -> None:
        self._levels[agent_id] = level

    def clear(self, agent_id: str) -> None:
        self._levels.pop(agent_id, None)

    def get(self, agent_id: str) -> AutonomyLevel | None:
        return self._levels.get(agent_id)

    @property
    def default_level(self) -> AutonomyLevel:
        return self._default


def resolve_level(registry: AutonomyRegistry, context: RunContext) -> AutonomyLevel:
    """The level for this run: a registered agent level wins, then the
    ``vegaduta.autonomy`` attribute, then the default. A malformed attribute
    resolves to the lowest level — never throws."""
    if context.agent_id:
        registered = registry.get(context.agent_id)
        if registered is not None:
            return registered
    raw = context.attributes.get(AUTONOMY_ATTRIBUTE)
    if raw is not None:
        try:
            return AutonomyLevel.parse(raw)
        except (KeyError, AttributeError):
            log.warning(
                "autonomy: run %s carries an invalid %s attribute; resolving to L1_OBSERVE",
                context.run_id,
                AUTONOMY_ATTRIBUTE,
            )
            return AutonomyLevel.L1_OBSERVE
    return registry.default_level


def evaluate(level: AutonomyLevel, classification: ToolClassification) -> Verdict:
    """The autonomy check alone — the same rules as the Java
    ``AutonomyGate.evaluate``. Useful standalone for UIs that pre-compute what
    a level would do."""
    requirement = AutonomyRequirement.for_classification(classification)
    if classification.side_effecting:
        if level is AutonomyLevel.L2_RECOMMEND:
            return Verdict.require_approval("autonomy:recommend_only")
        if level is AutonomyLevel.L3_ACT_BOUNDED and classification.risk is RiskLabel.HIGH:
            return Verdict.require_approval("autonomy:bounded_high_risk")
    if not requirement.satisfied_by(level):
        return Verdict.deny(f"autonomy:{level.name}<{requirement.minimum_level.name}")
    if requirement.approval_required:
        return Verdict.require_approval("autonomy:requires_approval")
    return Verdict.allow()


class AutonomyGate:
    """A ``ToolDispatchGate`` that enforces the run's autonomy level, then —
    when it did not deny — consults the delegate gate. A REQUIRE_APPROVAL
    verdict beats the delegate's ALLOW."""

    def __init__(
        self,
        registry: AutonomyRegistry | None = None,
        delegate: ToolDispatchGate | None = None,
    ) -> None:
        self.registry = registry or AutonomyRegistry()
        self.delegate = delegate

    def evaluate(
        self, call: ToolCall, classification: ToolClassification, context: RunContext
    ) -> Verdict:
        own = evaluate(resolve_level(self.registry, context), classification)
        if own.outcome is Outcome.DENY or self.delegate is None:
            return own
        other = self.delegate.evaluate(call, classification, context)
        if other.outcome is Outcome.DENY:
            return other
        if own.outcome is Outcome.REQUIRE_APPROVAL:
            return own
        return other
