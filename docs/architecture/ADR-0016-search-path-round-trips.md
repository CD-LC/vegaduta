# ADR-0016: Removing the per-transaction `SET search_path` round trips without leaking a tenant schema

| | |
|---|---|
| **Status** | Proposed. **Design only: no behaviour changes in this ADR.** Nothing in `SchemaMultiTenantConnectionProvider` is modified by it. |
| **Date** | 2026-10-13 |
| **Relates to** | [HOT-PATH-PERFORMANCE.md](HOT-PATH-PERFORMANCE.md) section 5 ("Two extra `SET search_path` per transaction"), [ADR-0014](ADR-0014-horizontal-scaling.md), AGENTS.md rules 6 and 8 |
| **Code** | `core/tenancy/.../SchemaMultiTenantConnectionProvider.java`, `TenantSchemaResolver.java`, `core/security/.../TenantContext.java`, `AgeSessionSupport.java` |

## 1. Context

Tenants are Postgres schemas. `SchemaMultiTenantConnectionProvider` is Hibernate's
`MultiTenantConnectionProvider`:

- `getConnection(tenant)` borrows a pooled connection and calls `Connection.setSchema(tenant)`. pgjdbc sends
  `SET search_path TO ...` and waits for the answer: **one synchronous round trip before the transaction's first
  query**.
- `releaseConnection(tenant, c)` calls `c.setSchema("public")`, then `c.close()`. On failure it `abort()`s the
  connection so a connection that still points at a tenant is never returned to the pool. **A second round trip.**

So every tenant transaction costs two extra round trips around its real work
([HOT-PATH-PERFORMANCE.md](HOT-PATH-PERFORMANCE.md) section 1 counts it as three including the query). A chat turn
runs many small transactions, so the overhead is `transactions per turn x 2 x RTT(API pod, Postgres)`.

### 1.1 Why this is dangerous to touch

The failure mode is not an exception. It is a query silently running in another tenant's schema. This has
happened once already on this stack: with PgBouncer in transaction mode, 41 of 60 concurrent clients got the wrong
schema and 21 of them another tenant's (provider javadoc, fixed 2026-07-24). Today's safety comes from two
independent habits: every acquisition sets the schema (so a stale value left by a previous borrower is overwritten),
and every release resets it (so raw `dataSource.getConnection()` users see `public`). Any optimisation removes one
of the two, so the replacement guarantee must be at least as strong and must be proven by tests that exist first.

### 1.2 Facts established by reading the code (not by running it)

1. The only place that calls `setSchema` is the provider (acquire and release). `TenantContext` is a plain
   `ThreadLocal` (not inheritable), so a tenant is resolved from the thread that opens the Hibernate session, via
   `TenantSchemaResolver`, which falls back to `public` and counts `tenant_context_public_fallback_total`.
2. Nine services in `core/**/src/main` take **raw** connections with `dataSource.getConnection()`, bypassing the
   provider (found by grep; the platform health and migration services also take connections but were not
   classified): `TenantStatusGuardImpl`, `SystemStatusController`, `TenantDataUsageService`,
   `TenantProvisioningService`, `TenantDataErasureService`, `TenantSchemaMigrator`, `TenantDataExportService`,
   `DemoSessionService`, `PlatformEmailSuppressionService`. They rely on a pooled connection
   being on `public` (or qualify names). Any design that leaves a tenant path on a pooled connection breaks them.
3. `AgeSessionSupport` changes the path with `set_config('search_path', ?, false)`, which is **session**-scoped, and
   restores it in `close()` (`AgeSessionSupport.java` lines 54-55 and 209-232). It runs on a connection that the
   provider already pointed at a tenant. `open()` reads `current_setting('search_path')` and `close()` writes that
   value back with `set_config(..., false)`. Under option C, inside a `SET LOCAL` transaction the value read is the
   **tenant** path, and the session-scoped write-back is not undone at `COMMIT` (a session-level setting issued after
   a `SET LOCAL` in the same transaction outlives it; this is Postgres documented behaviour, not run here). So the
   pooled connection would be left on the tenant path. Today the release `setSchema("public")` hides this. See L10
   and blocker B1.
4. Hikari tracks `setSchema` through its proxy connection. `core/app-config/.../application.yml` (lines 252-255)
   configures no `hikari.schema`; as far as we recall, Hikari only resets the schema on close when a pool schema is
   configured, in which case the release path is the single `setSchema("public")` and K5 is moot. That is a
   recollection of Hikari's source, **not verified** against a server. Phase 0 of section 4 settles it with a
   statement-count test.
5. The request tier runs on virtual threads (`spring.threads.virtual.enabled`). A connection is borrowed and
   released on the same virtual thread within one transaction, but a virtual thread can unmount while it holds the
   connection. Nothing in the provider is thread-affine, which is good; it also means thread identity cannot be used
   as a safety argument.
6. PgBouncer must stay in session mode (AGENTS.md rule 8). In session mode a client connection owns one server
   connection until it disconnects, so everything in this ADR is about the application pool (Hikari), not PgBouncer.

## 2. Leak scenarios any design must survive

| # | Scenario | What goes wrong if the design is careless |
|---|---|---|
| L1 | Exception or rollback inside the transaction | A session-level path set earlier survives `ROLLBACK`; only transaction-scoped state is undone by the server. This describes state set in autocommit. A session `SET` issued inside an open transaction is rolled back with it (see the `AgeSessionSupport.Session.close` comment). Today's provider is safe only because Hibernate acquires the connection in autocommit (assumption A1, tested by T11/T17). |
| L2 | Acquire-side `setSchema` fails (schema dropped, statement cancelled, network blip) | Connection handed back to the pool in an unknown state. Today: closed quietly (JAVA-044). |
| L3 | Release-side reset fails | Connection returned still pointing at tenant A. Today: aborted. |
| L4 | Pool return then raw borrower (`dataSource.getConnection()`) | The raw user runs on the previous tenant's path. Today: protected only by the release reset. |
| L5 | Next provider borrower for tenant B | Safe today because acquire always sets. Unsafe in any design that skips the set when it believes the path is already right. |
| L6 | Autocommit or no-transaction use (read-only `EntityManager` outside a transaction, open-in-view style use) | `SET LOCAL` outside a transaction is a no-op with a warning, so the query runs on `public`. Wrong data or an error, not a cross-tenant read, but silent. |
| L7 | Async hop that loses `TenantContext` (reactor scheduler, `CompletableFuture`, executor) | Resolver falls back to `public`. Existing counter `tenant_context_public_fallback_total`; unchanged by any option here. |
| L8 | Virtual-thread unmount while holding a connection | Only a problem for designs that cache the "current schema" per thread. |
| L9 | Background jobs (`TenantSchemaIterator`-style loops, schedulers) that `TenantContext.set` then `clear` in `finally` | A missing `clear` leaks context to the next task on a pooled thread, which then resolves to the wrong tenant at acquire. Orthogonal to the provider but exercised by the same suite. |
| L10 | `AgeSessionSupport` session-level `set_config` interleaved with a transaction-scoped path | `open()` reads the **tenant** path (the `SET LOCAL` value) and `close()` writes it back at session scope. That write survives `COMMIT`, so the tenant path sticks on the pooled connection even when `close()` restores "correctly". Cross-tenant leak path (L4/L5) if the release reset is removed. Fix needed in AGE (`set_config(..., true)` or a different restore) before phase 1. |
| L11 | Hikari `maxLifetime` or eviction, `abort()`, thread interrupt or statement timeout during the `SET` | Half-switched connection returned to the pool. |
| L12 | `REQUIRES_NEW` or nested transactions, a context change inside an open transaction | Two connections with different tenants in one thread; or a session bound to tenant A observing a tenant B context. |
| L13 | Connection-tracking designs (remember the schema per physical connection) | Tracking goes stale after any external `SET`, `DISCARD`, server restart behind a failover, or a raw user. |

## 3. Options

### A. Pin connections per tenant, or a pool per schema (for hot tenants)

Keep a tenant's connections on its path permanently, so no `SET` is ever sent for it.

- Removes both round trips for pinned tenants. Leaks: a pinned connection is never returned to the shared pool, so
  L4 and L5 disappear for it.
- **Fails the core goal.** Connection count becomes `pods x pinned tenants x pool size`, while Postgres connections
  are a fixed budget (`pods x pool size`, ADR-0014). Cost grows with users, the opposite of "nothing slows as users
  grow". Needs a policy for who is "hot", eviction, and per-tenant pool sizing. Raw users (fact 2) would need their
  own non-pinned pool.
- **Rejected.**

### B. Schema-qualified SQL (per-tenant default schema, no `search_path` at all)

Every statement names its schema, so no session state exists and PgBouncer transaction pooling becomes possible.

- The strongest isolation property (nothing to leak) and the only route to transaction pooling.
- As far as we know, Hibernate has no per-request `default_schema` on one `SessionFactory` with connection-based
  multi-tenancy; it would need per-tenant SQL rewriting or per-tenant metamodels. Native SQL and
  `@Query(nativeQuery = true)` across about 40 modules, Apache AGE Cypher, pgvector operators and extension
  functions resolved through `search_path` all need review. The size is unmeasured but it is a migration of the
  persistence layer, not an optimisation.
- **Correct long-term direction, wrong tool for this ADR.** Keep it as the exit if option C is killed or if
  transaction pooling is ever required.

### C. Transaction-scoped `SET LOCAL search_path` (the server undoes it)

Replace the session `SET` on acquire and the session `SET` on release with `SET LOCAL search_path TO "<tenant>"`
issued inside the transaction. Postgres reverts it at `COMMIT` or `ROLLBACK`, so the connection returns to its base
path for state set with `SET LOCAL`, including after an exception, an abort, or a lost reset. This is the intended
property for L1, L3, L4 and L5, **but it holds only if nothing else sets session-level path state in the same
transaction**. `AgeSessionSupport` does (L10, fact 3), so the claim is conditional on blocker B1 below.

- **C1: swap only.** Issue `SET LOCAL` as the first statement of the transaction; drop the release reset. Saves
  one of the two round trips and removes the release-failure branch. Needs a hook that runs after Hibernate has
  begun the transaction (autocommit off), because at the time `getConnection` runs the connection is still in
  autocommit and `SET LOCAL` would be a silent no-op (L6). That means a thin `Connection` wrapper that issues the
  statement lazily before the first statement in a transaction, and does nothing (and never claims a schema) when
  the connection is used in autocommit.
- **C2: fuse.** Send `SET LOCAL ...` in the same protocol message batch as the first real statement, so the extra
  cost is bytes, not a round trip. pgjdbc batches multiple statements in one `prepareStatement`; whether a leading
  update-count result breaks `executeQuery` and Hibernate's result handling, generated keys and JDBC batching is
  **not known** and must be proven on a real server before this is considered.
- Side benefit, not a goal: a path that exists only inside a transaction does not survive a transaction-pooling
  proxy. This could one day make transaction pooling safe for transactional paths. AGENTS.md rule 8 stays in force
  until the isolation suite has been run through PgBouncer in transaction mode and passed.
- **Base path changes.** Today release leaves a pooled connection on `public`. With C an idle connection sits on the
  server/role default (`"$user", public` unless configured), so the auditor and T2/T9 must assert the exact string
  `SHOW search_path` returns on a fresh connection in the test environment, recorded once by the suite, not
  "public". Raw users that rely on unqualified names still resolve to `public` via the default, but this is a
  behaviour change to state and test, not "no worse than today" by assumption.
- Open risks: L6 (non-transactional use), L10 (blocker B1), and every raw `dataSource.getConnection()` user.

### D. PgBouncer or session-parameter tricks

PgBouncer stays in session mode, so there is nothing to gain at the proxy. Newer PgBouncer and Postgres versions
can track selected parameters across transaction pooling; **whether the versions we ship do that for `search_path`
is unverified** and is not a basis for a design. Connection init SQL (`connectionInitSql`) runs once per physical
connection and cannot be per tenant. **No action; revisit only with a verified version matrix.**

### E. Elide the `SET` when the connection already has the right path

Remember each physical connection's current schema and skip the `SET` on a hit; skip the reset on release.

- Hit rate is roughly `1 / (tenants sharing the pool)` with an affinity-free pool, so it helps small installs and
  decays exactly as tenants grow.
- Violates the two independent habits of section 1.1: leaves tenant paths on pooled connections (L4, L13) and
  makes correctness depend on a cache that external `SET`s, AGE, and raw users invalidate invisibly.
- **Rejected.**

## 4. Decision (proposed)

**Adopt option C, in two gated phases, behind a flag that defaults to off. Do not start phase 1 until the isolation
test suite of section 5 exists, passes against the current implementation, and its negative controls fail
against a deliberately broken one.** Keep B as the exit strategy. Reject A and E. D is a non-action.

- **Blocker B1 for phase 1 (T10 must pass first).** `AgeSessionSupport.Session.close()` restores the path with a
  session-scoped `set_config(..., false)` of a value read inside a `SET LOCAL` transaction (L10). T10 is an explicit
  gate: phase 1 does not start until AGE uses a transaction-scoped restore (`set_config(..., true)`) or equivalent
  and T10 passes with the flag on. This was derived by reading the code, not run against a server.
- **Assumption A1 (to be tested, not assumed).** Hibernate acquires the connection in autocommit and the
  transaction begins afterwards; C1 relies on this. T11 and T17 test it.
- **Phase 0 (no behaviour change): measure and lock.** Count statements per transaction on a real server
  (including whether Hikari adds its own schema reset on close, fact 4) and lock the count in a guard test, as
  `HotPathQueryBudgetIT` does for queries. Measure the round-trip time (section 7).
- **Phase 1: C1**, flag `app.tenancy.schema-switch=session|local`, default `session` (today's behaviour). `local`
  is rolled out per environment only after the suite is green with it on.
- **Phase 2: C2 (fuse)**, only if phase 1 is shipped and measured and the remaining round trip is worth the
  pgjdbc risk (kill criterion K4).

### Kill criteria (stop and keep today's behaviour)

| | Criterion |
|---|---|
| K1 | Any isolation-suite case fails with the flag on, or any case cannot be written in a way that fails against a deliberately leaking implementation. |
| K2 | The saving is below 1 ms per chat turn at p50 on the kind benchmark (`tests/load`), or below the run-to-run noise of that benchmark. A real but unmeasurable saving is not worth a risk of cross-tenant exposure. |
| K3 | Any non-transactional (autocommit) code path that is not provably unaffected, or any increase of `tenant_context_public_fallback_total` attributable to the change. |
| K4 | Fusing (C2) needs more than a few lines in a `StatementInspector` or wrapper, or breaks generated keys, JDBC batching or result-set handling in any test. Then stay on C1. |
| K5 | The Hikari statement count shows the release path is not a separate, removable round trip (nothing to save). |
| K7 | T10 cannot be made to pass (AGE restore cannot be made transaction-scoped). |
| K6 | Any production signal of a wrong-schema read (the fallback counter, or an integrity probe described in section 5) after rollout: switch the flag back to `session` first, investigate second. |

## 5. Isolation test suite (must exist before any change)

**Rule: this suite is written, merged and green against the current `session` implementation first.** A change to
the switching mechanism is only reviewable when every case below already passes on `main` and has a demonstrated
negative control.

### 5.1 Harness

- **Real Postgres only.** Mocks of `Connection` (as in the current `SchemaMultiTenantConnectionProviderTest`) prove
  call sequences, not isolation. Use Testcontainers with the project image (`vegaduta-postgres:ci`, as
  `HotPathQueryBudgetIT` does), running with `-P it`, same invocation style.
- A real **Hikari pool of size 2 to 3** and the real `SchemaMultiTenantConnectionProvider` wired into a real
  Hibernate `SessionFactory`/Spring `JpaTransactionManager` with `TenantSchemaResolver`. A tiny pool is deliberate:
  it forces connection reuse between tenants on almost every borrow, which is where leaks show.
- Schemas `tenant_a`, `tenant_b`, `tenant_c`, plus `public`, each with the same table `leak_probe(owner text)`
  holding exactly one row naming its own schema. The probe is queried **unqualified**
  (`SELECT owner FROM leak_probe`), so the answer names the schema actually in effect. A wrong-schema read can never
  look like a pass.
- A **PgBouncer container in session mode** in front of the same Postgres for the suite's second run
  (`pool_mode = session`), plus a **negative-control run in transaction mode** that must fail the concurrency cases,
  reproducing the 2026-07-24 incident. A suite that cannot detect that incident is not trusted.
- A **statement counter**: `pg_stat_statements`, or a recording `DataSource` proxy, to assert exact `SET` counts per
  transaction. Locked with an exact number, like the query budget.
- A **pool auditor**: after each test, borrow every connection from the pool (via the pool MXBean and by holding
  `maximumPoolSize` raw borrows) and assert `current_setting('search_path')` is the base path on each.

### 5.2 Cases

| ID | Case | Pass condition |
|---|---|---|
| T1 | Sequential reuse A, B, C, A on a one-connection pool | Each probe returns its own tenant; pool auditor clean after each. |
| T2 | Exception inside a transaction (runtime and checked), rolled back, then raw `dataSource.getConnection()` | Raw borrower sees the recorded base path (today `public`; under C the default, see option C). |
| T3 | Acquire-side failure: tenant schema does not exist, or is dropped between resolve and acquire | Exception surfaces; connection is returned or closed; next borrower is clean; pool does not shrink permanently. |
| T4 | Release-side failure injected (proxy that fails the reset) | Connection aborted; never observed again with a tenant path. Pool recovers. |
| T5 | 64 virtual threads x 3 tenants x 200 transactions on a pool of 2 | Every probe equals the tenant of its own thread; zero mismatches; auditor clean. |
| T6 | Same as T5 through PgBouncer in session mode | Same. |
| T7 | Negative control: T5 through PgBouncer in transaction mode | Suite **fails** (mismatches detected). If it passes, the suite is not trustworthy. |
| T8 | Negative controls: (a) a deliberately broken provider that skips the **release** reset; (b) one that skips the **acquire** set | (a) T2 and T9 fail (raw borrowers see a tenant path); T5 cannot fail on (a), because every provider borrow sets the schema first (L5). (b) T5 and T1 fail (a probe returns another tenant's row). Each control must fail the cases named, and a control that does not is a suite bug. |
| T9 | Raw `dataSource.getConnection()` interleaved with provider traffic (the real raw users' pattern) | Raw users always on the recorded base path. |
| T10 | **Blocker for phase 1.** `AgeSessionSupport.open` inside a tenant transaction (flag `local`), commit, then reuse of the connection by another tenant and by a raw borrower | The path after commit equals the recorded base path; no tenant or AGE path remains (L10). Expected to FAIL against the current `set_config(..., false)` restore, which is the demonstration of B1. |
| T11 | Read-only, non-transactional use of an `EntityManager` (autocommit) | Either runs on the correct tenant, or fails loudly; never silently on `public` (L6). |
| T12 | Lost `TenantContext` on an async hop (`CompletableFuture`, executor) | Resolves to `public`, increments `tenant_context_public_fallback_total{kind=...}`, never to a previous task's tenant (L7, L9). |
| T13 | Background-job pattern: `TenantContext.set`, work, `clear` in `finally`, including a throwing task, on a pooled thread | Next task on that thread starts with no context. |
| T14 | `REQUIRES_NEW` inside a tenant transaction; context switched inside an open transaction | Each session stays bound to the tenant it was opened for; no cross-binding (L12). |
| T15 | Hikari eviction, `maxLifetime` expiry, `abort()`, thread interrupt and statement timeout while the switch statement is in flight (L11) | Pool heals; no connection with a tenant path is ever lent out. |
| T16 | Randomised operation sequences (borrow/commit/rollback/exception/raw/AGE/abort) from a fixed seed, with an oracle that knows the expected schema per operation | Zero oracle mismatches over a long run; seed printed on failure. |
| T17 | Statement budget: exact count of `SET` statements per tenant transaction, in the success, rollback and failure paths | Equals the locked number; any change must be intentional and reviewed. |

Cases T1 to T6 and T9 to T17 must run with the flag in both positions once phase 1 exists.

## 6. Consequences

- A phase-1 change would remove one of the two extra round trips and replace the "reset or abort" cleanup with a
  server-enforced revert. The intent is to remove failure branches (L1, L3, L4, L5) rather than add them; that is
  **not established**, and it is false while `AgeSessionSupport` leaves a session-level path behind (L10, B1).
- It adds a connection wrapper that must be correct about transaction boundaries. That wrapper is the new risk, and
  the suite exists to bound it.
- Option B stays the strategic path if the platform ever needs transaction pooling or database-level isolation
  proofs; nothing here blocks it.
- AGENTS.md rule 8 (PgBouncer in session mode) is unchanged by this ADR.

## 7. Measured cost of the two round trips

**Not measured.** An attempt to time the statements directly on the shared kind Postgres was not run (the shell
action to create scratch SQL files inside the Postgres pod was denied by the sandbox policy, and no workaround was
attempted). The only number available is a count: [HOT-PATH-PERFORMANCE.md](HOT-PATH-PERFORMANCE.md) section 3
reports about 130 fewer round trips on one step of a turn, in counts and not milliseconds. The kind Postgres also
holds very few tenant schemas (4 namespaces were visible), which says nothing about production schema counts.

Method to obtain the number (phase 0): on the kind stage, under the stage lock, run `tests/load` with and without a
statement-counting proxy to get (a) the exact `SET` count per turn and (b) the extra time per turn when an artificial
`SET search_path` pair is added around each transaction. The estimate is `transactions per turn x 2 x RTT`; the RTT
must be measured pod to pod, since loopback inside the database pod is only a lower bound and a same-node kind
cluster understates a real network.

## 8. What is not proven

- **No milliseconds.** The cost of the round trips was not measured (section 7). K2 exists because the benefit may
  be small next to the risk.
- Whether Hikari adds its own schema reset on `close()` (fact 4): the repo configures no `hikari.schema`, and we
  recall that Hikari then does not reset; this is not observed on a server. If it does reset, the true release cost
  is higher than described and K5 does not trigger.
- That the AGE write-back leaks the tenant path (L10, B1) is derived from reading the code and Postgres semantics;
  T10 is written to demonstrate it, but was not run.
- Whether pgjdbc returns a usable result for `SET LOCAL ...; SELECT ...` sent as one statement (option C2), and how
  that interacts with generated keys and JDBC batching, is unknown.
- That Hibernate reliably obtains the connection before the transaction is in the state `SET LOCAL` needs (L6), and
  that every code path runs inside a Spring-managed transaction, is unverified. T11 exists to find out.
- That no code relies on a tenant path outliving a transaction (for example a session-scoped `SET` done by a
  repository or native query) was **not** established by an exhaustive search; the search covered `setSchema`,
  `search_path` and `SET LOCAL` in `core/**/src/main` Java only, plus a grep for `search_path` in `services/`
  (`services/app-builder/app/baas.py` pins a search path on its own connection string and role; it does not share the
  core pool, and was not analysed further). `.sql` files and native `@Query` strings in core were not reviewed for
  session-level `SET`.
- The isolation suite is a specification. None of its cases were written or run for this ADR. The only existing
  coverage is the mock-based `SchemaMultiTenantConnectionProviderTest` and the query budget in
  `HotPathQueryBudgetIT`.
- Behaviour with PgBouncer versions or Postgres versions that track `search_path` across transaction pooling
  (option D) is unverified, and rule 8 stands.
- Nothing here was exercised on a multi-replica cluster or with production-like tenant counts.
