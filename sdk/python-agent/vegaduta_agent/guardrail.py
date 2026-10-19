"""Content guardrails around tool calls.

Mirrors ``io.vegaduta.agent.api.guardrail``. Guardrails run at four stages —
INPUT (user message), OUTPUT (model reply), TOOL_ARGUMENTS (what the model
wants to pass a tool), TOOL_OUTPUT (what a tool returned, before the model
reads it). The SDK ships two built-ins for the tool stages: secret redaction
and prompt-injection flagging on untrusted tool output.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum
from typing import Protocol

from .errors import GuardrailViolationException


class GuardrailStage(Enum):
    #: The user's message before it reaches the model.
    INPUT = "INPUT"
    #: The model's reply before it reaches the user.
    OUTPUT = "OUTPUT"
    #: The arguments the model wants to pass to a tool.
    TOOL_ARGUMENTS = "TOOL_ARGUMENTS"
    #: What a tool returned, before the model reads it.
    TOOL_OUTPUT = "TOOL_OUTPUT"


class GuardrailAction(Enum):
    BLOCK = "BLOCK"
    WARN = "WARN"
    REDACT = "REDACT"


@dataclass(frozen=True)
class GuardrailViolation:
    guardrail: str
    detail: str


@dataclass(frozen=True)
class GuardrailResult:
    """The verdict of one guardrail pass. ``content`` is the (possibly
    rewritten) text the pipeline continues with."""

    allowed: bool
    content: str
    violations: tuple[GuardrailViolation, ...] = ()
    action: GuardrailAction = GuardrailAction.BLOCK

    @classmethod
    def pass_(cls, content: str) -> GuardrailResult:
        return cls(allowed=True, content=content)

    @classmethod
    def block(cls, content: str, guardrail: str, detail: str) -> GuardrailResult:
        return cls(
            allowed=False,
            content=content,
            violations=(GuardrailViolation(guardrail, detail),),
            action=GuardrailAction.BLOCK,
        )

    @classmethod
    def warn(cls, content: str, guardrail: str, detail: str) -> GuardrailResult:
        return cls(
            allowed=True,
            content=content,
            violations=(GuardrailViolation(guardrail, detail),),
            action=GuardrailAction.WARN,
        )


class ContentGuardrail(Protocol):
    """Inspects content at one or more stages. Must be cheap: it runs inline."""

    @property
    def name(self) -> str: ...

    @property
    def stages(self) -> frozenset[GuardrailStage]: ...

    def check(self, content: str, stage: GuardrailStage) -> GuardrailResult: ...


# --- built-ins ---------------------------------------------------------------

_SECRET_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        "pem-private-key",
    ),
    (
        re.compile(
            r"\b(?:sk|pk|ak|xox[baprs]|ghp|gho|github_pat|glpat|sk_live|sk_test|rk_live|whsec|AIza|ya29)[-_][A-Za-z0-9_-]{8,}\b"
        ),
        "provider-api-key",
    ),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "aws-access-key-id"),
    (
        re.compile(
            r"(?i)\b(?:api[_-]?key|secret|token|password|passwd|authorization)\b\s*[:=]\s*['\"]?[^\s'\"]{8,}"
        ),
        "key-value-secret",
    ),
    (re.compile(r"\bBearer\s+[A-Za-z0-9._~-]{16,}"), "bearer-token"),
)

REDACTED = "[REDACTED]"


class SecretRedactionGuardrail:
    """Replaces text that looks like a credential with ``[REDACTED]``. Runs on
    TOOL_OUTPUT (a tool leaking a key must never reach the model's context) and
    TOOL_ARGUMENTS (a secret must not be sent to a tool that would exfiltrate
    it — with ``block_arguments=True`` those calls are refused instead)."""

    def __init__(self, block_arguments: bool = True) -> None:
        self._block_arguments = block_arguments

    @property
    def name(self) -> str:
        return "secret-redaction"

    @property
    def stages(self) -> frozenset[GuardrailStage]:
        return frozenset({GuardrailStage.TOOL_ARGUMENTS, GuardrailStage.TOOL_OUTPUT})

    def check(self, content: str, stage: GuardrailStage) -> GuardrailResult:
        found = [label for pattern, label in _SECRET_PATTERNS if pattern.search(content)]
        if not found:
            return GuardrailResult.pass_(content)
        detail = "matched: " + ", ".join(sorted(set(found)))
        if stage is GuardrailStage.TOOL_ARGUMENTS and self._block_arguments:
            return GuardrailResult.block(
                content, self.name, "credential material in tool arguments — " + detail
            )
        redacted = content
        for pattern, _ in _SECRET_PATTERNS:
            redacted = pattern.sub(REDACTED, redacted)
        return GuardrailResult(
            allowed=True,
            content=redacted,
            violations=(GuardrailViolation(self.name, detail),),
            action=GuardrailAction.REDACT,
        )


_INJECTION_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(
        r"(?i)\bignore (?:all |any |the )?(?:previous|prior|above|earlier) (?:instructions|prompts|rules)\b"
    ),
    re.compile(r"(?i)\byou are now\b.*\b(?:mode|dan|unrestricted|jailbreak)\b"),
    re.compile(
        r"(?i)\b(?:reveal|print|show|repeat|output)\b.{0,40}"
        r"\b(?:system prompt|system instructions|initial prompt)\b"
    ),
    re.compile(r"(?i)\b(?:new|override)\s+(?:instructions|directives|system)\s*:"),
    re.compile(r"(?i)</?(?:system|assistant|im_start|im_end|instruction)\s*>"),
    re.compile(r"(?i)\bdo not (?:follow|obey|listen to)\b.{0,40}\b(?:previous|user|operator)\b"),
)


class InstructionLikeTextGuardrail:
    """Flags tool output that reads like an attempt to re-program the agent
    ("ignore previous instructions", fake system tags, "you are now ...").

    Action is WARN, not BLOCK: a page a web tool fetched containing these
    phrases is the normal case, and the model should see the content with the
    flag noted — the audit record is what makes the flag reviewable.
    """

    @property
    def name(self) -> str:
        return "instruction-like-text"

    @property
    def stages(self) -> frozenset[GuardrailStage]:
        return frozenset({GuardrailStage.TOOL_OUTPUT})

    def check(self, content: str, stage: GuardrailStage) -> GuardrailResult:
        hits = sum(1 for p in _INJECTION_PATTERNS if p.search(content))
        if hits == 0:
            return GuardrailResult.pass_(content)
        return GuardrailResult.warn(
            content,
            self.name,
            f"{hits} instruction-like pattern(s) in tool output — possible prompt injection",
        )


def run_guardrails(
    content: str, stage: GuardrailStage, guardrails: list[ContentGuardrail]
) -> GuardrailResult:
    """Run every guardrail that covers ``stage`` over ``content`` in order.
    A BLOCK raises ``GuardrailViolationException``; WARN/REDACT accumulate and
    the last content wins."""
    violations: list[GuardrailViolation] = []
    current = content
    action = GuardrailAction.WARN
    for g in guardrails:
        if stage not in g.stages:
            continue
        result = g.check(current, stage)
        violations.extend(result.violations)
        current = result.content
        if not result.allowed:
            raise GuardrailViolationException(
                f"guardrail {g.name} blocked {stage.value} content: "
                + "; ".join(v.detail for v in result.violations)
            )
        if result.action is GuardrailAction.REDACT:
            action = GuardrailAction.REDACT
    return GuardrailResult(allowed=True, content=current, violations=tuple(violations), action=action)
