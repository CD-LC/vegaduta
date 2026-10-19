"""The same support agent wrapped in the VegaDūta Agent SDK.

Same shop backend, same scripted model turns as without_sdk.py. The only
additions are the @governed classification on each tool and the Governance
pipeline the calls pass through: approval, effect journal, budget, guardrails
and audit. Requires `pip install -e sdk/python-agent`.
"""

from __future__ import annotations

import json

from vegaduta_agent import (
    AsiRisk,
    BudgetExceededException,
    Governance,
    InMemoryAuditSink,
    RiskLabel,
    RunContext,
    default_provider,
    governance_report,
    governed,
)

from scenario import STEPS, banner
from shop import Shop

REVIEWER = "maya@acme-shop (support lead)"
AUTO_APPROVE_LIMIT_CENTS = 10_000  # $100: a lead's discretionary limit


class SupportTools:
    """The same four operations as the ungoverned agent, classified once."""

    def __init__(self, shop: Shop) -> None:
        self.shop = shop

    @governed(risk=RiskLabel.LOW, side_effecting=False)
    def lookup_order(self, order_id: str) -> str:
        """Look up an order by id."""
        return self.shop.get_order(order_id)

    @governed(
        risk=RiskLabel.MEDIUM,
        side_effecting=False,
        untrusted_input=True,
        reads_private_data=True,
        risks={AsiRisk.ASI01, AsiRisk.ASI06},
    )
    def read_email(self, message_id: str) -> str:
        """Read an inbound customer email."""
        return self.shop.get_email(message_id)

    @governed(risk=RiskLabel.HIGH, requires_approval=True, risks={AsiRisk.ASI02, AsiRisk.ASI09})
    def issue_refund(self, order_id: str, cents: int, reason: str) -> str:
        """Refund money to an order's payment method."""
        return self.shop.refund(order_id, cents, reason)

    @governed(risk=RiskLabel.MEDIUM, idempotent=True, risks={AsiRisk.ASI02})
    def apply_store_credit(self, order_id: str, cents: int) -> str:
        """Apply goodwill store credit."""
        return self.shop.store_credit(order_id, cents)


def review_pending(governance: Governance, approval_ids: list) -> None:
    """The human step: whoever reviews the queue decides, not the model."""
    for req in governance.approval_gate.pending():
        cents = json.loads(req.payload_json).get("cents", 0)
        print(f"  review queue: {req.summary} args={req.payload_json}")
        if cents <= AUTO_APPROVE_LIMIT_CENTS:
            decided = governance.approval_gate.approve(req.id, REVIEWER)
            print(f"  reviewer: APPROVED -> {decided.result_json}")
        else:
            governance.approval_gate.reject(
                req.id, REVIEWER, "over the $100 limit — route to a manager, not a chatbot"
            )
            print("  reviewer: REJECTED (over the $100 limit)")
        approval_ids.append(req.id)


def main() -> None:
    shop = Shop()
    audit_sink = InMemoryAuditSink()
    approval_ids: list = []
    governance = Governance(
        audit_sink=audit_sink,
        budget_provider=default_provider(max_tool_calls=8),
        on_approval_requested=lambda req: print(f"  [approval requested] {req.summary}"),
    )
    tools = governance.scan(SupportTools(shop))
    ctx = RunContext(
        run_id="ticket-4821",
        tenant_id="acme-shop",
        principal="customer-7721@example.com",
        agent_id="support-bot",
    )

    def dispatch(tool: str, args: dict) -> str:
        print(f"  -> {tool}({args})")
        result = tools[tool](args, context=ctx)
        print(f"  <- {result}")
        return result

    banner("SUPPORT AGENT — WITH VEGADŪTA SDK (governed dispatch)")
    for step in STEPS:
        if step["kind"] != "call":
            print(step["text"])
            continue
        try:
            result = dispatch(step["tool"], step["args"])
        except BudgetExceededException as exc:
            print(f"  !! run stopped: {exc}")
            break
        try:
            pending = json.loads(result).get("approvalRequired")
        except (ValueError, AttributeError):
            pending = False
        if pending:
            review_pending(governance, approval_ids)

    banner("MONEY MOVED")
    print(shop.money_moved())

    banner("AUDIT TRAIL (one key=value line per governed event)")
    for event in audit_sink.events:
        print(f"  {event.to_line()}")

    approvals = [governance.approval_gate.find(i) for i in approval_ids]
    print(governance_report(ctx.run_id, audit_sink.events, approvals).to_markdown())


if __name__ == "__main__":
    main()
