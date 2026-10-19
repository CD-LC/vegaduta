# ADR-0022: An explicit TurnContext on the chat turn path

| | |
|---|---|
| **Status** | Proposed. Stages 1-3 implemented (see section 2.5), stages 4 and 6 implemented (see section 2.6): compiled and unit-tested, **not yet benchmarked** at 32 users (section 2.4 requires that before stages 2-4 are called healthy). |
| **Date** | 2026-10-16 |
| **Relates to** | AGENTS.md rules 6 and 12, [ADR-0016](ADR-0016-search-path-round-trips.md) (the JDBC boundary), [HOT-PATH-PERFORMANCE.md](HOT-PATH-PERFORMANCE.md) section 4a (the 32-user rule), [ADR-0020](ADR-0020-turn-journal.md) |
| **Code** | `core/security/.../TenantContext.java`, `TenantScope.java`; `core/agents-turn/.../TurnContext.java`, `AgentChatService.java`, `ChatTurnPreflight.java`, `RlArmSelection.java`, `ChatClientSelection.java`, `ChatPromptAssembler.java`, `ChatTurnDispatcher.java`, `ChatTurnExecutor.java` (+ `FastStreamTurn.java`, `FastStreamRecovery.java`), `BufferedTurnRunner.java` (+ `BufferedTurnCalls.java`), `TenantScopedFlux.java`; `core/agents-tools/.../tools/AgentToolProvider.java`, `tools/TenantContextPropagatingToolCallback.java`; `core/tenancy/.../TenantSchemaResolver.java` |

## 1. Context

`TenantContext` is a plain (non-inheritable) `ThreadLocal` holding the caller's tenant id, subject, roles and an
optional external trigger id. `TenantContextFilter` (JWT requests) and each channel adapter (webhooks) set it;
`TenantSchemaResolver` reads it when Hibernate opens a session, and `SchemaMultiTenantConnectionProvider` turns that
into `SET search_path` on the borrowed connection (ADR-0016). Everything else on the turn path that needs "which
tenant" also reads the `ThreadLocal`, either directly (`TenantContext.current()`) or by capturing it into a
`TenantScope` before a hop.

A chat turn does not stay on one thread. It hops to `preTurnExecutor` (memory recall), to Reactor schedulers
(`subscribeOn(boundedElastic)`, completion threads for `doOnComplete`/`doFinally`), and to tool callbacks invoked by
Spring AI. Each hop must capture and re-apply the context (rule 12). Missing one does not fail at compile time: it
falls back to `public` (counted by `tenant_context_public_fallback_total`), fails closed, or, on a pooled thread left
holding a stale value, runs in the wrong tenant's schema.

Measured by grep on main `a8476f547` (production sources only):

| Where | `TenantContext.current()` call sites |
|---|---|
| `core/**/src/main` | 584 |
| `core/agents/src/main` | 217, in 140 files |
| Turn pipeline classes (`ChatTurnPreflight` 1, `ChatPromptAssembler` 2, `ChatTurnDispatcher` 5 incl. `TenantScope.of`, `ChatTurnExecutor` 2, `BufferedTurnRunner` 1) | 11 |

Five of the dispatcher's sites exist only to carry the tenant from the request thread to an operator that runs on a
completion thread (`dispatchTenant`, `capturedTenant`, `cacheTenant`). They are correct today because someone
remembered; `AsyncHopTenantScopeRulesTest` catches some, not all, of the forgotten ones.

Other per-turn facts travel the same implicit way or not at all: the correlation id lives in SLF4J `MDC` (also a
`ThreadLocal`, lost on the same hops), the turn has no single deadline (three separate timeouts in `ChatTimeouts`),
and the hosted budget reservation (`TurnReservationGuards`) is carried on `HostedChainLink`s.

## 2. Decision

Introduce an immutable `TurnContext` record and pass it **explicitly** down the turn pipeline, replacing reads of the
`ThreadLocal` above the JDBC boundary, in measurable stages. The `ThreadLocal` stays, for now, as the one thing the
JDBC boundary reads.

### 2.1 The record

`com.agenticai.core.agents.turn.TurnContext` (agents module, which owns the turn path):

| Component | Meaning |
|---|---|
| `tenant` | The `TenantContext` instance the request carried (tenant id, subject, roles, external trigger id). `null` for a tenant-less turn. Accessors `tenantId()`, `subject()`. |
| `requestedAgentId` | The agent the caller asked for, before experiment routing. |
| `sessionId` | The raw client session id (not the SEC-004 scoped memory id). |
| `requestId` | Correlation id: `X-Request-Id` from MDC when present, else generated. Never null. |
| `startedAt` | Turn start. Never null. |
| `deadline` | When the turn should give up; `null` = none. Stage 1 sets none. |
| `budget` | The hosted turn's `TurnReservationGuards` once client selection created them (`withBudget`); `null` before and for BYOK. |

`scope()` returns `TenantScope.of(tenant)`: the single bridge back to the `ThreadLocal` for code that still needs it.
The record holds no mutable state of its own; `TurnReservationGuards` is a shared one-shot handle by design.

### 2.2 Why the ThreadLocal stays at the JDBC boundary

- Hibernate's `CurrentTenantIdentifierResolver` has no parameter for a request object; it is called from inside the
  session factory. Spring Data repositories, `@Transactional` proxies and the nine raw `dataSource.getConnection()`
  users (ADR-0016 fact 2) all sit behind that call. Passing a tenant into every repository method is a persistence
  rewrite, not a turn-path change.
- ADR-0016 is changing the same boundary (`SET LOCAL search_path`). Changing who supplies the tenant at the same time
  would make a leak impossible to attribute. One variable at a time.
- The safe shape is therefore: **explicit above the repository layer, ThreadLocal at it.** A turn-path method that
  calls a repository does so inside `context.scope().run(...)` (or on the request thread, where the filter already
  set it). The `ThreadLocal` becomes an implementation detail of "talk to the database", not a way of passing
  arguments.
- Java `ScopedValue` (final in JDK 25; the build targets 21) would not help on its own either: it is lost on Reactor
  scheduler hops exactly like a `ThreadLocal`. Reactor `Context` with Micrometer context-propagation was considered;
  it propagates automatically but is invisible in signatures and adds per-operator overhead on the hot path. Both stay
  options for the JDBC-boundary bridge in a later ADR.

### 2.3 Staged migration

Each stage is one reviewable PR, behaviour-preserving, with the metric it must move.

| Stage | Change | Done when (measurable) |
|---|---|---|
| 1 (this ADR) | Add `TurnContext` + unit tests. Build it once in `AgentChatService#chat(ChatTurnRequest)` from the request thread (`TurnContext.atEntry`). `ChatTurnPreflight.run` takes it instead of `requestedAgentId`/`sessionId` and reads the tenant from it; `ChatTurnPreflight.Turn` carries it onward. | `ChatTurnPreflight` has 0 `TenantContext.current()` calls (was 1). `TurnContextTest` green. |
| 2 | Dispatcher and prompt assembler read `turn.context()`: replace `dispatchTenant`, `capturedTenant`, `cacheTenant` and `preTurnTenant` captures with `context.scope()`. `RlArmSelection`, `ChatClientSelection` take the context. `ChatClientSelection` stores the hosted `TurnReservationGuards` via `withBudget`. | Turn pipeline classes: 11 call sites to at most 3 (`ChatTurnExecutor`, `BufferedTurnRunner` remain). |
| 3 | After `ChatTurnExecutor` is split (planned separately), pass `TurnContext` into `streamFast`, `bufferAndGuardGenerationClaims`, `planThenRespond`, `verifyThenRespond`; drop the `currentTenantId` string parameters. Tool callbacks receive the context through `AgentToolProvider` instead of capturing at construction (`TenantContextPropagatingToolCallback` reads `context.scope()`). | 0 `TenantContext.current()` in the turn pipeline classes; the `String currentTenantId` parameter is gone from them. |
| 4 | Invert the entry: `AgentChatStreamController#chat` (formerly `AgentController#chat`) and the channel adapters build the `TurnContext` (they already know tenant and subject) and call `chat(TurnContext, ChatTurnRequest)`. The entry applies the `ThreadLocal` from it for the synchronous part and the Flux is subscribed inside `TenantScopedFlux.subscribeInScope(context.scope(), ...)`. `atEntry` remains only for legacy callers. | Every caller of `AgentChatService#chat` in `core/**/src/main` passes a context (grep count of the legacy entry = 0 outside tests). |
| 5 | Deadline: set `deadline` from configuration at the entry and make the per-depth timeouts derive from `remaining()`. Request id: stop reading MDC below the entry; log with `context.requestId()`. | One configured turn deadline; `ChatTimeouts` reduced to the derived values. |
| 6 | Guard: an ArchUnit rule (in `core/app/src/test`, like `AsyncHopTenantScopeRulesTest`) that forbids `TenantContext.current()` in the turn pipeline package classes listed above, frozen at the stage-3 count (0). The ThreadLocal remains legal in repositories, the tenancy module and background jobs. | Rule green; a new `TenantContext.current()` in a pipeline class fails the build. |

Out of scope: background jobs, workflow, omnichannel internals beyond building the context at stage 4, and anything
behind the repository layer.

### 2.4 Performance guard

- No new network hop (rule 6): the record is an in-process value; nothing here calls another service.
- Cost per turn at stage 1: one record allocation, one `MDC.get`, one `Instant.now()`, and a `UUID.randomUUID()`
  only when no request id is in MDC (channel webhooks). Later stages remove `TenantContext.current()` reads and
  `TenantScope` captures, so they should not add cost.
- Rule from [HOT-PATH-PERFORMANCE.md](HOT-PATH-PERFORMANCE.md) section 4a: **every stage from 2 on is benchmarked
  at 32 concurrent users on the kind stage** (`tests/load/kind/bench.sh`, `tests/load/scripts/turn-overhead.js`)
  before it is called healthy. Accept the stage when failed turns stay at 0 and TTFT p95 at 32 users is within the
  run-to-run noise of the baseline taken on the same machine state just before it. Stage 1 changes no hop, lock or
  pool use and was not benchmarked (see section 4).
- Stage 4 moves where the Flux is subscribed. That is the stage most likely to pin threads or change scheduling, and
  it is the one to bisect first if the benchmark regresses.

### 2.5 Implementation status (stages 2 and 3)

Implemented on branch `wave2/turn` (architecture hardening wave 2), together with the split of the four turn-path
files over the 665-line gate. Behaviour-preserving apart from the hops listed below, which now get the turn's tenant
where they previously depended on whichever thread they ran on.

| Item | Where it landed |
|---|---|
| Stage 2: dispatcher and assembler read the context | `ChatTurnDispatcher` and `ChatPromptAssembler` read `ChatClientSelection.Clients#context()`. `dispatchTenant`, `capturedTenant`, `cacheTenant` and `preTurnTenant` are gone: the pool-exhausted issue write and the semantic-cache write run in `context.scope()`, the RL episode close gets `context.tenantContext()`, the three pre-turn reads run in `context.scope().wrapSupplier(..)`. |
| Stage 2: selections take the context | `RlArmSelection#select(TurnContext, Turn, ..)`, `ChatClientSelection#select(TurnContext, Turn, ..)`. `ChatTurnPreflight.Turn` no longer carries `currentTenantId`. |
| Stage 2: budget on the context | `ChatClientSelection` returns `context.withBudget(hostedChain.get(0).guards())` for a hosted turn; the dispatcher releases a failed chain's reservation with `context.budget()` (the same guards object every link carries). |
| Stage 3: depth methods take the context | `ChatTurnExecutor#streamFast(TurnContext, ..)` (body now in `FastStreamTurn` / `FastStreamRecovery`), `#bufferAndGuardGenerationClaims(TurnContext, ..)`, `BufferedTurnRunner#verifyThenRespond(TurnContext, ..)` / `#planThenRespond(TurnContext, ..)`. Their `String currentTenantId` / `tenantId` parameters are gone. The buffered worker applies `context.scope(pin)` instead of `TenantScope.capture()`; the fallback-provider client resolution in the FAST path runs in `context.scope()`. |
| Stage 3: tool callbacks | `AgentToolProvider#resolveToolsFor(TurnContext, ..)` resolves the whole tool list inside `context.scope()`, and `TenantContextPropagatingToolCallback` holds the `TenantScope` built from that context once and re-applies it on every call. |

Measured by grep (production sources, comment lines excluded) on the branch:

| Where | `TenantContext.current()` / `TenantScope.capture()` call sites |
|---|---|
| Turn pipeline classes (`ChatTurnPreflight`, `ChatPromptAssembler`, `ChatTurnDispatcher`, `RlArmSelection`, `ChatClientSelection`, `ChatTurnExecutor`, `FastStreamTurn`, `FastStreamRecovery`, `BufferedTurnRunner`, `BufferedTurnCalls`) | **0** (was 11; stage 2's target was at most 3, stage 3's 0) |
| `AgentChatService` | 0 (the one read is `TurnContext.atEntry`, in `TurnContext`) |

What stage 3 deliberately did not do, and why:

- `AgentToolGuard#guardAndLog` still reads `TenantContext.current()` to build each wrapper chain (the captured
  context, the MCP grant id, the output redactor). On the chat turn that read now happens inside the turn's
  `context.scope()`, so it returns the context's tenant, but the value still passes through the ThreadLocal on the
  resolving thread. Threading the context explicitly would change all 73 `guardAndLog` call sites in the tool
  resolvers, which another lane is splitting at the same time; do it after that split lands.
- The C1 `AgentVersionPin` is not a `TurnContext` component. The buffered worker reads the pin on the subscribing
  thread (exactly what `TenantScope.capture()` did) and passes it to `TurnContext#scope(UUID)`
  (`TenantScope.of(TenantContext, UUID)`, new). Making the pin part of the record is a candidate for stage 5.
- `ChatTurnExecutor#callWithProviderFallback(String tenantId, ..)` keeps its string parameter: it is shared with the
  delegate-call and specialist-handoff paths, which have no `TurnContext`.

Hops that now get the turn's tenant where they previously took whatever the executing thread held (a stricter
version of the old behaviour, identical whenever the old code ran on the request thread):

- the buffered worker (previously `TenantScope.capture()` at subscription, which is empty if a hosted-chain failover
  resubscribes from a scheduler thread);
- the FAST fallback-provider resolution and the fallback issue records (previously the ThreadLocal of the thread that
  called `streamFast`, which for a failover rung is a scheduler thread);
- the fabricated-URL guard's tool-log read in the FAST completion and in `bufferAndGuardGenerationClaims` (previously
  unscoped on the stream-completion thread).

### 2.6 Implementation status (stages 4 and 6)

Implemented on branch `wave2/stage4b` (on top of wave 2, `f7c2b3826`).

| Item | Where it landed |
|---|---|
| Stage 4: the entry method | `AgentChatService#chat(TurnContext, ChatTurnRequest)` (declared on `LegacyChatEntryPoints`). It runs the synchronous part of the turn under `TurnContext#onEntryThread` and returns `TenantScopedFlux.subscribeInScope(context.scope(), () -> reply)`. `chat(ChatTurnRequest)` and the positional overloads are deprecated delegates that build the context with `TurnContext.atEntry` (calling thread's tenant); only tests and the reflective `ReflectiveAgentChatTurnReplayer` (`chatTextOnly`) still reach them. |
| Stage 4: the context factory | `TurnContext.forEntry(TenantContext tenant, ChatTurnRequest request)` (public). `atEntry(request)` is now `forEntry(TenantContext.current().orElse(null), request)`. |
| Stage 4: the callers | Every production caller builds its own context and calls `chat(TurnContext, ChatTurnRequest)`: the HTTP chat entry (`AgentChatStreamResponder`, used by `AgentChatStreamController` for `POST /api/agents/{agentId}/chat`), the share controller (stream and blocking), A2A, AG-UI, OpenAI-compat / Open Responses, MCP (developer API, call-agent tool, exposed tools), workflow (agent node, supervisor node, `chatWithParams` via `ChatStructuredCall`), agent jobs (goal turn and the tool-less repair turn, which now builds the `suppressAgentTools` request itself), Quick Task, studio apps, voice push-to-talk, every omnichannel adapter (Discord, Slack, Teams, Telegram, Meta social/WhatsApp, Twilio SMS/WhatsApp, Twilio voice relay, email and X DM polling) and the demo surfaces (demo chat/share/magic/studio, demo Telegram/WhatsApp). An HTTP or webhook entry passes the tenant its filter or adapter put on the thread; the Twilio voice relay passes the explicit channel principal it builds; the demo surfaces build the context inside `inDemoTenantContext`. |
| Stage 6: guard | `core/app/src/test/java/com/agenticai/core/archrules/TurnPipelineTenantContextRulesTest`: no `TenantContext.current()` or `TenantScope.capture()` call in `AgentChatService`, `ChatTurnPreflight`, `RlArmSelection`, `ChatClientSelection`, `ChatPromptAssembler`, `ChatTurnDispatcher`, `ChatTurnExecutor`, `FastStreamTurn`, `FastStreamRecovery`, `BufferedTurnRunner`, `BufferedTurnCalls` (nested and lambda classes included). Not frozen: the count is 0, so there is no baseline. |

Measured by grep (production sources, comment lines excluded; script counts calls on an `AgentChatService`
receiver and classifies them by first argument):

| Where | Before (`f7c2b3826`) | After |
|---|---|---|
| Legacy chat entry calls (`chat(..)` / `chatTextOnly(..)` without a `TurnContext`) in `core/**/src/main` | 36 | **0** |
| `chat(TurnContext, ChatTurnRequest)` calls in `core/**/src/main` | 0 | 36 |
| `TurnContext.atEntry` in `core/**/src/main` | 1 (`AgentChatService#chat`) | 1 (the deprecated `LegacyChatEntryPoints#chat(ChatTurnRequest)`, no production caller) |

The 0 is also checked by the compiler: with every legacy overload deleted from `LegacyChatEntryPoints`, all
production code (`-Dmaven.test.skip=true compile`, whole reactor) still compiled. The reflective
`chatTextOnly(UUID, String, String, String)` lookup in `core/quality` is the one remaining legacy user, by name
at run time.

What changed at run time, and what did not:

- **Subscription thread: unchanged.** `subscribeInScope` subscribes on whichever thread subscribes to the
  returned Flux (the request thread for the SSE controller, the caller's thread for the `block()` callers, the
  voice relay's `promptScheduler` thread there); it adds no `subscribeOn`/`publishOn` and no scheduler. What it
  adds is one `TenantScope` apply/restore around the `subscribe` call: the subscribing thread sees the turn's
  tenant during subscription and is put back as found. On every current caller the subscribing thread already
  carries that tenant, so this is a set to the same value. A caller that subscribes on a thread without the
  tenant now gets the turn's tenant there (stricter than before, as for the stage-3 hops).
- **Synchronous part: unchanged on every current caller.** `onEntryThread` runs the work untouched when the
  thread already holds the context's exact `TenantContext` instance, which is the case for every caller above
  (each builds the context from that instance). Only when the thread holds a different tenant (no current
  caller) does it apply the context's tenant and restore it afterwards. It deliberately does not use
  `TenantScope` there: `TenantScope` restores the `AgentVersionPin`, and the preflight pins the agent version
  on the request thread for the subscription and the tool callbacks; the pin is left alone, as before.
- Per turn this adds one `TurnContext.forEntry` at the caller (moved, not new: the service no longer calls
  `atEntry`), one identity check, one `Flux.from(publisher)` wrapper and the scope apply/restore at subscribe.
  No new hop, lock, executor or network call.

Tests: `AgentChatStreamControllerTurnContextTest` (the HTTP entry passes a context whose tenant is the same
instance as the request thread's ThreadLocal),
`VoiceRelayWebSocketHandlerTenantScopeTest#theAdapterBuildsTheTurnContextEqualToTheThreadLocalOnTheCallingThread`
(channel adapter, same assertion on the scheduler thread that enters the turn), `TurnContextEntryTest` (`forEntry`, `atEntry`, `onEntryThread` incl. the pin), and the
stage-6 rule. Caller tests that mocked the positional overloads now stub `chat(TurnContext, ChatTurnRequest)`.

## 3. Consequences

- The tenant becomes visible in signatures on the turn path. A missed hop becomes a compile error (no context in
  scope) instead of a silent `public` fallback.
- For a while both mechanisms coexist. The invariant during the migration is: **the `TurnContext` of a turn is built
  from, and equal to, the `ThreadLocal` value on the request thread.** Stage 4 inverts the source of truth; until then
  the record never diverges from the filter's value.
- `ChatTurnPreflight.Turn` grows a component; collaborators read `turn.context()` instead of new parameters, which
  keeps the stage diffs small while `ChatTurnExecutor` is being split.
- The JDBC boundary is unchanged and still depends on the `ThreadLocal`. A later ADR can replace that bridge (Reactor
  `Context`, `ScopedValue`, or schema-qualified SQL per ADR-0016 option B) without touching the turn pipeline again.

## 4. What is not proven

- Stage 1 was compiled and unit-tested (`TurnContextTest`, the `AgentChatService*Test` turn tests). It was **not**
  run on a stack and **not** benchmarked at 32 users. It adds no hop, lock or executor, so the benchmark was not
  judged necessary for this slice; stage 2 onward must run it.
- Stages 2 and 3 were compiled (whole reactor) and unit-tested (the `agents` module tests, including the
  `AgentChatService*Test`, `ChatTurn*Test`, `TurnContextTest` and `BufferedTurnRunner*Test` turn tests, and the
  architecture/wiring tests in `core/app`). They were **not** run on a stack and **not** benchmarked: the 32-user
  kind benchmark (section 2.4) is owed before they are called healthy. They add no hop, lock, executor or network
  call; per turn they add one `withBudget` record copy on hosted turns and one extra `TenantScope` apply around tool
  resolution.
- The call-site counts in section 1 are grep counts, not a semantic analysis; some sites may be legitimate
  (background work) and stay.
- That every caller of `AgentChatService#chat` reaches it on the thread the filter or adapter set up (the stage-1
  invariant) is believed from reading the controllers and adapters, not proven by a test. Stage 4 adds tests for
  the HTTP chat entry and one channel adapter (Twilio voice relay); the other callers are covered by reading
  only.
- Stages 4 and 6 were compiled (whole reactor) and unit-tested (whole `core` suite, plus `make gates` and a
  boot check of the API jar; see the branch hand-over for the exact results). They were **not** run on a stack
  and **not** benchmarked: stage 4 moves where the Flux is subscribed inside a tenant scope and is the stage to
  bisect first if the 32-user kind benchmark (section 2.4) regresses. The integrator runs that benchmark.
