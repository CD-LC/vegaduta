# ADR-0017: Connection budget, read replicas and cells

| | |
|---|---|
| **Status** | Proposed. Design only: no code, chart or configuration changes accompany this record. |
| **Date** | 2026-10-13 |
| **Deciders** | Platform Engineering (maintainers) |
| **Related** | [ADR-0014 horizontal scaling](ADR-0014-horizontal-scaling.md), ADR-0015 hybrid tenancy (PR #96, proposed; not yet on `main`, so not linked), ADR-0016 `search_path` round trips (PR #93, proposed; not yet on `main`), ADR-0002 schema per tenant ([handbook](../platform-documentation/decisions/adr-0002-schema-per-tenant.html)), [Hot-path performance](HOT-PATH-PERFORMANCE.md), [Deployment shapes](../deploy/DEPLOYMENT-SHAPES.md) |

> **Dependency on ADR-0015 (hybrid tenancy, PR #96, open).** This record does not define a tenancy shape; it
> builds on ADR-0015's: tenants are `SCHEMA` (dedicated schema) or `SHARED` (rows in `tenant_shared` under
> row-level security), and the placement row already carries a `cell` column and expects an in-memory placement
> lookup on the hot path. Section 6 states exactly what is taken from it. If ADR-0015 changes before it merges,
> section 6 is the part to revisit. Numbering: 0015 is hybrid tenancy (PR #96), 0016 is the `search_path` ADR
> (PR #93), this record is 0017.

## 1. Context

ADR-0014 made the API scale by replica and put a render-time guard on the database connection budget. It
deferred two things on purpose: read replicas were never designed, and cells were "deferred until one cluster
is genuinely saturated". This record decides what to do about the database as the shared ceiling, in the order
the ceiling is reached, and fixes the criteria that say when each step is worth its cost.

Constraints that do not move (AGENTS.md rules 6 and 8, ADR-0014 R1):

- The chat turn stays in one process. No step below may add a network hop between platform modules.
- Tenants are Postgres schemas selected by `SET search_path` (via `Connection#setSchema` in
  `SchemaMultiTenantConnectionProvider`). Every transaction runs the `SET`, the work, and a reset. PgBouncer
  must stay in session mode.
- Chat-turn latency must not get worse. Every step is off by default and measured before it is turned on.

## 2. Decision summary

1. **Connection budget.** Keep the existing render-time guard as the floor. Add measurement of the thing the
   guard cannot see (how long connections are held per turn), and a decision order for what to do when the
   budget is the limit: shrink hold time first, then raise `max_connections`, then add cells. Do **not** fix it
   by moving PgBouncer to transaction mode.
2. **Read replicas.** Support them only for an explicit, small allowlist of read paths that tolerate staleness,
   chosen per call site, routed in-process with a second `DataSource`. Default off. Nothing on the
   authorization or write-then-read path ever goes to a replica.
3. **Cells.** The way to partition the turn path itself. A cell is a full stack (API pods, jobs, Postgres,
   Valkey) serving a subset of tenants. Routing is by tenant at the edge (DNS or ingress), using a cell
   directory. The API pod does not call a router. Build the pieces in an order where each is useful alone.

## 3. The connection budget

### 3.1 What the chart does today

Source: `charts/vegaduta/templates/_guards.tpl`, `application.yml`, [Deployment shapes](../deploy/DEPLOYMENT-SHAPES.md).

| Quantity | Value |
|---|---|
| Hikari pool, API | 12 (15 when `core-jobs` is not deployed); Spring default in `application.yml` is 15 |
| Hikari pool, jobs / ingest / worker / migrate Job | 10 / 6 / 8 / 10 |
| Bundled cluster `max_connections` | 200 (`postgres.parameters.max_connections`) |
| Reserve the guard keeps | 20 (Keycloak, monitoring, superuser access) |
| Usable for core | 180 |
| API pods counted | `max(replicaCount, hpa.maxReplicas, keda.maxReplicas)`; the guard assumes 4 if an autoscaler omits a maximum |

The guard sums `API pods x API pool + jobs + ingest + worker + 10` (migrate) and fails the render above
`max_connections - 20`. It checks **allocated** connections, the Hikari maximum. It does not check how many
are in use.

### 3.2 Capacity model: how many API pods fit

Arithmetic from the formula above, not measured (recomputed by hand, e.g. 400 with worker: (400-20-34)/12 = 28.8, so 28; API only at 400: 370/15 = 24.7, so 24; at 800: 770/15 = 51.3, so 51). Pods = floor((max_connections - 20 - fixed) / API pool).
"Fixed" is jobs (10) + migrate (10), plus ingest (6) and worker (8) where noted.

| Deployment | Fixed demand | API pool | Max API pods at `max_connections` 200 | at 400 | at 800 |
|---|---|---|---|---|---|
| API + jobs (chart default shape) | 20 | 12 | 13 | 30 | 63 |
| API + jobs + ingest | 26 | 12 | 12 | 29 | 62 |
| API + jobs + ingest + Temporal worker | 34 | 12 | 12 | 28 | 62 |
| API only, no `core-jobs` | 10 | 15 | 11 | 24 | 51 |

So the guard stops the default shape at 13 API pods on the bundled 200-connection cluster.
`values-scale-out.yaml` (3 replicas) is far below that.

### 3.3 What breaks first (ordering is a hypothesis to be measured)

The guard prevents the loud failure. The likely first failures are quieter and happen earlier:

| # | Failure | Why it is plausible | Signal that proves it |
|---|---|---|---|
| 1 | Hikari wait inside one pod: all 12 connections checked out, new transactions queue (`connection-timeout` 20 s) | Virtual threads let far more than 12 turns run concurrently; a warm tool-resolve step alone is 8 checkouts ([Hot-path performance](HOT-PATH-PERFORMANCE.md) section 3). Queueing shows as latency before it shows as errors. | `hikaricp_connections_pending` above 0, `hikaricp_connections_acquire_seconds` p99 |
| 2 | Pinned connections: each advisory-locked sweep that wins a tick holds one connection for the whole tick; sweeps walk every active tenant schema | 14 advisory-locked sweeps also tick in the API shape (ADR-0014 section 5). Cost grows with tenant count, not with traffic. | `hikaricp_connections_usage_seconds` by pod, sweep duration |
| 3 | Postgres CPU or memory before the connection count: each backend has real memory, and 180 busy backends on a small node is already contended | A connection limit is a count; the cost of a connection is not constant. | Node CPU, `pg_stat_activity` by state |
| 4 | `max_connections` reached (the guard's number) | Only after autoscaling beyond the guard's assumption, or an external server whose limit was left at `0` (unchecked). | `FATAL: too many clients` |
| 5 | PgBouncer client limit (Compose: `MAX_CLIENT_CONN` 200, `DEFAULT_POOL_SIZE` 25) | In session mode a client holds a server slot for its whole lifetime, so the pooler multiplies nothing. Compose runs one core (pool 15, with background work), which fits 25; a second replica queues. | PgBouncer `cl_waiting` |

Consequence: in session mode PgBouncer is a cap and a connection-storm absorber, not a multiplexer. Capacity
is bought by shorter hold times, a bigger server, or more servers (cells), never by the pooler.

### 3.4 Decision order when the budget is the limit

1. **Measure hold time first.** Export per-pod Hikari usage and acquire histograms into the load harness
   (`tests/load/kind`), and record connection-seconds per turn next to the existing checkout count in
   `HotPathQueryBudgetIT`. Without this, pool sizing is guesswork.
2. **Reduce hold time and checkouts per turn** (the caching and snapshot work already under way; batching
   small transactions). This helps every later step.
3. **Right-size pools per workload class** rather than raising all of them: the API pool is the only one
   proportional to chat traffic.
4. **Raise `max_connections` / the node**, up to the point where criterion K1 below trips.
5. **Cells** (section 5) when K1 trips. Not a larger pooler, not transaction mode.

Transaction-mode pooling is closed, not merely discouraged. A multiplexing pooler needs `search_path` out of
the session: schema-qualified statements everywhere, or database-per-tenant. The
`SchemaMultiTenantConnectionProvider` javadoc records the measured failure (41 of 60 concurrent clients on the
wrong schema, 21 of them another tenant's). That is a rewrite of the data layer and out of scope here.

## 4. Read replicas

### 4.1 What exists

The bundled `postgres` chart is a CloudNativePG `Cluster` with an `instances` value (default 1). CloudNativePG
creates a read-only Service that routes to standbys when a cluster has more than one instance. **Nothing in
this repository consumes it**: there is no second `DataSource`, no routing and no chart value for a read
endpoint. That Service's behaviour is the operator's documented behaviour and was not run here.

There are 378 matches of `readOnly *= *true` in core Java sources (`grep -rn 'readOnly *= *true' core --include='*.java'`; 377 outside `src/test`). That attribute means "this code does not
write", not "this code tolerates stale data". It must not be the routing signal.

### 4.2 Safe and unsafe reads

A read may use a replica only if stale data is acceptable for its stated bound **and** it is not an input to
an authorization or write decision. Streaming replication lag is normally small but unbounded in the worst
case (a replay conflict with a long query, vacuum, a network problem).

| Read | Replica? | Tolerance | Reason |
|---|---|---|---|
| Authorization, `requireAccess`, plan and permission checks | No | 0 | Hot-path rule 3: authorization never reads a cache, and must not read a lagging copy |
| A read that follows a write by the same user in the same request or session | No | 0 | Read-your-writes would break (edit an agent, the list shows the old one) |
| Budget, quota and usage enforcement | No | 0 | Lag lets a tenant exceed a cap |
| Idempotency, effect journal, job claim, lease, advisory-lock work | No | 0 | Correctness of exactly-once handling |
| Per-turn agent config (already cached per pod with a short TTL) | Possible later | TTL plus lag | The cache already accepts TTL staleness; lag adds to the bound, so it must stay well under it |
| Console list views and history browse (agents, runs, audit viewer) | Yes | seconds | Read-only, human-facing; a refresh converges |
| Knowledge/RAG retrieval (vector and keyword search) | Yes, conditionally | seconds to a minute | Highest-volume read and the best offload candidate; a just-ingested document may be missing for the lag duration |
| Analytics, usage dashboards, exports, reporting | Yes | minutes | Heavy scans that should not compete with the primary |
| Sweeps that only scan (do not claim) | Possible | seconds | Must not decide on stale state |

Order of value: reporting and dashboards first (no turn impact, most primary load), retrieval second
(turn-adjacent, so gated by measurement), per-turn config last.

### 4.3 Routing mechanism

Decision: **explicit opt-in per call site, with a second pooled `DataSource`, inside the API process.**

- A second Hikari pool pointed at the read endpoint, with its own small maximum and its own metrics. Same image,
  same process; no hop on the turn path beyond the database connection that already exists.
- Selection by an explicit marker, not by `readOnly`: a `@ReplicaRead(maxLagSeconds = n)` annotation (or a
  `ReplicaReads.run(...)` wrapper) on the specific service methods in the allowlist. Spring's
  `AbstractRoutingDataSource` with the Hibernate multi-tenant provider is the usual composition; the routing key
  is "replica or primary" and the tenant key stays the schema.
- The tenant schema must be applied on the replica connection exactly as on the primary. Because the
  `MultiTenantConnectionProvider` obtains its connection from a `DataSource`, the replica path needs its own
  provider instance (or routing below it) so `setSchema` and the reset run on the same connection that serves
  the query. The tenant isolation tests must run against the replica path before it ships; the hot-path page
  already says not to change that provider without them.
- Lag handling: the signal is the primary-side `pg_stat_replication.replay_lag` (or an LSN comparison,
  `pg_current_wal_lsn()` against the standby's `pg_last_wal_replay_lsn()`), **not** `now() -
  pg_last_xact_replay_timestamp()` on the standby, which keeps growing on an idle primary and would trip fallback
  with no real lag. A background check samples it every `p` seconds, so the worst-case staleness a call can see
  is roughly `p + lag`, not `lag`. To honour `maxLagSeconds` the gate must therefore trip at `maxLagSeconds - p`
  (with `p` kept to 1 to 2 s). When the sampled lag exceeds the gate, the sample is older than a few intervals,
  or the replica pool is unhealthy, the call **falls back to the primary**. Fallback, not failure: the feature
  must never make a read less available than today.
- Ordering against transactions: a connection is acquired when the transaction begins, so the replica-or-primary
  decision has to be made before it opens. `@ReplicaRead` must therefore sit on the outermost method, ahead of
  `@Transactional` (its advice ordered before `TransactionInterceptor`), and must set a thread-bound routing key
  that the routing `DataSource` and the replica `MultiTenantConnectionProvider` read at connection acquisition.
  A `@ReplicaRead` method called from inside an already-open primary transaction stays on the primary (a join
  cannot switch connection); a test must cover that. This is a design requirement, not something prototyped.
- The replica pool counts in a connection budget of its own. The chart guard must add
  `replica pods x replica pool` against the replica server's `max_connections`, a separate limit.
- A write in a replica-routed method is a bug; the standby rejects it, and a test must assert the allowlist
  contains no writing method.

### 4.4 Decision

Build it only behind `app.datasource.read-replica.enabled=false` and only after K2 below is met. First
consumer: one reporting endpoint, then retrieval, each measured against `HotPathQueryBudgetIT` and the kind
harness with and without it.

## 5. Cells

### 5.1 Definition

A **cell** is a full, independent deployment unit: API pods, jobs pods, a Postgres cluster, a Valkey, and
optionally its own object storage, serving a fixed subset of tenants. A tenant lives in exactly one cell. All
cells use the same chart and image; only values and the tenant list differ. It is the only mechanism in this
record that partitions the turn path itself: a cell's database, pools and pods see only that cell's tenants,
so more tenants means more cells rather than more load on one.

### 5.2 Routing without a hop on the chat path

The API pod must not ask "which cell?" at request time. Routing happens before the request reaches a pod:

| Layer | Mechanism | Hop cost |
|---|---|---|
| Per-tenant hostname | Each tenant gets a stable hostname (`<tenant>.<domain>`, or a custom domain). DNS (CNAME to the cell's ingress) resolves it to the owning cell. | None added: DNS replaces the lookup and the browser connects straight to the cell. |
| Shared hostname, tenant in token | An edge router forwards to the cell by a JWT-claim match on `tenant_id`. Stock ingress-nginx cannot match on a JWT claim; this needs an Envoy-based gateway (a Gateway API implementation, or Envoy with its JWT filter) or an auth proxy in front. | One proxy hop at the edge, which the ingress already is. It must be a data-plane proxy with a cached table, not a call into the application. Not available with the ingress controller the charts assume today. |
| Programmatic clients | The API base URL returned at sign-in or tenant discovery points at the cell hostname. | None on later calls. |

Per-tenant hostnames are not free of side effects. AGENTS.md rule 7 applies: every public issuer the browser
reaches Keycloak on must be listed in `KEYCLOAK_ADDITIONAL_ISSUERS`, so a hostname per tenant (or per cell) for
Keycloak itself would make that list change with every cell; the intended shape is one stable public Keycloak
hostname shared by all cells. Per-tenant application hostnames also multiply OAuth redirect URIs and allowed web
origins, CORS origins and cookie scope. None of this was designed or tried; it must be settled before the
hostname option is chosen over the claim-match option.

Recommendation: **per-tenant hostname via DNS as the primary mechanism** (subject to the issuer, redirect and
CORS work above), edge match on the tenant claim (needs the gateway above) as the fallback for a shared
hostname. Both keep the router out of the process and off the chat path. The control
plane (cell directory, 5.3) is consulted at sign-up, login, migration and by the edge's config sync, never per
chat request.

Tenant identity is a token claim in one Keycloak realm, so authentication may stay global while data is
cell-local.

**Control plane: "global in one home cell" does not work as the code stands.** The control-plane tables (115
migrations under `core/app/src/main/resources/db/migration/controlplane`) live in the same database the core
boots against, and every tenant iterator (`TenantSchemaIterator`) reads the list of active tenants from them. A
cell's core therefore needs its own control-plane schema in its own database. The workable shape is: each cell
holds a **replica of the registry rows for its own tenants** (tenant, plan, status, placement) plus the
cell-local control-plane tables, and the home cell is the writer of record for sign-up, billing and platform
admin. That replicated registry is a cost the earlier draft left unstated: it needs a sync mechanism, a rule for
who wins a conflict (the home cell, for registry rows), and a statement of staleness (a cell keeps serving from
its copy if home is unreachable, but cannot learn of a suspension or plan change until sync resumes). It is the
load-bearing assumption of section 5, it was not validated, and it is a reason cells are criterion-gated (K1).

### 5.3 Cell directory

A small control-plane table: `tenant_id -> cell_id, state, version`, and `cell_id -> ingress host, region,
capacity, status`. Tenant states: `ACTIVE`, `MIGRATING`, `READ_ONLY`, `DRAINING`. Edge routers and DNS
automation sync from it; a pod never reads it on the turn path. A cell that cannot reach the directory keeps
serving its tenants from its own registry copy (5.2), so the directory is an availability dependency of
provisioning and migration only.

**Relation to ADR-0015's placement row.** ADR-0015 puts a `cell` column on the per-tenant placement row and
expects the hot path to consult placement from memory. That is the **same fact** as `tenant_id -> cell_id`
here, so there is one source of truth, not two: the `cell` column on the placement row in the control-plane
registry is authoritative for "which cell owns this tenant", and the directory is a **projection** of it for the
edge (DNS and gateway config), written by the home cell and never edited independently. A pod's in-memory
placement lookup (ADR-0015 section 3.2) answers "`SCHEMA` or `SHARED`" within its own cell and may check that
the tenant belongs to this cell; it does not route between cells. The earlier "pod never reads the directory"
is thus narrowed to: a pod never makes a call to find another cell, and consults only its own placement cache,
which ADR-0015 already requires.

Placement at sign-up: the least-loaded cell with headroom under K1 and K3, or a requested region for data
residency (ADR-0002 already names residency as a reason for schema-per-tenant).

### 5.4 Migrating a tenant between cells

A tenant is a Postgres schema plus Valkey keys plus object-store paths plus Keycloak attributes. Migration is
an operation, not a hot path, and may include a write freeze of minutes.

1. Directory state `MIGRATING`; the source cell sets the tenant read-only (the existing tenant emergency
   controls and kill switches are the natural lever).
2. Dump or logically replicate the tenant schema to the destination Postgres; copy object-store prefixes;
   verify row counts and checksums.
3. Run Flyway for the tenant schema on the destination. Flyway versions are one namespace, so cells must run
   the same image, which the core-family image skew check already encourages inside a release.
4. Flip the directory entry and the DNS record (TTL lowered beforehand). In-flight streams on the source finish
   within the drain window; resumable streams are cell-local and are not migrated.
5. Keep the source schema read-only for a retention window, then drop it. Rollback is flipping back before the
   drop.

Open: durable agent jobs, schedules and workflow runs in flight at cut-over; per-tenant secrets
(`APP_ENCRYPTION_KEY` must be identical for every process sharing a database, so cells either share the key or
re-encrypt secrets on move).

### 5.5 Failure isolation

| Failure | Without cells | With cells |
|---|---|---|
| Postgres primary down or saturated | Every tenant | Only that cell's tenants |
| Bad deploy or migration | Every tenant | Roll out to a canary cell first; blast radius is the cell |
| One tenant's runaway load or noisy sweep | All pods and the database | Bounded by the cell; move the tenant to its own cell |
| Valkey outage | Every replica's shared stores | That cell |
| Control plane or directory down | Everything that provisions | Existing tenants keep serving; no sign-ups or moves |

Cost: N copies of the baseline footprint, N upgrades to coordinate, cross-cell reporting needs a fan-out job
(off the chat path), and platform-admin features that assume one database must become cell-aware.

## 6. Composition with hybrid tenancy (ADR-0015)

This section relies on ADR-0015 (PR #96, proposed, design only) and does not change it. Taken from ADR-0015:

- Placement per tenant is `SCHEMA` (a dedicated schema, for large or regulated tenants) or `SHARED` (rows in one
  `tenant_shared` schema with `FORCE ROW LEVEL SECURITY`, for the long tail). Both live **inside one Postgres
  database**; `DATABASE` is mentioned there only as a possible later third placement for the top tier.
- The placement row has a `cell` column, and there is a hard cap on dedicated schemas per database (initially
  2,000), after which "a new cell is needed". The residency rule (a declared region only on a cell in that
  region) hangs off `cell`.
- The hot path reads placement from an in-process cache (rule 6).

An earlier draft of this record assumed the opposite (small tenants as schemas in a shared database, large
tenants on a dedicated database or cluster). That was wrong and is withdrawn. Consequences under the real shape:

- A **cell is a database cluster plus its pods**, and it holds both `SCHEMA` tenants and the `tenant_shared`
  tier. A cell is the level of scale **above** hybrid placement, as ADR-0015's alternative 2 says: hybrid
  placement reduces per-tenant catalog and release cost inside a database; cells bound the number of tenants and
  the blast radius per database. They are complementary, and neither replaces the other.
- A large tenant is **not** a one-tenant cell by default; it is a `SCHEMA` tenant in a cell. A one-tenant cell
  (or ADR-0015's possible `DATABASE` placement) is an option for the very top tier only.
- The connection budget in section 3 applies **per cell**, shared by `SCHEMA` and `SHARED` tenants alike
  (ADR-0015 section 3.4: both draw from the same Hikari pools). ADR-0015's second `jobs` role and pool draws on
  the same budget and must be added to the "fixed demand" column of 3.2 if it is built; the table above does not
  include it.
- Moving a tenant between cells (5.4) means, for a `SHARED` tenant, copying its rows by `tenant_id` (ADR-0015's
  export path), not dumping a schema; for a `SCHEMA` tenant it is the schema dump in 5.4. The procedure should
  reuse ADR-0015's promotion/demotion copy-and-fence machinery rather than build a second one. Neither exists;
  both are designs.
- Read replicas are per cell and belong to the cell's Postgres. For a `SHARED` tenant the replica connection must
  set `app.tenant_id` as well as `search_path`, or RLS returns zero rows, so the replica provider (4.3) must
  reuse ADR-0015's provider switch rather than fork it. How it interacts with ADR-0016's transaction-scoped
  `SET LOCAL` (if adopted) is unresolved: the replica path must follow whichever tenant-binding scheme is in
  force.
- Order of work: measurement (3.4), then ADR-0015's stage 0 and its gates, then the cell directory as a
  projection of the placement row, then per-tenant hostname or gateway routing, then the migration procedure.
  Cells should not be started before ADR-0015's early stages show whether the shared tier is built at all (its
  own kill criterion), because that changes how many tenants a cell needs to hold.

## 7. Alternatives considered

- **Transaction-mode pooling to multiplex.** Rejected: wrong-tenant data (3.4).
- **Database per tenant.** Needs a pool per tenant, the property ADR-0002 chose schema-per-tenant to avoid.
  A single-tenant cell remains possible for the very top tier (section 6).
- **Sharding inside one deployment (a router in the API).** Puts routing in the process and keeps one
  deployment as the failure domain. Cells move routing to the edge and isolate failure.
- **Route every `readOnly` transaction to a replica.** Rejected: the annotation does not mean stale-tolerant
  (4.1), and about 380 sites would change behaviour at once.
- **An application-level cell router the API calls per request.** Rejected: a new hop on the turn path
  (AGENTS.md rule 6).

## 8. Kill criteria

Numbers are starting points to be set from measurement, not claims. The kind harness has run-to-run noise, so
every latency threshold below (K1's 50 ms Hikari p99, K2's 3% turn p95, K4's "worsens") is judged on at least
five alternating A/B runs per arm at equal load, comparing medians against the observed spread of the baseline
arm; a difference inside that spread is "no change", neither pass nor fail. The run count and spread are recorded
with the result. Until a baseline spread has been measured, these are not usable thresholds.

| ID | Do the step when | Abandon or roll back the step when |
|---|---|---|
| K1 (cells) | Primary Postgres at or above 70% CPU or 70% of usable connections at peak after 3.4 steps 1 to 4 are done, **or** Hikari acquire p99 above 50 ms with pool and server already enlarged, **or** one cluster's blast radius is a stated business risk | A rehearsal cannot move a test tenant with under 5 minutes of write freeze and zero data loss |
| K2 (replicas) | Reporting or retrieval reads are more than 25% of primary load (measured) and offloading them is projected to relieve K1 | Replica lag p99 exceeds the tightest enabled call-site bound for 5 minutes in a day, or turn p95 latency rises more than 3% in the kind harness with the replica path on, or any isolation test shows a wrong-schema read |
| K3 (move a tenant) | A single tenant uses more than 20% of a cell's connection-seconds or database CPU for a sustained week | Migration rehearsal fails twice |
| K4 (any step) | n/a | Chat-turn p50 or p95 worsens versus the previous published `tests/load/kind` run at equal load. Hard stop on every step: revert, do not tune forward |

## 9. Verification plan (before any code)

- Add Hikari usage and acquire histograms and connection-seconds per turn to the kind harness and
  `HotPathQueryBudgetIT`, and record a baseline at 1, 2 and 4 API replicas.
- A lag-injection test for the replica path (pause replay on the standby) that proves fallback to the primary.
- The existing tenant isolation tests run against a replica-routed provider.
- A migration rehearsal between two kind namespaces with a synthetic tenant and a running stream.

## 10. What is not proven

- **No number in 3.3 is measured.** The ordering of what breaks first is reasoning from pool sizes, the
  hot-path checkout counts and the known pinned-connection behaviour. Hold time per turn, Hikari wait and
  Postgres cost per connection were not measured.
- **The capacity table in 3.2 is arithmetic** from the chart guard's formula, not a load test. It counts
  allocated connections, not used ones, and covers Keycloak and monitoring only through the reserve of 20.
- **The read-only Service** is the CloudNativePG operator's behaviour. The repository has no consumer of it, and
  no cluster with more than one Postgres instance was run for this record.
- **No replica routing was prototyped.** That Hibernate's multi-tenant provider composes with a second
  `DataSource` while keeping the `setSchema` and reset guarantees is a design expectation, not a result.
- **Replica lag tolerances are proposals** per read, not observed lag. Replay delay under real vacuum and long
  query load is unknown.
- **No cell exists.** The routing options, migration procedure and directory shape were not built or tested;
  the 5-minute write-freeze target is a goal. Whether Keycloak and the control plane can stay global was not
  verified, nor how many platform-admin features assume a single database.
- **Hybrid tenancy** (ADR-0015) and the `search_path` record (ADR-0016) are open PRs (#96, #93), not on `main`.
  Section 6 was reconciled against the text of PR #96 as it stood when this was revised, not against code.
- **What was run for this PR:** only the repository documentation gates (`make gates`), which check links, file
  size and secrets and do not validate any technical content here. Every figure is hand arithmetic or a `grep`
  count.
- **Multi-replica API itself remains unproven** (ADR-0014 section 6), and everything here inherits that.
