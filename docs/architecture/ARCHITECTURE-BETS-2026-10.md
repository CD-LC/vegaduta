# Architecture bets for an agentic platform (2026-10-15)

Status: **proposed**, except where a bet below carries a status line (updated 2026-10-15): bet 13 and bet 1 have a first slice implemented, bet 10 has a first slice. Nothing else here ships today. Each bet builds on something already in the repository
(named in "Builds on"), has a **first thin slice** that can be built and tested alone, and an **acceptance test**.
They are ranked by how much they reduce risk or unlock for an agent platform, not by effort.

Design stance. VegaDuta's strengths are a governed turn path (approval gate, effect journal, audit seals, budgets), schema-per-tenant
isolation, and the Agent SDK contracts. The common thread of the bets is: **make every agent action a first-class, replayable,
policy-checked, budgeted event**, so debugging, evaluation, compliance evidence and cost control all come from one source.

## 1. Turn journal: the agent turn as an event-sourced state machine

**Status: first slice implemented behind a flag** ([ADR-0020](ADR-0020-turn-journal.md)). `TurnJournal` SPI with in-memory and Postgres implementations, hash-chained events, written for every reasoning depth and for delegated or specialist turns, with approval events, when `app.turn-journal.enabled=true` (default `false`); read at `GET /api/agents/{agentId}/turns/{turnId}/journal`. Not done: audit-seal integration, workflow runs, a retention job, CLI. The acceptance test is only partly met (replay compares tool sequence and answer hash; the seal chaining is open).

- **Idea.** Model a turn as explicit states (`INTAKE -> GUARD -> PLAN -> TOOL_LOOP -> VERIFY -> COMMIT`) and append every
  transition, model call, tool call, approval and guardrail hit to one append-only per-turn journal.
- **Why.** Replay, time-travel debugging, deterministic evals, resumable streams and audit evidence stop being separate features.
- **Builds on.** `EffectJournal`, audit seals (Ed25519), `RunCostTimeline`, SDK replay testkit, resumable chat streams.
- **First slice.** A `TurnJournal` SPI plus an in-memory and a Postgres implementation, written by the existing `ChatTurnExecutor`
  without changing behaviour; a CLI/endpoint that renders one turn from the journal.
- **Acceptance.** Replaying a recorded turn against a stubbed model reproduces the same tool calls and final text; journal rows chain into the
  existing audit seals.

## 2. Capability tokens instead of allow-lists (object-capability tools)

**Status: proposed.**

- **Idea.** A run receives short-lived signed capabilities ("send at most 3 emails to this domain, 5 minutes") rather than a standing tool
  allow-list. A tool call without a matching capability cannot execute.
- **Why.** Delegation to sub-agents and remote A2A agents can only narrow authority; the lethal-trifecta analysis becomes a graph check on capabilities.
- **Builds on.** SDK delegated mandates, `TrifectaEnforcementService`, approval tiers, `McpToolPin`.
- **First slice.** Mint a capability per run in `AgentToolProvider.resolveToolsFor` for the HTTP and email tools only; verify it in the dispatch gate.
- **Acceptance.** A delegated sub-agent cannot exceed its parent's capability (property test); revoked capability stops an in-flight run.

## 3. One policy decision point (policy-as-code) with simulation

**Status: proposed.**

- **Idea.** Approvals, autonomy tiers, guardrails, egress, budgets and channel rules are today separate code paths. Put them behind one
  `PolicyDecisionPoint.decide(subject, action, resource, context)` backed by declarative policies (Cedar- or OPA-like), versioned per tenant.
- **Why.** Auditors and operators can read the rules; "what would last week's runs have done under this new policy" becomes a query over the turn journal (bet 1).
- **Builds on.** `HumanApprovalGateService`, `ApprovalPolicyService`, `SsrfGuard.egressPermits`, DPDP consent gate, WhatsApp policy guard.
- **First slice.** Route only the approval decision through the PDP; keep today's rules as the policy set; assert identical decisions on the existing approval tests.
- **Acceptance.** Policy simulation over 1,000 recorded turns reports changed decisions without side effects.

## 4. Agent CI/CD: eval-gated promotion, shadow runs and canaries

**Status: proposed.**

- **Idea.** A new agent version is promoted only if continuous evals on sampled production traces pass; run it in shadow (no side effects) and then as a 5% canary with automatic rollback on guardrail, cost or quality regression.
- **Builds on.** `AgentPromotionGate` SPI, agent versions with STAGING/PRODUCTION labels, evals module, experiments (A/B arms), `agent-scanner` readiness score.
- **First slice.** A promotion gate that requires a passing eval suite id and the scanner score above a threshold; shadow mode that records the candidate's tool calls as `would-have` events.
- **Acceptance.** A deliberately regressed version is blocked by the gate; a canary that breaches the cost budget is rolled back automatically (integration test).

## 5. Memory as a governed substrate (working / episodic / semantic / procedural)

**Status: proposed.**

- **Idea.** Tier memory by purpose, give every item provenance (which turn wrote it, source trust level) and a TTL, and make **forget** propagate to every store.
- **Why.** Prompt-injection persistence and DPDP erasure are the same problem: untrusted data written into memory must be attributable and removable.
- **Builds on.** pgvector memory, Apache AGE bi-temporal graph, `KnowledgeWriteScreen`, DPDP erase/export controls, cache layers.
- **First slice.** A `MemoryItem` envelope (provenance, trust, ttl) written by the existing add-to-knowledge and memory tools; an erase fan-out test that removes one subject from vectors, graph and caches.
- **Acceptance.** Erasing a contact removes it from vectors, AGE edges (closed, not deleted, with an erasure marker) and both response-cache tiers.

## 6. Tenant cells and adaptive admission control

**Status: proposed.**

- **Idea.** Implement the placement abstraction from ADR-0015/0017 starting with *logical* cells (groups of schemas with a connection budget), and add an adaptive per-tenant concurrency limiter on the turn path (gradient/AIMD) driven by the existing open-requests signal.
- **Why.** Noisy neighbours are the failure mode of schema-per-tenant plus long SSE streams; admission control degrades one tenant, not all.
- **Builds on.** HPA metric `http_server_requests_active`, connection-budget gate in Helm, `TenantStatusGuard`, kill switches, ADR-0015/16/17 (proposed).
- **First slice.** A `TenantConcurrencyLimiter` in front of chat turns (off by default), exposed as a metric; load-test with the kind 32-user harness.
- **Acceptance.** One tenant saturating its limit does not raise another tenant's TTFT p99 beyond a stated bound.

## 7. Model gateway as a router with a capability graph

**Status: proposed.**

- **Idea.** Treat models as nodes with capabilities (tools, vision, context, languages), cost and measured quality. Route per turn by a bandit over (cost, latency, eval score), with hedged requests for tail latency and local models first for privacy-tiered data.
- **Builds on.** `LlmGatewayService`, economy model plus `VerifierEscalationRouter`, circuit breakers, Ollama pool, per-agent fallback, `llm_call_log`.
- **First slice.** Record per-(agent, model) eval score and latency; a read-only "recommended route" in the Cost page, then an opt-in router per agent.
- **Acceptance.** On a replayed workload the router meets the same eval score at lower cost than the static route (reported with confidence interval).

## 8. One tool registry with signed manifests (tool supply chain)

**Status: proposed.** The `ToolSource` SPI exists inside the agents module (see [Extending](../extending.md#10-add-a-tool-source)), but it carries no manifest, hash pin or drift check.

- **Idea.** MCP tools, A2A agents, OpenAPI tool sources, user-script tools and SDK tools share one `ToolSource` SPI and a **manifest** (hash pin, scopes, egress hosts, effect class, idempotency, risk). Changes to a manifest need approval, like MCP tool pinning today.
- **Builds on.** MCP tool pinning, `ToolClassification`, tool-sandbox bytecode verifier, A2A egress guard, agent-scanner.
- **First slice.** Generate manifests for existing tool callbacks, store them, and flag drift; expose them in the Tools page.
- **Acceptance.** A tool whose schema or egress changes is blocked until re-approved, for every tool source, not only MCP.

## 9. One durable "run" abstraction

**Status: proposed.**

- **Idea.** Agent jobs, workflows, approvals and long chat turns are all *runs*: durable, resumable, budgeted, observable, cancellable. One run table and timeline; chat turns are short runs.
- **Builds on.** Temporal workflows, agent-job durability ADR, `RunCostTimeline`, run budgets, resumable streams.
- **First slice.** A read-model `run_view` unifying jobs, workflow runs and chat turns for the console "Runs" screen (no write-path change).
- **Acceptance.** Cancel and budget-stop work identically from the unified screen for all three run kinds.

## 10. Observability as a product: the turn inspector

**Status: first slice implemented** ([ADR-0021](ADR-0021-turn-inspector.md)). Read-only inspector page `/agents/turns` over the turn journal with client-side chain verification and "export as test fixture". Not done: prompt diff between versions, guardrail and approval drill-down beyond the journal events, cost per turn, evidence bundle export, loading a fixture in the SDK testkit.

- **Idea.** A UI built on OTel GenAI spans and the turn journal: prompt diff between versions, tool-call tree, guardrail hits, approvals, cost, with **one click to turn a bad turn into a regression test** (replay testkit) and an evidence bundle export.
- **Builds on.** `invoke_agent`/`execute_tool` spans, Langfuse tracing, SDK evidence bundles and trace replay, audit export.
- **First slice.** A read-only inspector page for one turn id from existing logs; "export as test fixture" producing a JSON the SDK testkit can load.
- **Acceptance.** A fixture exported from the UI fails when the same agent regresses.

## 11. Execution venues: cloud, local, on-device, with one governance envelope

**Status: proposed.**

- **Idea.** Cloud models, Ollama and on-device WebGPU are *venues*; policy decides the venue per data class (privacy, latency, cost) and every venue goes through the same budgets, approvals and audit.
- **Builds on.** Edge relay, in-browser WebGPU tier-1 models, local model catalog, `tool bridge`.
- **First slice.** A `venue` field on `llm_call_log` and a policy rule "personal data -> local venue only".
- **Acceptance.** A turn tagged personal data never reaches a cloud provider (test with a spy gateway).

## 12. Red-team in CI

**Status: proposed.**

- **Idea.** Run an adversarial corpus (OWASP Agentic ASI01-10, indirect prompt injection, tool-poisoning, exfiltration attempts) against every agent version on promotion, producing a scored report that feeds bet 4.
- **Builds on.** `agent-scanner`, OWASP mapping, `KnowledgeWriteScreen`, guardrails, injection judge.
- **First slice.** A corpus of 50 attacks and a runner that reports "blocked / leaked / acted" per attack against a test agent.
- **Acceptance.** Known-vulnerable fixture agent scores below the gate; the shipped demo agents score above it.

## 13. Conformance by construction

**Status: first slice implemented** ([ADR-0019](ADR-0019-conformance-by-construction.md)). `ListPagingConformanceTest` (ratcheting allowlist of unpaged list `GET`s) and `scripts/ci/gen-config-reference.py` with `--check` (generated `docs/engineering/CONFIG-REFERENCE.generated.md`, allowlist `scripts/ci/config-reference-allowlist.txt`). Not in the first slice: the `@Unbounded(reason)` annotation (the allowlist is the only escape hatch) and a tenant-isolation coverage check per table.

- **Idea.** Turn conventions into failing tests: every list `GET` has paging params or an explicit `@Unbounded(reason)` annotation; every mutating route has an authorization rule (exists); every table has tenant-isolation coverage; every config property is documented (generated reference).
- **Builds on.** `AuthorizationCoverageTest`, OpenAPI generator test, bean/entity name lints, `docs/configuration.md`.
- **First slice.** An OpenAPI-driven test listing list-returning GETs without paging params (today about 80% lack them) with a ratcheting allowlist.
- **Acceptance.** The allowlist can only shrink; docs/config reference generated from `@ConfigurationProperties` and checked in CI.

## Suggested order

1. Bet 13 (cheap, locks in quality) and bet 1 (turn journal: the foundation for 3, 4, 10).
2. Bets 10 and 4 (visible value: inspector, gated promotion), then 12.
3. Bets 2, 3, 8 (authority model and tool supply chain).
4. Bets 6, 7, 9, 11 (scale, routing, unified runs, venues), then 5 (memory substrate) alongside the DPDP work.

Every bet is a proposal to be written up as an ADR before code. None changes the rule that the agent turn stays in one process (ADR-0014).
