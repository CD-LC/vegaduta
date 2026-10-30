# ADR-0027: Agent mandates and delegated authority

| | |
|---|---|
| **Status** | Proposed. Nothing in this ADR is built; no code, migration or configuration key it names exists unless it is marked as existing |
| **Date** | 2026-10-28 |
| **Deciders** | Platform Engineering (maintainers) |
| **Related** | [ADR-0013 embeddable agent SDK](ADR-0013-embeddable-agent-sdk.md), [ADR-0023 agents module decomposition](ADR-0023-agents-module-decomposition.md), [ADR-0024 tenant-aware executors](ADR-0024-tenant-aware-executors.md), [OWASP mapping](../security/owasp-mapping.md) (ASI03, ASI08, ASI10), [standards alignment pack](../compliance/README.md), `AGENTS.md` rules 6, 9, 12, 13 |

## Context

An agent acts with authority someone gave it. Today that authority is spread over five mechanisms
that do not know about each other, and one question has no single answer: *under whose authority,
within what limits and until when did this agent make this call, and can it be stopped now?*

What exists, verified in the tree on 2026-10-28:

| Mechanism | Where | What it limits | What it does not do |
|---|---|---|---|
| **Payment mandate** (money) | `core/payments/.../PaymentMandate.java`, `MandateEnforcer.java`, `MandateRevocationCache.java`, `MandateSigningService.java`; tests `MandateEnforcerTest`, `MandateSigningServiceTest` | A human-granted, Ed25519-signed, immutable letter: scope (collect / pay / both), per-transaction, daily and monthly caps via a ledger with row lock, payee and merchant-category allow-lists, validity window, status, Redis revocation tombstone so in-flight calls die within about a second. A change is revoke plus re-grant | Money only. Gated by the `BILLING_PAYMENTS` edition feature. Says nothing about non-payment tools |
| **Delegation grant** (outbound tokens) | `core/agents/.../trust/DelegationGrant.java`, `DelegationTokenService.java`, `DelegationController.java` (`/api/agents/{agentId}/delegations`); tests `DelegationTokenServiceTest`, `DelegationTokenAuthFilterTest` | A user lets an agent act on their behalf toward one named audience: scopes, token lifetime cap, expiry, optional wallet and spend cap in minor units for `pay:*` scopes, revocable, each token recorded by `jti`. RFC 8693 semantics (`sub` is the agent, `act` the delegator) | It authorises *tokens to a counterparty*. It is not consulted when an agent dispatches an in-platform tool. No attenuation: there is no parent grant |
| **Budgets** | `core/agents-budget/.../SpendCapService.java`, `PerUserBudgetService.java`; `core/agents/.../runbudget/AgentRunBudgetService.java`, `collab/RunBudgetCaps.java`; key `app.guardrails.run-budget.usd` (default 2.0); tests `SpendCapServiceTest`, `PerUserBudgetServiceTest`, `AgentRunBudgetServiceTest` | Dollar ceilings on LLM spend: per tenant, per user, per run, and a per-agent run cap that can only lower the platform cap | Keyed to tenant, user or run, not to an authority. A budget does not expire or get revoked as a unit and is not inherited by a sub-agent |
| **Tool governance** | `core/agents-tools/.../AgentToolGovernance.java`, `core/guardrails/.../GuardedToolCallback.java`, `ToolDispatchGate.java`; `Agent.allowedToolNames`; autonomy tiers `AgentAutonomyTier.java` | Which tools an agent may call, which need a person | A static per-agent list: no expiry, no spend, no derivation, no revocation separate from editing the agent |
| **Outbound identity** | `core/agents/.../attestation/AgentAttestationService.java` (test `AgentAttestationServiceTest`) | A short-lived Ed25519 JWT naming tenant and agent on outbound calls | Signed with the one deployment-wide operator key (`app.a2a.signing.*`); an unpinned instance uses an ephemeral per-boot key. It is an identity *claim*, not a per-agent credential |

And in the SDKs (not the platform): `sdk/python-agent/vegaduta_agent/mandates.py` (and the Java
twin `sdk/java/agent-api/.../mandate/Mandate.java`, `sdk/java/agent-core/.../mandate/MandateGate.java`).

- A `Mandate` is one link of a delegation chain: issuer, subject (the agent), optional principal,
  a set of allowed tool patterns (glob), a risk ceiling (`RiskLabel`), depth and max depth, an
  expiry, the parent id and a SHA-256 digest over the canonical fields plus the parent digest.
- `Mandate.derive` can only **attenuate**: fewer tools, a risk ceiling at or below the parent's,
  an expiry at or before the parent's, depth within `max_depth`. `verify_chain` recomputes the
  digests from root to leaf.
- `MandateGate` is a `ToolDispatchGate`: with `require_mandate` it denies a call with no mandate;
  it always denies a malformed one, a digest mismatch, a subject mismatch, an expired mandate, a
  tool not permitted and a call whose risk exceeds the ceiling. Reason codes are stable
  (`mandate:expired`, `mandate:tool-not-permitted`, ...).
- By its own docstring a mandate is an **in-process capability, not a token**: it carries no
  signature and proves nothing to a remote party. It has **no spend ceiling and no revocation**
  (expiry only). Its tests are in `sdk/python-agent/tests/test_parity.py` and, for the Java twin,
  `MandateTest` and `MandateGateTest`.

The gaps this leaves, which the [standards alignment pack](../compliance/README.md) keeps
meeting from different directions (ISO/IEC 42001 A.9, NIST AI RMF Govern/Manage, EU AI Act
Arts. 14 and 26, SOC 2 CC6):

1. No single record of an agent's authority. An auditor must read the agent configuration, the
   delegation grants, the payment mandates and three budget settings.
2. No way to give a sub-agent *less* than the parent has. Delegation depth is capped at 1 with
   at most 8 team branches (see the OWASP mapping), but a delegated call is not bound to a
   narrower authority than the delegator's.
3. Spend limits are not tied to a grant of authority, so "this agent may spend up to X until
   Friday on this task" cannot be expressed outside payments.
4. Revocation exists for payments and delegation grants, not for "stop this agent's tool use now"
   short of editing the agent or the tenant-level kill switch (platform-admin-only).
5. No per-agent credential. Every agent's attestation is signed by the same key.

## Decision drivers

- **Hot path stays in-process** (AGENTS.md rule 6). The check runs inside the turn, next to
  `GuardedToolCallback`; no network hop between platform modules.
- **Tenant carried across threads** (rule 12). Mandate lookups run on turn threads and on
  `delegate_parallel` branches; they must use `TenantScope`, not ad-hoc thread-locals.
- **Fail closed, introduce quietly.** Taint and tool pinning shipped as `observe` / `warn` and
  stayed advisory; a mandate check should measure first, but the decision to enforce must be a
  dated one.
- **Do not break what exists.** Payment mandates and delegation grants are released, signed and
  immutable; a migration must not edit or renumber them (rule 3).
- **Names stay unique** (rule 9): `PaymentMandate` and `MandateEnforcer` already exist in
  `core/payments`; a new concept needs new class, bean and table names.
- **Architecture baselines only shrink** (rule 13): new code adds no package cycle and no
  controller-to-repository edge.
- **Evidence by default.** Every issue, derivation, denial and revocation is an audit event, so it
  is covered by the audit seal when that is enabled.

## Options

### Option A: Status quo, document the five mechanisms

Write one page that explains how the mechanisms combine.

- For: no code risk, no migration.
- Against: the gaps above stay. Five records still have to be joined by an auditor, and a
  sub-agent still cannot be given less than its parent.

### Option B: Port the SDK mandate to the platform as a tool gate only

Add an in-process `MandateGate` (scope, risk ceiling, expiry, depth, derivation) to the platform
`ToolDispatchGate` chain; store mandates per tenant.

- For: closes gaps 2 and 4 for tools; the model and tests exist in two languages; small.
- Against: leaves spend (gap 3) and identity (gap 5) open, and creates a *sixth* mechanism beside
  payment mandates and delegation grants unless it is built to absorb them.

### Option C: A unified platform agent mandate (recommended)

One `AgentMandate` record per grant of authority, with the SDK's attenuating chain, plus a spend
ceiling, explicit revocation and optional per-agent workload identity, enforced at tool dispatch.
Payment mandates and delegation grants stay as they are and are *referenced* by a mandate (a
mandate can carry a `payment_mandate_id` or `delegation_grant_id` as the money or outbound-token
part of its authority) before any consolidation is considered.

- For: one answer to "under whose authority"; closes gaps 1 to 5; reuses proven pieces (the SDK
  chain and gate, the payment ledger and Redis tombstone pattern, the atomic Redis scripts the
  spend cap already uses, `EncryptionService` for key custody).
- Against: the largest change; a new module, tenant migration and enforcement mode; a real
  question of how mandates interact with autonomy tiers and approvals (below).

### Option D: Delegate to an external policy engine and identity provider

Express authority as policy in OPA or Cedar and as tokens from an external workload-identity
provider (SPIFFE/SPIRE or a cloud IdP).

- For: standard tooling; separation of duties.
- Against: adds a network hop or sidecar to the hot path (rule 6), a new operational dependency for
  every install including the single-owner personal profile, and does not give attenuating chains
  for free. Worth keeping as an *integration point* for workload identity (Phase 4), not as the
  core.

## Recommendation

**Option C, staged, behind a mode switch that starts at `observe`.** Phase 1 is Option B done in
a way that does not preclude the rest.

### Proposed model

An `AgentMandate` (proposed entity `AgentMandate`, table `agent_mandate`; the names do not exist
today) carries:

| Field | Meaning | Source of the idea |
|---|---|---|
| `id`, `tenant` (from `TenantContext`, never from a request body), `subject_agent_id` | whose authority and for which agent | SDK `Mandate.subject` |
| `issuer` (a user, or a parent mandate), `principal` (the human the chain traces to) | accountability | SDK `issuer`, `principal`; delegation `act` claim |
| `parent_id`, `depth`, `max_depth` | attenuating chain | SDK |
| `allowed_tools` (exact or glob patterns), `max_risk` (the existing `ToolClassification` risk) | scope | SDK; `AgentToolGovernance` |
| `spend_ceiling_usd` (LLM and tool spend under this mandate), `spend_ceiling_minor` + `wallet_id` (optional, references the money part) | spend ceiling | `SpendCapService` / `RunBudgetCaps`; `DelegationGrant.spendCapMinor` |
| `valid_from`, `expires_at` | expiry | SDK; `PaymentMandate` validity window |
| `status` (`ACTIVE`, `SUSPENDED`, `REVOKED`, `EXPIRED`), `revoked_at`, `revoked_by`, `revoke_reason` | revocation | `DelegationGrant`, `MandateStatus` |
| `digest`, `signature` | tamper evidence: SHA-256 chain digest as in the SDK, Ed25519 signature over the canonical form as in `MandateSigningService` | SDK + payments |

Rules carried over unchanged from the SDK: a derived mandate can only name a subset of the
parent's tools, a risk ceiling at or below it, an expiry at or before it, and now also a spend
ceiling at or below the parent's *remaining* budget; a derived mandate cannot exceed `max_depth`.
Immutable after issue; a change is revoke plus re-grant (as `PaymentMandate` and `DelegationGrant`
already do), which keeps the audit record honest.

### Enforcement

- A `MandateToolGate` implements the existing `ToolDispatchGate` SPI (`core/guardrails/.../ToolDispatchGate.java`) and
  is consulted by `GuardedToolCallback` like the other gates. It checks, in order: revocation
  tombstone (Redis, fail-soft to the database row as `MandateRevocationCache` does), status,
  validity window, tool pattern, risk ceiling, then reserves spend atomically (a Redis script,
  as `SpendCapService` does, with the database row as the authority).
- Mode `app.mandates.mode` (**proposed key**): `off` | `observe` | `enforce`. `observe` records
  what would have been denied as an audit event and a counter and changes no behaviour. The
  default at first release is `off`; the flip to `observe` and then `enforce` is a dated decision
  in the CHANGELOG, not an accident of a default.
- A mandate **narrows, never widens**. It is checked *in addition to* `Agent.allowedToolNames`,
  the approval gate and guardrails; a mandate that permits a tool does not skip approval. This
  keeps the existing tests' meaning intact and avoids a mandate becoming a bypass.
- Sub-agents: when `delegate_parallel` or an A2A call hands work on, the caller derives a mandate
  for the branch. Phase 3.
- Fail closed: a malformed or unreadable mandate denies when the mode is `enforce`, as
  `MandateGate` does in the SDK.

### Workload identity (Phase 4)

Replace the single shared operator key as the *agent's* identity with a per-agent Ed25519 key
pair created with the agent, private half encrypted with `EncryptionService`, public half served
per tenant at a JWKS URL, `kid` carrying agent id and key version. `AgentAttestationService` and
mandate signatures use it. Keep the operator key for platform-level signing (agent cards, audit
seals). External identity (SPIFFE/SPIRE, cloud workload identity) plugs in behind a port, as the
Option D integration; it is not required.

### Audit

Audit events (proposed names, written through `AuditLogService`): `mandate.issued`,
`mandate.derived`, `mandate.denied` (with the stable reason code), `mandate.observed_denial`
(in `observe` mode), `mandate.spend_reserved`, `mandate.revoked`, `mandate.expired`. They fall
under the audit seal when `app.audit.seal.enabled` is on and appear in the signed audit export.
The turn journal records the mandate id with each tool step when enabled.

## Rollout

Each phase is releasable alone and starts at `observe`. Nothing here is scheduled.

| Phase | Content | Exit criteria |
|---|---|---|
| 0 | This ADR accepted or rejected; decide module placement (likely a new `agents-mandates` module over ports in `agentspi`, per ADR-0023); claim the area in `docs/maintainers/agent-coordination.md` | Decision recorded |
| 1 | `AgentMandate` entity and tenant migration (next free Flyway version at the time; `bash scripts/ci/flyway-lint.sh`); issue, revoke, list API under a new base path; `MandateToolGate` for tool scope, risk ceiling, expiry, revocation; mode `off`/`observe`/`enforce`; audit events; console card listing an agent's active mandates | Unit tests per reason code; a cross-tenant test; `AsyncHopTenantScopeRulesTest` and the `app` structure tests green; one `observe` period reviewed |
| 2 | Spend ceiling: per-mandate USD reservation and rollup; optional link to a payment mandate or delegation grant; budget exhaustion reuses the existing stop reasons | Concurrency test (two parallel reservations cannot jointly exceed the ceiling). `MandateEnforcer` serialises charges with a row lock by design (its javadoc); a concurrency test for it was not found, so this phase adds one for both |
| 3 | Derivation across `delegate_parallel` and outbound A2A: a branch runs under a derived mandate; the A2A client sends the mandate id (and digest) as a claim | Attenuation property tests; chain verification test; conformance page updated |
| 4 | Per-agent workload identity and key rotation; JWKS per tenant; attestation and mandate signatures move to it | Key-rotation test; documented recovery when `APP_ENCRYPTION_KEY` is wrong |
| 5 | Decide whether to fold delegation grants and payment mandates into mandate kinds | A separate ADR |

Per AGENTS.md: configuration goes in `deploy/compose/.env.example` and `docs/configuration.md`,
user-visible behaviour in `CHANGELOG.md`, and the touched modules' tests plus the `app` module's
structure and wiring tests run before a PR.

## Consequences

- One place answers "whose authority"; audit and compliance rows (ISO 42001 A.9, EU AI Act Art. 14
  and 26, SOC 2 CC6) can cite a mandate id instead of a configuration screenshot.
- A new table, module and mode switch to maintain; the SDK and platform must keep the canonical
  form byte-compatible (the SDK already ships a cross-language parity test, `test_parity.py`).
- Mandates add one lookup to every tool call. It must be a cached, tenant-scoped read with a Redis
  tombstone, measured before enforcement (the turn journal ADR makes the same point about
  synchronous writes).
- Risk: operators may read "a mandate permits the tool" as "the tool is safe". The design says the
  opposite; the console copy and docs must too.

## Not decided here

- Whether a mandate can be issued by an agent to another *tenant's* agent (A2A across tenants).
  Default answer: no.
- The shape of the console and the permission that may issue a mandate (likely a new `PERM_*`
  distinct from the approvals permission, so issuing authority and approving a call are separate
  duties).
- Whether the distinct-approver gap in the approval flow (an admin can approve their own request;
  see the pack's Known gaps) is fixed here or separately. It should be separate.
- Pricing and edition gating.

## Not verified

- The behaviour of the existing mechanisms was read in code and their tests named; no test was
  re-run for this ADR and nothing was run on a stack.
- The Python and Java SDK mandates were read, not compared byte-for-byte; the parity test is the
  source for that claim.
- The proposed key, table, entity and event names were not checked against anything in flight on
  other branches. Check `docs/maintainers/agent-coordination.md` before starting.
