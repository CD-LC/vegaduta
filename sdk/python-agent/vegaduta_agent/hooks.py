"""Lifecycle hooks around the governed pipeline.

Mirrors ``io.vegaduta.agent.api.hook``. A hook observes — or vetoes — the
pipeline at fixed points. Hooks are where org-specific policy lives without
subclassing anything: log every HIGH-risk call, block a tool name at night,
page a human on CRITICAL.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from enum import Enum
from typing import Any, Protocol

from .context import RunContext
from .tool_call import ToolCall


class HookPoint(Enum):
    BEFORE_TOOL_CALL = "BEFORE_TOOL_CALL"
    AFTER_TOOL_CALL = "AFTER_TOOL_CALL"
    ON_TOOL_DENIED = "ON_TOOL_DENIED"
    ON_APPROVAL_REQUESTED = "ON_APPROVAL_REQUESTED"
    ON_APPROVAL_DECIDED = "ON_APPROVAL_DECIDED"
    ON_BUDGET_EXCEEDED = "ON_BUDGET_EXCEEDED"
    ON_GUARDRAIL_VIOLATION = "ON_GUARDRAIL_VIOLATION"
    BEFORE_MODEL_CALL = "BEFORE_MODEL_CALL"
    AFTER_MODEL_CALL = "AFTER_MODEL_CALL"


class HookOutcome(Enum):
    CONTINUE = "CONTINUE"
    BLOCK = "BLOCK"


@dataclass(frozen=True)
class HookDecision:
    outcome: HookOutcome
    reason: str = ""

    @classmethod
    def continue_(cls) -> HookDecision:
        return cls(HookOutcome.CONTINUE)

    @classmethod
    def block(cls, reason: str) -> HookDecision:
        return cls(HookOutcome.BLOCK, reason)

    @property
    def blocks(self) -> bool:
        return self.outcome is HookOutcome.BLOCK


class FailMode(Enum):
    #: A throwing hook is logged and treated as CONTINUE.
    OPEN = "OPEN"
    #: A throwing hook is treated as BLOCK. Default; only choose OPEN with a reason.
    CLOSED = "CLOSED"


@dataclass(frozen=True)
class HookEvent:
    point: HookPoint
    call: ToolCall | None
    context: RunContext
    detail: dict[str, Any]


class Hook(Protocol):
    @property
    def points(self) -> frozenset[HookPoint]: ...

    def on_event(self, event: HookEvent) -> HookDecision: ...


class WebhookHook:
    """A hook that POSTs each event to a URL — Slack, a ticket system, an
    internal policy service — through a caller-supplied transport.

    The SDK deliberately imports no HTTP client (the contract test
    ``test_sdk_makes_no_http_call_to_core`` enforces it), so the transport is
    a port: pass ``sender(url, body_bytes, headers) -> (status, body)``. With
    the standard library that is three lines of ``urllib.request`` in *your*
    code::

        def sender(url, body, headers):
            req = urllib.request.Request(url, data=body, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, r.read(65536)

    A non-2xx response or a transport error is a hook failure: under
    ``FailMode.CLOSED`` (the default) the call is blocked; under ``OPEN`` it
    is logged and the pipeline continues. A response body of
    ``{"block": "reason"}`` blocks with that reason — the endpoint is a policy
    service, not just a notifier.
    """

    def __init__(
        self,
        url: str,
        points: Iterable[HookPoint],
        sender,
        *,
        fail_mode: FailMode = FailMode.CLOSED,
        headers: dict[str, str] | None = None,
    ) -> None:
        self.url = url
        self._points = frozenset(points)
        self._sender = sender
        self.fail_mode = fail_mode
        self.headers = headers or {}

    @property
    def points(self) -> frozenset[HookPoint]:
        return self._points

    def on_event(self, event: HookEvent) -> HookDecision:
        import json

        body = json.dumps(
            {
                "point": event.point.value,
                "tool": event.call.tool_name if event.call else None,
                "runId": event.context.run_id,
                "tenantId": event.context.tenant_id,
                "agentId": event.context.agent_id,
                "detail": event.detail,
            }
        ).encode("utf-8")
        status, payload = self._sender(self.url, body, {"Content-Type": "application/json", **self.headers})
        if status >= 400:
            return HookDecision.block(f"webhook answered {status}")
        try:
            parsed = json.loads(payload or b"{}")
        except ValueError:
            parsed = {}
        reason = parsed.get("block")
        return HookDecision.block(str(reason)) if reason else HookDecision.continue_()
