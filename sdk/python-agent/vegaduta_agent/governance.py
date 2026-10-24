"""The governance pipeline and the ``Governance`` facade.

This is the runtime counterpart of ``io.vegaduta.agent.core.Governance`` /
``GovernedTool``. ``Governance.wrap`` turns a plain (or ``@governed``) function
into a governed tool: a callable that takes the model's JSON arguments and
returns the tool's text result, with classification, gate, approval, journal,
budget, guardrails, hooks, audit and observers around the call — in that order.

Result contract: the wrapped tool returns a **string** — either the tool's own
output (redacted, journaled) or a JSON object describing why it did not run::

    {"approvalRequired": true, "approvalId": "...", "message": "..."}
    {"error": "denied", "reason": "..."}
    {"error": "invalid_arguments", "detail": "..."}

Hard governance failures raise instead of returning: ``ToolDeniedException``,
``BudgetExceededException``, ``GuardrailViolationException`` — they must break
the tool loop, not be summarised away by the model.
"""

from __future__ import annotations

import inspect
import json
import logging
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from enum import Enum

from .approval import ApprovalGate
from .audit import AuditEvent, AuditOutcome, AuditSink, LoggingAuditSink
from .budget import (
    Budget,
    BudgetProvider,
    ChargeEstimator,
    default_provider,
    one_call_per_tool,
)
from .context import RunContext
from .errors import (
    BudgetExceededException,
    GuardrailViolationException,
    ToolDeniedException,
)
from .gate import Outcome, ToolDispatchGate, Verdict, allow_all
from .governed import classification_of, governed_tool_name
from .guardrail import (
    ContentGuardrail,
    GuardrailStage,
    InstructionLikeTextGuardrail,
    SecretRedactionGuardrail,
    run_guardrails,
)
from .hooks import FailMode, Hook, HookDecision, HookEvent, HookPoint
from .journal import EffectJournal, InMemoryEffectJournal, effect_key
from .observe import ToolCallObserver
from .risk import RiskLabel, ToolClassification
from .tool_call import ToolCall

log = logging.getLogger(__name__)

APPROVAL_PENDING_MESSAGE = (
    "This action needs human approval before it can run. Tell the user it has been "
    "submitted for review (approval id {approval_id}); do not claim it was done."
)


class GovernanceMode(Enum):
    """How hard the pipeline pushes back. Mirrors ``GovernanceMode``."""

    #: Deny, gate and guardrail verdicts stop the call.
    ENFORCE = "ENFORCE"
    #: Everything is evaluated, audited and reported, but nothing is stopped —
    #: the dry-run mode for rolling governance out on an existing agent.
    OBSERVE = "OBSERVE"
    #: The pipeline is a pass-through except audit. For tests.
    OFF = "OFF"


@dataclass(frozen=True)
class GovernancePolicy:
    """Platform policy independent of per-tool declarations."""

    #: Calls whose declared risk is at least this label need human approval even
    #: without ``requires_approval=True``. CRITICAL is always gated regardless.
    approval_threshold: RiskLabel | None = None
    #: Summary template for approval requests the pipeline raises itself.
    approval_reason: str = "risk at or above the approval threshold"


class GovernedTool:
    """A governed callable. ``__call__`` takes the model's arguments as a JSON
    string (or a dict) plus an optional ``RunContext``; share one context
    across a run's calls so the budget and effect journal see the whole run."""

    def __init__(
        self, fn: Callable, name: str, classification: ToolClassification, governance: Governance
    ) -> None:
        self.fn = fn
        self.tool_name = name
        self.classification = classification
        self._g = governance
        self.__name__ = name
        self.__doc__ = getattr(fn, "__doc__", None)

    def __call__(self, arguments: str | dict = "{}", *, context: RunContext | None = None) -> str:
        if inspect.iscoroutinefunction(self.fn):
            raise TypeError(
                f"{self.tool_name} is async: 'await tool.acall(...)' or wrap it with governance.wrap_async"
            )
        ctx = context or RunContext.anonymous()
        args_json = arguments if isinstance(arguments, str) else json.dumps(arguments)
        call = ToolCall(tool_name=self.tool_name, arguments_json=args_json)
        return self._g.dispatch(self.fn, self.tool_name, self.classification, call, ctx)

    async def acall(self, arguments: str | dict = "{}", *, context: RunContext | None = None) -> str:
        """The async entry point. A sync tool runs inline (same as
        ``__call__``); a coroutine function runs the whole governed pipeline
        on a worker thread, where the tool body gets its own event loop —
        hooks, gate, approval, journal, budget and audit all still apply."""
        if not inspect.iscoroutinefunction(self.fn):
            return self(arguments, context=context)
        import asyncio

        def shim(**kwargs):
            return asyncio.run(self.fn(**kwargs))

        ctx = context or RunContext.anonymous()
        args_json = arguments if isinstance(arguments, str) else json.dumps(arguments)
        call = ToolCall(tool_name=self.tool_name, arguments_json=args_json)
        return await asyncio.to_thread(self._g.dispatch, shim, self.tool_name, self.classification, call, ctx)

    def describe(self) -> dict:
        """A framework-neutral description for adapters: name, docstring and
        the declared parameters (name, type annotation, required, default)."""
        params = []
        try:
            sig = inspect.signature(self.fn)
        except (ValueError, TypeError):
            sig = None
        if sig is not None:
            for p in sig.parameters.values():
                if p.kind in (p.VAR_POSITIONAL, p.VAR_KEYWORD):
                    continue
                ann = p.annotation
                params.append(
                    {
                        "name": p.name,
                        "type": getattr(ann, "__name__", str(ann))
                        if ann is not inspect.Parameter.empty
                        else "string",
                        "required": p.default is inspect.Parameter.empty,
                        "default": None if p.default is inspect.Parameter.empty else p.default,
                    }
                )
        return {
            "name": self.tool_name,
            "description": (self.fn.__doc__ or "").strip().split("\n\n")[0],
            "parameters": params,
            "risk": self.classification.risk.name,
            "requiresApproval": self.classification.requires_approval,
            "idempotent": self.classification.idempotent,
        }


class Governance:
    """Wires the pieces and dispatches calls. Build once per agent
    (or process); budgets are per run via the ``BudgetProvider``."""

    def __init__(
        self,
        *,
        gate: ToolDispatchGate | None = None,
        approval_gate: ApprovalGate | None = None,
        journal: EffectJournal | None = None,
        budget_provider: BudgetProvider | None = None,
        estimator: ChargeEstimator | None = None,
        guardrails: Iterable[ContentGuardrail] | None = None,
        hooks: Iterable[Hook] = (),
        audit_sink: AuditSink | None = None,
        observers: Iterable[ToolCallObserver] = (),
        policy: GovernancePolicy | None = None,
        mode: GovernanceMode = GovernanceMode.ENFORCE,
        on_approval_requested: Callable | None = None,
        provenance=None,
    ) -> None:
        self.gate = gate or allow_all()
        self.approval_gate = approval_gate or ApprovalGate()
        self.journal = journal if journal is not None else InMemoryEffectJournal()
        self._budget_provider = budget_provider or default_provider()
        self._estimator = estimator or one_call_per_tool()
        self.guardrails: list[ContentGuardrail] = (
            list(guardrails)
            if guardrails is not None
            else [SecretRedactionGuardrail(), InstructionLikeTextGuardrail()]
        )
        self.hooks = list(hooks)
        self.audit = audit_sink or LoggingAuditSink()
        self.observers = list(observers)
        self.policy = policy or GovernancePolicy()
        self.mode = mode
        self._on_approval_requested = on_approval_requested
        #: Optional ``provenance.ProvenanceGate``: every wrapped tool's
        #: manifest is pinned/checked at registration.
        self.provenance = provenance
        self._budgets: dict[str, Budget] = {}
        self._tools: dict[str, GovernedTool] = {}

    # -- registration ------------------------------------------------------

    def wrap(self, fn: Callable, name: str | None = None) -> GovernedTool:
        """Wrap one function as a governed tool. Undeclared functions get the
        default MEDIUM/side-effecting classification — never silent trust."""
        classification = classification_of(fn) or ToolClassification()
        tool = GovernedTool(fn, name or governed_tool_name(fn), classification, self)
        if self.provenance is not None:
            drift = self.provenance.check(tool.describe())
            if drift is not None and getattr(self.provenance, "mode", None) is not None:
                from .provenance import ProvenanceMode

                if self.provenance.mode is ProvenanceMode.ENFORCE:
                    raise ToolDeniedException(
                        f"tool {tool.tool_name} drifted from its pinned manifest: {drift.detail}"
                    )
        self._tools[tool.tool_name] = tool
        return tool

    def wrap_async(self, fn: Callable, name: str | None = None) -> GovernedTool:
        """Wrap a coroutine function (``async def``) for use through
        ``tool.acall(...)``. Identical pipeline; only the invocation awaits."""
        if not inspect.iscoroutinefunction(fn):
            raise TypeError("wrap_async expects an async def; use wrap for plain functions")
        return self.wrap(fn, name)

    def scan(self, *sources) -> dict[str, GovernedTool]:
        """Wrap every ``@governed``-marked function found on the given modules,
        classes or objects, keyed by tool name."""
        found: dict[str, GovernedTool] = {}
        for source in sources:
            for _name, member in inspect.getmembers(source):
                fn = getattr(member, "__wrapped__", member)
                if callable(member) and classification_of(fn) is not None:
                    tool = self.wrap(member)
                    found[tool.tool_name] = tool
        return found

    @property
    def tools(self) -> dict[str, GovernedTool]:
        return dict(self._tools)

    # -- dispatch ----------------------------------------------------------

    def _budget(self, context: RunContext) -> Budget:
        budget = self._budgets.get(context.run_id)
        if budget is None:
            budget = self._budget_provider.budget_for(context)
            self._budgets[context.run_id] = budget
        return budget

    def _fire_hooks(
        self, point: HookPoint, call: ToolCall | None, context: RunContext, **detail
    ) -> HookDecision:
        event = HookEvent(point=point, call=call, context=context, detail=detail)
        for hook in self.hooks:
            if point not in hook.points:
                continue
            try:
                decision = hook.on_event(event)
            except Exception as exc:  # a hook that throws must not crash the turn
                log.warning("hook %r failed at %s: %s", hook, point.value, exc)
                decision = (
                    HookDecision.continue_()
                    if getattr(hook, "fail_mode", FailMode.CLOSED) is FailMode.OPEN
                    else HookDecision.block(f"hook error at {point.value}: {exc}")
                )
            if decision.blocks:
                return decision
        return HookDecision.continue_()

    def _audit(
        self,
        outcome: AuditOutcome,
        call: ToolCall,
        classification: ToolClassification,
        context: RunContext,
        detail: str | None = None,
        **attributes: str,
    ) -> None:
        self.audit.record(
            AuditEvent(
                action_type="tool.call",
                target_type="tool",
                target_id=call.tool_name,
                context=context,
                outcome=outcome,
                actor_id=context.principal or "agent",
                detail=detail,
                risks=classification.risks,
                attributes={
                    "callId": call.call_id,
                    "risk": classification.risk.name,
                    **{k: str(v) for k, v in attributes.items()},
                },
            )
        )

    def _needs_approval(self, classification: ToolClassification) -> bool:
        return (
            classification.requires_approval
            or classification.risk is RiskLabel.CRITICAL
            or (
                self.policy.approval_threshold is not None
                and classification.risk.at_least(self.policy.approval_threshold)
            )
        )

    def dispatch(
        self,
        fn: Callable,
        tool_name: str,
        classification: ToolClassification,
        call: ToolCall,
        context: RunContext,
    ) -> str:
        observe = self.mode is not GovernanceMode.OFF

        # 1. hooks + dispatch gate
        if observe:
            decision = self._fire_hooks(HookPoint.BEFORE_TOOL_CALL, call, context)
            verdict = (
                Verdict.deny(decision.reason)
                if decision.blocks
                else self.gate.evaluate(call, classification, context)
            )
            if not verdict.allowed and verdict.outcome is Outcome.DENY:
                self._fire_hooks(HookPoint.ON_TOOL_DENIED, call, context, reason=verdict.reason)
                self._audit(AuditOutcome.DENIED, call, classification, context, detail=verdict.reason)
                if self.mode is GovernanceMode.ENFORCE:
                    raise ToolDeniedException(f"tool {tool_name} denied: {verdict.reason}")

            # 2. approval
            needs_approval = verdict.outcome is Outcome.REQUIRE_APPROVAL or self._needs_approval(
                classification
            )
            if needs_approval and self.mode is GovernanceMode.ENFORCE:
                summary = (
                    verdict.reason
                    if verdict.outcome is Outcome.REQUIRE_APPROVAL
                    else f"{tool_name}: {self.policy.approval_reason}"
                )

                def resumption() -> str:
                    return self._invoke(fn, tool_name, classification, call, context)

                request = self.approval_gate.request_for_tool(call, context, summary, resumption=resumption)
                self._fire_hooks(
                    HookPoint.ON_APPROVAL_REQUESTED,
                    call,
                    context,
                    approval_id=str(request.id),
                    reason=summary,
                )
                self._audit(
                    AuditOutcome.PENDING_APPROVAL,
                    call,
                    classification,
                    context,
                    detail=summary,
                    approvalId=str(request.id),
                )
                if self._on_approval_requested is not None:
                    self._on_approval_requested(request)
                return json.dumps(
                    {
                        "approvalRequired": True,
                        "approvalId": str(request.id),
                        "message": APPROVAL_PENDING_MESSAGE.format(approval_id=request.id),
                    }
                )

        # 3. effect journal: replay a call this run already made
        key = effect_key(context.run_id, tool_name, call.arguments_json)
        if observe and classification.idempotent:
            prior = self.journal.lookup(key)
            if prior is not None:
                self._audit(
                    AuditOutcome.REPLAYED,
                    call,
                    classification,
                    context,
                    detail="served from the effect journal",
                )
                return prior
            self.journal.begin(key)

        # 4. budget
        if observe:
            budget = self._budget(context)
            try:
                budget.reserve(self._estimator.estimate(call, classification, context))
            except BudgetExceededException as exc:
                self._fire_hooks(HookPoint.ON_BUDGET_EXCEEDED, call, context, detail=str(exc))
                self._audit(AuditOutcome.BUDGET_EXCEEDED, call, classification, context, detail=str(exc))
                if self.mode is GovernanceMode.ENFORCE:
                    raise
            # 5. arguments through the input-side guardrails
            checked = run_guardrails(call.arguments_json, GuardrailStage.TOOL_ARGUMENTS, self.guardrails)
            if checked.violations:
                self._fire_hooks(
                    HookPoint.ON_GUARDRAIL_VIOLATION,
                    call,
                    context,
                    stage="TOOL_ARGUMENTS",
                    violations=[v.detail for v in checked.violations],
                )
            if checked.content != call.arguments_json:
                call = ToolCall(tool_name=tool_name, arguments_json=checked.content, call_id=call.call_id)
            arg_violations = len(checked.violations)
        else:
            arg_violations = 0

        return self._invoke(
            fn,
            tool_name,
            classification,
            call,
            context,
            journal_key=key if classification.idempotent else None,
            arg_violations=arg_violations,
        )

    def _invoke(
        self,
        fn: Callable,
        tool_name: str,
        classification: ToolClassification,
        call: ToolCall,
        context: RunContext,
        journal_key: str | None = None,
        arg_violations: int = 0,
    ) -> str:
        """Execute the tool: arguments parse, call, output guardrails, journal,
        audit, observers. Reached directly for allowed calls and via the
        approval resumption for approved ones."""
        for observer in self.observers:
            observer.on_call(call, classification, context)
        started = time.monotonic()
        result: str | None = None
        error: BaseException | None = None
        try:
            try:
                kwargs = json.loads(call.arguments_json or "{}")
                if not isinstance(kwargs, dict):
                    raise ValueError("arguments must be a JSON object")
            except (ValueError, TypeError) as exc:
                self._audit(
                    AuditOutcome.FAILURE, call, classification, context, detail=f"invalid_arguments: {exc}"
                )
                return json.dumps({"error": "invalid_arguments", "detail": str(exc)})
            raw = fn(**kwargs)
            result = raw if isinstance(raw, str) else json.dumps(raw)
            out_violations = 0
            if self.mode is not GovernanceMode.OFF:
                checked = run_guardrails(result, GuardrailStage.TOOL_OUTPUT, self.guardrails)
                if checked.violations:
                    self._fire_hooks(
                        HookPoint.ON_GUARDRAIL_VIOLATION,
                        call,
                        context,
                        stage="TOOL_OUTPUT",
                        violations=[v.detail for v in checked.violations],
                    )
                result = checked.content
                out_violations = len(checked.violations)
            if journal_key is not None:
                self.journal.record(journal_key, result)
            total_violations = arg_violations + out_violations
            self._audit(
                AuditOutcome.SUCCESS,
                call,
                classification,
                context,
                **({"guardrailViolations": str(total_violations)} if total_violations else {}),
            )
            self._fire_hooks(HookPoint.AFTER_TOOL_CALL, call, context)
            return result
        except GuardrailViolationException as exc:
            error = exc
            self._fire_hooks(
                HookPoint.ON_GUARDRAIL_VIOLATION, call, context, stage="TOOL_OUTPUT", violations=[str(exc)]
            )
            self._audit(AuditOutcome.DENIED, call, classification, context, detail=str(exc))
            raise
        except Exception as exc:
            error = exc
            self._audit(
                AuditOutcome.FAILURE, call, classification, context, detail=f"{type(exc).__name__}: {exc}"
            )
            raise
        finally:
            elapsed = time.monotonic() - started
            for observer in self.observers:
                try:
                    observer.on_result(call, classification, context, result, error, elapsed)
                except Exception:  # telemetry must never break a turn
                    log.warning("observer %r failed in on_result", observer)
