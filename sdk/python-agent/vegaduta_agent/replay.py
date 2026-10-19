"""Policy replay and shadow gating: what a stricter policy would have done.

Mirrors ``agent-core``'s ``replay`` package and the testkit's trace support.
The question a policy change must answer before it ships: "if last week's
calls ran under the new rules, what would have been denied?" — answered two
ways:

- ``shadow_gate(candidate, delegate)`` — a live gate that reports what the
  candidate policy *would* have decided while the delegate's verdict still
  governs. Shadow mode on production traffic.
- ``policy_replay(candidate, calls)`` — re-evaluates recorded calls offline
  and diffs verdicts: ``newly_denied`` is the blast radius.
- ``TraceRecorder``/``replay_trace`` — record a run's calls as JSONL and
  re-run them against the tool to compare outputs (the regression harness for
  a tool whose implementation changed).
"""

from __future__ import annotations

import json
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
from enum import Enum

from .context import RunContext
from .gate import Outcome, Verdict
from .risk import RiskLabel, ToolClassification
from .tool_call import ToolCall


class ChangeKind(Enum):
    NEWLY_ALLOWED = "NEWLY_ALLOWED"
    NEWLY_DENIED = "NEWLY_DENIED"
    NEWLY_REQUIRES_APPROVAL = "NEWLY_REQUIRES_APPROVAL"
    NO_LONGER_REQUIRES_APPROVAL = "NO_LONGER_REQUIRES_APPROVAL"
    REASON_CHANGED = "REASON_CHANGED"
    UNCHANGED = "UNCHANGED"


@dataclass(frozen=True)
class RecordedCall:
    """One call as it ran: enough to replay it — name, raw arguments, the
    classification it carried and the context that mattered to policy."""

    tool_name: str
    arguments_json: str
    risk: str
    outcome: str = ""
    result: str | None = None

    def to_line(self) -> str:
        return json.dumps(
            {
                "tool": self.tool_name,
                "arguments": self.arguments_json,
                "risk": self.risk,
                "outcome": self.outcome,
                "result": self.result,
            }
        )

    @classmethod
    def from_line(cls, line: str) -> RecordedCall:
        d = json.loads(line)
        return cls(d["tool"], d["arguments"], d["risk"], d.get("outcome", ""), d.get("result"))

    def classification(self) -> ToolClassification:
        return ToolClassification(risk=RiskLabel[self.risk])


@dataclass(frozen=True)
class PolicyDiff:
    call: RecordedCall
    kind: ChangeKind
    before: str
    after: str


@dataclass(frozen=True)
class ReplayReport:
    total: int
    unchanged: int
    diffs: tuple[PolicyDiff, ...]

    @property
    def changed(self) -> int:
        return len(self.diffs)


def _classify_change(before: Outcome, after: Outcome, before_reason: str, after_reason: str) -> ChangeKind:
    if before == after:
        return ChangeKind.REASON_CHANGED if before_reason != after_reason else ChangeKind.UNCHANGED
    if after is Outcome.DENY:
        return ChangeKind.NEWLY_DENIED
    if after is Outcome.REQUIRE_APPROVAL:
        return ChangeKind.NEWLY_REQUIRES_APPROVAL
    if before is Outcome.REQUIRE_APPROVAL:
        return ChangeKind.NO_LONGER_REQUIRES_APPROVAL
    return ChangeKind.NEWLY_ALLOWED


def policy_replay(
    before_gate,
    after_gate,
    calls: Iterable[RecordedCall],
    classification_of: Callable[[str], ToolClassification] | None = None,
    context: RunContext | None = None,
) -> ReplayReport:
    """Re-evaluates recorded calls under two gates and diffs the verdicts.
    ``classification_of`` maps a tool name to its current classification
    (default: the risk recorded on the call)."""
    ctx = context or RunContext.anonymous()
    diffs: list[PolicyDiff] = []
    total = unchanged = 0
    for recorded in calls:
        total += 1
        classification = (
            classification_of(recorded.tool_name)
            if classification_of is not None
            else ToolClassification(risk=RiskLabel[recorded.risk])
        )
        call = ToolCall.of(recorded.tool_name, recorded.arguments_json)
        before = before_gate.evaluate(call, classification, ctx)
        after = after_gate.evaluate(call, classification, ctx)
        kind = _classify_change(before.outcome, after.outcome, before.reason, after.reason)
        if kind is ChangeKind.UNCHANGED:
            unchanged += 1
        else:
            diffs.append(
                PolicyDiff(
                    call=recorded,
                    kind=kind,
                    before=f"{before.outcome.value}:{before.reason}",
                    after=f"{after.outcome.value}:{after.reason}",
                )
            )
    return ReplayReport(total, unchanged, tuple(diffs))


class ShadowGate:
    """Wraps the live gate and a candidate: the delegate's verdict governs;
    the candidate's verdict is computed and reported (``diffs``) so a stricter
    policy can be measured before it is enforced."""

    def __init__(self, candidate, delegate, on_diff: Callable[[PolicyDiff], None] | None = None) -> None:
        self._candidate = candidate
        self._delegate = delegate
        self._on_diff = on_diff
        self.diffs: list[PolicyDiff] = []

    def evaluate(self, call, classification, context) -> Verdict:
        actual = self._delegate.evaluate(call, classification, context)
        try:
            shadow = self._candidate.evaluate(call, classification, context)
        except Exception:
            return actual  # a shadow must never change the outcome
        kind = _classify_change(actual.outcome, shadow.outcome, actual.reason, shadow.reason)
        if kind is not ChangeKind.UNCHANGED:
            diff = PolicyDiff(
                call=RecordedCall(call.tool_name, call.arguments_json, classification.risk.name),
                kind=kind,
                before=f"{actual.outcome.value}:{actual.reason}",
                after=f"{shadow.outcome.value}:{shadow.reason}",
            )
            self.diffs.append(diff)
            if self._on_diff is not None:
                self._on_diff(diff)
        return actual


class TraceRecorder:
    """A ``ToolCallObserver`` that writes each call+result as one JSONL line —
    the file ``replay_trace`` re-runs."""

    def __init__(self, sink) -> None:
        self._sink = sink  # a text file object or anything with .write(str)

    def on_call(self, call, classification, context) -> None:
        pass

    def on_result(self, call, classification, context, result, error, elapsed_seconds) -> None:
        outcome = "ERROR" if error is not None else "SUCCESS"
        self._sink.write(
            RecordedCall(
                call.tool_name, call.arguments_json, classification.risk.name, outcome, result
            ).to_line()
            + "\n"
        )


@dataclass(frozen=True)
class TraceMismatch:
    tool_name: str
    expected: str | None
    actual: str


def replay_trace(
    tools: dict[str, Callable],
    calls: Sequence[RecordedCall],
) -> list[TraceMismatch]:
    """Re-runs recorded calls against the given tools and reports every result
    that differs from the recording. Tools run ungoverned here — this is the
    regression harness, not production dispatch."""
    mismatches = []
    for recorded in calls:
        fn = tools.get(recorded.tool_name)
        if fn is None:
            mismatches.append(TraceMismatch(recorded.tool_name, recorded.result, "<no tool>"))
            continue
        try:
            raw = fn(**json.loads(recorded.arguments_json or "{}"))
            actual = raw if isinstance(raw, str) else json.dumps(raw)
        except Exception as exc:
            actual = f"<error {type(exc).__name__}: {exc}>"
        if actual != recorded.result:
            mismatches.append(TraceMismatch(recorded.tool_name, recorded.result, actual))
    return mismatches
