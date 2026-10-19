---
title: "Tool dispatch gate allows the call when its lookup throws"
labels: [security, owasp, high]
status: draft
owasp: ASI02, LLM06
---

# Tool dispatch gate allows the call when its lookup throws

OWASP: ASI02, LLM06. Source: [docs/security/owasp-mapping.md](../../docs/security/owasp-mapping.md). Rated High by the author's own judgement, not by a scoring system.

## Problem

`GuardedToolCallback.checkDispatchGate` catches `RuntimeException` from `ToolDispatchGate.check` and returns `allow()` ("allowing the call (fail-open)"). The gate is where `QUARANTINED`/`UNCLASSIFIED` tools are denied and approvals are required (`AgentToolGovernance.decide`). A database hiccup or a bug in classification lookup therefore turns the deny list into an allow-all for that call. Tool-argument policies are also off by default (`app.tool-arg-policies.enabled`).

## Proposed fix

- Fail closed for tools whose static classification is not `TRUSTED_INTERNAL` (deny with a retryable message); keep fail-open only for read-only built-ins, behind `app.tools.classification.gate-failure-mode=open|closed` (default `closed`).
- Count gate failures in a Micrometer counter and log at ERROR once per minute.
- Decide and document the default for `app.tool-arg-policies.enabled`.

## Tests

Gate throws -> external/unclassified tool denied, read-only built-in allowed (per mode); counter increments; existing `GuardedToolCallbackGovernanceTest` cases stay green.

## Status

None yet.
