"""Scan before you integrate: a risk map of the tools a module exposes.

Mirrors the Java ``agent-scanner`` jar. ``risk_map(source, ...)`` walks a
module, class or object, picks up every ``@governed``-declared function plus
every public callable that is not declared (those run under the pipeline's
default MEDIUM/side-effecting classification — the scanner counts them as
*unclassified*), then scores the set the way the Java scanner does:

- ``UNCLASSIFIED_TOOLS``: 10 points each, capped at 40
- ``APPROVAL_GAP``: up to 20 for side-effecting tools below the approval
  threshold
- ``LETHAL_TRIFECTA``: 25 when private-readers, untrusted-input consumers and
  an exfiltration sink are all offered
- ``UNGATED_SINK``: 10 for an exfiltration sink with no approval while the
  trifecta holds
- ``NO_ASI_TAGS``: 2 each (cap 10) for classified tools with no OWASP ASI tags

Score = 100 minus deductions, floored at 0. The answer is a report a reviewer
can read before a single line of integration code exists.
"""

from __future__ import annotations

import inspect
from collections.abc import Iterable
from dataclasses import dataclass

from .governed import classification_of
from .risk import AsiRisk, RiskLabel, ToolClassification

UNCLASSIFIED_POINTS = 10
UNCLASSIFIED_CAP = 40
APPROVAL_GAP_CAP = 20
TRIFECTA_POINTS = 25
UNGATED_SINK_POINTS = 10
NO_ASI_POINTS = 2
NO_ASI_CAP = 10


@dataclass(frozen=True)
class ScannedTool:
    name: str
    classification: ToolClassification
    classified: bool
    approval_gated: bool


@dataclass(frozen=True)
class Deduction:
    code: str
    points: int
    reason: str


@dataclass(frozen=True)
class RiskMap:
    tools: tuple[ScannedTool, ...]
    unclassified: tuple[str, ...]
    side_effecting: int
    gated: int
    coverage: int  # percent of side-effecting tools that are approval-gated
    trifecta_holds: bool
    private_readers: tuple[str, ...]
    untrusted_consumers: tuple[str, ...]
    sinks: tuple[str, ...]
    asi_exposure: tuple[tuple[str, str, tuple[str, ...]], ...]  # (id, title, tool names)
    score: int
    deductions: tuple[Deduction, ...]

    def to_markdown(self) -> str:
        lines = [
            "# Tool risk map",
            "",
            f"**Readiness score: {self.score}/100**",
            "",
            f"- Tools: {len(self.tools)} ({len(self.unclassified)} unclassified)",
            f"- Side-effecting: {self.side_effecting}, approval-gated: {self.gated} "
            f"({self.coverage}% coverage)",
            f"- Lethal trifecta: {'PRESENT' if self.trifecta_holds else 'absent'}",
            "",
        ]
        if self.deductions:
            lines += ["## Deductions", ""]
            lines += ["| Code | Points | Why |", "|---|---|---|"]
            lines += [f"| {d.code} | {d.points} | {d.reason} |" for d in self.deductions]
            lines.append("")
        lines += [
            "## Tools",
            "",
            "| Tool | Risk | Side-effecting | Approval | ASI |",
            "|---|---|---|---|---|",
        ]
        for t in self.tools:
            c = t.classification
            lines.append(
                f"| {t.name}{'' if t.classified else ' (unclassified)'} | {c.risk.name} | "
                f"{'yes' if c.side_effecting else 'no'} | {'yes' if t.approval_gated else 'no'} | "
                f"{','.join(sorted(r.id for r in c.risks)) or '-'} |"
            )
        if self.trifecta_holds:
            lines += [
                "",
                "## Lethal trifecta",
                "",
                f"- Private readers: {', '.join(self.private_readers)}",
                f"- Untrusted consumers: {', '.join(self.untrusted_consumers)}",
                f"- Exfiltration sinks: {', '.join(self.sinks)}",
            ]
        if self.asi_exposure:
            lines += ["", "## OWASP ASI exposure", "", "| Item | Title | Tools |", "|---|---|---|"]
            lines += [
                f"| {i} | {title} | {', '.join(names)} |"
                for i, title, names in self.asi_exposure
            ]
        return "\n".join(lines) + "\n"


def risk_map(
    source,
    *,
    approval_threshold: RiskLabel | None = None,
) -> RiskMap:
    """Scans a module, class or iterable of functions for tool declarations.
    ``approval_threshold`` mirrors ``GovernancePolicy.approval_threshold``:
    tools at or above it (plus CRITICAL, plus ``requires_approval``) count as
    gated."""
    if not isinstance(source, Iterable) or isinstance(source, (str, bytes)):
        members = [
            (n, m)
            for n, m in inspect.getmembers(source)
            if callable(m) and not n.startswith("_")
        ]
    else:
        members = [
            (getattr(m, "__name__", type(m).__name__), m)
            for m in source
            if callable(m) and not getattr(m, "__name__", "").startswith("_")
        ]

    tools: list[ScannedTool] = []
    unclassified: list[str] = []
    exposure: dict[AsiRisk, list[str]] = {r: [] for r in AsiRisk}
    for name, fn in members:
        declared = classification_of(getattr(fn, "__wrapped__", fn))
        c = declared or ToolClassification()
        if declared is None:
            unclassified.append(name)
        gated = (
            c.requires_approval
            or c.risk is RiskLabel.CRITICAL
            or (approval_threshold is not None and c.risk.at_least(approval_threshold))
        )
        tools.append(ScannedTool(name, c, declared is not None, gated))
        for risk in c.risks:
            exposure[risk].append(name)

    readers = tuple(n for n, c in ((t.name, t.classification) for t in tools) if c.reads_private_data)
    untrusted = tuple(t.name for t in tools if t.classification.untrusted_input)
    sinks = tuple(t.name for t in tools if t.classification.exfiltration_sink)
    trifecta = bool(readers and untrusted and sinks)

    side_effecting = sum(1 for t in tools if t.classification.side_effecting)
    gated = sum(1 for t in tools if t.classification.side_effecting and t.approval_gated)
    coverage = 100 if side_effecting == 0 else int(100.0 * gated / side_effecting)

    deductions: list[Deduction] = []
    if unclassified:
        deductions.append(
            Deduction(
                "UNCLASSIFIED_TOOLS",
                min(UNCLASSIFIED_CAP, UNCLASSIFIED_POINTS * len(unclassified)),
                f"{len(unclassified)} tool(s) carry no @governed and run with the policy default "
                f"(MEDIUM, side-effecting): {', '.join(unclassified)}",
            )
        )
    if coverage < 100 and side_effecting:
        points = round(APPROVAL_GAP_CAP * (100 - coverage) / 100.0)
        if points > 0:
            deductions.append(
                Deduction(
                    "APPROVAL_GAP",
                    points,
                    f"{side_effecting - gated} of {side_effecting} side-effecting tool(s) run "
                    f"without human approval",
                )
            )
    if trifecta:
        deductions.append(
            Deduction(
                "LETHAL_TRIFECTA",
                TRIFECTA_POINTS,
                "Private readers, untrusted consumers and exfiltration sinks are all offered",
            )
        )
        ungated = [t.name for t in tools if t.classification.exfiltration_sink and not t.approval_gated]
        if ungated:
            deductions.append(
                Deduction(
                    "UNGATED_SINK",
                    UNGATED_SINK_POINTS,
                    "Exfiltration sink(s) without approval while the trifecta holds: "
                    + ", ".join(ungated),
                )
            )
    no_tags = sum(1 for t in tools if t.classified and not t.classification.risks)
    if no_tags:
        deductions.append(
            Deduction(
                "NO_ASI_TAGS",
                min(NO_ASI_CAP, NO_ASI_POINTS * no_tags),
                f"{no_tags} classified tool(s) declare no OWASP ASI risks; audit events carry "
                "no coverage tags",
            )
        )
    deductions.sort(key=lambda d: -d.points)

    asi = tuple(
        (r.id, r.title, tuple(exposure[r])) for r in AsiRisk if exposure[r]
    )
    score = max(0, 100 - sum(d.points for d in deductions))
    return RiskMap(
        tools=tuple(tools),
        unclassified=tuple(unclassified),
        side_effecting=side_effecting,
        gated=gated,
        coverage=coverage,
        trifecta_holds=trifecta,
        private_readers=readers,
        untrusted_consumers=untrusted,
        sinks=sinks,
        asi_exposure=asi,
        score=score,
        deductions=tuple(deductions),
    )
