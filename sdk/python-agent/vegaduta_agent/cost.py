"""Cost attribution and showback: which agent, tool and principal spent what.

Mirrors ``agent-core``'s ``cost`` package. ``CostLedger`` records charges
against the run context (run, agent, tenant, principal); ``showback`` renders
the ledger as a report a finance or platform review reads. ``CostObserver``
plugs into the pipeline as a ``ToolCallObserver`` so every call is priced as
it finishes — pair it with a ``ChargeEstimator`` or ``ModelPrice`` table when
per-call USD is known, otherwise calls are counted.

Deliberately simple: USD amounts are ``Decimal``, the price table is yours
(pricing moves faster than any library can ship), and the ledger is
process-local — export rows to your own store for cross-process accounting.
"""

from __future__ import annotations

import threading
from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from decimal import Decimal

from .context import RunContext


@dataclass(frozen=True)
class ModelPrice:
    """USD per token for one model, input and output separately."""

    model: str
    usd_per_input_token: Decimal
    usd_per_output_token: Decimal

    def cost(self, input_tokens: int, output_tokens: int) -> Decimal:
        return (
            self.usd_per_input_token * input_tokens
            + self.usd_per_output_token * output_tokens
        )


class CostModel:
    """The price table the estimator consults. Unknown models cost zero —
    under-pricing is visible (calls still count); over-pricing blocks a run,
    which is worse."""

    def __init__(self, prices: Iterable[ModelPrice] = ()) -> None:
        self._prices = {p.model: p for p in prices}

    def set(self, price: ModelPrice) -> None:
        self._prices[price.model] = price

    def price_of(self, model: str) -> ModelPrice | None:
        return self._prices.get(model)


@dataclass(frozen=True)
class CostEntry:
    run_id: str
    tenant_id: str | None
    agent_id: str | None
    principal: str | None
    tool_name: str
    tool_calls: int
    tokens: int
    usd: Decimal


class CostLedger:
    """A process-local attribution ledger. Rows group by
    (run, agent, principal, tool) so a review can ask "who spent" as well as
    "what cost"."""

    def __init__(self) -> None:
        self._rows: dict[tuple, list] = defaultdict(lambda: [0, 0, Decimal("0")])
        self._lock = threading.Lock()

    def record(
        self,
        context: RunContext,
        tool_name: str,
        *,
        tokens: int = 0,
        usd: Decimal = Decimal("0"),
        tool_calls: int = 1,
    ) -> None:
        key = (context.run_id, context.tenant_id, context.agent_id, context.principal, tool_name)
        with self._lock:
            row = self._rows[key]
            row[0] += tool_calls
            row[1] += tokens
            row[2] += usd

    def entries(self) -> list[CostEntry]:
        with self._lock:
            return [
                CostEntry(run, tenant, agent, principal, tool, tc, tok, usd)
                for (run, tenant, agent, principal, tool), (tc, tok, usd) in self._rows.items()
            ]

    def total_usd(self) -> Decimal:
        return sum((e.usd for e in self.entries()), Decimal("0"))


class CostObserver:
    """A ``ToolCallObserver`` that prices each finished call into the ledger.
    ``estimator`` computes the charge; the default counts calls only."""

    def __init__(self, ledger: CostLedger, estimator=None) -> None:
        self.ledger = ledger
        self._estimator = estimator

    def on_call(self, call, classification, context) -> None:
        pass

    def on_result(self, call, classification, context, result, error, elapsed_seconds) -> None:
        tokens = 0
        usd = Decimal("0")
        if self._estimator is not None:
            charge = self._estimator.estimate(call, classification, context)
            tokens, usd = charge.tokens, charge.usd
        self.ledger.record(context, call.tool_name, tokens=tokens, usd=usd)


def showback(ledger: CostLedger) -> str:
    """A markdown table a reviewer reads: principal/agent/tool rows with calls,
    tokens and USD, plus a total."""
    rows = ledger.entries()
    lines = [
        "| Tenant | Agent | Principal | Tool | Calls | Tokens | USD |",
        "|---|---|---|---|---|---|---|",
    ]
    total_calls = total_tokens = 0
    total_usd = Decimal("0")
    for e in sorted(rows, key=lambda e: (-e.usd, e.tool_name)):
        lines.append(
            f"| {e.tenant_id or '-'} | {e.agent_id or '-'} | {e.principal or '-'} | "
            f"{e.tool_name} | {e.tool_calls} | {e.tokens} | {e.usd} |"
        )
        total_calls += e.tool_calls
        total_tokens += e.tokens
        total_usd += e.usd
    lines.append(f"| **Total** | | | | {total_calls} | {total_tokens} | {total_usd} |")
    return "\n".join(lines) + "\n"
