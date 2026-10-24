# Hot-path performance: how a chat turn stays fast as load grows

A chat turn is where speed is felt, so this page is about the work the platform does **before the model is
called** and **after it answers**, and the rules that keep that work from growing with users, agents and
features. The model call itself (seconds) is covered in
[LLM latency and model selection](LLM-LATENCY-AND-MODEL-SELECTION.md). Scaling by replica is in
[ADR-0014](ADR-0014-horizontal-scaling.md).

> **What this page does not claim.** Nothing here proves the platform stays fast at a million users. That
> needs a load test on a real cluster (see [What is not proven](#what-is-not-proven)). What it records is
> what was measured, the rules that make the cost of a turn bounded, and what is still unfinished.

## 1. The cost model

Database work dominates the platform's share of a turn, for one reason that is easy to miss:

- Tenants are Postgres **schemas**, selected with `SET search_path` on the connection.
- `SchemaMultiTenantConnectionProvider` runs that `SET` when a transaction takes a connection and resets it
  when the connection goes back. So every transaction costs **three round trips**, one of them the query.
- A turn used to open dozens of small transactions, most of them re-reading the same agent row.

So the cost of a turn is roughly **(transactions per turn) x (3 round trips + query)**, and it scales with the
number of lookups a feature adds, not with how heavy each lookup is. That is why the rules below are about
counting transactions.

## 2. Rules

1. **A turn reads each fact once.** Anything read by many tool resolvers or wrapped tools in one turn is read
   through a snapshot, not re-queried.
2. **Reads that do not need to be fresh to the millisecond are cached per pod, keyed by tenant first**, with a
   short TTL, a hard cap, and **eviction on every write on the same pod** (so an edit shows up on the next
   request there). Another pod sees it within the TTL. The TTL is the documented staleness bound.
3. **Authorization and edits never read a cache.** `requireAccess` and every setter read the row.
4. **Cache what is immutable and small.** Cache a value object built inside the loading transaction, never a
   detached JPA entity (its lazy state can fail later on another thread).
5. **A cache is off-able.** TTL `0` is a pass-through that reads the row every time.
6. **Every new per-turn query has to be paid for in a test.** `HotPathQueryBudgetIT` counts connection
   checkouts and statements for one turn on a real Postgres and fails when they exceed the recorded budget.

The patterns are cache-aside, snapshot (value object), and write-through eviction by a JPA listener. The
mechanism already used for tenant plan and permissions (`TtlCache`, `HotpathCaches`) is reused, not replaced.

## 3. What was measured

`HotPathQueryBudgetIT` boots the real application against Postgres 16 in a throwaway container and resolves
tools for one turn of a default agent (no integrations configured), using Hibernate's own statistics.

| Step | Connections | Statements |
|---|---|---|
| Resolve tools, before the agent snapshot cache | 52 | 55 |
| Resolve tools, with the agent snapshot cache | 23 | 26 |
| Resolve tools, with the config lookup cache as well (warm) | 8 | 11 |
| Resolve tools, with the last eight transactions snapshotted too (warm) | 0 | 0 |
| Resolve tools, cold first call on a pod | 21 | 24 |
| **Whole turn, pre-flight to the first token, warm** | 15 | 14 |
| Whole turn, pre-flight to the first token, cold first turn on a pod | 44 | 46 |

Warm is the steady state of a busy agent and tenant, which is what almost every turn looks like; cold is the
first call after a pod starts, an agent is edited, or the TTL runs out. Warm went from 52 to 8 connection
checkouts, so about 130 fewer round trips on this one step of a turn. These are counts, not milliseconds: the
time saved depends on the round-trip latency between the API pod and Postgres, and was not measured.

The change: the 16 read-only agent getters on `AgentService` (tool allowlist, autonomy tier, capability flags,
knowledge scope, collaborators, run budget) were each their own transaction. They now read an immutable
`AgentProjection` through `AgentProjectionCache`. Setting `app.chat.hotpath.agent-projection-ttl=0s` restores the
old behaviour exactly. Default is 3 seconds, capped at 30.

The second change covers the tenant-wide "is this configured" checks the tool resolvers make on every turn: is
any MCP server enabled, is the ElevenLabs, Sarvam or E2B credential active, is an image or video provider
configured, does this agent hold an active payment mandate, which specialist tags exist, and the platform's
tool credential rows. They go through `HotpathLookups`, a small read-through cache keyed by tenant first (or
platform-wide for rows the same for everyone). A write to any of the watched entities on the same pod evicts
the tenant's answers at flush and again after commit (`TenantConfigEviction`; each entity marks itself
with `@TenantConfigEntity`, so the kernel depends on no vendor module, and `TenantConfigEntitiesMarkedTest`
fails if one is missing), and another pod sees it within `app.chat.hotpath.config-lookup-ttl` (default 3 seconds,
capped at 30, `0s` turns it off). Platform credentials are cached still encrypted and decrypted per call.

### 3a. BYOK budget check: a counter instead of a monthly SUM

`LlmGatewayBudgetService.assertWithinBudget` ran `SELECT COALESCE(SUM(estimated_cost_usd), 0) FROM llm_call_log
WHERE started_at >= <start of month>` on every BYOK turn. It is now a primary-key read of `llm_spend_month`
(migration `V481`, tenant schema, one row per UTC month). `LlmCallLogService.record` adds each call's cost with
`UPDATE ... SET cost_usd = cost_usd + :c` in the same transaction as the log row (so a rollback undoes both); when the
month has no row yet it inserts one seeded from the real SUM (or, if a concurrent transaction created it first, adds
to it). The update is atomic in Postgres, so it holds across threads and replicas, and it survives restarts because it
is a table. The migration backfills the current month. Calls with no cost (failed calls with no usage data) skip the
write. If the row is missing the check falls back to the old SUM; if the read throws it still fails open, as before.

The counter write runs behind a JDBC savepoint (`LlmSpendCounterWriter`). Without it, a Postgres error in the counter
statement (for example a tenant whose V481 has not been applied) aborts the whole transaction, and the swallow in
`record` does not help: a probe showed `record` throwing `UnexpectedRollbackException` and the log row lost. With the
savepoint the failure rolls back only the counter statement, is logged, and the log row commits.

`ByokSpendCounterIT` (real Postgres 16, Hibernate statistics), run 2026-10-13 on a laptop:

| Measure | Before (SUM) | After (counter) |
|---|---|---|
| Connections / statements for the budget check | 1 / 1 | 1 / 1 |
| Time per check with 300,000 call-log rows this month (30 runs, warm, after a 5-run warm-up) | 12.1 ms | 0.83 ms |
| Statements added per recorded LLM call | 0 | 1 (the `UPDATE`) |

The statement count of the read does not change; what changes is that its cost no longer grows with the month's
call volume. The write is one extra indexed `UPDATE` per LLM call, on the same transaction as the log insert, on the thread
that logs the call. Its added latency was not measured. All calls of one tenant update the same row, so concurrent
calls of a tenant serialise on that row lock until the log-insert transaction commits; if `record` were ever joined
to a caller's longer transaction, the lock would be held that long. No such caller was found on the main paths, but
nothing enforces it. This was not measured under contention beyond the 8-thread by 25-call test below.
Tests (`ByokSpendCounterIT`, real Postgres 16, run 2026-10-13): counter equals `SUM` after 8 threads x 25
concurrent calls starting from an empty table (all first calls race to create the row), after the seed path (rows
written by something that does not know the counter), and after the migration's backfill, which also excludes last
month's rows. Failure modes: with the counter table renamed away, `record` does not throw, in or outside a caller
transaction, and the log rows are written; `assertWithinBudget` does not throw; a caller transaction that rolls back
leaves neither a log row nor a counter change; a call with no usage data leaves the counter row's tuple version
(`xmin`) unchanged. The 300,000-row read benchmark in that class runs only with `-Dbyok.counter.benchmark=true`.
#### Counter drift and the reconcile job

The counter is exact only for writers that run the counter code. A pod on an older image inserts `llm_call_log` rows
without touching `llm_spend_month`, so during a rolling deploy (or when images are switched back and forth) the
counter falls below the true SUM, and the budget cap, which reads the counter, lets a tenant overshoot. This was
measured on the kind stage after switching images back and forth: counter 4.050 against a true SUM of 4.269, about
5.1% low (a single stage tenant, one run).

`LlmSpendCounterReconciler` (module `chat`, `billing` job pool, advisory-locked) now recomputes the current and the
previous UTC month for each ACTIVE tenant from `llm_call_log` and corrects the counter, every
`app.llm.spend-counter.reconcile-interval` (default `1h`; `0` or `off` disables). Drift is therefore self-correcting
within that interval, instead of lasting until the end of the month. It is safe against concurrent writers: it takes
the counter row lock first, computes the SUM in a separate statement after the lock is held, then updates (a single
`UPDATE ... SET cost_usd = (SELECT SUM(...))` can overwrite a concurrent increment). The metric
`llm.spend.counter.drift.usd` (tag `outcome=corrected|ok`, no tenant tag) accumulates the absolute drift found, and
each tick that corrected anything logs one WARN with the tenant count and the largest drift.

Not proven: the overshoot window is still up to one interval (a tenant can exceed its cap by whatever old-image pods
log in that time); the correction is tested for lost increments with 8 threads on one tenant row in
`LlmSpendCounterReconcilerIT`, but the effect of the reconcile's row lock on a single very busy tenant (writers block
for the SUM over the month's rows while it holds the lock) was not measured under production-like volume; and the pass
over many tenants (the per-tick cap, `reconcile-max-tenants-per-tick`, default 200) was not run against thousands of
tenants.

The third change covers the last eight warm transactions of tool resolution, which return JPA entities and
therefore needed snapshots first (rule 4): the caller's Google connection (a two-field record, keyed by tenant
and subject because the effective credential is per viewer), whether the hosted pool can serve images (a boolean;
the pool chain it is computed from is decrypted on every call and is not cached), the skills metadata and
settings of an agent (records, keyed by agent), and the lists of user scripts, custom code tools and OpenAPI
tools (records of what the model is shown, without source code, spec text or secrets). The callbacks of custom
code and OpenAPI tools re-read their row by id when the model calls the tool, so an edit or a disable applies to
the next call. Writes evict through the same listener (`TenantConfigEviction`), and a platform twin
(`PlatformConfigEviction`, marker `@PlatformConfigEntity`) drops the platform-wide answers when the hosted pool
rows change. `app.chat.hotpath.config-lookup-ttl=0s` turns all of it off; `HotPathQueryBudgetIT` runs with it
off and checks the same tools are offered and every read is a transaction again (23 connections instead of 0).
Authorization is not served from these: `SkillService#loadForAgent` still re-reads the scope.

`HotPathQueryBudgetIT` also counts the **whole turn**: it activates a mock Ollama-compatible server (a JDK
`HttpServer` in the test) for a tenant, sends a turn through `AgentChatService#chat` and counts connections and
statements from the call to the first token. Warm that is 15 connections and 14 statements; the budget is 17 and
16. What the 14 statements are is in the next section.
As of 2026-10-23 the measured warm turn is 17 connections and 18 statements and the budget is 17 and 18: a message of
at most `app.chat.follow-up.short-message-chars` characters (40) reads the session's `tool_call_log` once to keep the tools
the conversation already used on offer (`FollowUpTools`). A longer message does not.

## 4. Measured latency of a whole turn

Section 3 counts database round trips. This section measures milliseconds, end to end through the ingress, on a
**single developer laptop**. Treat the shape of the numbers as the finding, not the absolute values.

**Method.** A tenant on the local kind stage uses a mock Ollama-compatible model server
(`tests/load/mock-llm`) with a fixed 50 ms first-token delay and 20 tokens at 5 ms, so no real model is called.
`tests/load/scripts/turn-overhead.js` (k6, closed loop, one conversation per virtual user, ten turns each) sends
turns for 60 s after a 20 s warmup. Time to first byte is the time to first token as the client sees it (the
platform sends nothing before the first token), so **platform overhead is roughly time to first token minus 50 ms**.
The pod's own `agentic.chat.ttft` timer agrees with the client figure within 3 to 7 ms in every cell, so the
ingress adds almost nothing. `tests/load/kind/bench.sh` runs the matrix (Helm upgrade per configuration);
raw results are in `tests/load/kind/results-2026-10-13.jsonl`. Every cell had 0 failed turns.

"Caches on" is the shipped default (agent projection and config lookup TTL 3 s); "caches off" sets both to `0s`.
"1 replica" is the shipped single-replica configuration (in-memory stores); "2 replicas" is the shipped
multi-replica configuration (those stores on Valkey, `APP_REPLICA_MODE=multi`). They differ in more than the pod
count.

| Replicas | Caches | Users | Turns/s | TTFT p50 (ms) | p95 | p99 | Overhead p50 (ms) |
|---|---|---|---|---|---|---|---|
| 2 | on | 1 | 4.4 | 111 | 145 | 156 | 61 |
| 2 | on | 8 | 36.5 | 104 | 167 | 236 | 54 |
| 2 | on | 32 | 61.6 | 394 | 534 | 691 | 344 |
| 2 | off | 1 | 4.0 | 132 | 170 | 212 | 82 |
| 2 | off | 8 | 28.5 | 167 | 228 | 266 | 117 |
| 2 | off | 32 | 58.2 | 412 | 555 | 641 | 362 |
| 1 | on | 1 | 4.7 | 100 | 118 | 129 | 50 |
| 1 | on | 8 | 40.4 | 94 | 114 | 131 | 44 |
| 1 | on | 32 | 72.2 | 322 | 461 | 603 | 272 |
| 1 | off | 1 | 4.1 | 124 | 144 | 157 | 74 |
| 1 | off | 8 | 29.0 | 162 | 202 | 220 | 112 |
| 1 | off | 32 | 61.2 | 408 | 515 | 610 | 358 |

**What the numbers say.**

- **The caches earn their keep.** At 1 user they save about 20 ms per turn; at 8 users about 60 to 70 ms and about
  40% more turns per second (28.5 to 36.5 with two replicas, 29 to 40 with one). At 32 users the gain mostly
  vanishes: the pods are out of CPU and the database round trips are no longer the limit.
- **Idle platform overhead is 50 to 60 ms per turn** with the caches on, about 75 to 80 ms with them off.
- **At 32 users the laptop, not the code, is the limit.** The pods use 1.5 to 2.9 CPUs and k6 and Postgres share
  the same cores. Do not read the 32-user rows as platform capacity.
- **Two replicas did not beat one here, and could not.** Both pods share the same laptop cores, so a second pod
  adds no CPU and adds the Valkey hops of multi-replica mode. Whether replicas add throughput on separate nodes
  is untested.

**Decision on defaults.** The data supports keeping the 3 s defaults for `agent-projection-ttl` and
`config-lookup-ttl`. Other TTLs (1 s, 10 s) were not measured, so the staleness bound was not tuned against data.
The plan cache (`plan-cache-ttl`, default 0) was not part of this run. Section 4b explains why it stays off.

### 4a. A regression the benchmark caught

The first benchmark after merging the once-per-turn lookups (the plan, flags, MCP answer and query embedding)
showed 52 to 95% failed turns and 20 to 30 s waits at 32 concurrent users on one tenant, where the build before
it had none. Unit tests, the integration tests and a three-turn smoke test all passed. The cause was in the
embedding that two memory recalls now share: `QueryEmbedding.vector()` was `synchronized` and made the blocking
sidecar call inside it. Turns run on virtual threads, and a virtual thread that blocks inside a monitor pins its
carrier thread; with about as many carriers as cores, a few concurrent turns pin them all. The threads that would
have released database connections could not run, so Hikari showed 12 of 12 active while Postgres sat idle, and the
liveness probes timed out. Found by bisecting images (old build and the build after the first PR: 0% and 99%
failed) and confirmed by the fix: a `ReentrantLock` instead of the monitor, **0% failed and 44.6 turns/s at 32
users on the same machine state where the old image did 38.6**. `QueryEmbeddingTest` now fails if the embedder runs
while the object's monitor is held.

Lesson, and the rule from now on: **any change on the turn path is benchmarked at 32 concurrent users before it is
called healthy**; a counted-queries test and a smoke test do not see thread pinning or pool starvation.

### 4b. After the hot-path PRs (main at `7b7f6cce4`)

The same matrix, two replicas, after the plan/flags/embedding, tool-resolution snapshot, Redis, spend-counter and
other PRs were merged and the pinning fix in 4a was applied (`tests/load/kind/results-2026-10-13-after-merges.jsonl`,
0 failed turns in every cell). The machine was in a busier state than for the table in section 4 (more rows in the
database after repeated runs), so compare shapes, not single digits.

| Caches | Users | Turns/s (before, after) | TTFT p50 ms (before, after) | p99 ms (before, after) |
|---|---|---|---|---|
| on | 1 | 4.4, 4.3 | 111, 111 | 156, 198 |
| on | 8 | 36.5, 38.3 | 104, 100 | 236, 177 |
| on | 32 | 61.6, 67.7 | 394, 359 | 691, 543 |
| off | 8 | 28.5, 29.7 | 167, 163 | 266, 234 |
| off | 32 | 58.2, 60.4 | 412, 401 | 641, 638 |

At idle nothing changed; the gains are at 8 and 32 users (about 5 to 10% more turns per second, a lower tail). The
whole-turn database cost is the steady 14 connections and 12 statements from `HotPathQueryBudgetIT`. These are
modest, as expected: the earlier work had already removed most round trips, and the model call is not in this
number. What is *not* shown: any run with the opt-in features on (stream-end executor, parallel ingestion, job
pools, plan cache), which are off by default and were not benchmarked.

Reproduce: see [tests/load/README.md](../../tests/load/README.md#platform-overhead-per-turn-local-kind-stage).

### 4c. Everything on, and the failure drills (main at `5646394e0`)

Run on the kind stage with every opt-in scaling feature on (`tests/load/kind/values-all-features.yaml`: the `ingest`,
`webhooks`, `intake` and `billing` job pools, tenant idle-skip, per-job ingestion claiming) and request-based
autoscaling (`values-autoscaling-kind.yaml`, Prometheus plus prometheus-adapter from `prometheus*.yaml`). Two features
are deliberately **off**: the stream-end executor (`app.chat.stream-end.async`) and the plan cache.

- **Why the stream-end executor is off.** It moves the call-log row, the BYOK counter increment, the hosted-spend
  accounting and the output-guardrail audit into an in-memory queue. That queue is lost on SIGKILL or OOM (the drain on
  a graceful stop waits 10 s), and a burst can overshoot a spend cap by about the queue depth (found in the security
  review). The A/B showed no gain: at 32 users, 63.2 turns/s with it on and 54.7 with it off, inside the machine's
  noise of about 10%. With it on, 150 concurrent turns produced exactly 150 call-log rows (nothing lost on a graceful
  path).
- **Why the plan cache is off.** The plan is read once per turn now, so the cache would save one connection per turn.
  Measured at 32 users: 65.4 turns/s with the cache off, 63.2 with it on. No benefit, and a plan downgrade would take
  up to the TTL to apply on other pods.
- **The pools cost memory, not CPU.** The four extra pods used 11 to 14 millicores each when idle and about 790 MiB
  each (about 3 GB for the four on this node). The log of each shows the role split: the main jobs pod skips exactly
  the offloaded jobs and each pool skips everything except its own.
- **Request-based autoscaling works.** Under a step to 40 concurrent long streams the API went from 2 to 4 pods in
  about 45 s. The custom metric `http_server_requests_active` reached 20.5 open requests per pod against a target of
  6. Scale-in is deliberately slow (one pod per five minutes after ten quiet minutes) and was not waited out.
- **Rolling restart under load** (12 users, 10 s streams, full restart of both API pods): 1 of about 114 streams was
  cut (0.9%). Every other stream carried the exact event count. Time to first token rose to a p50 of 1.5 s and about
  6 s at worst while the new pods warmed up (normally about 0.1 s), and the restart took about 3 minutes.
- **Spend counter drift is real and now self-corrects.** After the stage briefly ran images from before the counter
  existed, the counter sat 5.1% below the true sum (4.050 against 4.269). New traffic was counted exactly (200
  concurrent calls: both moved by 0.009). The reconcile job brought the live counter to the true sum.
- **A pre-existing bug the load exposed, now fixed.** Streamed-reply webhooks (`agent.message.completed`) and the
  judge's call-log writes ran without a tenant context, failed closed with `relation ... does not exist`, and logged
  an error with the reply excerpt for every streamed reply. After the fix the API pods show zero such lines.
- **Image A/B on a quiet machine** (previous image against current `main`, fixed 2 replicas, two rounds each). At 8
  users the throughput is the same (35.1 and 39.9 turns/s against 35.3 and 40.1). At 32 users the previous image
  failed 41% of turns (p99 41 s) in one round and 0.15% (p99 20 s) in the other; the current image did 60.4 and 70.9
  turns/s with no failures and a p99 under 0.9 s. The cause of the previous image's stalls was not isolated.

**Not fixed, found by these runs.**
- **Scale-out under a sudden step is not clean.** In one of three runs, 19% of requests got no first byte for about a
  minute (ingress logged them as 499) while the HPA added pods; the other two runs were clean (0 failed). The cause was
  guessed to be cold pods; it was not. It was a pinned-thread herd on a pod that was already serving, now fixed:
  see section 4d.
- A core pod was once killed by its liveness probe under CPU saturation; the probe now tolerates about two minutes
  (period 10 s, timeout 10 s, 12 failures).
- Everything here is one laptop with a mock model, and several JVMs share the same cores.

### 4d. The scale-out stall: not cold pods, a pinned-thread herd (main at `af3597f54`)

Section 4c left one problem open: when load steps from about 0 to 40 long streams on 2 API pods and the HPA adds pods,
some requests get no first byte for about a minute. The guess was that freshly started pods report Ready while still cold
and receive traffic they cannot serve. That was tested first and is **not** what dropped the requests.

**Reproduced.** `tests/load/kind/step.sh`: 2 warm pods, mock model at 200 ms x 50 tokens (a reply streams for 10 s),
`VUS=40 DURATION=90s EXPECT_EVENTS=50`, request-based HPA on. Main at `5646394e0`, six runs: failed 3.7% (5 with no response
and 3 HTTP 500 of 216), 18.9% (35 of 185 with no response, ingress status 499, the exact figure from 4c), 18.9% (35 of 185
again), 0.6% (2 HTTP 500 of 311, p95 time to first byte 22.8 s), 0%, and 23.6% with pinned-thread tracing on. Five of six
runs lost requests.

**Where the failed requests went.** The ingress log has the upstream pod of every request. In every failing run the stuck
requests (35, 36, 39 of them) were all on **one of the two pods that were already serving**, never on a pod the HPA had added
(those served 30 to 38 requests each, all 200). That pod also failed its readiness and liveness probes
(`context deadline exceeded`), Prometheus could not scrape it for 40 to 110 s, and in one run it stayed unready until its
liveness probe killed it. A pod that is wedged for a minute is not "cold".

**Cause.** Running the pods with `-Djdk.tracePinnedThreads=full` (`kind/pinned-threads.py` reads the output) showed 37 to 86
pinned-virtual-thread traces per pod during the step, over 90% of them at one place:
`PlatformBroadcastService.current()`, the maintenance-mode check that runs on every `/api/**` request. Its 10-second
snapshot was reloaded inside `synchronized (this)`, and the reload is a database read over TLS. On JDK 21 a virtual thread
that blocks inside a monitor pins its carrier thread, and a thread that waits to enter a monitor is pinned too. Every
request that arrived while one thread reloaded queued on the monitor and held a carrier. There are about as many carriers
as cores (10 on the stage), so a burst of in-flight requests at the moment the TTL expired took them all; the reloading
thread then needed a pooled connection that was held by a request that could not get a carrier to finish; and the pod
answered nothing, probes included, until the Hikari timeouts cleared it (`HikariPool-1 - Connection is not available,
request timed out after 49035ms (total=9, active=9, idle=0, waiting=1)`). It is the mechanism of 4a at another site. It
needs a burst of in-flight requests close to the number of carriers, which fits a step (about 20 simultaneous streams
per pod while only 2 pods exist) and not 4 pods or steady load, and explains why a given run is a coin flip.

**Fix.** `PlatformBroadcastService.current()` uses a `ReentrantLock` (it parks the virtual thread and pins nothing), and a
caller that finds a stale snapshot while another thread is reloading serves the stale one at once. Only a caller with no
snapshot at all (first request, or just after a write) waits for the load. `PlatformBroadcastServiceTest` fails on the old
code for two of its three new cases: no monitor is held during the read, and 20 readers do not queue behind a reload.

| Main (`5646394e0`) | Fixed |
|---|---|
| step runs failed: 3.7%, 18.9%, 18.9%, 0.6%, 0% (and 23.6% with tracing) | step runs failed: 0%, 0%, 0%, 0%, 0% (320, 280, 320, 360, 360 turns) |
| pinned traces at `PlatformBroadcastService.current`: 42, 84, 37, 36 per pod | 0 (1 to 3 traces per pod remain, below) |
| 8 users: 29.4 and 36.9 turns/s, time to first token p50 139 and 104 ms | 8 users: 38.9 and 41.5 turns/s, p50 98 and 92 ms |
| 32 users: 44.2 and 54.5 turns/s, p50 561 and 435 ms, p99 1.5 and 1.2 s | 32 users: 60.4 and 72.0 turns/s, p50 388 and 333 ms, p99 1.0 and 0.5 s |

The cells are two rounds each (`cell.sh`, fixed 2 replicas, order main, fixed, fixed, main) with 0 failed turns. The fixed
image was never slower. Do not read the gain as the size of the fix: the machine drifts between rounds by about 10% (main alone
measured 44.2 and 54.5 turns/s at 32 users). The claim is "no regression, and the stall is gone", not a percentage.

**What the step still shows.** All five fixed runs had 0 failures, but time to first token is not clean: p50 438 to 2059 ms
and p99 3.8 to 12.0 s over the 90 s, against 0.3 s at rest. The two serving pods carry about 20 streams each (more than the
12 pooled connections) for the roughly 100 s the HPA's new pods need to boot, and those boots take CPU from the pods that
are serving on the same machine. Both are properties of the one-node stage and of the step; they were not changed here. A
rolling restart under load (12 users, 10 s streams) is unchanged by the fix, as expected: 0 failed and 0 cut streams in
both, 276 and 278 turns before against 288 and 288 after, time to first token p50 669 and 531 ms before against 463 and
521 ms after, p99 4.2 and 2.9 s before against 4.0 and 3.0 s after (stage noise about 10%).

**How cold is a new pod, measured.** Not enough to justify a readiness gate.
- 40 sequential turns sent straight to a replacement pod right after it became Ready: the first took 707 ms, the second
  137 ms, the rest about 120 ms; an old pod took 177 ms for its first and 100 to 110 ms after.
- 12 long streams started at once on a replacement pod right after Ready (two runs): the first wave had a time to first
  token of 2.5 s and 2.0 s (p50), against 0.67 s and 0.75 s for the same burst on an old pod. By the second wave (10 s
  later) they were equal (0.75 and 0.74 s; 0.50 and 0.48 s).
- 480 requests to a tenant-free public endpoint before the burst (a stand-in for a generic warm-up) changed nothing
  measurable (1.65 s against 2.0 s, inside the spread of the old-pod control, which also showed a 1.6 s p90 once).

So a fresh pod costs about 1.5 s on the first wave of a burst and about 0.6 s on its first single turn, then behaves like
the others. A warm-up that removes that would have to run a real turn through `AgentChatService`, which needs a tenant, an
agent and a provider: a synthetic tenant means a tenant schema migration at boot on every pod, rows in tenant listings,
billing and admin views, and a race between replicas in `replicaStrict` mode. Exercising only generic code (the experiment
above) does not reach the chat path and showed no effect. Neither is worth shipping for a 1.5 s first-wave cost; a readiness
gate would also delay the capacity the HPA is waiting for. If the cost matters, the safer levers are a higher
`hpa.minReplicas` or lower autoscaling target (more pods before the step), not a gate.

**Second pin site, changed and not yet re-measured.** `SidecarClient.embed` (the query embedding of memory recall) blocked in
`BufferingClientHttpResponseWrapper.getBody` while holding a monitor, 1 to 3 traces per pod in a 90 s step (all of the
pinned traces in the later 32-user A/B). `SidecarClient` now uses `RequestBufferingClientHttpRequestFactory`, which
buffers the request body only (to keep the real `Content-Length`) and returns the JDK client's response unwrapped.
The raw traces mark the monitor on `BufferingClientHttpResponseWrapper.getBody` itself: its bytecode has a `monitorenter` there, held while the virtual thread parks in the JDK body stream (which uses only j.u.c locks). `SidecarVirtualThreadPinningTest` reads a slow response on a virtual thread and counts `jdk.VirtualThreadPinned` JFR events; it runs only on JDK 21 to 23 (on 24+ JEP 491 removes monitor pinning, the control does not pin and the test is skipped).
`NoSpringResponseBufferingGuardTest` and `SidecarClientTransportTest` guard it. Not yet re-measured under load. To look for sites in a pod: add `-Djdk.tracePinnedThreads=full` to `JAVA_TOOL_OPTIONS`, run the step
(`PIN_TRACE=1 kind/step.sh`), then `kubectl logs <pod> | python3 tests/load/kind/pinned-threads.py`.

**Not verified.** One laptop and one node, mock model. The pin traces and the failures coincide in time and place and the
fix removes both, but no complete thread dump of the virtual threads in a wedged pod was taken (JDK 21's `kill -3` does
not list them, and the image has no `jcmd`), so the order of events above (monitor waiters hold all carriers, the holder
waits for a connection) is inferred from the traces and the Hikari log, not observed in one dump. Whether a multi-node
cluster shows the boot-time CPU contention of the step was not tested.

## 4b. Each per-turn fact is looked up once

A read-only trace of `chat()` found the same facts being asked for several times in one turn. All four
are now resolved once, and `ChatTurnLookupCountTest` runs a real `chat()` turn over mocked collaborators and
fails if the counts go up. Every path that does not hold the value (delegated calls, specialist handoffs,
workflows, tests that build the pieces by hand) passes `null` and looks it up exactly as before.

| Fact | Lookups per turn before | After | Where |
|---|---|---|---|
| The tenant's effective plan | preflight, inbound guardrails, gateway budget and gateway rate limit for each client built, and one per RL dimension on a Playground turn (up to 5): 4 to 10 on a BYOK turn with guardrails | 1 in preflight, plus the outbound guardrail check (see 5) | `ChatTurnPreflight` hands `currentTenantPlan` to `AgentGuardrailService`, `LlmGatewayService.resolveClient(tenant, spec, plan)`, `RlPolicyPort.selectArm(.., plan)` |
| The tenant's feature flags | 3 (version pin, effective-agent overlay, experiment variants), each a transaction | 1, kept on the turn (`Turn.activeFlags`) | `AgentVersionResolutionService.pinAndApply`, `ChatPromptAssembler` |
| "Is any MCP tool offered" | the MCP tools resolved twice (`resolveToolsFor`, then `anyMcpToolsOfferedFor`) | resolved once; the tool list carries the answer (`ResolvedToolList`) | `AgentToolProvider`, `ChatPromptAssembler` |
| The user message embedded for memory recall | 2 sidecar HTTP calls (agent memory, tenant-shared memory) | 1, shared (`QueryEmbedding`) | `ChatMemoryRecall` |

Measured on a real Postgres (`HotPathQueryBudgetIT`, 2026-10-13): one plan lookup is 1 connection checkout
(and 1 statement, the free-mode setting, in that test's configuration); one flag evaluation is 1 connection;
a version pin handed the turn's flags costs 1 connection and the same pin evaluating them itself costs 2.
Before this change a turn evaluated the flags three times, so it now saves two connections (about six round
trips) there. The counts of plan lookups, flag evaluations, MCP resolutions and embeddings per turn are
asserted by `ChatTurnLookupCountTest`, `DefaultRlPolicyPortKnownPlanTest`, `LlmGatewayKnownPlanTest`,
`AgentGuardrailKnownPlanTest`, `AgentVersionResolutionFlagsOnceTest` and `ChatMemoryRecallEmbedOnceTest`.
A failed embedding is not remembered: the second store tries again, as two separate embeds would have.

### The plan cache stays off

`app.chat.hotpath.plan-cache-ttl` (default `0s`) was the other candidate for the plan reads. With the plan
resolved once and passed down, the lookups the cache could still save are the one in preflight and the ones
listed in section 5, at 1 connection each. Against that, it adds up to `ttl` of staleness for a plan change on
another replica, and the plan decides what a tenant is allowed to do. No data here shows a saving large enough
to take that trade, so the default does not change. Milliseconds were not measured; if a measurement on a real
network shows a plan lookup matters, the cache is one setting away and already evicts on a plan write.

## 5. What remains (not measured end to end)

The whole-turn count above is measured. The rest of this table comes from a read-only trace of the code (not a
measurement) and is ordered by how many round trips the items are believed to cost.

| Opportunity | Where | Approach |
|---|---|---|
| The 14 statements a warm turn still makes before the first token (read from the SQL log of one warm turn: `agent_experiment`, `agent`, `agent_version`, `feature_flag` three times, `platform_setting` twice, `tenant` three times, `llm_provider_credential`, `agent_procedure`, `llm_gateway_content_policy`) | experiment routing and version resolution, the plan and tenant status, the gateway's content policy and provider key, procedures | `agent` and the tenant rows are read again by other paths than the ones already cached; each needs a snapshot first, then the same lookup cache |
| The tenant's plan is re-read 8 to 12 times in one turn | guardrails, RL selection, gateway budget and rate limit | Pass the per-turn plan down; turn on the existing plan cache by default |
| Feature flags evaluated three times, MCP availability twice, user message embedded twice | version resolution, prompt assembly, memory recall | Evaluate once per turn; embed once |
| ~~A monthly `SUM` over the call log on every BYOK turn~~ done, see section 3a | gateway budget | An incrementally maintained counter (`llm_spend_month`, V481) |
| ~~5 to 6 sequential Redis round trips to append one conversation turn, twice per turn~~ done, see section 5a | `SessionMemoryService` | One Lua script (`SessionAppendScript`), on by default |
| ~~A blocking Redis `DEL` every 500 ms for the cancel watcher~~ done, see section 5a | `ChatTurnCancelService` | One batched poll per pod for all in-flight turns |
| Work done inline when the stream ends (audit, history append, call log, RL close) | `ChatTurnExecutor` | Built, see section 5a: `StreamEndExecutor` behind `app.chat.stream-end.async`, **off by default**; the history append stays inline on purpose |
| Two extra `SET search_path` per transaction | `SchemaMultiTenantConnectionProvider` | Design in [ADR-0016](ADR-0016-search-path-round-trips.md) (proposed: transaction-scoped `SET LOCAL`, behind a flag); do not change it before the isolation test suite it specifies exists |
| The tenant's plan is re-read in the outbound guardrail check and by the clients built for delegated calls, specialist handoffs and workflows | `ChatGuardrailGate` output path, `ChatDelegateCall`, `ChatSpecialistHandoff`, `WorkflowNodeExecutor` | Thread the plan through `streamFast` and `verifyThenRespond` (`ChatTurnExecutor` is at the file-size limit, so it needs a split first) |
| ~~A monthly `SUM` over the call log on every BYOK turn~~ done (duplicate of the row above), see section 3a | gateway budget | An incrementally maintained counter |
| Tools that make the whole reply buffered (first token = full reply) | `ChatTurnDispatcher` | Analysis in section 7; not implemented |

### 5a. Redis round trips and stream-end work (done in this change)

Three items from the list above. Round trips are counted by `SessionAppendScriptRedisTest` and
`ChatTurnCancelServiceRedisTest`, which count template calls that reach the Redis connection (one script call is one command on the shared
connection; the cancel test also checks `INFO stats total_connections_received` to show no new TCP connection is
opened per poll) against a real Valkey 8. They are skipped unless `VEGADUTA_TEST_REDIS=host:port` points at one:

```bash
docker run --rm -d -p 16379:6379 valkey/valkey:8-alpine
cd core && VEGADUTA_TEST_REDIS=<host>:16379 mvn -pl agents-turn -am test \
  -Dtest='SessionAppendScriptRedisTest,ChatTurnCancelServiceRedisTest' -Dsurefire.failIfNoSpecifiedTests=false
```

**Session memory append** (`SessionMemoryService.appendTurn`, twice per turn). RPUSH, the fold claim, the trim,
EXPIRE and the owner record are one Lua call (`SessionAppendScript.APPEND_SCRIPT`), in the same order, so it is
also atomic per session. Measured round trips per append, before to after:

| Case | Before | After |
|---|---|---|
| No summariser, no tenant context (assistant turn, written from the stream-completion thread) | 3 | 1 |
| No summariser, tenant context (user turn, owner record written) | 5 | 1 |
| Summariser on, tenant context (user turn, may claim a fold batch) | 6 | 1 |
| Summariser on, no tenant context | 3 | 1 |

Per chat turn that is 8 to 2 without the summariser and 9 to 2 with it, assuming the user turn is appended with a
tenant context and the assistant turn without, as the code comments say. The test also runs 450 appends through the
old and the new path on the real server and requires the same stored list, fold marker, owner record, TTLs and the
same batches handed to the summariser. Milliseconds saved were not measured: they depend on the distance to Redis.
`app.chat.session-append-single-roundtrip=false` restores the old sequence. A Redis that REFUSES the script before
running it (ACL `NOPERM`, scripting disabled, `CROSSSLOT` on a cluster) falls back to it automatically; the
refusal is remembered for five minutes and logged once a minute. Any other failure (timeout, dropped connection,
Lua error) is NOT replayed: the script is atomic and may have run, so the append is skipped and logged, as the
old code did. `SessionAppendScriptRedisTest` exercises the refusal against a real server with an ACL that denies
`EVAL`/`EVALSHA`; the timeout case is covered with a mock only. On Redis Cluster the three keys are in different
slots, so the script is always refused and every append uses the multi-call path.

**Cancel watcher** (`ChatTurnCancelService`). Each in-flight turn used to run its own blocking `DEL` every
500 ms on a Reactor parallel-scheduler thread. Now one poller per pod sends one Lua call (a `DEL` per watched key, returning the counts) per interval on the
shared connection for all in-flight turns, and nothing when none is streaming. (A first version used
`executePipelined`, which makes Spring Data Redis open a dedicated connection per call; it was replaced.) If Redis
refuses the script the poller falls back to one `DEL` per key, as before this change; the cancel signal is delivered on the bounded-elastic
scheduler because cancelling a turn writes its partial reply. One poll covering 200 turns measured 1 template call and 0 new TCP
connections over 25 polls (before: 200 `DEL`s per 500 ms). The poll interval is unchanged at 500 ms
(`app.chat.cancel-poll-interval`), so the worst case is that interval plus one Redis command;
`ChatTurnCancelServiceTest#aCancelIsObservedWithinOnePollIntervalPlusTheCheck` only asserts a loose upper bound
(twice a short test interval plus 100 ms, with a mocked check), and another test fails if the default stops being
500 ms. Latency against a real network was not measured. Like the other scripts, the batch script is refused on
Redis Cluster (keys differ per turn), where the per-key fallback is used.

**Stream-end work** (`StreamEndExecutor`, `app.chat.stream-end.async`, **off by default**). The output-guardrail audit,
the `agent.message.completed` event, the RL episode close and the LLM call-log row can run on a fixed pool with a
bounded queue instead of the thread that delivered the last token. A full queue runs the work on the caller (nothing
is dropped); a graceful stop drains the queue before the database pool closes; the tenant context is cleared after
each task that ran on a pool thread, while work run inline on the caller (queue full, stopping) leaves the caller's own
tenant context untouched (tested). It is off because no end-to-end number supports turning it on yet.
**The history append stays inline on purpose:** the next turn of the same session reads and appends the history
as soon as the client sees the stream end, so an asynchronous append could land after the next user turn and reorder
the conversation. It is one Redis round trip now. Consequences when the flag is on: those rows can appear a moment
after the reply, the Langfuse score lookup by session may not find the call-log trace id yet and falls back to a
session-level score, and a hard kill (SIGKILL, OOM, node loss) loses what is still queued.

## 6. Running the budget test

```bash
cd core
mvn -P it -pl app -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=HotPathQueryBudgetIT -Dvegaduta.test.postgres.image=<image with pgvector and AGE>
```

It needs Docker. It prints a `HOTPATH-BUDGET` line with the current numbers. When you cache another lookup,
lower `BUDGET_CONNECTIONS` and `BUDGET_STATEMENTS` in the test in the same change.

## 7. Analysis only: why some replies are buffered whole (`bufferForGenerationSafety`)

Nothing in this section is implemented. It records what the code does today and what a safe narrowing would have
to prove.

**What the code does.** In `ChatTurnDispatcher`, a FAST-depth turn is wrapped in
`ChatTurnExecutor.bufferAndGuardGenerationClaims` when

```
bufferForGenerationSafety = offersGenerationTool(tools) || anyMcpToolsOffered
```

The wrapper does `stream.collectList()`, joins the chunks, and emits one element. For such a turn the first token
the client sees is the whole reply, so time to first token equals total generation time (plus the tool loop). The
condition is true when the resolved tool list contains `generate_image`, `generate_video`, `generate_music` or
`generate_document`, or `search_tools` (on-demand tool loading is assumed to be able to reach a generation tool
through `call_tool`), or when any MCP tool is a candidate for the turn (`anyMcpToolsOfferedFor` checks the
candidate set before relevance trimming, so it can be true when no MCP tool is finally offered).

**What the buffer protects.** The only check inside the wrapper is `containsFabricatedGenerationUrl(fullText)`
with no tool evidence for the turn (`collectToolEvidenceSince == null`). `FABRICATED_GENERATION_URL_MARKERS`
currently holds one host, `oaidalleapiprodscus.blob.core.windows.net` (DALL-E's signed storage URLs). If the full
reply contains it and no tool call produced it, the reply is replaced by an apology. The persisted-history guard
cannot un-send tokens already streamed, which is why a live-stream half exists.

**What looks narrowable.** These are hypotheses to test, not conclusions.

1. *The MCP half has no marker of its own.* The history comment names MCP browsing tools claiming a screenshot,
   but the code only matches the DALL-E host. As written, an MCP-only agent is buffered to protect against a URL
   that only an image-generation path produces. The condition could become "an image-capable tool is reachable":
   `generate_image` listed, `search_tools` listed and the agent's catalog containing an image-generation tool, or
   an MCP tool whose name or description says it creates images. Risk: an MCP server that returns image URLs on
   the same host, and a model that invents one in a later "try again" turn. Needs an explicit decision to extend
   the marker set before the MCP half is removed.
2. *`search_tools` alone.* It is treated as offering a generation tool even when the catalog behind it has none.
   Checking the catalog (already resolved for the turn) for a generation tool name would drop the buffering for
   agents with on-demand loading and no generation tools.
3. *Stream with a hold-back instead of buffering.* Scan the stream as it flows and hold back only a tail long
   enough to contain a partial marker (from the last `http` start, or the marker length). On a match with no tool
   evidence, stop emitting and send the correction. Cost: text before the URL has already reached the client, so
   the correction no longer replaces the whole reply; the stored reply must match what the client saw. This is a
   product decision, not only an engineering one.
4. *Decide per turn after the first tool event.* If the model never calls a tool, the answer is plain text and the
   fabricated-URL risk is the same as for an agent with no tools at all, which streams today. A reply that has
   already started streaming without a tool call and without a marker prefix could keep streaming. Needs the
   step-event stream to say "a tool was called" before the first text chunk, which is not guaranteed.

**Tests a change would need** (none exist beyond `AgentChatServiceGenerationBufferingTest`, which covers only
`offersGenerationTool`):

- Table test of the condition: every combination of generation tool, `search_tools` (with and without a
  generation tool in the catalog), MCP candidate set empty or not, MCP tool trimmed by relevance.
- Time to first emitted element on a slow upstream `Flux` (virtual time): buffered turn emits at completion,
  narrowed turn emits at the first chunk.
- Incident replay: a model reply containing the DALL-E host with no tool call is corrected and the URL never
  reaches the subscriber, for each branch left buffered, and for the hold-back variant with the marker split
  across chunk boundaries (every split position of the marker, including inside `https://`).
- Evidence case: the same reply after a real `generate_image` call is passed through unchanged.
- Multi-turn "try again" case: the request was in an earlier turn, the current message has no keyword.
- Error and cancel mid-stream pass through unchanged; the persisted assistant turn equals what the client got.
- A load comparison (`tests/load`) of time to first token for an MCP agent before and after, on the kind harness.

## What is not proven

- **The budget counter (3a) on a stack.** Measured on a laptop Postgres 16 container with one tenant schema and 300,000
  synthetic rows, not on the kind cluster and not as end-to-end turn latency. During a rolling update, calls logged
  by a pod without this change are not added to the counter, so the budget undercounts (fails toward allowing) until
  the month ends; the same applies to anything that inserts into `llm_call_log` without going through
  `LlmCallLogService.record`. A transaction that rolls back after the update also rolls back the counter, so the two
  cannot drift apart. No periodic reconciliation job or drift metric exists, so an undercount is silent. The latency
  the counter write adds to `record`, and row-lock contention on a very busy tenant, were not measured.
- **Section 5a in milliseconds.** The Redis changes are proven by round-trip counts and an old-versus-new equality run
  against one standalone Valkey, not by a latency measurement, and not on a cluster, a Redis Cluster (the scripts use
  several keys, as the older ones did) or Sentinel. The stream-end hand-off was not benchmarked, was not run with the
  flag on in a full application context, and its shutdown drain was tested on the executor alone, not through a real
  Spring context close with open streams.
- **Milliseconds for the per-turn changes.** Section 4b counts lookups and connection checkouts. The saved
  embedding is a sidecar HTTP call whose latency was not measured. The outbound guardrail check still reads
  the plan, and so do the delegated, specialist and workflow clients.
- **A whole turn on a stack.** `ChatTurnLookupCountTest` runs `chat()` over mocks. No turn was run end to end
  after this change on the kind stage or any other.

- **Production latency.** Section 4 is milliseconds on one laptop with a mock model, a Postgres one hop away and
  CPU shared with the load generator. Network distance to Postgres, noisy neighbours and real model behaviour
  will change the absolute numbers. The remaining database round trips were not converted to milliseconds
  individually.
- **A million users.** That depends on Postgres connection capacity, Valkey, the model providers' rate limits
  and the per-tenant schema count as much as on this code. It needs a load test with realistic tenants on a
  cluster (`tests/load`). Two limits are already visible in the code: every background sweep walks every active
  tenant schema ([measured cost and the idle-skip decision](TENANT-SWEEP-COST.md)), and the database connection budget is fixed by pods times pool size
  ([Deployment shapes](../deploy/DEPLOYMENT-SHAPES.md#the-database-connection-budget); the proposed answer to the schema count is [ADR-0015](ADR-0015-hybrid-tenancy.md)).
- **Multi-replica scaling.** Two API replicas serving traffic were run on one machine, which cannot show
  scaling. Staleness across pods is bounded by the TTL by design and not observed under load. No rolling update
  with open streams and no scale-in under load have been run.
- **Milliseconds saved by the third step.** Section 3 counts connections and statements; the eight removed
  transactions were not converted to milliseconds and no kind-cluster benchmark (section 4) was re-run after
  them.
- **Which statements fall before the first token.** The list of 14 in section 5 was read from the Hibernate SQL
  log of one warm turn in order, not from instrumentation at the first token; the count (14) is measured at the
  first token, the attribution of each statement is inferred.
- **The whole-turn number is for one shape of turn.** A default agent on a BYOK Ollama provider with a mock
  model, no knowledge base, no tools configured, one user, an empty conversation. Turns with attachments,
  knowledge retrieval, memory recall that hits, RL routing or guardrails that are switched on will read more.
- **Staleness on a second pod** is bounded by the TTL by design and was not observed with two pods; only the
  same-pod eviction is tested (`HotPathQueryBudgetIT`, `ToolResolutionSnapshotsTest`).
