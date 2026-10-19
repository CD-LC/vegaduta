# ADR — Durable execution for agent jobs: Temporal vs. Postgres

- **Date:** 2026-08-17
- **Status:** Accepted
- **Scope:** `core/agents` — `agent_job` execution and recovery (autonomous-agents
  workstream)

---

## Context

An agent job outlives the HTTP request that asked for it. It runs on a bounded
worker pool inside `core` (`AgentJobExecutor`: 4 platform threads, per-tenant cap
2) and can occupy that worker for minutes. When the `core` container is
restarted — a deploy, an OOM, a pod eviction, a SIGKILL — every in-flight job's
thread disappears while its `agent_job` row is still `RUNNING`.

Two properties are required:

1. A job interrupted by a restart must **resume or fail loudly**. It must never
   sit in `RUNNING` forever with nothing executing it. A stuck `RUNNING` row is
   not merely cosmetic: V208's `uq_agent_job_live_per_schedule` unique index
   makes a live job the schedule overlap guard, so one abandoned row **mutes
   that schedule** until the row reaches a terminal state.
2. Recovery must not double-charge the requester's budget, and must not re-run a
   tool call that already had a side effect.

The platform already contains a durable-execution engine — Temporal, used by
`core/workflow` — so "just use the thing we already have" is the obvious first
proposal and has to be answered with evidence rather than taste.

---

## Options considered

### Option A — Route agent jobs through Temporal

Model each job as a Temporal workflow, each turn as an activity. Temporal then
owns durability: on restart, the worker re-attaches and the workflow continues
from its event history, with activity-level retry and idempotency support.

This is genuinely the stronger primitive **in the abstract**. It gives durable
timers, replay-based resume, and per-activity retry policies that Postgres
polling does not.

**Evidence against it in THIS deployment:**

| Fact | Where |
|---|---|
| The Temporal SDK is a dependency of `core/workflow` only | `core/workflow/pom.xml` (`io.temporal:temporal-sdk`) |
| The Temporal client/worker beans are profile-gated | `TemporalConfig` is `@Profile("temporal")`; `core/workflow/pom.xml`'s own comment records that core started without the profile "never even constructs" them |
| Temporal is enabled on **staging only** | `deploy/compose/docker-compose.domain.yml` appends `,temporal` to `SPRING_PROFILES_ACTIVE` and sets `WORKFLOW_EXECUTION_ENGINE: temporal`, with an in-file note that prod stays legacy "until the VM RAM decision" |
| **Production has neither profile.** `.ai` runs `SPRING_PROFILES_ACTIVE: security,knowledge,graph-memory,mcpserver,mcp` — no `temporal`, and `workflow` was deliberately removed | `deploy/compose/docker-compose.gcp.yml` |
| The Temporal containers are not even started in production | `deploy/compose/docker-compose.gcp.yml` marks `temporal`, `temporal-postgresql`, `temporal-ui` and `redpanda` as `profiles: ["deferred"]` |
| CI brings up `temporal` + `temporal-postgresql` containers but sets no `SPRING_PROFILES_ACTIVE` override, so core runs on the base file's profile list — which does not include `temporal` — and never connects | `.github/workflows/stack.yml:101`, `.github/workflows/e2e.yml:121`, plus the `@Profile("temporal")` gate above |

The consequence is decisive: **routing agent jobs through Temporal would make
agent jobs non-functional in production on the day they shipped.** They would
work on `*.staging.example.com` and do nothing on `*.example.com`. Turning them on in
production requires flipping the `.ai` profile list and accepting a RAM cost
that the compose file itself records as an *explicitly undecided* question
(memory headroom on the production host is already a known constraint — see the
2026-08-16 memory-starvation finding that produced the 4 GB swap + 6 GB cap).
Nobody writing this PR has the authority to make that call, and shipping a
feature that silently only works in staging is worse than shipping a simpler one
that works everywhere.

A secondary point: it would also be untestable in CI for the same reason, so the
recovery paths would ship with zero coverage.

### Option B — Checkpoint to Postgres and reconcile with the existing sweeps

Keep `agent_job` as the queue of record and extend the ownership/liveness
columns V207 already ships (`owner_instance`, `heartbeat_at`, `attempt_count`).
An interrupted job is detected by heartbeat staleness and resolved by the
existing `reapOrphans` tick, which gains one decision point: re-queue the narrow
class of orphan that provably did no chargeable work, fail everything else with
`ORPHANED`.

No new dependency, no new container, no new profile, identical behaviour in
staging and production, and fully unit-testable.

---

## Decision

**Option B.** Agent-job durability is implemented on Postgres, using the
ownership and heartbeat columns that already exist, and is reconciled by the
orphan sweep that already exists. Temporal is not used for agent jobs.

Concretely, PR-4 adds:

- `ZeroWorkOrphanRecoveryPolicy` — the `AgentJobOrphanRecoveryPolicy`
  implementation consulted by `AgentJobRunner#reapOrphans`. It returns `REQUEUE`
  for, and only for, an orphan that provably did zero chargeable work; `FAIL`
  otherwise.
- `AgentJobBootReconciliation` — an `ApplicationReadyEvent` **report** (counts,
  log lines, `agent.job.recovery.orphans_at_boot`) that transitions nothing.
- `AgentJobRecoveryLookup` — a read-only Spring Data view used by that report.

No migration. `owner_instance`, `heartbeat_at` and `attempt_count` are all
already in V207; V212 was reserved for this PR and is deliberately left
unclaimed rather than burning a shared Flyway number on an `ALTER` nothing
needs.

### How "orphaned" is decided — and why a blanket boot reclaim is forbidden

An orphan is:

```
status = RUNNING
AND heartbeat_at < now - staleHeartbeatAfter()      -- max(2 min, 5 × heartbeat interval)
AND NOT executor.isActiveHere(jobId)
```

A boot-time reclaim **by status alone** is explicitly rejected. `core` is
single-replica today only because `container_name: agentic-core` in
`deploy/compose/docker-compose.yml` blocks `--scale`; the job design is written
multi-replica-safe throughout, and a rolling deploy is transiently two processes
by construction. A fresh replica cannot tell, from `status = RUNNING` alone, a
row abandoned by a dead process from one a sibling is executing right now.
Reaping the second kind burns a tenant's budget and loses their work silently —
a worse bug than the one this ADR fixes. Heartbeat staleness is the only signal
that distinguishes them, so it remains the decision rule and the boot listener
only reports.

`owner_instance` is reported but **not trusted as a liveness signal**:
`AgentJobExecutor.instanceId()` is `host:pid`, and in a container the JVM is
usually PID 1 behind a fixed `container_name`, so a restarted container produces
the *identical* instance id as the process that died. This does not affect the
sweep — which discriminates on `heartbeat_at` plus the in-memory `isActiveHere`
map (empty on a fresh JVM) and only ever *logs* `owner_instance` — but it does
mean the boot report labels its two breakdowns carefully instead of calling
either one "orphans".

### The idempotency argument

This is why the safe default is *fail*, not *retry*, and it is reproduced in
javadoc on `ZeroWorkOrphanRecoveryPolicy` so it cannot drift from the code.

- **There is no job-level cost ledger.** `agent_job.cost_usd` is a column
  nothing writes — `AgentJobExecution` calls `AgentJob#succeed` with a `null`
  cost. A resumed job cannot be reconciled against what its previous attempt
  spent, because nothing recorded it.
- **Spend is metered per model call.** Every `AgentChatService#chat` invocation
  independently consumes a FREE-tier message unit and takes a
  `PerUserBudgetService` reservation keyed on (tenant, subject, TEXT) — where
  the subject is the *real human's* budget key, on purpose. Re-running a job
  that already made a model call charges that human twice for one request.
- **Tool side effects are not idempotent.** Nothing in the tool path keys on a
  request id; `tool_call_log` is a log, not a dedupe key; and
  `ToolCircuitBreakerService` counts failures, not replays. A re-run of a job
  that already called `send_channel_message`, `generate_document`, image
  generation or any `mcp_tool.*` fires it a second time.
- **With `max_turns = 1`, "resume from the last completed turn" and "re-run the
  whole job" are arithmetically identical.** There is no partial-progress state
  worth resuming from — which is why this design re-queues from the top rather
  than attempting a continuation.

Therefore: a job with `turns_used = 0` **and** zero `tool_call_log` rows for its
session (`job:<uuid>`) has, by construction, made no model call and taken no
side effect. Its previous attempt died between `claim` and the first `chat`.
Re-queuing it double-charges nothing because nothing was charged, and repeats no
side effect because none occurred. Everything else fails loudly.

Additional guards, each closing a way that argument could stop being true:
`attempt_count < max-attempts` (default 2) bounds recovery; `created_at` inside
`queue-ttl` stops a re-queue that the dispatch tick would instantly expire with
`QUEUE_TTL_EXPIRED`; `cancel_requested = false`; and a *failed* tool-call lookup
is treated as FAIL, because an unprovable zero-work claim is not a zero-work
claim.

---

## Consequences

**Accepted:**

- Recovery latency is bounded by heartbeat staleness plus the sweep interval:
  worst case ≈ `max(2 min, 5 × 15 s)` + 15 s ≈ 2m15s from the last heartbeat, in
  addition to the orphan sweep's 60 s startup delay. Temporal would have been
  faster here. For minute-scale background jobs this is acceptable; for a
  request-scoped path it would not be.
- Most interrupted jobs **fail rather than resume.** Any job that had started a
  turn — the common case for a long-running job killed by a deploy — is failed
  with `ORPHANED`, not retried. This is a deliberate correctness-over-convenience
  trade: the requester is told plainly and can re-submit, rather than being
  charged twice without being told. "Resumes or fails loudly" is satisfied by
  the second half far more often than the first.
- No cross-replica interrupt exists (no message bus is added by this
  workstream), so a cancel of a job owned by another replica remains a durable
  flag observed at that replica's next heartbeat tick.
- The `queue-ttl` guard means a job orphaned more than an hour after submission
  is failed rather than re-queued, even if it did zero work.

**Gained:**

- Identical behaviour in staging and production, on the infrastructure that is
  already running.
- No new dependency, container, profile or migration.
- Fully unit-testable: `ZeroWorkOrphanRecoveryPolicyTest`,
  `AgentJobRecoverySweepTest` and `AgentJobBootReconciliationTest` are plain
  Mockito with no Spring context and no database.
- Reversible. `AgentJobOrphanRecoveryPolicy` is an interface resolved through
  `ObjectProvider`; setting
  `app.agent-jobs.durability.requeue-zero-work-orphans: false` restores the
  previously shipped fail-loudly behaviour exactly, and replacing the bean is
  how a future Temporal-backed policy would be introduced without touching the
  sweep.

**Revisit this ADR when:** `deploy/compose/docker-compose.gcp.yml` activates the
`temporal` profile in production and the VM RAM question is settled. At that
point routing jobs through Temporal becomes a real option, and this decision's
only load-bearing objection disappears.
