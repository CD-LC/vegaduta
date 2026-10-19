"""Risk vocabulary for tool classification.

Mirrors ``io.vegaduta.agent.api.risk`` in the Java Agent SDK: the same enum
names, the same ordering and the same OWASP Agentic Top 10 identifiers, so a
team running Java and Python services reports risk in one vocabulary.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum, IntEnum


class RiskLabel(IntEnum):
    """How much damage a wrong call can do. Drives default approval and audit
    behaviour."""

    #: Read-only, public data, no side effects.
    LOW = 0
    #: Reads internal data or performs reversible writes.
    MEDIUM = 1
    #: Irreversible or externally visible effects: money, messages, deletions,
    #: deployments.
    HIGH = 2
    #: Effects that can harm people or the organisation at scale; always
    #: approval-gated.
    CRITICAL = 3

    def at_least(self, other: RiskLabel) -> bool:
        return self >= other


class AsiRisk(Enum):
    """OWASP Agentic Security Initiative Top 10 items a tool is exposed to."""

    ASI01 = "Agent Goal Hijack"
    ASI02 = "Tool Misuse and Exploitation"
    ASI03 = "Identity and Privilege Abuse"
    ASI04 = "Agentic Supply Chain Vulnerabilities"
    ASI05 = "Unexpected Code Execution"
    ASI06 = "Memory and Context Poisoning"
    ASI07 = "Insecure Inter-Agent Communication"
    ASI08 = "Cascading Failures"
    ASI09 = "Human-Agent Trust Exploitation"
    ASI10 = "Rogue Agents"

    @property
    def title(self) -> str:
        return self.value

    @property
    def id(self) -> str:
        """The identifier as printed in the OWASP list, for example ``ASI02``."""
        return self.name


@dataclass(frozen=True)
class ToolClassification:
    """The risk picture of one tool, declared once with ``@governed`` and read
    by the gate, the approval policy, the journal and the audit record."""

    risk: RiskLabel = RiskLabel.MEDIUM
    side_effecting: bool = True
    reads_private_data: bool = False
    untrusted_input: bool = False
    exfiltration_sink: bool = False
    idempotent: bool = False
    requires_approval: bool = False
    risks: frozenset[AsiRisk] = field(default_factory=frozenset)
