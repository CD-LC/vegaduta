# ADR-0024: Tenant-aware executors, one factory for every thread core creates

| | |
|---|---|
| **Status** | Accepted; implemented 2026-10-16 |
| **Date** | 2026-10-16 |
| **Deciders** | Platform Engineering (maintainers) |
| **Related** | AGENTS.md rule 12, [extending guide section 8](../extending.md#8-hand-work-to-another-thread), `AsyncHopTenantScopeRulesTest`, `RawExecutorBanRulesTest`, [ADR-0015 hybrid tenancy](ADR-0015-hybrid-tenancy.md) |

## Context

Tenants are Postgres schemas. The schema for a query comes from `TenantContext`, a
`ThreadLocal` set per request. Work handed to another thread does not have it:

- on a fresh thread the query fails closed (`tenant_context_missing`), which silently drops
  background work;
- on a pooled thread that an earlier task left holding a tenant, the query runs in **another
  tenant's schema**.

Before this ADR, core had about 130 places that created threads, pools or schedulers by hand
(`Executors.*`, `new Thread`, `new ThreadPoolExecutor`, `Thread.ofVirtual()`,
`Schedulers.boundedElastic()`, executor-less `CompletableFuture.supplyAsync`). Each site carried
the tenant itself, or not. `AsyncHopTenantScopeRulesTest` checked hand-offs at the call site, but
it could not see where an executor came from, so 78 classes sat on its allowlist with a reason
written by hand. A new pool in a new class was one forgotten `TenantScope.capture()` away from a
cross-tenant read.

## Decision

1. **One factory.** `com.agenticai.core.security.concurrent.TenantAwareExecutors` (core/security,
   which every module already depends on) is the only place in core main code that creates threads
   and pools. `TenantAwareSchedulers` in the same package wraps Reactor's schedulers. Reactor
   lives in its own class so modules without reactor-core can use the executor factory;
   reactor-core is an `optional` dependency of core/security.
2. **Capture on submit, apply on the worker, always restore.** Every executor and scheduler the
   factory builds wraps each task, on the submitting thread, with a snapshot of the
   `TenantContext`, the `AgentVersionPin` and the SLF4J MDC. The worker runs the task with exactly
   that snapshot and puts its own previous state back in `finally`.
3. **Platform tasks run with no tenant.** A task submitted from a thread without a tenant (start-up,
   a timer, a Kafka listener) runs with the tenant **cleared**, not with whatever the worker held.
   A platform task that needs a tenant binds it itself (`TenantScope.of(..)`,
   `TenantSchemaIterator`). This is stricter than `TenantScope`'s empty-scope pass-through and is
   what makes a pooled thread unable to leak.
4. **Periodic tasks** (`scheduleAtFixedRate`, `scheduleWithFixedDelay`, `Flux.interval`) keep the
   scheduling thread's snapshot for every run. Periodic jobs are scheduled from tenant-less threads
   (construction, start-up) and bind tenants per tick, as before. A pod-wide periodic job that is
   started lazily from a request thread (the chat cancel poller) wraps its task in
   `TenantAwareExecutors.platform(..)`, which pins an empty snapshot that the executor does not
   re-capture; otherwise it would run with the first request's tenant, pin and MDC forever.
5. **Reactor semantics follow from "capture on schedule".** `subscribeOn` schedules on the
   subscribing thread, so the upstream subscription sees the subscriber's tenant (what
   `TenantScopedFlux` does by hand). `publishOn` schedules from whichever thread emits, often a
   provider's network thread with no tenant, so downstream tenant work after a `publishOn` still
   binds a captured `TenantScope`.
6. **Spring `TaskExecutor` beans** come from `TenantAwareExecutors.newTaskExecutor`, a
   `SimpleAsyncTaskExecutor` whose `TaskDecorator` does the same capture/restore. The
   `applicationTaskExecutor` bean in all three shapes (api, jobs, worker) uses it, so `@Async`
   methods run with the caller's tenant.
7. **Ban the raw forms at build time.** `RawExecutorBanRulesTest` (core/app, ArchUnit) fails on any
   use outside the factory package of: `java.util.concurrent.Executors.*`; `new Thread` (or a
   subclass); `Thread.ofVirtual/ofPlatform/startVirtualThread`; constructing a
   `ThreadPoolExecutor`, `ScheduledThreadPoolExecutor` or `ForkJoinPool` (or a subclass);
   `ForkJoinPool.commonPool()`; constructing Spring's `ThreadPoolTaskExecutor`,
   `ThreadPoolTaskScheduler`, `SimpleAsyncTaskExecutor`, `SimpleAsyncTaskScheduler` or
   `VirtualThreadTaskExecutor`; any `CompletableFuture.*Async` call without an `Executor`
   argument; any `reactor.core.scheduler.Schedulers` method. Nothing is frozen: every site was
   migrated, and the test's in-code allowlist is empty. An entry needs a class name and a reason,
   and an entry that no longer has a raw form fails the test.

`AsyncHopTenantScopeRulesTest` stays as the companion rule: it still checks each hand-off,
because a field typed `ExecutorService` does not say where its executor came from. It now treats
any call into the factory package as an approved wrapper and skips the package itself; 23 entries
left its allowlist as a result.

### API

| Need | Call |
|---|---|
| Virtual thread per task | `TenantAwareExecutors.newVirtualThreadPerTaskExecutor("name-")` |
| One worker thread | `newSingleThreadExecutor(name, daemon)` |
| Fixed / cached pool | `newFixedThreadPool(n, threadFactory)`, `newCachedThreadPool(threadFactory)` |
| Exact `ThreadPoolExecutor` settings (bounded queue, rejection policy) | `newThreadPool(core, max, keepAlive, unit, queue, threadFactory[, handler])` |
| Scheduler / timer | `newSingleThreadScheduledExecutor(name, daemon)`, `newScheduledThreadPool(n, threadFactory)` |
| A one-off thread | `startThread(name, daemon, task)`, `startVirtualThread(name, task)` |
| Spring `TaskExecutor` bean | `newTaskExecutor(prefix, virtualThreads)`, or `taskDecorator()` on one |
| Wrap a task for an executor you do not own | `propagating(runnable)`, `propagatingCallable(callable)` |
| Wrap an executor you do not own | `wrap(executorService)` |
| Reactor | `TenantAwareSchedulers.boundedElastic()`, `.parallel()`, `.fromExecutorService(es, name)`, `.wrap(scheduler)` |

Thread factories (`threadFactory`, `numberedThreadFactory`, `virtualThreadFactory`) only name
threads and set the daemon flag; they propagate nothing, because a pool's worker thread outlives
the task that caused it to be created. Use them to configure a pool built by the factory.

`ContextPropagatingExecutor` (the `platformVirtualExecutor` bean) is kept; its `propagating`
method now delegates to the factory, which also carries the agent-version pin.

## Consequences

- A new pool, thread or scheduler in core cannot skip tenant propagation without failing the build.
- The worst case of a forgotten capture changes from "runs in another tenant's schema" to "runs
  with the submitter's tenant, or none".
- Hops that used to run with no tenant now run with the submitting thread's tenant. Every migrated
  site either already re-applied a captured tenant inside the task (the set/restore pairs still
  work: the outer wrapper restores the worker after the task's own `finally`) or touches no tenant
  table. Reviewed: heartbeat timers, Redis mirrors, HTTP body watchdogs, provider calls.
- Thread names changed for pools that used unnamed virtual threads (they now have a prefix such as
  `chat-pre-turn-`), which shows in thread dumps only.
- `StreamEndExecutor` dropped its `afterExecute` clear: the tenant-aware pool restores the pool
  thread's (empty) tenant and pin after every task.
- Cost per task: one snapshot (two `ThreadLocal` reads and an MDC copy) and a restore. This is
  noise next to the I/O the pools exist for; the chat hot path is unchanged in shape (rule 6).

## Not done (follow-ups)

- **Explicit context instead of the ThreadLocal in the turn path.** This ADR makes the hop
  structural; it does not remove `TenantContext`. Replacing it with an explicit parameter through
  the turn (API, guardrails, tools, retrieval, the LLM call) is a larger, incremental change.
- **Hand-offs to executors the code does not create.** Spring's `@Scheduled` scheduler, Kafka
  listener containers, Netty event loops and the JDK `HttpClient`'s internal executor are not
  built here. They run tenant-less entry points that bind tenants themselves.
- **Reactor callbacks** (`doOnComplete`, `subscribe(..)` lambdas) that run on whatever thread
  delivers a signal are still a review item, as in the extending guide.
- Most `async-hop-allowlist.txt` entries could be retired by typing the fields as the factory's
  concrete classes (`TenantAwareExecutorService`, `TenantAwareThreadPoolExecutor`,
  `TenantAwareScheduledExecutor`); not done here to keep call-site edits small.
