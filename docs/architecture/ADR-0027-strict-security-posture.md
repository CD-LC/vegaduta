# ADR-0027: A `strict` security posture profile, and the path to making it the default

Status: Proposed. The profile file is being written by Phase 0 work package A (`application-strict.yml`,
a posture test under `core/app/.../posture/`). This ADR records the decision and the owner-decision points;
it does not change any default. **Nothing here flips a default without the owner's explicit decision.**

## Context

The base profile ships several safety controls in a permissive mode so that existing installs keep
behaving as before. Each value below was read from `core/app-config/src/main/resources/application.yml`
on 2026-11-03 (see also [the permissive-defaults table](../security/owasp-mapping.md#defaults-that-are-permissive)):

| Control | Key | Base default | Personal profile |
|---|---|---|---|
| Untrusted-content taint | `app.agents.taint.mode` | `observe` | `enforce` (`application-personal.yml`) |
| MCP tool pinning | `app.mcp.tool-pinning.mode` | `warn` | not overridden (no match in the file) |
| Lethal trifecta | `app.tools.classification.trifecta-enforcement` | `warn` | not overridden |
| Trifecta covers Auto-select agents | `app.tools.classification.trifecta-analyse-auto-select` | `false` | not overridden |
| Unclassified tools | `app.tools.classification.unclassified-action` | `allow` | not overridden |
| Gate-exempt tools | `app.tools.classification.confirmation-gate-exempt-tools` | 7 tools (send, code, browser, Gmail, Calendar) | not overridden |
| Approval expiry | `app.approvals.expiry.ttl` | `0` (never) | not overridden |
| Audit seal | `app.audit.seal.enabled` | `false` | not overridden |
| STDIO MCP sidecar | `mcp-stdio-sidecar.enabled` | `false` | n/a (OS sandbox instead, per `docs/configuration.md`) |
| Spend and abuse controls on Redis error | `GovernanceFailOpenPolicy.SPEND_CONTROLS_FAIL_OPEN` | `true`, a Java constant | n/a |

Already strict in the base profile: guardrails `fail-mode=closed`, `implementation=semantic`,
`skills.supply-chain.enforce=true`, `tool-arg-policies.enabled=true`, `confirmation-gate=true`,
`supervised.gate-read-only=true`, A2A client off and signed cards required.

The taint default is an **explicit owner decision of 2026-10-23** (comment above `app.agents.taint` in
`application.yml`): ship `observe` so that the observe period the defaults review asks for runs by default,
with behaviour unchanged. This ADR does not reverse it; it provides the opt-in and the criteria for
reconsidering it.

Why now: the "learning agents" roadmap makes every permissive control more expensive. A page the agent
reads can become a memory, a memory a prompt, a drafted skill a procedure. See the
[learning-agents threat model](../security/threat-model-learning-agents.md).

## Decision

1. Add a Spring profile `strict` (`application-strict.yml`), layered **on top of** the base profile, that
   sets every row in the table above to its stricter value:
   `taint.mode=enforce`, `tool-pinning.mode=enforce`, `trifecta-enforcement=require-approval`,
   `trifecta-analyse-auto-select=true`, `unclassified-action=require-approval`, a shorter
   `confirmation-gate-exempt-tools`, a positive `approvals.expiry.ttl`, and `audit.seal.enabled=true`
   (which still needs a pinned signing key, so the profile must fail loudly at boot, not silently skip, if none is set; that boot check is package A's to design).
2. A posture test (package A) asserts the profile's values, so a later edit cannot quietly loosen it.
3. `strict` is **opt-in** (`SPRING_PROFILES_ACTIVE=...,strict`) when first shipped. No default changes.
4. `strict` and `personal` are mutually exclusive in intent: `personal` is one person who is also the approver
   and chooses convenience (`application-personal.yml` header says so). Activating both is a startup error
   (to be implemented by package A; this ADR does not verify it exists).
5. `SPEND_CONTROLS_FAIL_OPEN` is a compile-time constant, not a key, so a profile cannot change it. Making it
   configurable (and `false` under `strict`) is a code change requested of package F, not part of this ADR.

## Migration path to default-on for non-personal installs

Each step is a separate owner decision. None is automatic.

| Stage | What changes | Gate to proceed |
|---|---|---|
| 0 (now) | `strict` available, opt-in. Base unchanged | Posture test green; red-team suite (package H) passes against `strict` |
| 1 | Operators run `strict` in staging; metrics collected from the existing observe counters (`agent.tool.classification.unclassified_allowed`, `confirmation_enforced`, taint "would have required approval" counts, `mcp.tools.changed` events) | A written count of what `strict` would have blocked on real traffic, reviewed by the owner |
| 2 | Release notes announce that the next minor flips defaults for non-personal installs; `CHANGELOG.md` entry under a breaking-change heading; opt-out documented as `...,permissive` or explicit key overrides | Owner decision recorded in this ADR's log below |
| 3 | Base profile values change (taint `enforce` first, as it has the clearest evidence; then pinning; then unclassified); `personal` and `lite` pinned to their current values explicitly | Per-key owner decision; each flip is its own commit so it can be reverted alone |
| 4 | `strict` becomes a no-op alias, then is removed in a later release | None needed |

Order of flips, lowest user disruption first: `tool-pinning=enforce` (only affects a changed remote
server), `approvals.expiry.ttl` (an unattended request closes), `audit.seal.enabled` (needs key work),
`taint=enforce` (adds approvals after a page read, the most visible change), `unclassified-action`
(a new MCP tool needs classifying before it acts, the most disruptive for integrators).

## Owner-decision points

1. Reconsider the 2026-10-23 taint decision, and when (evidence needed: observe counters from stage 1).
2. Whether `strict` is the default for hosted/multi-tenant only, or for all non-personal installs.
3. The default expiry TTL for approvals (value is a product call; this ADR proposes none).
4. Which tools stay gate-exempt under `strict` (the current seven exist because unattended channel
   responders, schedules and workflows would stall with no human present; `application.yml` comment).
5. Whether `unclassified-action` becomes `require-approval` or `deny`.
6. Whether to make `SPEND_CONTROLS_FAIL_OPEN` configurable.

## Consequences

- Positive: a documented, tested configuration an operator can adopt today; a measured path rather than a
  surprise flip; the permissive set is listed in one place.
- Negative: more human approvals, and unattended workflows that use gate-exempt tools or unclassified
  tools can stall or be refused under `strict`; operators must classify new MCP tools; a pinned signing
  key becomes mandatory for sealing.
- A profile adds a second configuration state to test. The posture test and CI must run the boot check
  (`scripts/ci/boot-check.sh`) with `strict` active, or it will rot.
- It does not close the structural gaps in the OWASP page (regex-only layers, no output-side leak filter).
  `strict` is stricter defaults, not new controls.

## Rollback

- Profile level: remove `strict` from `SPRING_PROFILES_ACTIVE`. No migration or stored data depends on it.
- Per key: every key in the table has an environment override (`APP_AGENTS_TAINT_MODE`,
  `APP_MCP_TOOL_PINNING_MODE`, `TOOL_TRIFECTA_ENFORCEMENT`, `TOOL_UNCLASSIFIED_ACTION`,
  `TOOL_CONFIRMATION_GATE_EXEMPT`, `APP_APPROVALS_EXPIRY_TTL`, `AUDIT_SEAL_ENABLED`, from `application.yml`),
  so one control can be relaxed without leaving `strict`.
- After a default flip (stage 3): revert that key's commit; operators who need the old behaviour set the
  environment override. Seals already written are immutable and stay valid.
- A request stored as expired is REJECTED by `system:approval-expiry` and cannot be re-approved
  (`application.yml` comment under `app.approvals.expiry`); rolling the TTL back does not revive it.

## Not verified

That `application-strict.yml` exists or what it contains (package A, not in this branch); that `personal` and
`strict` conflict is detected; counters named above exist with those exact names beyond the ones quoted
from `AgentToolGovernance` and `application.yml`; any behaviour on a running stack.

## Log

| Date | Decision | By |
|---|---|---|
| 2026-10-23 | Taint ships `observe` in the base profile (pre-existing; quoted from `application.yml`) | owner |
| 2026-11-03 | This ADR proposed; no default changed | docs package I |
