# ADR-0014: Scale the core by workload class and replica, not by module

| | |
|---|---|
| **Status** | Accepted. Implemented and verified locally (unit tests, Helm render and schema checks). Two API replicas now boot and serve on a kind cluster with `replicaStrict` and the Valkey stores (2026-10-14, section 6); open-stream rolling updates and scale-in under load are still unproven. |
| **Date** | 2026-10-12 |
| **Deciders** | Platform Engineering (maintainers) |
| **Related** | [ADR-0001 modular monolith](../platform-documentation/decisions/adr-0001-modular-monolith.html), [Kubernetes architecture](kubernetes.md), [Deployment shapes](../deploy/DEPLOYMENT-SHAPES.md), [overview](overview.md) |

## 1. Context

Kubernetes makes it cheap to run many small services, and the `core` Maven reactor (about 45 modules)
looks like a candidate for splitting. The constraint that decides the question is the chat path. An agent
turn runs input guardrails, tool dispatch, retrieval, the model call, output guardrails, budget accounting
and the audit write, and it streams tokens back over one open HTTP connection. Putting a network call
between any two of those steps adds latency to every turn and a new way for a turn to fail halfway through
a stream. AGENTS.md rule 6 already forbids it.

So the question is not "which modules become services" but "what can be separated without touching that
path, and what stops us running more than one copy of it".

What already existed when this was written:

- One image, several **shapes** selected by Spring profile: `api`, `jobs` (every scheduled sweep and
  poller), `worker` (Temporal) and `migrate`. A scheduling-role mechanism skips beans per shape.
- Cross-replica mutual exclusion for scheduled work (`PgAdvisoryJobLock`), enforced by
  `ScheduledJobsAreLockedOrAnnotatedTest`: every `@Scheduled` method is either locked or carries
  `@AllowUnlockedSchedule` with a reason.
- Valkey-backed implementations of most per-pod stores, a boot-time `ReplicaModeSafetyCheck`
  (`app.replica-mode=multi`), and a Helm gate that refused more than one API replica.

What was missing:

1. The Helm chart switched only **three** of the **nine** per-pod stores, and never turned
   `ReplicaModeSafetyCheck` on. Eight of the nine were inspected by that check; the tool effect journal by
   nothing. A chart that said "ready for replicas" still kept guardrail procedure state, the effect journal,
   sandbox files, the E2B quota, resumable streams and Studio approvals in per-pod memory.
2. Nothing stopped a new in-memory store from being added without anyone noticing.
3. Autoscaling looked only at CPU, which is a poor signal for a process that mostly waits on model tokens.
4. Nothing checked that API replicas times the Hikari pool fits the database's connection limit. The charts
   referred to a "DB connection budget" that no document defined.
5. All background work shared one jobs pod, so a large document upload competed with agent jobs for heap,
   CPU and database connections.

## 2. Decision

**Keep one deployable codebase and one image. Scale it by replica and by workload class. Never insert a
network hop between platform modules on the turn path.** Five rules follow.

### R1. The turn path stays in one process

Guardrails, tool calls, retrieval, provider calls, budget and audit remain method calls inside the API
pod. Scaling the API means more identical pods behind the Service, not a different topology. A stream is one
connection held by one pod, so streaming needs no cross-pod traffic. The only cross-pod read is a client
that reconnects to a different pod and resumes a stream from the shared buffer (Valkey), which costs one
read on reconnect.

### R2. Work is partitioned by profile, and the partition is tested

A *shape* or *pool* is the same image started with a profile that decides which `@Scheduled` beans and boot
listeners run. Splitting a pool changes **which pod** runs a job, never **whether** it runs.
`JobsPoolsCoverEveryJobTest` reads the real profile files and the real `@Scheduled` classes and fails if a
class the single jobs pod runs would run in neither pool of a split, if a profile names a class that does not
exist, or if the ingest pool would run anything other than the two knowledge pollers.

The mechanism is `app.scheduling.skip-bean-classes` plus a new `app.scheduling.keep-bean-classes` for
exceptions, so a narrow pool can say "skip everything except these".

### R3. Every piece of per-pod state is classified, and the classification is enforced

| Class | Meaning | Examples | Enforced by |
|---|---|---|---|
| Durable | In Postgres; any pod sees it | agent jobs, workflow runs, step journal | schema |
| Shared | Per-pod memory by default, Valkey when multi-replica | A2A tasks, abuse throttles, MCP registry, guardrail procedure state, effect journal, resumable streams | `ReplicaStoreCoverageTest`, `ReplicaModeSafetyCheck` |
| Per-JVM by design | Describes this JVM only; every replica runs it | browser sessions, MCP heartbeat, landing-event buffer | `@AllowUnlockedSchedule(reason = ...)` |

`ReplicaStoreCoverageTest` fails when a property whose code default is `memory` is neither inspected by
`ReplicaModeSafetyCheck` nor allowlisted with a reason, and when the Helm library chart does not set a checked
store to `redis` for a multi-replica API. Adding a ninth store without wiring it breaks the build.

### R4. Autoscale on open requests per pod and scale in slowly

CPU alone under-reports load. Spring Boot already exports the number of HTTP requests a pod is serving,
`http_server_requests_active_seconds_count`, which for this API is dominated by open SSE chat streams.
`InFlightRequestsMetricContractTest` pins that series name against a Micrometer or Boot upgrade. The chart
accepts extra HPA metrics, offers an opt-in KEDA `ScaledObject`, and ships an example. Scale-in is slowed by
default (one pod per five minutes after ten quiet minutes) because removing a pod cuts the streams still
open on it once the drain window passes.

Resumable streams let a client **read** a turn from another pod; they do not move the **generation**. The
turn runs in the pod that started it, so a pod that is removed finishes its open turns only within the drain
window. The scale-out preset raises the drain to 300 seconds with a matching termination grace period.

### R5. The database connection budget is checked when the chart renders

Every core pod holds up to its Hikari pool: API 12 (15 without `core-jobs`), jobs 10, ingest 6, worker 8,
and the migrate hook Job 10. The umbrella chart sums the worst case, using the autoscaler's maximum for the
API, and fails the render if it exceeds the server limit minus a reserve of 20. The limit is
`postgres.parameters.max_connections` for the bundled cluster (chart default 200) or
`global.postgresql.maxConnections` for an external server. PgBouncer must stay in **session** mode
(AGENTS.md rule 8); transaction pooling would run queries in the wrong tenant's schema.

## 3. What was built

| Area | Change | Where |
|---|---|---|
| Replica contract | Chart sets `APP_REPLICA_MODE=multi` when `replicaCount > 1`, an HPA or KEDA is on, and puts all shared stores in Valkey. New flags default to true so existing multi-replica installs keep rendering. `replicaStrict` makes fatal findings refuse to boot. | `vegaduta-lib/_core.tpl`, `charts/core/values.yaml` |
| Safety check | Also reports the in-memory tool effect journal (non-fatal). | `ReplicaModeSafetyCheck` |
| Drift guard | New stores cannot be added without being checked and wired. | `ReplicaStoreCoverageTest` |
| Autoscaling | `hpa.metrics`, slow scale-in default, opt-in KEDA, `values-autoscaling.yaml`. | `vegaduta-lib/_workload.tpl` |
| Connection budget | Render-time guard. | `charts/vegaduta/templates/_guards.tpl` |
| Pools | `app.scheduling.keep-bean-classes`; profiles `shape-jobs-core` and `shape-jobs-ingest`; chart `core-ingest` (same image as `core-jobs`); `shape-api` also skips `KnowledgeReembedJobRunner` so heavy re-embedding stays off the chat pods. | `SchedulingRoleMatcher`, `app-config`, `charts/core-ingest` |
| Presets | `values-scale-out.yaml` (3 replicas, strict, long drain) and `values-autoscaling.yaml`. | `deploy/helm/examples` |

## 4. Alternatives considered

- **Split modules into services.** Rejected for everything on the turn path (R1). Possible for work that is
  already asynchronous, which is what pools do without adding a network protocol between modules.
- **One jobs pod, scaled up.** Does not isolate heavy ingestion from agent jobs, and a single JVM is a single
  failure domain.
- **Arbitrary pool names in the chart.** Rejected: a pool is only meaningful with a profile that defines what
  it owns and a test that proves the partition. The first change shipped one named pool with the mechanism ready
  for more; webhooks, intake and billing have since been added the same way (section 5), each with its own
  profile entry, chart and test coverage.
- **Overlapping Deployments of one chart for pools.** Rejected: two Deployments with overlapping pod
  selectors are fragile, and NetworkPolicies and Services select by component name. A separate chart gives a
  clean identity, matching how `core-worker` is built.
- **Cells (one full stack per tenant group, routed at the ingress).** The only way to partition the turn path
  itself, with no hops between modules. Deferred until one cluster is genuinely saturated; it adds routing and
  operations cost that nothing here needs yet.

## 5. Consequences and limits

- **More pools (webhooks, intake, billing), same rules.** They are defined in `app.scheduling.pools` and chosen
  by `app.scheduling.pool` (pool pod) and `app.scheduling.offloaded-pools` (main jobs pod), because a profile
  replaces a whole list and so cannot say "skip whatever pools run elsewhere" for an arbitrary combination.
  `JobsPoolsCoverEveryJobTest` checks all 16 combinations. Eval runs are not a pool: they execute in the API JVM
  and have no poller to move. See [Deployment shapes](../deploy/DEPLOYMENT-SHAPES.md#who-runs-which-scheduled-bean).
- **The ingest pool adds isolation, not parallelism.** Both knowledge pollers hold one Postgres advisory lock for
  the whole queue walk, so extra replicas give failover only. Parallel ingestion is opt-in per-job claiming
  (`KNOWLEDGE_INGESTION_CLAIMING_ENABLED`, below).

### Parallel ingestion claiming (opt-in)

`KnowledgeIngestionJobClaimer` claims one `knowledge_ingestion_job` row per statement:
`WITH c AS (SELECT ... WHERE status = 'QUEUED' OR (status = 'PROCESSING' AND updated_at < now() - lease) ORDER BY
created_at LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE ... SET status = 'PROCESSING', updated_at = now()`. The lease is the
existing `updated_at` column, refreshed by a heartbeat every lease/4 while the job runs, so no migration is needed.
On the claiming path every write to the job row (claim, heartbeat, completion, failure) is a SQL statement using the
database's `now()`, and the lease test also uses `now()`, so the lease compares database time with database time and no
JVM clock is involved (the JPA entity is no longer saved on this path). The sweep over tenants is unchanged (same
rotating walk, same per-tenant cap and tick budget); only the per-tenant step differs, and each claim names its tenant
schema explicitly. A reclaimed job first deletes the document's chunks and parent chunks, so the retry does not
duplicate what a dead run had already written.

- **Attempt cap, no migration.** `result_chunk_count` is unused until a job completes, so while a job is not
  `COMPLETE` it counts claims (1 on the first run, +1 per reclaim). A reclaim of a job already claimed
  `KNOWLEDGE_INGESTION_CLAIM_MAX_ATTEMPTS` times (default 3) marks it `FAILED` and clears its audio instead of running
  it, so a job that kills its pod every time stops after the cap. The API hides the counter unless the job is
  `COMPLETE`. A dedicated column would be cleaner; it was not added because this change takes no migration.
- **Fencing the job row.** The attempt number is a fencing token: heartbeat, completion and failure apply only while
  `status = 'PROCESSING'` and the stored attempt equals the caller's, so a live-but-slow earlier attempt cannot
  overwrite or regress what a later attempt wrote. Its chunk writes (through `KnowledgeIngestionService`) are not
  fenced: a duplicate run can still write chunks, and the reclaim purge only runs before the second run starts.
- **Mixed mode.** A replica with the flag off still starts jobs under the advisory lock, but through a conditional
  `UPDATE ... WHERE status = 'QUEUED'`, so it skips a job a claiming replica already owns. It does not heartbeat, so a
  job it runs for longer than the lease can be reclaimed by a claiming replica. Switch all `ingest` replicas in one
  rollout. Replicas still on a build from before this change are not protected at all.

What is not proven: the lease is a time-based guess. A runner that is alive but cannot heartbeat (a long pause, a lost
connection, a starved connection pool) can have its job re-run; keep the lease well above a worst-case pause. Run on
real Postgres (`KnowledgeIngestionClaimingIT`): the claim statement under 6 concurrent threads in one JVM, the attempt
cap, fencing, and the real runner, service and claimer together with a stubbed sidecar and ingest call (completion,
reclaim purge, a real heartbeat thread, mixed mode). Not run: several replicas as separate processes, the real
sidecar, or connection-pool pressure; no throughput number is measured. Re-embedding still uses the advisory lock, and
running it next to claiming has not been verified. The Helm `core-ingest` chart still refuses more than one replica
unless `leaderElection` is true, which has not been checked together with claiming.
- **Fourteen advisory-locked sweeps still tick in the API shape as well as the jobs shape.** They are
  correct (the lock makes one pod win) but they borrow a pinned database connection in a chat pod. Each can move
  once its owner confirms nothing depends on API-pod state; the ownership table in
  [Deployment shapes](../deploy/DEPLOYMENT-SHAPES.md) lists them.
- **MCP OAuth is multi-replica safe only behind `app.mcp.oauth.store=redis`.** The
  `McpOAuthSharedStore` Valkey implementation now carries the single-use state set, the token
  cache (written encrypted, as before) and the refresh single-flight; the default `memory` is
  per JVM. With `replicaStrict` the pod refuses to boot while `app.mcp.oauth.enabled` is on and
  the store is `memory`; the provider-OAuth finding is non-fatal. The chart switches it through
  `core.replicaReadiness.mcpOAuthStoreRedis` (default on, render fails if set false with 2+
  replicas).
- **A removed pod cuts its open turns after the drain window.** Mitigated, not eliminated (R4).
- **One config checksum changes**, so existing installs roll their core pods once on upgrade.
- **Jobs pods are not autoscaled.** There is no queue-depth gauge to scale on.

## 6. Verification status

| What | State |
|---|---|
| `ReplicaStoreCoverageTest`, `JobsPoolsCoverEveryJobTest`, `SchedulingRoleTest`, `ReplicaModeSafetyCheckTest`, `InFlightRequestsMetricContractTest` | **Run**, pass. The first two were also run against a deliberately broken input and failed as intended. |
| Helm render guards (`deploy/helm/test-guards.sh`) | **Run**, 278 cases pass |
| `deploy/helm/validate.sh`: lint, template across scenarios including `scale-out` and `scale-out-autoscaling`, kubeconform on Kubernetes 1.28 and 1.31 | **Run**, pass |
| HPA with a Pods metric, KEDA `ScaledObject` | **Rendered** and schema-checked only. No cluster had prometheus-adapter or KEDA. |
| Two or more API replicas serving traffic, a rolling update with open streams, scale-in under load | **Partially run** (2026-10-14, kind). Two `core` replicas booted Ready under `replicaStrict` with every shared store on the bundled Valkey, including `MCP_OAUTH_STORE=redis` with `APP_MCP_OAUTH_ENABLED=true` (the safety check logged "no unsafe per-JVM settings found" on both pods, and the same settings on the pre-shared-store image failed boot exactly as designed). The rolling upgrade replaced one replica at a time with `maxUnavailable=0`. NOT yet proven: an OAuth callback handled by the other replica mid-flow, open chat streams surviving a rollout, scale-in under load. |
| Memory and database connections under the budget guard | Arithmetic from configured pool sizes, **not measured** |

The deeper exercise still owed is a load run from `tests/load` against the kind scale-out - open
streams across a rollout, a Valkey restart, and an OAuth callback that lands on the replica that did
not start the flow.
