# Tenant sweep cost: what the background pollers cost and what idle-skip can save

Every tenant-scoped `@Scheduled` job walks every active tenant schema through `TenantSchemaIterator`. Tenants are
Postgres schemas, so each visit is at least one database call (and, per
[Hot-path performance](HOT-PATH-PERFORMANCE.md), about three round trips because of the per-tenant `SET search_path`).
The cost therefore grows with tenants times sweeps, whether or not any tenant has work. This page records what was
measured, what the opt-in idle-skip (`APP_TENANT_IDLE_SKIP_*`) covers, and why it stays off by default.

None of this touches the chat hot path: sweeps run in the jobs shape (or the jobs pool of the API shape) and never
inside a turn.

## 1. What was measured

One 300 s window on the local kind stage (read-only: the actuator `/actuator/prometheus` of the `core-jobs` pod was
read at the start and the end, nothing on the cluster was changed). Image `main-71bbb2843`, idle-skip off (the pod has
no `APP_TENANT_IDLE_SKIP_*` variable, the code default is off), **one active tenant** (the stage does not have the
roughly 70 tenants that a production install may have; the 70 users live in that one tenant).

| Quantity (jobs pod, 1 active tenant, idle) | Value |
|---|---|
| Spring Data repository invocations | 1.77 per second |
| of which the tenant list (`TenantRepository.findByStatus`, one per sweep run) | 0.77 per second (about 46 sweep runs per minute) |
| of which tenant-scoped calls, one per tenant per sweep run | about 1.0 per second |
| Hikari connection checkouts | 2.5 per second |
| Process CPU | 0.020 cores |
| Busy time of all scheduled methods | 6.9 ms per second |

Tenant-scoped calls per second, by sweep (the ones above 0.05 per second; the interval column is the code's configured delay):

| Sweep | Calls per second per tenant | Interval |
|---|---|---|
| `WorkflowEventOutboxRelay` (two queries) | 0.40 | 10 s |
| `AgentJobRunner.dispatchQueued` | 0.117 | 10 s |
| `VideoGenerationJobRunner` | 0.100 | 10 s |
| `WebhookDeliveryRelay` | 0.100 | 10 s |
| `AgentJobRunner.reapOrphans` (heartbeat query) | 0.067 | 15 s |
| `AgentJobRunner.enforceDeadlines` | 0.067 | 15 s |

### Extrapolation (not measured)

The per-tenant cost is linear by construction (one pass over the tenant list per sweep run), so for N active tenants
the tenant-scoped load is about N x 1.0 calls per second, and about N x 2.5 connection checkouts per second at most.
For 70 tenants that is about 70 calls per second and about 175 checkouts per second from the jobs pod alone, all
idle. This was **not** run: an attempt to create synthetic tenant schemas on the shared stage to measure it was
refused by the environment's permission system (it changes shared cluster state), and the same applied to an on/off
helm upgrade of the jobs pod. The extrapolation is arithmetic on the one-tenant measurement.

## 2. What idle-skip covers

`forEachActiveTenantSchemaSkippingIdle` is used by exactly two sweeps: `AgentJobRunner.dispatchQueued` and
`WebhookDeliveryRelay`. Together they are 0.217 of the roughly 1.0 tenant-scoped calls per second, about 22%. The
other sweeps above (outbox relay, orphan reaper, deadline enforcement, video polling, schedule tickers) call the plain
iterator and are not affected by the flag.

With the default cap (3 skipped ticks, 60 s) an idle tenant is visited one tick in four. The saving is therefore at
most 75% of those 0.217 calls, about 0.16 calls per second per tenant, **about 16% of tenant-scoped sweep statements**
(about 11 of 70 calls per second at 70 tenants, by the extrapolation above). The on/off comparison itself was not
measured.

New counters make the real number readable on any stack:
`vegaduta_tenant_sweep_tenants_total{job, outcome="visited"|"skipped"}` (one increment per pass, only for sweeps that
use the idle-aware iterator).

## 3. Can skipping starve a tenant?

No, by a bound that a test now checks. The activity signal is the sweep's own result: a visit that returns "had work"
(or throws) resets the tenant to every-tick. A visit that returns "no work" skips the next 1, 2, 4 ... ticks, capped by
`max-skipped-ticks` and also by `max-skip-duration` measured from the last visit. So new work in a skipped tenant waits
at most

    min(max-skipped-ticks, ceil(max-skip-duration / interval) - 1)   extra ticks

which is 3 ticks, 30 s, for a 10 s sweep with the defaults. Tenants are independent of each other and each sweep has its
own memo. State is per replica; losing it (restart, another replica takes the lock) only means a tenant is visited.
A tenant that is new, or comes back after being inactive, has no memo and is visited at once.

`TenantSchemaIteratorTest.newWorkIsPickedUpWithinTheDocumentedStalenessBoundAtEveryPointOfTheBackOffLadder` checks the
bound for caps of 1 to 6 ticks and 15 s to 600 s, at every position of the back-off ladder (0 to 70 idle ticks).
`aBusyTenantIsNeverDelayedByAnIdleNeighbourOrAnotherJobsMemo` checks isolation.

The bound is a latency cost, not a loss: nothing is ever dropped.

## 4. Decision: stay off by default

Idle-skip stays **off**. Reasons:

1. **It buys little.** About 16% of tenant-scoped sweep statements, because only two of the sweeps use it.
2. **It adds visible latency where it matters most.** When the API only enqueues work (`app.agent-jobs.run-now-mode=enqueue`,
   the split-shape setting) the poller is the only thing that dispatches a queued agent job, so a job submitted to an
   idle tenant waits up to 30 s longer. Webhook deliveries are the same. "Latency never gets worse" is a rule of this
   project; a flag that trades up to 30 s of job start time for 16% of idle statements does not meet it by default.
3. **Turning it on for the other sweeps is not safe to decide centrally.** Each one has its own latency promise (the
   schedule tickers fire on the minute, the outbox relay carries events). Adoption has to be a per-sweep decision by its
   owner, with the staleness bound above in the sweep's documentation.

Operators who run many tenants and accept up to 30 s extra dispatch latency for agent jobs and webhooks can set
`APP_TENANT_IDLE_SKIP_ENABLED=true`, and watch the counters above.

## 5. The design that removes the cost instead of trading it

Polling cost is N tenants x S sweeps x ticks. Skipping lowers the constant; only removing the poll changes the shape.
The design, not implemented here:

- **Work queues with one global claim.** For the sweeps that find rows to process (agent jobs, webhook deliveries,
  outbox, video jobs), keep a small control-plane table `tenant_work(job, tenant_schema, ready_at)` written in the
  same transaction that creates the work row. A single query
  `SELECT ... FROM tenant_work WHERE ready_at <= now() ORDER BY ready_at LIMIT n FOR UPDATE SKIP LOCKED` returns the
  tenants that have work. The cost per tick is one statement, independent of the tenant count; replicas and pods split
  the rows with no advisory lock.
- **Wake-up.** `LISTEN/NOTIFY` (or the existing Valkey channel) tells a poller to run now, so latency drops below
  today's 10 s instead of rising. The timer stays as the backstop, so a lost notification costs one tick, never a job.
- **Safety conditions to prove first.** The write to `tenant_work` must be in the same transaction as the work row (an
  outbox), rows must be idempotent to re-claim, and a periodic full reconciliation sweep (hourly) must repair any row
  the table missed. A migration is needed, which is why it is not part of this change.

Order of adoption by expected gain: outbox relay (0.40 calls/s/tenant), agent job dispatch and reaper, video polling,
webhook delivery.

## What is not proven

- **No on/off measurement.** The idle-skip-on number does not exist. Only the off state was measured, with one tenant;
  everything for 70 tenants is linear extrapolation.
- **No synthetic scale run.** Nothing was run with tens of tenants on a stack.
- **CPU and statement counts are from the application's metrics**, not from Postgres (the stage image has no
  `pg_stat_statements`), so connection checkouts and repository calls are the proxies for load.
- **The guard test is a unit test** with a fake clock and a mocked repository. It proves the skip logic, not that a
  deployed jobs pod picks work up within the bound.
- The event-driven design in section 5 is a proposal; nothing in it was built or measured.
