"""Governance report: one run's audit events rendered for a reviewer.

The pitch this implements: an agent run leaves an audit trail anyway; turning
that trail into "which tools ran, at what risk, who approved what, which OWASP
ASI items were exercised" is the compliance artifact a security review asks
for. ``governance_report`` folds a run's ``AuditEvent``s into a structured
summary; ``to_markdown`` renders it for a ticket, a wiki page or a finding.

Scope is deliberately narrow: events that have ``action_type="tool.call"`` are
the tool inventory; approval requests/decisions come from the ``ApprovalGate``
records the caller passes in (or the PENDING_APPROVAL audit events alone, when
no store is available).
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import datetime

from .approval import ApprovalRequest
from .audit import AuditEvent, AuditOutcome
from .risk import AsiRisk


@dataclass(frozen=True)
class ToolCallSummary:
    tool: str
    calls: int
    risk: str
    risks: tuple[str, ...]
    outcomes: tuple[tuple[str, int], ...]


@dataclass(frozen=True)
class GovernanceReport:
    run_id: str
    tenant_id: str | None
    agent_id: str | None
    generated_at: datetime
    tool_calls: tuple[ToolCallSummary, ...]
    approvals: tuple[ApprovalRequest, ...]
    outcomes: tuple[tuple[str, int], ...]
    asi_coverage: tuple[tuple[str, str, int], ...]  # (ASI id, title, tagged call count)
    guardrail_violations: int
    denied: int
    pending_approval: int

    @property
    def total_calls(self) -> int:
        return sum(t.calls for t in self.tool_calls)

    def to_jsonable(self) -> dict:
        return {
            "runId": self.run_id,
            "tenantId": self.tenant_id,
            "agentId": self.agent_id,
            "generatedAt": self.generated_at.isoformat(),
            "totalCalls": self.total_calls,
            "toolCalls": [
                {
                    "tool": t.tool,
                    "calls": t.calls,
                    "risk": t.risk,
                    "risks": list(t.risks),
                    "outcomes": dict(t.outcomes),
                }
                for t in self.tool_calls
            ],
            "approvals": [
                {
                    "id": str(a.id),
                    "action": a.action_type,
                    "summary": a.summary,
                    "status": a.status.value,
                    "requestedBy": a.requested_by,
                    "decidedBy": a.decided_by,
                    "decisionReason": a.decision_reason,
                }
                for a in self.approvals
            ],
            "outcomes": dict(self.outcomes),
            "asiCoverage": [{"id": i, "title": t, "taggedCalls": n} for i, t, n in self.asi_coverage],
            "guardrailViolations": self.guardrail_violations,
            "denied": self.denied,
            "pendingApproval": self.pending_approval,
        }

    def to_markdown(self) -> str:
        lines = [
            f"# Governance report — run `{self.run_id}`",
            "",
            f"- Agent: `{self.agent_id or '-'}`  Tenant: `{self.tenant_id or '-'}`",
            f"- Generated: {self.generated_at.isoformat()}",
            f"- Tool calls: **{self.total_calls}** — denied: **{self.denied}**, "
            f"guardrail violations: **{self.guardrail_violations}**, "
            f"approval-gated: **{self.pending_approval}**",
            "",
            "## Tool calls",
            "",
            "| Tool | Calls | Risk | OWASP ASI | Outcomes |",
            "|---|---|---|---|---|",
        ]
        for t in self.tool_calls:
            outcomes = ", ".join(f"{k}×{v}" for k, v in t.outcomes)
            lines.append(f"| `{t.tool}` | {t.calls} | {t.risk} | {', '.join(t.risks) or '—'} | {outcomes} |")
        lines += ["", "## Approvals", "", "| Action | Summary | Status | Decided by |", "|---|---|---|---|"]
        for a in self.approvals:
            lines.append(f"| `{a.action_type}` | {a.summary} | {a.status.value} | {a.decided_by or '—'} |")
        if not self.approvals:
            lines.append("| — | — | — | — |")
        lines += ["", "## OWASP ASI coverage", "", "| Item | Title | Tagged calls |", "|---|---|---|"]
        for i, title, n in self.asi_coverage:
            lines.append(f"| {i} | {title} | {n} |")
        return "\n".join(lines) + "\n"


def governance_report(
    run_id: str,
    events: Iterable[AuditEvent],
    approvals: Sequence[ApprovalRequest] = (),
    tenant_id: str | None = None,
    agent_id: str | None = None,
) -> GovernanceReport:
    """Fold one run's audit events (plus its approval records, when available)
    into a ``GovernanceReport``."""
    events = [e for e in events if e.context.run_id == run_id]
    tool_events = [e for e in events if e.action_type == "tool.call"]

    by_tool: dict[str, list[AuditEvent]] = {}
    for e in tool_events:
        by_tool.setdefault(e.target_id, []).append(e)

    tools = tuple(
        ToolCallSummary(
            tool=name,
            calls=len(evs),
            risk=evs[-1].attributes.get("risk", "MEDIUM"),
            risks=tuple(sorted({r.id for e in evs for r in e.risks})),
            outcomes=tuple(sorted(Counter(e.outcome.value for e in evs).items())),
        )
        for name, evs in sorted(by_tool.items())
    )

    outcome_counts = Counter(e.outcome.value for e in events)
    asi = [(r.id, r.title, sum(1 for e in tool_events if r in e.risks)) for r in AsiRisk]
    return GovernanceReport(
        run_id=run_id,
        tenant_id=tenant_id or next((e.context.tenant_id for e in events if e.context.tenant_id), None),
        agent_id=agent_id or next((e.context.agent_id for e in events if e.context.agent_id), None),
        generated_at=datetime.now().astimezone(),
        tool_calls=tools,
        approvals=tuple(approvals),
        outcomes=tuple(sorted(outcome_counts.items())),
        asi_coverage=tuple(asi),
        guardrail_violations=sum(
            int(e.attributes["guardrailViolations"])
            for e in tool_events
            if e.attributes.get("guardrailViolations")
        ),
        denied=outcome_counts.get(AuditOutcome.DENIED.value, 0),
        pending_approval=outcome_counts.get(AuditOutcome.PENDING_APPROVAL.value, 0),
    )
