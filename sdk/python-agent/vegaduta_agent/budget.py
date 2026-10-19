"""Per-run budgets: the cap that stops a tool loop.

Mirrors ``io.vegaduta.agent.api.budget``. A ``Budget`` is created per run by a
``BudgetProvider``; the pipeline reserves one ``BudgetCharge`` per tool call
before dispatching. Exhaustion raises ``BudgetExceededException`` — the model
gets told the run is out of budget rather than silently looping.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass
from decimal import Decimal
from typing import Protocol

from .context import RunContext
from .errors import BudgetExceededException
from .risk import ToolClassification
from .tool_call import ToolCall


@dataclass(frozen=True)
class BudgetCharge:
    """A cost in the three units the SDK tracks. All components are
    non-negative; ``NONE`` and ``ONE_TOOL_CALL`` cover the common cases."""

    tool_calls: int = 0
    tokens: int = 0
    usd: Decimal = Decimal("0")

    def __post_init__(self) -> None:
        if self.tool_calls < 0 or self.tokens < 0 or self.usd < 0:
            raise ValueError("charges must not be negative")

    def plus(self, other: BudgetCharge) -> BudgetCharge:
        return BudgetCharge(
            self.tool_calls + other.tool_calls,
            self.tokens + other.tokens,
            self.usd + other.usd,
        )


BudgetCharge.NONE = BudgetCharge()  # type: ignore[misc]
BudgetCharge.ONE_TOOL_CALL = BudgetCharge(tool_calls=1)  # type: ignore[misc]


class Budget(Protocol):
    def reserve(self, estimate: BudgetCharge) -> None:
        """Reserve ``estimate`` or raise ``BudgetExceededException``."""
        ...

    def spent(self) -> BudgetCharge: ...

    def cap(self) -> BudgetCharge: ...

    def exhausted(self) -> bool:
        """True when nothing more can be reserved."""
        ...


class CountingBudget:
    """An in-memory budget that enforces tool-call, token and USD caps. Every
    component of a charge must fit under the matching cap; a zero cap component
    means that unit is unbounded (a cap of ``BudgetCharge(tool_calls=50)``
    limits calls only)."""

    def __init__(self, cap: BudgetCharge):
        self._cap = cap
        self._spent = BudgetCharge.NONE
        self._lock = threading.Lock()

    def _fits(self, charge: BudgetCharge) -> bool:
        after = self._spent.plus(charge)
        return (
            (self._cap.tool_calls == 0 or after.tool_calls <= self._cap.tool_calls)
            and (self._cap.tokens == 0 or after.tokens <= self._cap.tokens)
            and (self._cap.usd == 0 or after.usd <= self._cap.usd)
        )

    def reserve(self, estimate: BudgetCharge) -> None:
        with self._lock:
            if not self._fits(estimate):
                raise BudgetExceededException(
                    f"run budget exceeded: {self._spent.plus(estimate)} would exceed cap {self._cap}"
                )
            self._spent = self._spent.plus(estimate)

    def spent(self) -> BudgetCharge:
        return self._spent

    def cap(self) -> BudgetCharge:
        return self._cap

    def exhausted(self) -> bool:
        return not self._fits(BudgetCharge.ONE_TOOL_CALL)


class BudgetProvider(Protocol):
    def budget_for(self, context: RunContext) -> Budget:
        """The budget for one run. Called once per run."""
        ...


def default_provider(
    max_tool_calls: int = 50, max_tokens: int = 0, max_usd: Decimal | float = Decimal("0")
) -> BudgetProvider:
    """The stock policy: ``max_tool_calls`` per run (default 50, matching the
    Java starter). ``0`` on any unit makes that unit unbounded."""
    cap = BudgetCharge(tool_calls=max_tool_calls, tokens=max_tokens, usd=Decimal(str(max_usd)))

    class _Provider:
        def budget_for(self, context: RunContext) -> Budget:
            return CountingBudget(cap)

    return _Provider()


class ChargeEstimator(Protocol):
    def estimate(
        self, call: ToolCall, classification: ToolClassification, context: RunContext
    ) -> BudgetCharge:
        """The charge a call will cost, reserved before it runs."""
        ...


def one_call_per_tool() -> ChargeEstimator:
    """Every tool call costs exactly one tool call."""

    class _Estimator:
        def estimate(
            self, call: ToolCall, classification: ToolClassification, context: RunContext
        ) -> BudgetCharge:
            return BudgetCharge.ONE_TOOL_CALL

    return _Estimator()
