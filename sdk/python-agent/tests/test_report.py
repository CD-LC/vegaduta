"""The governance report: one run's audit events rendered for a reviewer."""

from __future__ import annotations

import json
import uuid

from vegaduta_agent import (
    ApprovalStatus,
    AsiRisk,
    AuditOutcome,
    Governance,
    InMemoryAuditSink,
    RiskLabel,
    RunContext,
    governance_report,
    governed,
)


def test_report_summarises_a_run():
    sink = InMemoryAuditSink()
    g = Governance(audit_sink=sink)
    ctx = RunContext(run_id=str(uuid.uuid4()), tenant_id="acme", agent_id="agent-7")

    @governed(risk=RiskLabel.HIGH, requires_approval=True, idempotent=True, risks={AsiRisk.ASI02})
    def refund(order_id: str) -> str:
        return f"refunded {order_id}"

    @governed(risk=RiskLabel.LOW, side_effecting=False, idempotent=True)
    def status(order_id: str) -> str:
        return "shipped"

    refund_tool = g.wrap(refund)
    status_tool = g.wrap(status)

    refund_tool({"order_id": "o_1"}, context=ctx)  # -> pending approval
    status_tool({"order_id": "o_1"}, context=ctx)  # runs
    status_tool({"order_id": "o_1"}, context=ctx)  # replayed

    req = g.approval_gate.pending()[0]
    decided = g.approval_gate.approve(req.id, "reviewer@corp")  # executes refund

    report = governance_report(
        ctx.run_id,
        sink.events,
        [decided],
        tenant_id="acme",
        agent_id="agent-7",
    )

    assert report.tenant_id == "acme"
    assert report.agent_id == "agent-7"
    # refund: PENDING_APPROVAL + SUCCESS (the approval resumption runs it);
    # status: SUCCESS + REPLAYED.
    assert report.total_calls == 4
    assert report.pending_approval == 1
    assert report.denied == 0

    by_tool = {t.tool: t for t in report.tool_calls}
    assert by_tool["refund"].calls == 2
    assert by_tool["refund"].risk == "HIGH"
    assert "ASI02" in by_tool["refund"].risks
    assert dict(by_tool["status"].outcomes)[AuditOutcome.REPLAYED.value] == 1

    asi02 = next(row for row in report.asi_coverage if row[0] == "ASI02")
    assert asi02[2] == 2  # refund's pending + executed audit events both carry the tag
    assert any(row[0] == "ASI01" and row[2] == 0 for row in report.asi_coverage)

    approvals = report.approvals
    assert approvals[0].status is ApprovalStatus.EXECUTED
    assert approvals[0].decided_by == "reviewer@corp"

    md = report.to_markdown()
    assert "# Governance report" in md
    assert "ASI02" in md and "refund" in md and "reviewer@corp" in md

    js = json.loads(json.dumps(report.to_jsonable()))
    assert js["totalCalls"] == 4
    assert js["approvals"][0]["status"] == "EXECUTED"


def test_report_counts_guardrail_violations_from_audit_attributes():
    sink = InMemoryAuditSink()
    g = Governance(audit_sink=sink)
    ctx = RunContext(run_id=str(uuid.uuid4()))

    def leaky() -> str:
        return "key: sk_live-4eC39HqLyjWDarjtT1zdp7dc and a Bearer " + "x" * 20

    g.wrap(leaky)("{}", context=ctx)
    report = governance_report(ctx.run_id, sink.events)
    assert report.guardrail_violations >= 1


def test_events_from_other_runs_are_excluded():
    sink = InMemoryAuditSink()
    g = Governance(audit_sink=sink)
    g.wrap(lambda: "x", name="t")("{}", context=RunContext.of_run("run-a"))
    g.wrap(lambda: "x", name="t")("{}", context=RunContext.of_run("run-b"))
    report = governance_report("run-a", sink.events)
    assert report.total_calls == 1
