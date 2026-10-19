"""The ``@governed`` decorator: declare a tool's risk once, inline.

Mirrors ``io.vegaduta.agent.api.annotation.Governed``. Decorating a function
attaches a ``ToolClassification``; the ``Governance`` pipeline reads it when
the function is wrapped or scanned into a registry::

    @governed(risk=RiskLabel.HIGH, requires_approval=True, idempotent=True,
              risks={AsiRisk.ASI02})
    def refund(order_id: str, cents: int) -> str: ...
"""

from __future__ import annotations

from collections.abc import Callable, Iterable
from typing import TypeVar

from .risk import AsiRisk, RiskLabel, ToolClassification

F = TypeVar("F", bound=Callable)

CLASSIFICATION_ATTR = "__vegaduta_governance__"


def governed(
    _fn: F | None = None,
    *,
    risk: RiskLabel = RiskLabel.MEDIUM,
    side_effecting: bool = True,
    reads_private_data: bool = False,
    untrusted_input: bool = False,
    exfiltration_sink: bool = False,
    idempotent: bool = False,
    requires_approval: bool = False,
    risks: Iterable[AsiRisk] = (),
) -> Callable[[F], F] | F:
    """Classify a tool. Usable bare (``@governed``) or with arguments
    (``@governed(risk=...)``)."""
    classification = ToolClassification(
        risk=risk,
        side_effecting=side_effecting,
        reads_private_data=reads_private_data,
        untrusted_input=untrusted_input,
        exfiltration_sink=exfiltration_sink,
        idempotent=idempotent,
        requires_approval=requires_approval,
        risks=frozenset(risks),
    )

    def mark(fn: F) -> F:
        setattr(fn, CLASSIFICATION_ATTR, classification)
        return fn

    return mark(_fn) if _fn is not None else mark


def classification_of(fn: Callable) -> ToolClassification | None:
    """The classification a ``@governed`` decorator attached, or ``None`` for
    an undeclared tool. Undeclared tools get the pipeline's default policy —
    they are never silently trusted."""
    return getattr(fn, CLASSIFICATION_ATTR, None)


def governed_tool_name(fn: Callable) -> str:
    return getattr(fn, "__name__", type(fn).__name__)
