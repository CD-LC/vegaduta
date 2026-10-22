"""Telemetry around tool calls.

Mirrors ``io.vegaduta.agent.api.observe.ToolCallObserver``. Observers get one
call at dispatch and one at completion with the outcome; they never alter the
result. The optional ``otel`` extra ships an adapter that emits
``execute_tool`` spans in the OpenTelemetry GenAI convention, matching the
platform's ``core/chat`` instrumentation.
"""

from __future__ import annotations

import time
from typing import Protocol

from .context import RunContext
from .risk import ToolClassification
from .tool_call import ToolCall


class ToolCallObserver(Protocol):
    def on_call(self, call: ToolCall, classification: ToolClassification, context: RunContext) -> None: ...

    def on_result(
        self,
        call: ToolCall,
        classification: ToolClassification,
        context: RunContext,
        result: str | None,
        error: BaseException | None,
        elapsed_seconds: float,
    ) -> None: ...


def _noop_on_call(call, classification, context) -> None:
    return None


class TimedCall:
    """Small helper observers can use to measure elapsed time."""

    def __init__(self) -> None:
        self.started = time.monotonic()

    @property
    def elapsed(self) -> float:
        return time.monotonic() - self.started


class OtelToolCallObserver:
    """Emits one ``execute_tool`` span per tool call when
    ``opentelemetry-api`` is installed (the ``otel`` extra).
    Attribute names follow the OTel GenAI ``execute_tool`` conventions."""

    def __init__(self, tracer_name: str = "vegaduta.agent") -> None:
        try:
            from opentelemetry import trace  # noqa: PLC0415
        except ImportError as exc:
            raise ImportError(
                "OtelToolCallObserver needs opentelemetry-api (the otel extra): pip install opentelemetry-api"
            ) from exc
        self._trace = trace
        self._tracer = trace.get_tracer(tracer_name)
        self._spans: dict[str, tuple] = {}

    def on_call(self, call, classification, context) -> None:
        span = self._tracer.start_span(f"execute_tool {call.tool_name}")
        span.set_attribute("gen_ai.operation.name", "execute_tool")
        span.set_attribute("gen_ai.tool.name", call.tool_name)
        span.set_attribute("gen_ai.tool.call.id", call.call_id)
        span.set_attribute("vegaduta.risk", classification.risk.name)
        if context.tenant_id:
            span.set_attribute("vegaduta.tenant.id", context.tenant_id)
        if context.agent_id:
            span.set_attribute("gen_ai.agent.id", context.agent_id)
        self._spans[call.call_id] = (span, time.monotonic())

    def on_result(self, call, classification, context, result, error, elapsed_seconds) -> None:
        entry = self._spans.pop(call.call_id, None)
        if entry is None:
            return
        span, _started = entry
        if error is not None:
            span.set_status(self._trace.Status(self._trace.StatusCode.ERROR, str(error)))
            span.record_exception(error)
        span.set_attribute("gen_ai.tool.duration_s", round(elapsed_seconds, 6))
        span.end()
