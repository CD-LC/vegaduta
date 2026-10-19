# ADR-0015: Hybrid tenancy: dedicated schemas for large tenants, shared tables with row-level security for the long tail

| | |
|---|---|
| **Status** | Proposed. Design only: no code, configuration or migration ships with this document. The measurements in section 2 were run on a scratch database on the local kind stage; nothing here has run on a production-sized cluster. |
| **Date** | 2026-10-13 |
| **Deciders** | Platform Engineering (maintainers) |
| **Related** | [ADR-0014 horizontal scaling](ADR-0014-horizontal-scaling.md), [Hot-path performance](HOT-PATH-PERFORMANCE.md), [Deployment shapes](../deploy/DEPLOYMENT-SHAPES.md), [ADR-0001 modular monolith](../platform-documentation/decisions/adr-0001-modular-monolith.html) |

## 1. Context

Every tenant is a Postgres schema. A request sets `search_path` on the connection it borrows
(`SchemaMultiTenantConnectionProvider`, which calls `Connection.setSchema`), and Hibernate resolves the schema
from `TenantContext` (`TenantSchemaResolver`). Background jobs walk the active tenants one schema at a time
(`TenantSchemaIterator`). Each release runs Flyway against every active tenant schema
(`TenantMigrationService`, `TenantSchemaMigrator`). This is simple and strongly isolating, and it is the
reason a missed `WHERE tenant_id = ?` cannot leak data between tenants today.

The goal of the wider effort is a platform that does not slow down as users grow. Three costs grow with the
tenant count, not with the load a tenant puts on the platform:

1. **A release costs time proportional to tenants.** Boot-time migration touches every schema.
2. **The Postgres catalog grows by about a thousand relations per tenant**, whether the tenant is active or
   has never sent a message.
3. **Every background sweep is O(tenants)**, one `SET search_path` and one query per schema.

Most tenants in a self-serve product are small and idle. A few are large. One structure serves both badly: it
over-isolates the long tail and under-serves the large tenants that would be better on dedicated resources
anyway.

Constraints that this design must keep (from AGENTS.md and ADR-0014):

- The chat hot path stays in one process. No new network hop between platform modules (rule 6). The placement
  decision for a request must be answered from memory.
- PgBouncer stays in session pooling mode (rule 8). Anything that sets per-request database state must be
  reset on release, and must fail closed.
- Flyway versions are one namespace (rule 3); a released migration is never edited.
- `APP_ENCRYPTION_KEY` and the token issuers are untouched.

## 2. What the repository and the stage say today

Counts come from the source tree and, where marked, from the live tenant schema and a scratch database on the
kind Postgres 16.15 (`max_connections=200`, `max_locks_per_transaction=64`, `shared_buffers=256MB`). The
scratch database is `tenancy_scratch`, built from a schema-only dump of the one live tenant schema, copied 300
times; it is not a production data shape.

### 2.1 What one tenant costs

| Measure | Value | Source |
|---|---|---|
| Versioned tenant migrations | 233 files in `db/migration/tenant` (highest V474) | source tree |
| Versioned control-plane migrations | 115 files (highest V480) | source tree |
| Tables per tenant schema | **193** | live tenant schema |
| Indexes, constraints, TOAST tables per tenant | 500, 439, 150 | live tenant schema |
| `pg_class` rows per tenant | about 1,000 (300,113 rows at 300 schemas) | scratch |
| `pg_attribute` rows per tenant | about 5,570 (1,670,508 rows at 300 schemas) | scratch |
| Disk per tenant with empty tables | about 7.7 MB (2,328 MB database at 300 schemas) | scratch |
| Second schema per tenant (Apache AGE graph `<schema>_kg`) | 1, with 2 tables | live tenant schema |
| Tenant JPA entities | roughly 170 of 216 `@Entity` classes (those that do not name `control_plane`; coarse count, re-derive in stage 1) | source tree |
| `TenantSchemaIterator` users | 72 files reference it, about 60 call sites of `forEachActiveTenantSchema*` (59 when first counted, 64 on a later grep that also matched definitions) | source tree |

Extrapolated linearly (an assumption; Postgres does not promise linear behaviour at these sizes):

| Tenants | `pg_class` rows | `pg_attribute` rows | Empty-tenant disk | Relation files, lower bound |
|---|---|---|---|---|
| 1,000 | 1 M | 5.6 M | 7.7 GB | 1 M |
| 10,000 | 10 M | 56 M | 77 GB | 10 M |
| 100,000 | 100 M | 557 M | 770 GB | 100 M |

Each tenant also adds a `_kg` schema, so namespaces are twice the tenant count.

### 2.2 What a release costs

`TenantSchemaMigrator` documents about 1.35 s per schema for a Flyway run that finds nothing to do (70 s for 52
schemas, sequentially, in production; a classpath scan of about 200 scripts plus checksum validation per
schema). That figure is the repository's own and was **not re-measured here**. It grows with the script count.
`app.tenancy.migrate-parallelism` defaults to 4 and is capped by the pool size.

| Tenant schemas | Today: full Flyway per schema, 4 at a time | `migrate-skip-up-to-date=true`, measured 1.4 ms per schema over one session, plus network round trips |
|---|---|---|
| 1,000 | 5.6 min | 1.4 s |
| 10,000 | **56 min** | 14 s |
| 100,000 | **9.4 h** | 144 s |

The 1.4 ms is 300 schemas checked in 433 ms by a loop on the server (`select count(*) from <schema>.flyway_schema_history`),
so it excludes connection and network cost. The migration itself is not the expensive part: `ALTER TABLE ... ADD
COLUMN` on an empty table in each of 300 schemas took 390 ms in total (1.3 ms each) and a `CREATE INDEX` took 747 ms
(2.5 ms each). On real data the DDL will cost more, but the fixed per-schema Flyway overhead is what makes a
release scale with the tenant count.

A release at 100,000 tenant schemas means the migrate Job runs for hours while the old pods keep serving. That is
a deploy that cannot be rolled forward quickly or safely rolled back.

### 2.3 Other limits seen on the stage

- **Whole-database logical backup breaks early.** `pg_dump -s` of the scratch database (298 schemas at that point; it was built with 300 copies and the reason for the difference was not recorded) failed with
  `out of shared memory` while locking the tables of schema 129. With 193 tables per tenant and the default
  `max_locks_per_transaction`, a plain dump holds one lock per table; the ceiling is in the low hundreds of
  tenants unless the setting is raised. Logical backup is therefore not a disaster-recovery plan past that size.
  Physical backup with WAL archiving is the one that scales; this document did not check what the bundled
  Postgres chart provisions for it.
- **Per-tenant dump cost follows total catalog size, not tenant size.** `pg_dump -n` of one empty schema in the
  298-schema database took 2.86 s; of the real 32 MB tenant schema in the 2-schema database, 0.56 s. One run
  each, through `kubectl exec`, so only the direction is informative.
- **Row-level security is not the expensive part of the shared design.** A 1 M-row table with `tenant_id`, a
  `(tenant_id, id)` primary key and a policy on `current_setting('app.tenant_id', true)`: `begin; set_config;
  select ... limit 20; commit` ran at 6,449 tps (0.620 ms) against 5,935 tps (0.674 ms) for the same shape with
  `SET LOCAL search_path` into a 1,000-row tenant schema (4 clients, 15 s, same host as the load generator). The
  plan pushes the policy into the index condition (`Index Cond: (tenant_id = NULLIF(current_setting(...)))`).
  The same query with an explicit `WHERE tenant_id = ?` and no transaction ran at 20,135 tps because it is one
  round trip instead of four, which says round trips dominate, not the policy. With no `app.tenant_id` set the
  RLS table returned 0 rows (fail closed). **Caveats, so this is a hypothesis from one run and not a result:** the
  two sides differ in row count (1 M rows in one shared table against 1,000 rows in a schema), the benchmark used
  `set_config(..., true)` inside a transaction while the proposed provider uses `is_local = false` with no
  transaction, the number of tenants sharing the 1 M rows was not recorded, and it did not exercise pgjdbc
  generic (prepared) plans with skewed tenant sizes, where a `tenant_id = NULLIF(current_setting(...))` predicate
  can pick one plan for every tenant. Stage 1 repeats it with the provider's real shape and skewed tenants.
- **The hot-path cost of a cold connection** and of `search_path` switching with many schemas was measured, but the
  two scripts differed in shape, so the comparison is confounded and is not used.

## 3. Decision

Introduce a **placement** for each tenant and support two data placements behind it:

- **`SCHEMA`** (today's model): the tenant has its own schema, `t_<26 chars>`, exactly as now. Used for large
  tenants and for anything that needs hard isolation (contract, residency, BYOK, graph memory, large vector
  corpus).
- **`SHARED`**: the tenant's rows live in one shared schema, `tenant_shared`, in tables with the same names and
  columns as a tenant schema plus a `tenant_id` column, protected by `FORCE ROW LEVEL SECURITY`. Used for the
  long tail.

Placement is a property of a tenant, stored in the control plane, cached in-process, and changed only by an
explicit promotion or demotion. A tenant is never in both at once for writes. New tenants start `SHARED` once the
shared tier is switched on for them; until then everything stays `SCHEMA`, which is also the rollback state.

The rest of this section states how it works. It is a design; every mechanism marked **spike** must pass the
stage-1 gate in section 8 before anything depends on it.

### 3.1 One definition of the tables, not two

Writing and maintaining a second set of migrations for the shared tier would double the work on every schema
change and would drift. The proposal is to keep `db/migration/tenant` as the only authored definition and to
derive the shared form from it:

1. **Baseline, once.** A one-time converter takes the schema produced by the tenant migrations up to a chosen
   version and produces `tenant_shared`: adds `tenant_id uuid NOT NULL DEFAULT
   nullif(current_setting('app.tenant_id', true), '')::uuid` to each table, enables and forces RLS, creates
   per-command policies (3.3), and rebuilds primary keys, unique constraints and unique indexes with `tenant_id`
   as the leading column. Foreign keys between tenant tables become composite keys including `tenant_id`.
2. **Later tenant migrations are not replayed through the converter as a one-shot `afterMigrate` step.** That
   was the first idea and it is wrong: once a table has been converted, the next migration sees a changed
   table. `ADD FOREIGN KEY ... REFERENCES t(id)` fails because the key is now `(tenant_id, id)`, and `ON CONFLICT
   (id)` no longer matches an index. So after the baseline each new tenant migration is applied to `tenant_shared`
   through a **per-migration shared variant**: the converter generates a draft from the schema-mode script
   (rewriting `REFERENCES`, `ON CONFLICT`, unique indexes), a human reviews and commits it under
   `db/migration/shared`, and a parity gate fails the build if a tenant migration has no shared variant or the two
   produce different column sets. This is the twin-tree fallback of stage 1 made the default for everything after
   the baseline, because an SQL rewriter that must be correct for arbitrary future DDL is not something this ADR
   can show is feasible. Whether a rewriter is good enough is an open question (section 9, item 6).
3. **Data migrations are not run through Flyway into the shared tier.** Ten existing tenant migrations contain
   DML (`V15`, `V48`, `V145`, `V154`, `V175`, `V215`, `V224`, `V342`, `V352`, `V366`; listed by the reviewer, not
   re-derived here). Under `FORCE ROW LEVEL SECURITY` and no `app.tenant_id`, a migration's `UPDATE` matches zero
   rows silently and its `INSERT` fails on `NOT NULL`; and a backfill is per-tenant by nature. Rule: a shared-tier
   migration contains DDL only. The shared baseline starts from an empty schema, so what the historic DML in those
   ten files produced is either part of the baseline definition or becomes per-tenant provisioning seed code,
   decided file by file in stage 1. A future backfill is a per-tenant job that runs for each `SHARED` tenant with
   `app.tenant_id` set, tracked by a per-tenant data-version column in the control plane, run by the jobs shape,
   and also run for a newly provisioned tenant.
4. A parity test fails the build if any table in `tenant_shared` lacks the column, the forced policies, or a
   tenant-leading key, or if a construct the converter cannot translate appears (it must be added to the
   converter or waived in a checked-in list with a reason). The waiver list is how tables that stay
   `SCHEMA`-only (graph, vector) are declared.

Because the default for `tenant_id` comes from the same setting the policy reads, the **intent** is that existing INSERT
statements, JPA entities and native queries need no change: they do not mention `tenant_id`, the database fills it
in, and RLS filters every read, update and delete. This is a design assertion, not something checked; the
native-SQL audit below and the stage-2 suite are what would confirm or refute it.

Whether the converter handles enough of the 193 tables automatically is the main unknown of this ADR (section 8,
stage 1). If it does not, the fallback is a hand-written `db/migration/shared` tree with a parity gate; that is
more work and is the reason stage 1 has a kill criterion.

### 3.2 How Hibernate and entities map in both modes

The entity classes, repositories and `@Table` annotations do not change. Tenant tables are mapped without a
schema qualifier and rely on `search_path`. What changes is the tenant identifier Hibernate passes to the
connection provider:

- `TenantSchemaResolver` returns an opaque placement token instead of a bare schema name, derived from
  `TenantContext` and an in-process placement cache (the same `TtlCache` family used for plan and permissions,
  keyed by tenant id; no database hop on the turn path, rule 6). `SCHEMA` placement returns the schema name as
  today. `SHARED` returns a token carrying the tenant id.
- `SchemaMultiTenantConnectionProvider.getConnection` switches on the token. `SCHEMA`: `setSchema` as today.
  `SHARED`: one statement, `select set_config('search_path', 'tenant_shared', false), set_config('app.tenant_id',
  ?, false)`. That is the same number of round trips as the two `SET`s per transaction that the hot-path document
  already lists, so the shared mode does not add a round trip.
- **Flag-off equivalence.** With `app.tenancy.shared.enabled=false` the resolver must return the bare schema name
  without consulting the placement cache and the provider must take today's `setSchema` branch, so the flag-off
  path is behaviourally the current code. This is a requirement that stage 2 proves (unit tests on both classes plus
  `HotPathQueryBudgetIT` unchanged); until it is proven, "default behaviour change: off" in section 8 is a goal.
  [Hot-path performance](HOT-PATH-PERFORMANCE.md) says not to change `search_path` handling without an isolation
  test suite; the cross-tenant suite of stage 2 is that gate and nothing ships before it.
- **Placement cache failure behaviour.** A miss does one synchronous, single-flight control-plane lookup (the only
  database hop, and only on a cold entry). If a refresh fails, the stale entry is served for up to twice the TTL,
  then requests for that tenant fail closed (503), never defaulting to `SCHEMA` or `SHARED`. Stale reads are
  tolerable because the write fence (3.6) is what protects correctness.
- `releaseConnection` resets both settings and, as today, aborts the connection if the reset fails, so a
  borrowed connection never carries a tenant into the next request.
- Control-plane entities keep their explicit `schema = "control_plane"` and never see RLS.

Things that need an audit, not just a code change:

- **Native SQL.** 56 non-test native-SQL sites: 27 `nativeQuery = true` annotations in 11 files plus 29 `createNativeQuery`
  calls, and 42 files use `JdbcTemplate` (that last count was not re-derived). Each must be unqualified
  (so it follows `search_path`) and must not rely on a table existing only per tenant. A guard test lists any that
  name a tenant schema explicitly.
- **Identity.** Primary keys must be unique across tenants in the shared schema. UUID keys are; any
  per-tenant natural key or sequence-derived value used as an identifier outside its table must be checked. The
  converter makes keys tenant-leading but cannot fix application code that treats a number as globally unique.
- **Hibernate caches.** Any second-level or query cache keyed by entity id without the tenant would cross
  tenants in shared mode. Confirm none is enabled; if one is, key it by tenant.
- **Apache AGE graph memory** (`<schema>_kg`) and **pgvector** are schema-bound. Graph memory stays
  `SCHEMA`-only: enabling it on a `SHARED` tenant triggers promotion. A shared vector table would need a
  tenant filter on an ANN index, which is weak in pgvector: either keep an exact scan with the `(tenant_id, ...)`
  prefix for small corpora (a few thousand vectors per tenant) and promote past a chunk-count threshold, or use
  pgvector's iterative index scans. This is an open decision (section 9).

### 3.3 Roles, and what keeps RLS honest

**Today there is one login.** `deploy/compose/.env.example` (lines 102-107) documents `vegaduta_app` as the owner of
the database and the single login every core shape uses; `db-roles` creates it and adopts objects. The role split
below is therefore **new work**, touching `db-roles`, the Helm chart and the provisioning path (the `api` shape
creates schemas and runs Flyway for new tenants), and it is a stage-2 prerequisite (section 8), not an existing
property. To keep it small:

- **Only `tenant_shared` changes ownership.** Dedicated schemas stay owned by `vegaduta_app` exactly as now (no RLS
  there), so the provisioning path for `SCHEMA` tenants is unchanged. `tenant_shared` and its tables are owned by a
  new `vegaduta_shared_owner`, used by the `migrate` shape only. `vegaduta_app` has DML privileges on
  `tenant_shared`, is not the owner and is not `BYPASSRLS`; tables use `FORCE ROW LEVEL SECURITY` anyway.
- **Policies are per command**, not a single `FOR ALL`, where `ctx` is
  `NULLIF(current_setting('app.tenant_id', true), '')::uuid` and `fenced` is the `placement_fence` probe of 3.6:
  `SELECT USING (tenant_id = ctx)`; `INSERT WITH CHECK (tenant_id = ctx AND NOT fenced)`;
  `UPDATE USING (tenant_id = ctx AND NOT fenced) WITH CHECK (tenant_id = ctx AND NOT fenced)`;
  `DELETE USING (tenant_id = ctx AND NOT fenced)`. `WITH CHECK` alone would not stop a `DELETE`, which consults
  only `USING`. These four policies are created `TO vegaduta_app` (the application login) only. A policy without a
  `TO` clause applies to every role, which would make the fence block the rollback-copy cleanup and the erasure
  that must outlive it (3.6 steps 5-6, 3.8), so `vegaduta_erasure` gets **its own** policies:
  `SELECT USING (tenant_id = ctx)` and `DELETE USING (tenant_id = ctx)`, with no fence term and no `INSERT` or
  `UPDATE` policy. It is still scoped to one tenant by `app.tenant_id`. `vegaduta_jobs` has only its `SELECT`
  policy below, and `vegaduta_promoter` has the two policies below. A test in stage 1 must show a fenced tenant's `DELETE` matching zero rows as `vegaduta_app` and
  deleting all of its rows as `vegaduta_erasure`.
- **Who reads `placement_fence`.** The policies evaluate the fence probe as the invoking role, so `vegaduta_app`
  needs `SELECT` on `placement_fence` (only `SELECT`; `vegaduta_erasure`, `vegaduta_jobs` and `vegaduta_promoter` do
  not evaluate it). `placement_fence` has no RLS (it is a control table keyed by tenant, read by every shared-tier
  write) and is owned by `vegaduta_shared_owner`.
- **The promoter needs its own policies.** The promoter is a control-plane code path that runs as a separate
  `vegaduta_promoter` login (new, section 9 item 9). Under `FORCE ROW LEVEL SECURITY` a role with no applicable
  policy sees zero rows, and the four policies above are `TO vegaduta_app` only, so without more it would copy,
  replay and hash an empty set and report a match. It therefore gets, `TO vegaduta_promoter`:
  `SELECT USING (tenant_id = ctx)` (it sets `app.tenant_id` to the tenant being promoted or demoted, so it needs no
  `BYPASSRLS`, and it reads that one tenant only) and `INSERT WITH CHECK (tenant_id = ctx)`, which is how the
  **demotion** copy-back writes rows into `tenant_shared`. It has no `UPDATE` or `DELETE` policy, so it cannot alter
  or remove shared rows; cleaning up a half-finished demotion copy, like the rollback-copy cleanup of promotion, is
  done by `vegaduta_erasure`. The promoter's insert policy has no fence term because the demoting tenant has no
  `placement_fence` row (the shared side is not authoritative for it yet); what stops it writing for the wrong tenant
  or at the wrong time is `ctx` plus the control-plane state machine of 3.6, which only starts the promoter for a
  tenant in `PROMOTING` or `DEMOTING`. Its other privileges: `INSERT`/`DELETE` on `placement_fence`,
  `pg_read_all_stats` for the drain of 3.6, `SELECT`/`INSERT` table grants on `tenant_shared`, and the privileges to
  create and populate the dedicated schema (as `vegaduta_app` has today). None of this has been built or run.
- **What `vegaduta_erasure` needs.** It is a `LOGIN` role used only by a dedicated small datasource in the erasure
  service (`TenantDataErasureService`, jobs shape), never by a request path. It needs `USAGE` on `tenant_shared` and
  **table-level** `SELECT` and `DELETE` on every table in it (policies alone grant nothing; `SELECT` is needed for the
  `WHERE tenant_id = ?` predicate and the verify pass), added to new tables by the lint of 3.5. It sets the tenant
  with `select set_config('app.tenant_id', ?, false)` on each borrowed connection and resets it on release, the same
  provider logic as 3.2; a connection with no setting deletes nothing. Because it carries no fence term, the
  control plane refuses to start erasure for a tenant that is `PROMOTING` or still serving writes from `SHARED`
  (the tenant is suspended first), so erasure cannot race the drain of 3.6.
- **`tenant_id` is immutable to application roles** through column privileges: `REVOKE UPDATE (tenant_id)` from
  `vegaduta_app` (grant `UPDATE` on every other column; the other roles have no `UPDATE` on shared tables at all). A policy cannot compare `NEW` to `OLD`,
  so this is what stops a role from rewriting `tenant_id`. **Maintenance cost:** a `REVOKE` of one column only has
  an effect if no table-level `UPDATE` grant exists, so the design is a per-column `UPDATE` grant on every column
  except `tenant_id`. Default privileges do not cover columns, so every shared-variant migration that adds a column
  must also `GRANT UPDATE (new_col)` to `vegaduta_app`, or application `UPDATE`s of that column fail with a permission
  error. The shared-variant lint of 3.5 enforces this (see there). The alternative, a `BEFORE UPDATE` trigger that
  raises when `NEW.tenant_id IS DISTINCT FROM OLD.tenant_id`, needs no per-column grants but adds a trigger to
  every shared table; which one to use is part of open decision 9.
- **Which login each shape uses, and what `vegaduta_jobs` is.** Every core shape, including `jobs` and `worker`,
  logs in for its tenant work as **`vegaduta_app`**, exactly as ADR-0014 has them share one login today. The jobs
  shape writes agent jobs, workflow runs and ingest rows, so those writes go through the same four fenced policies
  as the API shape's, and the drain of 3.6 covers them. `vegaduta_jobs` is **not** a shape login: it is a
  read-only *discovery* login behind a separate small datasource, used only to answer "which tenants have work" in
  one indexed query instead of visiting 100,000 schemas. Its only policy is `FOR SELECT TO vegaduta_jobs USING
  (true)`, per table and listed explicitly; it has table `SELECT` and no `INSERT`, `UPDATE` or `DELETE` grant of any
  kind (so there is no column `UPDATE` to maintain and no way to rewrite `tenant_id`). The sweep then processes each
  tenant it found through the normal `vegaduta_app` pool under that tenant's `TenantContext`, because the
  downstream service code needs the context anyway. The API shape's discovery is unchanged by this ADR; the
  fourteen sweeps ADR-0014 notes still tick there only get a cross-tenant query if the API shape is also given the
  discovery datasource, which is a deployment decision (section 9, item 9), otherwise they keep iterating schemas
  or move to the jobs shape.
- **Write-capable roles are an enumerated list, each covered by the fence and the drain.** The roles that can write
  tenant rows in `tenant_shared` are exactly: `vegaduta_app` (fenced, in the drain), `vegaduta_promoter` (`INSERT`
  only, tenant-scoped, started only by the state machine, so it is never concurrent with a fence on the same
  tenant) and `vegaduta_erasure` (`DELETE` only, refused while the tenant serves writes). Any role later given a
  write policy on tenant data must be added to this list, to the fenced-policy `TO` clause and to the drain's role
  filter in 3.6 step 3; the stage-2 role-matrix test (below) fails the build if a role holds `INSERT`, `UPDATE` or
  `DELETE` on a shared table and is not on the list.
- **`vegaduta_erasure`** exists only for deleting from the shared tier, including the append-only tables (3.8).
- Views over tenant tables are `security_invoker`. A grep of `core/app/src/main/resources/db/migration` for
  `SECURITY DEFINER` finds nothing, and the trigger functions of `V75` and `V422` are plain invoker `plpgsql`. That is
  all that was checked: functions created outside the Flyway tree were not searched. `COPY` and `pg_stat_*` are not
  exposed to tenants.
- Missing context fails closed: unset `app.tenant_id` yields zero rows (measured above) and a null `tenant_id`
  fails the `NOT NULL`. The existing `app.tenancy.fail-on-missing-context` guard continues to apply.

### 3.4 PgBouncer and `SET search_path`

Nothing about the pooler changes. Both placements set session state on a borrowed connection and reset it on
release, which is only safe under **session** pooling, and that is the supported mode (rule 8). The shared mode
therefore uses `is_local = false` for `set_config` and relies on the provider's reset, not on transaction scope.

A transaction-pooled pool for the shared tier alone (every statement inside a transaction that starts with
`set_config(..., true)`) would remove the session-state requirement for that tier and let one pooler serve many
more clients than the Postgres connection limit allows. It is deliberately **out of scope** here: it needs its
own isolation test suite (the same one the hot-path document asks for before touching `search_path` handling),
a second pool, and an answer to prepared statements through the pooler. It is listed as stage 5 work.

The connection budget is unchanged: both modes draw from the same Hikari pools, so the chart's render-time
check still holds.

### 3.5 Flyway and the release path

Three changes, in order of value:

1. **Stop touching every schema to learn it is current.** Add `migrated_version` (and `migrated_at`) to the
   control-plane `tenant` row, written by the migrator on success. A release selects the schemas whose version is
   below the target and migrates only those. A no-op release becomes one query regardless of tenant count. This
   is independent of the hybrid and is worth doing first (stage 0). It supersedes `migrate-skip-up-to-date`,
   which still opens a history table per schema.
2. **Migrate the shared tier once.** `tenant_shared` is migrated by the `migrate` Job in a single pass, under the
   existing advisory lock, before the dedicated schemas. Because many pods still run the old code while it
   happens, the shared tier requires **expand/contract** discipline: a release only adds (columns nullable or
   with cheap defaults, tables, indexes) and the next release removes. Enforce it with a lint on shared-tier
   migrations: no table rewrite, no `NOT NULL` without default on a large table, no non-concurrent index on a
   table above a size threshold, always `SET lock_timeout`. `CREATE INDEX CONCURRENTLY` cannot run inside a
   Flyway transaction, so such scripts need Flyway's `executeInTransaction=false` and a naming convention.
   The same lint also requires that every `ADD COLUMN` on a shared table is followed in the same script by
   `GRANT UPDATE (<column>)` to `vegaduta_app` (the column-level grant model of 3.3), and that
   a new shared table gets its four per-command policies, the promoter, erasure and jobs policies, the table grants
   of 3.3 and `FORCE ROW LEVEL SECURITY`;
   the lint fails the build otherwise. Not yet written.
3. **Migrate the dedicated schemas as today**, now only a bounded set (section 3.7), in parallel and gated by the
   pool-size cap that exists.

Migration numbers stay in the single Flyway namespace. The control-plane migration that adds the placement
columns takes the next free version at implementation time; this ADR does not reserve one.

### 3.6 Promotion and demotion

A tenant's placement is a small state machine on the control-plane row: `SHARED`, `PROMOTING`, `SCHEMA`,
`DEMOTING`, plus `placement_version` (a counter) and `cell` (section 5, alternatives).

**Promote when any of these holds** (initial thresholds, to be tuned from the stage-3 data, not yet justified by
measurement):

- shared-tier footprint above 5 GB or 20 M rows for the tenant (rolled up by a job in the jobs shape, not on the
  turn path);
- sustained share of database time above 5% of the shared tier, measured by per-tenant timing in the
  persistence layer (Postgres cannot attribute statements to a tenant here, so the application has to; this is a
  new counter, listed in section 9);
- a feature that is `SCHEMA`-only is enabled (graph memory, a vector corpus above the threshold);
- a commercial or compliance flag: enterprise plan, a declared data-residency region other than `UNSPECIFIED`,
  customer-managed keys. A tenant known at creation to have such a flag is created `SCHEMA` and never starts
  `SHARED`; promotion by this rule is for flags set later.

**Demote** is manual by default, never automatic, and only after 30 days below a quarter of every threshold with
no `SCHEMA`-only feature enabled. Automatic demotion invites flapping; moving a tenant is expensive and a large
tenant that quietened is likely to return.

**Mechanism (promotion):**

1. Set `PROMOTING` with a compare-and-set on `placement_version` (`UPDATE ... WHERE placement_version = ?`), so two
   promoters cannot both proceed. Create the tenant schema with the normal provisioning path (already supported).
   Start change capture (below).
2. Copy table by table in foreign-key order with `INSERT ... SELECT ... WHERE tenant_id = ?`, in key-ordered
   chunks, throttled so the shared tier's other tenants do not notice. The tenant keeps serving from `tenant_shared`.
3. **Freeze, then drain.** Insert the tenant into `placement_fence` and commit. Then wait until no transaction that
   was open before the fence committed is still running on `vegaduta_app`: take `clock_timestamp()` right
   after the commit returns and poll `pg_stat_activity` (the promoter needs `pg_read_all_stats`) until no backend of
   `vegaduta_app` (the only role that writes under the fence, 3.3) has `state <> 'idle'` and `xact_start` earlier than that timestamp; abort the promotion on a timeout.
   This is needed under READ COMMITTED because a statement that took its snapshot before the fence committed did
   not see the fence and can still commit after the replay; a check of transaction ids alone is not enough, because
   such a transaction may not have been assigned an id yet. **Wait scope:** `pg_stat_activity` cannot attribute a
   backend to a tenant (`app.tenant_id` is a session setting that is not visible there), so the drain waits for
   every long transaction of `vegaduta_app` anywhere in the shared tier, not only the promoting tenant's. A
   single long transaction of any tenant can delay the promotion until the timeout aborts it, and promotion latency
   is bounded below by the longest open transaction at that moment. Mitigations, not built: a statement and
   idle-in-transaction timeout on `vegaduta_app` (so the wait is bounded), and retrying the promotion;
   stage 3 measures the abort rate. Only after the drain, replay the captured changes, then
   compare per-table row counts and a chunked hash of the key set.
4. In one control-plane transaction set `SCHEMA` and bump `placement_version`. The compare-and-set of step 1 is the
   only place the version is compared; the fence, not the version, stops stale writers. Caches pick the change up
   within the placement cache TTL (target 5 s or less).
5. **The fence row stays after cutover** for as long as the rollback copy exists, because pods with a stale cache
   keep trying to write `tenant_shared` for up to the TTL (and a stuck pod for longer). It is deleted only together
   with the shared rows in step 6. On failure before step 4 the tenant returns to `SHARED` and the fence row is
   deleted in the same transaction that restores the state.
6. Keep the shared rows, marked, for 7 days as the rollback copy; then delete them through the erasure path of 3.8
   (batched; autovacuum follows), then delete the fence row.

**Demotion is not a pure mirror.** The schema side has no RLS, so its fence is a per-schema change that affects only
that tenant: `REVOKE INSERT, UPDATE, DELETE` on the tenant schema's tables from `vegaduta_app`, drain as in
step 3, copy back (as `vegaduta_promoter`, using its `INSERT` policy of 3.3), flip, and keep the schema revoked
while it is the rollback copy; an abort re-`GRANT`s. `vegaduta_app` owns the dedicated schemas, so this `REVOKE` is a guard against stale application code, not against a hostile owner (an owner can
re-grant itself), and the schema provisioning and drop path needs the same rights back; it needs a spike.

**Change capture: no per-tenant DDL on shared tables.** The first draft attached a trigger for the tenant to the
shared tables. A trigger belongs to a table, not a tenant: `CREATE TRIGGER` takes a lock that blocks writes to that
table for every tenant and queues behind long transactions, which is the blast radius this ADR is trying to avoid.
Candidates, none built or measured:

- (a) **Logical decoding** with a slot and a publication whose row filter selects the tenant (row filters exist
  from PostgreSQL 15; the kind stage runs 16.15). This needs `wal_level=logical`, which was not checked in the
  bundled chart, and a slot retains WAL while it lags. Preferred, subject to a spike.
- (b) **An unconditional capture trigger** installed once by the converter on every shared table (so no later DDL),
  which writes to a delta table only when the row's tenant is listed in a tiny `placement_capture` table. The cost
  is one probe per shared-tier write for everyone, the same order as the fence probe, to be measured; it could share
  one trigger with the fence.
- (c) **No capture** for small tenants: freeze first, copy, flip. Downtime is the copy time, so this is only for
  small tenants or a maintenance window (open decision 3).

**Write fence.** The fence is a probe against `placement_fence` inside the `INSERT`, `UPDATE` and `DELETE` policies
of 3.3, one policy per command. It adds one index probe per written row for every shared-tier write; its cost is a
stage-1 measurement. A fenced `INSERT` fails with a policy violation, but a fenced `UPDATE` or `DELETE` silently
matches zero rows, which application code may not notice. If stage 1 shows that is a risk, add an unconditional row
trigger on every shared table that raises on a fenced tenant so the failure is loud (it can be the same trigger as
option (b)). Revoking the application role's privileges per tenant is not possible on shared tables with one role.

Failure handling: any step failure before step 4 returns the tenant to `SHARED` and deletes the half-built schema
(through the existing `TenantSchemaGuard`, which refuses to drop a schema it did not create); after step 4 the
rollback copy allows demotion back.

### 3.7 Noisy-neighbour controls

- **Per-tenant concurrency bulkhead** on database-heavy operations (retrieval, history export, bulk ingest) in the
  application: a semaphore per tenant with a plan-derived limit, so one tenant cannot hold the whole pool.
- **Statement and lock timeouts** per role, with a tighter `statement_timeout` on the API role in the shared tier
  than the dedicated one.
- **Per-tenant database-time accounting** feeding the promotion rule above. This is also the alert for "the shared
  tier is hot, who is it".
- **Table layout for the churny tables.** Hash-partition the few high-volume append tables in the shared tier
  (messages, tool-call log, audit, workflow run output) by `tenant_id` into a fixed number of partitions
  (initially 32): vacuum runs per partition and bloat is contained to one partition, which holds about 1/32 of all shared-tier tenants, not one tenant (hash
  partitioning does not isolate bloat per tenant). Per-tenant partitions are
  rejected because they recreate the catalog problem.
- **Sweep fairness.** Shared-tier sweeps read a bounded batch ordered by oldest-first with a per-tenant cap per
  round, so a backlog from one tenant does not starve the others.
- **Hard cap on dedicated schemas per database** (initial 2,000), after which a new cell is needed (alternatives).
  This keeps a dedicated-schema release at or under the 11 minutes of section 4 even without the stage-0 change.

### 3.8 Backup, restore, export, delete, residency

| Operation | `SCHEMA` | `SHARED` |
|---|---|---|
| Disaster recovery | Physical backup with WAL archiving for the cluster. Logical backup of the whole database is not viable past a few hundred schemas (section 2.3) | Same |
| Tenant export | As today (`TenantDataExportService`) | `COPY (select ... where tenant_id = ?)` per table, driven by the catalog list of tables so a new table is exported without code. Must be reimplemented and is covered by the same export tests |
| Tenant restore | Restore the schema from a per-tenant dump or a point-in-time clone | Restore a point-in-time clone, then copy the tenant's rows back. There is no single-tenant point-in-time restore in a shared table; this is a runbook, slower than today, and is a real cost of the shared tier |
| Delete (erasure) | `DROP SCHEMA ... CASCADE` through the existing guarded path in `TenantDataErasureService` (a schema drop sidesteps the append-only triggers) | Batched `DELETE ... WHERE tenant_id = ?` in key-ordered chunks as `vegaduta_erasure` (see below), an erasure ledger row, then a read-only verify pass that counts zero rows in every table. Bloat is reclaimed by autovacuum; file removal is not immediate, unlike a schema drop |
| Residency | Schema lives in the cell's database | `tenant_shared` exists once per cell, so a tenant's rows stay in its cell's region |

**Append-only tables.** `audit_log_entry` has a `BEFORE UPDATE OR DELETE` trigger (`V75`) and `audit_seal` refuses
`DELETE` (`V422`). Both fire for every role including the owner, so in the shared tier they would block the erasure
`DELETE`s and the post-promotion rollback-copy cleanup. (The verify pass is a `SELECT` and is not affected.) Design:
a new tenant migration `CREATE OR REPLACE`s both trigger functions (released files are not edited) to allow `DELETE`
only when `current_user = 'vegaduta_erasure'`, a role no application shape logs in as, and only for the tenant in
`app.tenant_id`; everything else stays refused and the `UPDATE` rules are unchanged. A tenant migration runs in dedicated schemas as
well, so this replacement loosens the same functions there for `vegaduta_erasure`; that role does not log in as an
application shape and its use is limited to the tenant in `app.tenant_id`, but splitting the function per tier is
the alternative (section 9, item 7). In the shared tier the audit
chain must be per tenant: `uq_audit_seal_seq UNIQUE (seq)` becomes `(tenant_id, seq)` (the converter's
tenant-leading rule does this) and the sealer, which today assumes one chain per schema, must read and extend only
the current tenant's chain, which needs a code audit. Erasing sealed audit rows also breaks verification of that
tenant's chain by design, so the erasure ledger row records the sealed range and hash that was removed. Hash
partitioning (3.7) cannot help, because partitions are not per tenant. How the exemption is authorised is open
(section 9, item 7).

Backups retain deleted rows until the retention window passes in both placements, as they do today. If that is not
acceptable for a compliance case, the case is a `SCHEMA` (or dedicated-database) tenant, or later a
per-tenant data key; envelope encryption per tenant is mentioned as an option, not designed here.

**Residency.** `DataResidencyRegion` today is informational: it records a declared jurisdiction and moves no data
(its own Javadoc says so), because there is one Postgres. This ADR does not change that. It defines the hook:
`cell` on the placement row, with a rule that a tenant with a declared region other than `UNSPECIFIED` is only
placed on a cell in that region. Enforcement therefore depends on cells existing, which is outside this ADR.

## 4. What this buys, in the same units as section 2

Illustration at 100,000 tenants, assuming 2% are large enough to be `SCHEMA` (2,000 schemas) and 98% are `SHARED`.
The 2% is an assumption, not a forecast.

| | All `SCHEMA` (today) | Hybrid |
|---|---|---|
| Tenant schemas to migrate | 100,000 | 2,000 plus one shared pass |
| No-op release (today's path, 4 parallel, 1.35 s each) | 9.4 h | 11 min |
| No-op release (stage-0 version column) | one query | one query |
| `pg_class` rows from tenant data | about 100 M | about 2 M plus one shared copy of the 193 tables |
| Relation files from tenant data | 100 M or more | 2 M or more |
| Tenant sweep (one query, 0.36 ms per visit measured on a warm connection) | 36 s | 0.7 s for the dedicated tenants plus one indexed cross-tenant query |

The 0.36 ms is `set_config` plus `select count(*)` on a persistent connection in the scratch database; a real
sweep also pays the entity load. A poller with an interval shorter than the sweep time cannot finish a sweep.

Costs that move to the other side of the ledger: DDL on shared tables is online-safe-only and affects everyone at
once; a tenant restore is slower; the leak risk moves from "wrong schema" to "wrong policy", which the stage-2
suite exists to catch; code that assumed a tenant-local key being globally unique breaks.

## 5. Alternatives considered

1. **Keep schema-per-tenant and only fix the release path (stage 0).** Cheap, no isolation change, and it removes
   the release cost entirely. It does not touch catalog growth (section 2.1), the file count, the sweep cost, or
   the `pg_dump` ceiling. It is necessary and not sufficient; this ADR includes it as stage 0 because it is
   independently worthwhile. If the product stays under a few thousand mostly-active tenants, the right
   decision is to stop after stage 0 and never build the rest. That is the main kill criterion for the whole
   proposal.
2. **Cells: many identical Postgres clusters, each holding a bounded number of tenants, with a router.** Solves
   blast radius, residency and per-cluster limits, at the price of operating many clusters and a tenant-to-cell
   router that the hot path must consult from memory. It is **complementary**: a cell can be hybrid, and the
   `cell` column in section 3.6 is where it plugs in. Cells alone leave each cell with the per-tenant overheads
   of section 2.1, just fewer per cluster.
3. **Database-per-tenant for enterprise.** Strongest isolation, per-tenant restore and residency with no shared
   code path. Each database needs its own connection pool, which collides with the connection budget (pods times
   pool size) and with the session-mode pooler. Acceptable for tens of tenants behind a routing `DataSource`,
   not for the long tail. Offered as a third placement (`DATABASE`) for the top tier later; the placement column
   and the provider switch in section 3.2 are the extension point.
4. **Citus.** Citus can shard shared tables by `tenant_id` and has schema-based sharding for the schema model.
   The shared tier here is designed to be Citus-ready (tenant-leading keys, composite foreign keys), so it can be
   adopted later as a scale-out of the shared tier. It is not chosen now: it adds an extension that managed
   Postgres services often do not offer, its interaction with Apache AGE and pgvector is untested here, and
   the platform's single-process hot path does not need it at the sizes where the catalog is the first thing to hurt.
5. **All shared, no schema tier.** Simplest of the shared designs, but no isolation story for large or regulated
   tenants and no place for graph memory. Rejected.
6. **Application-level filtering only (Hibernate `@TenantId` or filters), no RLS.** A missing predicate leaks
   data, native SQL bypasses it, and it requires touching roughly 170 entities. Rejected as the control; it could be added
   later as defence in depth.
7. **Table-per-tenant partitions of shared tables.** Recreates the catalog growth that motivates this ADR.
   Rejected for tenants; hash partitioning (3.7) is used instead.

## 6. Consequences and limits

- Two placements double the test matrix. The cross-tenant suite and the repository tests must run in both modes.
- Shared-tier DDL becomes a production-safety discipline (expand/contract, lock timeouts, no rewrites), which
  today's per-schema model gets for free because each schema is small.
- Operators must understand that a tenant's data may live in either place; admin and support tools that connect
  to a tenant schema directly will need a placement-aware path.
- The shared tier is one larger failure domain than any single schema. Backups, vacuum tuning and the noisy-neighbour
  controls above are how it is kept small; cells are how its blast radius is bounded.
- The hot path is intended to add one in-process placement lookup and keep the same number of round trips per
  transaction. This is a claim the stage-1 gate has to prove with the query-budget test and the kind latency harness, not one
  this document has shown.

## 7. Verification status

- **Measured here** (scratch database on the kind stage, one run each unless stated): catalog rows and disk per
  empty tenant; the server-side up-to-date check, `ADD COLUMN` and `CREATE INDEX` loops over 300 schemas; the
  `pg_dump` behaviour; the RLS micro-benchmark and its fail-closed result.
- **Taken from the repository, not re-measured:** 1.35 s per schema for a no-op Flyway run.
- **Policy matrix, run once on a scratch container** (`vegaduta-postgres:ci`, PostgreSQL 16.15, roles used through
  `SET ROLE`, one throwaway table `tenant_shared.msg` with `tenant_id`, `FORCE ROW LEVEL SECURITY`, the per-command
  policies and grants of 3.3 and a `placement_fence` table). Observed: the app role saw only its own tenant's rows
  and could not `UPDATE` `tenant_id` (permission denied); the promoter saw 2 rows with `app.tenant_id` set to the
  tenant, 0 with it empty, could insert for its own tenant, got a policy violation inserting for another tenant and
  permission denied on `DELETE`; with the tenant fenced, the app role's `DELETE` and `UPDATE` matched 0 rows and its
  `INSERT` raised a policy violation; the erasure role deleted 0 rows with no tenant set and all 3 with it set,
  fence notwithstanding; the jobs role read all tenants' rows and was denied `INSERT` and `UPDATE`. This covers one
  table, no real schema, no PgBouncer, no concurrency and no drain. It does not exercise the converter, the
  audit-trigger exemption or `pg_read_all_stats`. The SQL is a scratch file, not checked in.
- **Not measured at all:** anything above 300 schemas, anything on real tenant data volumes, any chat-turn latency
  in shared mode, the converter, promotion, the write fence.
- **Not implemented:** everything in section 3.

The scratch database was dropped after measurement. The scripts used are not checked in; they were throwaway
shell and SQL driving `psql`, `pg_dump` and `pgbench` inside the Postgres pod.

## 8. Staged rollout and kill criteria

Each stage ends with a written result. Each has a condition that stops the work, and the earlier stages are safe
to keep if a later one is stopped.

| Stage | Scope | Default behaviour change | Proceed when | Kill, or stop and keep the earlier stages |
|---|---|---|---|---|
| 0. Make today cheap | `migrated_version` on the tenant row, release selects only stale schemas; catalog-size and schema-count metrics with alerts; `max_locks_per_transaction` guidance and a physical-backup runbook; batched variants for the heaviest `TenantSchemaIterator` users | None | No-op release at the largest test tenant count is one query; metrics visible | If tenant count is forecast to stay below about 5,000 and catalog metrics stay healthy, stop here |
| 1. Spike, no product code | The converter on all 193 tables in a scratch database; the parity test; the RLS and write-fence micro-benchmarks; `HotPathQueryBudgetIT` unchanged in shared mode; kind latency harness for one tenant in each placement | None | At least 95% of tables convert with no hand-written rule; fence overhead on a write is under 0.5 ms; shared-mode chat p50 and p99 within the harness noise of schema mode | More than 5% of tables need hand-written rules, or any measurable p99 regression that cannot be removed: switch to the twin-migration-tree fallback once; if that also fails, stop |
| 2. Shadow | Behind `app.tenancy.shared.enabled=false`, plus the flag-off equivalence proof of 3.2 and the role split of 3.3 with a role-matrix test (per role: `SELECT`, `INSERT`, `UPDATE`, `DELETE` on a shared table, with and without a fence row, for a matching and a different tenant). Shared tier created; full IT suite and a cross-tenant fuzz suite (two tenants, every repository and native query, assert no read or write crosses) run in both modes in CI | Off, once flag-off equivalence is proven | The fuzz suite and the dual-mode suite pass; flag-off path shown equal to today's; native-SQL audit complete | **Any** confirmed cross-tenant read or write: stop, revert to all-`SCHEMA`, root-cause before resuming |
| 3. Canary | Internal and free-tier new signups placed `SHARED`, 1% then 10% then 50% of new signups; manual promotion only; per-tenant DB-time counter live | New signups only, and only where enabled | Promotion succeeds on a rehearsed large tenant with verified counts; shared-tier CPU and lock waits stay under agreed limits; chat latency for `SHARED` tenants within 5% of `SCHEMA` on the kind harness | A promotion that loses or duplicates a row; promotion failure rate above 2% over a week (including aborts from the drain timeout of 3.6 step 3); p99 regression above 5%; shared tier above 60% CPU at 10% of signups. Halt new `SHARED` placements, promote everyone back |
| 4. Automatic promotion, and optional move of existing small tenants to `SHARED` | The promotion rules in 3.6 turned on (automatic **promotion** only; demotion stays manual per 3.6). Moving existing small tenants to `SHARED` is an operator-run batch using the manual demotion procedure, not an automatic rule | Existing tenants may move | Four weeks of canary data confirm the thresholds | Any flapping (a tenant promoted and, by an operator, moved back twice in a month); unrecoverable restore drill |
| 5. Cells and optional pooler change | Router and `cell` column; transaction-pooled pool for the shared tier only, with its own isolation suite | Per cell | A restore and residency drill in two cells | Isolation suite failure under transaction pooling: do not ship the pooler change |

**Unwind.** Setting `app.tenancy.shared.enabled=false` stops new `SHARED` placements. Promoting every `SHARED`
tenant back and dropping `tenant_shared` returns the platform to today's structure; the dedicated schemas were never
changed, which is the property that makes stages 2 and 3 safe to try.

## 9. What is not proven, and open decisions

Not proven:

- **That the converter works on the real migration set.** The 193 tables include composite keys, partial unique
  indexes, `CHECK` constraints that mention other columns, `ON CONFLICT` targets in migrations and in code,
  triggers, and pgvector columns. None has been run through a converter.
- **Behaviour at scale.** Nothing above 300 schemas or 1 M rows was run. The extrapolations in section 2 are
  linear arithmetic. Autovacuum scanning `pg_class`, relcache and catalog-cache pressure per backend, planner time
  with a very large catalog, and the file-system cost of tens of millions of relation files are known Postgres
  concerns that this document did not measure.
- **That the 1.35 s Flyway figure holds today.** It is quoted from the repository's own comment, taken in
  production at an earlier script count.
- **Chat latency in shared mode.** The micro-benchmark compares a statement shape on an idle machine. It does not
  show an agent turn. The hot-path budget test and the kind harness have not been pointed at a shared placement.
- **Promotion correctness.** The change capture (option a, b or c), the drain, delta replay, hash comparison and
  write fence are designed, not built or measured. The fence's cost per write and its behaviour when pods have stale caches are untested.
- **Multi-replica placement cache.** Staleness bounded by the cache TTL is the design, as in ADR-0014; it has not
  been observed under load, and the write fence is what makes the TTL safe.
- **Thresholds.** 5 GB, 20 M rows, 5% database time, 2,000 schemas per database, 30 days, 32 partitions and the 2%
  large-tenant share are starting points chosen for the illustration. None is derived from data.
- **Vector search in the shared tier.** Whether an exact scan, iterative index scans or promotion-on-size is the
  right answer depends on corpus sizes the stage does not have.
- **Restore.** The per-tenant restore from a point-in-time clone has not been rehearsed; neither has any export
  path in shared mode.
- **Sweeps and the sizing table.** The "0.7 s" in section 4 is the scan only. Each swept tenant still needs a
  `TenantContext` and its entity load, so a shared-tier sweep is one cross-tenant query plus per-tenant work.
- **RLS cost under generic plans and skewed tenant sizes** (caveats in section 2.3).
- **The no-change claim for SQL and entities.** Not verified; the native-SQL audit and the stage-2 suite decide it.
- **Reproducibility.** The measurement scripts are not checked in, so the section 2 numbers cannot be re-run from
  the repository; only the arithmetic built on them can be re-derived.
- **The role and policy matrix of 3.3 as a whole.** No SQL was executed for this ADR's policies, grants, fence
  probe or role split; the only SQL measured is the fail-closed `set_config` result of section 2.3. Whether the
  promoter's tenant-scoped `SELECT`, the erasure role's table grants and the discovery role behave as written under
  `FORCE ROW LEVEL SECURITY` is a stage-1 test, not a result.
- **Security review.** No threat model beyond the points in 3.3 has been written, and no external review has been
  done. RLS correctness is the property the whole shared tier rests on.

Open decisions for the maintainers:

1. Whether to approve stage 0 on its own now, since it is valuable under every outcome.
2. Whether graph memory and vector corpora are permanently `SCHEMA`-only or get a shared design.
3. The placement cache TTL and whether the fence is acceptable or promotion should require a short maintenance
   window instead.
4. Whether `DATABASE` placement for enterprise belongs in this ADR's rollout or a separate one.
5. Who owns the per-tenant database-time counter, since it adds work to the persistence path that the hot-path
   document asks to keep minimal.
6. Whether tenant migrations after the baseline get hand-reviewed shared variants (this ADR's default, 3.1) or an
   SQL rewriter, and which of the ten DML migrations are folded into the baseline versus re-expressed as seed code.
7. How the append-only trigger exemption is authorised (role check as in 3.8, or a separate erasure function with
   its own audit record), and how the per-tenant seal chain is introduced.
8. Which change-capture option of 3.6 to build, and whether `wal_level=logical` is acceptable in the bundled chart.
9. Where the new roles of 3.3 (`vegaduta_shared_owner`, `vegaduta_jobs` as the discovery login, `vegaduta_erasure`,
   `vegaduta_promoter`) are provisioned (`db-roles`, Helm, tenant provisioning in the `api` shape) and how an
   existing install adopts them; their `placement_fence` and table grants and policies (3.3); whether the API shape
   also gets the discovery datasource; and whether `tenant_id` immutability is column grants (with the lint of 3.5)
   or a trigger. Options for the jobs shape if one login proves too broad: keep `vegaduta_app` for all shapes (this
   ADR's default), or give the jobs shape its own write login with the same fenced policies in its `TO` clause and
   in the drain filter (a role must never gain write policies without both).
