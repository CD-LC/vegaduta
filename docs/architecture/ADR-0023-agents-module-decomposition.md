# ADR-0023: Decompose `core/agents` into modules of at most 300 files

| | |
|---|---|
| **Status** | Accepted — implemented 2026-10-16. All six cuts are done (`agents-jobs`, `agents-budget`, `agents-chatport`, `agents-browser`, `agents-interop`, `agents-studio`, `agents-tools`, `agents-turn`); the kernel is at ~256 main files and the `agents` root package is empty. Still open: step 7 (removing the split packages) and the owed 32-user kind benchmark after the `agents-tools`/`agents-turn` cuts. Note (2026-10-21, v0.1.9): `agents-turn` has since grown to 307 main files, over the 300 cap, and the kernel to 259 ([core module graph](core-module-graph.md)). |
| **Date** | 2026-10-16 |
| **Deciders** | Platform Engineering (maintainers) |
| **Related** | [ADR-0011 Provider plugins](ADR-0011-provider-plugins.md), [Structural audit 2026-10-14](STRUCTURAL-AUDIT-2026-10-14.md), [Core module graph](core-module-graph.md), [ADR-AGENT-JOB-DURABILITY](ADR-AGENT-JOB-DURABILITY-2026-08-17.md) |

## 1. Problem

`core/agents` is the largest module in the reactor by a wide margin. On main at
`a8476f547` it held **1165 main source files**, with **154 classes in the root
package** `com.agenticai.core.agents`. Every other module in `core/` is under
310 files.

At that size the module costs us in four ways:

- **Build and test time.** Any change in `agents` recompiles and retests about
  4000 tests, and every module that depends on `agents` (app, app-jobs,
  app-worker, brief, mcpserver, omnichannel, platform, templates, voice,
  workflow) rebuilds.
- **No enforced direction inside it.** The structural audit froze about 50
  package-cycle violations under `com.agenticai.core.agents.*`. Inside one Maven
  module nothing stops a new edge from closing another cycle. Between modules,
  Maven refuses a cycle at compile time.
- **The root package is a dumping ground.** 154 classes with no shared concept
  (the chat turn executor, the `Agent` entity, free-tier limits, response caches,
  edge telemetry, budget advisors) sit in one package, so package-private access
  couples all of them.
- **Ownership.** Nobody can review "the agents module". People can review "the
  turn path" or "jobs".

## 2. Decision

1. **Rule: at most 300 main files per module, and an empty root package.** The
   root package `com.agenticai.core.agents` holds no classes once the
   decomposition is finished. A gate (section 6) ratchets both numbers down and
   never up.
2. **Split by feature, in dependency order.** Each new module depends on
   `agents` (or on a lower split-out module). `agents` never depends on a module
   split out of it. Where code that stays in `agents` needs something from a
   split-out module, `agents` owns a **port** (an interface, or a Spring event)
   and the split-out module implements it.
3. **Keep Java package names on the first move.** A class moves to a new Maven
   module with the same package. Split packages across jars work on the plain
   classpath that Spring Boot uses (we do not use JPMS), so no import changes
   outside the moved code, no change to Spring bean names, JPA entity names,
   REST paths, ArchUnit "area" names (they come from the package) or the
   frozen ArchUnit store. Renaming packages is a separate, later, mechanical
   step.
4. **Every app shape that loaded the code keeps loading it.** A new module is
   added to `core/pom.xml` (module list and dependency management), to `app`,
   `app-jobs` and `app-worker` where `agents` is listed, and to the
   `COPY <module>/pom.xml` list in `core/Dockerfile`
   (`scripts/ci/dockerfile-poms.sh` enforces that).

## 3. Target module map

Counts are main source files as of this ADR. "Now" means after the
`agents-jobs` cut.

| Target module | Packages (under `com.agenticai.core`) | Files | State |
|---|---|---:|---|
| `agents-jobs` | `agents.job` and all sub-packages (`schedule`, `trigger`, `progress`, `durability`, `ownership`, `approval`, `output`, `sentinel`, `operator`, `dto`), `agents.scheduledtask`, `agents.research`, `agents.tools.scheduling.ScheduleTaskToolCallback` | 124 | **Done** (this ADR) |
| `agents-budget` | `agents.spendcap`, the per-user and per-run budget classes in the root package (`PerUserBudget*`, `AgentRunBudget*`, `BudgetPolicy`) | ~15 | In flight (parallel change) |
| `agents-chatport` | `agents.chatport` (chat export, import, transcripts) | 13 | In flight (parallel change) |
| vendor modules (ADR-0011) | `google.agenttools`, `payments.agenttools`, `sarvam.agenttools`, `imagegen.agenttools`, `elevenlabs.agenttools` move into their vendor modules, behind `agentspi` | 49 | In flight (ADR-0011 vendor extraction) |
| `agents-browser` | `agents.tools.browseruse`, `agents.tools.web` (web search, deep research tools) | 120 | **Done** (wave 2): 134 main files; Playwright left `agents`; ports `tools.WebToolPort`, `tools.BrowserToolPort`, `capability.WebSearchAvailability` |
| `agents-interop` | `agents.a2a`, `agents.agui`, `agents.openai` (compat endpoints), `agents.collab`, `agents.crew`, `agents.directory` | 52 | **Done** (wave 2): 50 files moved; ports `A2aRemoteAgentToolSource`, `CollaboratorToolFactory`, `SpecialistHandoffService` in agents; `A2aProperties`, `A2aCardSigningService`, `RunBudget*`, `SwarmVerifierConfig`, `HandoffChain`, `HandoffStatus`, `SpecialistCandidate` stay as shared vocabulary |
| `agents-studio` | `agents.appbuilder`, `agents.coding`, `agents.importer`, `agents.demo`, `agents.lab`, `agents.loadtest`, `agents.experiment`, `agents.vision`, `agents.face`, `agents.skills`, `tutor`, `agents.ragquality` | ~165 | **Done**: 100 main files; port `tools.coding.CodingTaskToolFactory`. `experiment`, `vision`, `loadtest` and `skills` went to `agents-turn` instead (the turn path uses them directly) |
| `agents-tools` | `agents.tools` root and the remaining `agents.tools.*` (classification, code, openapi, script, mcp*, search, messaging, device, x402, e2b, commerce, ...), `tables` | ~250 | **Done** (wave 2, section 5.2): 207 main files moved; ports `tools.AgentToolPort`, `tools.ToolReplaySafety` and the `tools.ToolSearchToolNames` constants in agents; the shared tool vocabulary (step events, tool-call log, journals, `classification`, trifecta enforcement, sandbox files and client, selection rules) and the ports other modules implement stay in `agents` |
| `agents-turn` | `agents.turn` (the turn path, with attachments, RL arms, `HostedChainFailover`, `TenantScopedFlux`, the turn journal endpoints and the chat SSE endpoint), `agents.turn.quality`, `agents.turn.cache`, `agents.edge`, `agents.usermemory`, `agents.memory`, `agents.context`, `agents.hooks`, `agents.turnjournal`, `agents.resumable`, `agents.branching`, `agents.decision`, `agents.gateway`, `agents.assist`, and (measured, section 5.3) `agents.skills`, `agents.experiment`, `agents.vision`, `agents.loadtest`, `agents.share`, `agents.collab`, `agents.crew` | ~260 | **Done** (wave 2, section 5.3): 283 main files moved plus the new `AgentChatStreamController`, 284 in all; `agents-tools` depends on it, not the other way round |
| `agents` (kernel) | `agents.core` (the `Agent` entity, settings, access, versioning, projection, prompt versions), `agents.modality`, `agents.hosted` (hosted-pool client `FreeTier*`/`Hosted*`), `agents.quota`, `agents.guardrail`, `agents.runbudget`, `agents.dto`, `agents.version`, `agents.capability`, `agents.toolpolicy`, `agents.trust`, `agents.attestation`, `agents.consent`, the shared tool vocabulary, the turn value types (`TurnContext`, `ChatTurnRequest`) and the ports the other modules implement (`agentspi` is its own module) | ~170 | **Done** (section 5.3): 255 main files, under the 300 cap; `core/agents` is no longer in the module-size baseline |

Dependency direction of the target:

```
agents (kernel, ports)
  ^-- agents-turn  ^-- agents-tools  ^-- agents-browser
  ^-- agents-turn  ^-- agents-jobs, agents-chatport, agents-interop, agents-studio
agents-budget, agentspi  (below agents)
```

As built (section 5.3) the edge between turn and tools points the other way
from the original plan: `agents-tools` depends on `agents-turn`. The class
graph showed no reference from the turn side into `agents-tools` (the turn
path already reached tools only through `tools.AgentToolPort`), while nine
tool classes referenced turn classes.

The two "Proposed" rows with the most risk are `agents-tools` and
`agents-turn`. Today `AgentToolProvider` needs `Agent`, and `AgentChatService`
needs `AgentToolProvider` (the reason `tools` was folded into `agents` in the
first place, see `core/agents/pom.xml`). With the entity in the kernel that edge
points the right way, but some tools call back into the turn (delegation,
`a2a_call_agent`, specialist hand-off). Those need ports in the kernel before
the move. A dependency scan of the actual class graph decides the final
boundary; the table is the plan, not a measurement.

## 4. How the `agents-jobs` cut was done

This is the pattern for the next cuts.

- **Moved** (packages unchanged): 122 main files and 74 test files. Two adapters and one paging test are new in `agents-jobs`; three port interfaces are new in `agents`. The
  research facade moved with jobs, because a research run is an agent job
  (`ResearchRunService` is a thin layer over `AgentJobService`) and nothing
  outside `research` referenced it.
- **Stayed in `agents` as shared vocabulary or ports** (split packages):
  - `job.AgentJobSession`: how a job id becomes a chat session id. The turn
    path and three tools use it; it has no dependencies.
  - `job.progress.ProgressSource`: the enum the progress port takes.
  - `job.schedule.ScheduledWorkflowRunner`: already a port, implemented by
    `core/workflow`. Keeping it in `agents` means `workflow` does not need a
    new dependency.
- **New ports in `agents`, implemented in `agents-jobs`:**
  - `job.AgentJobLookup` (read-only `JobFacts` snapshot). Used by the approval
    replay backstop in `AgentToolCallApprovalExecutor` and by
    `SkillDistillationService`, which used to read the `AgentJob` entity and
    repository directly. Implemented by `AgentJobLookupAdapter`.
  - `job.progress.JobProgressRecorder`. Used by the deep_research phase notes
    (`ResearchToolWiring`, `AgentIntegrationToolResolver`). Implemented by
    `AgentJobProgressService`.
  - `tools.scheduling.ScheduleTaskToolFactory`. Used by
    `AgentScheduledTaskToolResolver`. Implemented by
    `ScheduleTaskToolCallbackFactory`, which builds the `schedule_task` tool
    exactly as the resolver did.
- **Wiring:** `agents-jobs` is in the `core/pom.xml` module list and
  dependency management, in `app`, `app-jobs` and `app-worker`, and in the
  `core/Dockerfile` pom list. The `json-schema-validator` dependency moved with
  the structured-output code that imports it.
- **What did not change:** bean names, entity names, tables, REST paths,
  configuration keys, the class names listed in `application-shape-*.yml`
  scheduling roles, and the ArchUnit store.

`agents` went from 1165 to 1046 main files (122 out, 3 port interfaces in).

## 5. Sequencing

1. **Now, in parallel:** `agents-jobs` (done), `agents-budget`,
   `agents-chatport`, and the ADR-0011 vendor `agenttools` extraction. These
   are leaves with few inbound references, so they are the cheapest cuts.
   ADR-0011 goes first among the remaining work because it also removes five
   kernel-to-vendor Maven edges.
2. **Gate** (section 6), with today's numbers as the baseline.
3. **`agents-browser`.** Leaf; removes Playwright from every module that only
   needs agents.
4. **`agents-interop`** and **`agents-studio`.** Mostly controllers and
   services with inbound references only from tests. Same pattern: move,
   invert the few references with ports.
5. **Empty the root package, inside `agents`.** Move the 154 root classes into
   named packages (`agents.core`, `agents.turn`, `agents.budget`, ...) in one
   mechanical commit per target package, while no other change touches
   `agents`. This is the only step that rewrites imports across modules.
   Update the frozen ArchUnit store entries by renaming, never by refreezing.
   **Done** 2026-10-16, see 5.1.
6. **`agents-tools` and `agents-turn`.** Introduce the kernel ports that break
   the tool-to-turn callbacks, then move. The turn path stays in-process (an
   extra jar is not a network hop, AGENTS.md rule 6), but run the 32-user
   kind benchmark after this step anyway: it changes Spring wiring on the hot
   path. **`agents-tools` done** 2026-10-16, see 5.2; **`agents-turn` done**
   2026-10-16, see 5.3. The kind benchmark is still owed for both cuts.
7. **Remove the split packages.** Once a module is stable, move its ports into
   a package the module owns in `agents` (for example `agents.jobport`) and
   drop the shared package names.

### 5.1 Root package map (step 5)

All 163 classes of `com.agenticai.core.agents` (and their tests, which keep
their class's package) moved in one scripted commit; the root package is empty
in every module and the module-size baseline no longer lists it.

| Package | Side | Classes |
|---|---|---|
| `agents.core` | kernel | `Agent`, `AgentConfigColumns`, `AgentRepository`, `AgentService` and its collaborators (`AgentAccessService`, `AgentCatalogOps`, `AgentCreationService`, `AgentSettings*`, `AgentModel*`, `AgentProjection*`, `AgentVersioningService`, `AgentSchema*`, `AgentToolAccessWriter`, ...), `AgentController`, `AgentSessionAccessGate`, the entity value objects (`CapabilityFlags`, `GenerationParams`, `FallbackPolicy`, `CachingPolicy`, `EdgeRoutingPolicy`, `ReasoningDepth`, `TuningMode`, `AgentTier`, `AgentAutonomyTier`), `GenerationParamsInferenceService`, `PromptVersion*`, `AgentPromptHistory`, the eviction caches, `HotpathLookupsConfig`, the agent exceptions |
| `agents.modality` | kernel | `ModelModalities*`, `ModalityModels`, `ChatAttachmentCategory`, `UnsupportedAttachmentTypeException` (the entity and `dto` need them) |
| `agents.hosted` | kernel | `FreeTierChatClientService`, `FreeTierTokenTrackingAdvisor`, `Hosted*` except the stream failover (used by the kernel, tools, workflow and platform; no turn dependency) |
| `agents.quota` | kernel | `FreeTierUsageLimiterService`, `FimCompletionEntitlementsAdapter`, `PlatformQuotaResetLog*`, `DailyQuotaResetScheduler` |
| `agents.guardrail` | kernel | `AgentGuardrailController`, `AgentGuardrailService`, `GuardrailNotAvailableForPlanException` |
| `agents.runbudget` | kernel | `AgentRunBudgetController`, `AgentRunBudgetService` (not `agents.budget`: that package lives in the lower `agents-budget` module) |
| `agents.turn` | turn | `AgentChatService`, `ChatTurn*`, `ChatPrompt*`, `BufferedTurn*`, `FastStream*`, `Chat*Facade`, `ChatServiceParts`, `TurnContext`, `ChatTurnRequest`, `LegacyChatEntryPoints`, `StreamEndWiring`, `TenantScopedFlux`, `HostedChainFailover`, `HostedFailoverPolicy`, `VerifierEscalationRouter`, the attachment classes (`ChatAttachment*`, `InlineAttachmentProcessor`, `PendingAttachments`, attachment exceptions), the RL arm classes (`Rl*`, `ChatArmConfig`, `ExperimentArmConfig`), `AgentChatSessionController`, `AgentChatStreamResponder`, `AgentLabCompletionService`, `AgentMessageEvents`, `AgentReliabilityService`, `TurnFeedbackAccessGate`, `TurnJournalController`, `TurnJournalService` |
| `agents.turn.quality` | turn | `LlmQualityJudgeService`, `AnswerConsistencyChecker`, `QueryComplexityClassifier`, `ReflexionService`, `TaskPlanningService` |
| `agents.turn.cache` | turn | `LlmResponseCacheService`, `SemanticResponseCacheService` |
| `agents.edge` | turn | `Edge*` except `EdgeRoutingPolicy` (an entity value object) |
| `agents.usermemory` | turn | `UserMemory*` |
| `agents.memory`, `agents.decision` | turn | `AgentMemoryController`; `AgentRelevanceSelector` |

The attachment and RL-arm classes were not given their own packages: they
read `AgentChatService`'s package-private fields and nested types, and a
split would have widened most of the turn path to public. The turn journal
endpoints stay in `agents.turn` because in `agents.turnjournal` they would
pull that acyclic package into the package cycle. Four successors of the
root package sit in the same package cycle the root package was in, so the
frozen store entry for the root package was renamed to `agents.core`,
`agents.guardrail`, `agents.turn` and `agents.turn.quality`.

Kernel-to-turn references left for step 6 (each needs a port in the kernel
or a move):

- `agents.core.AgentController` hosts the chat endpoints and builds
  `AgentChatStreamResponder` over `AgentChatService`.
- `agents.share.AgentShareController` calls `AgentChatService#chat`.
- `agents.tools` (`AgentToolProvider`, `ToolResolverConfiguration`,
  `AgentCustomToolResolver`, `AgentCrewBrowserToolResolver`) use
  `AgentChatService` and `TurnContext`. These moved to `agents-tools` in 5.2,
  where they are legal for now (see the list there).

### 5.2 `agents-tools` cut (step 6, first half)

A scan of the class graph (jdeps over `agents/target/classes`) listed every
kernel or turn class that referenced a class under `agents.tools.*` or
`tables`. The cut keeps that set compiling without `agents-tools`:

- **Ports in `agents`, implemented in `agents-tools`:**
  - `tools.AgentToolPort`: every public instance method of
    `AgentToolProvider` (the tool list for a turn, a delegated call or a
    specialist hand-off; tool candidates; the tools endpoint listing; the
    skills and procedures prompt blocks; the MCP cache eviction).
    `AgentToolProvider` implements it and keeps its bean name. The turn path
    (`AgentChatService`, `ChatServiceParts`, `ChatPromptAssembler`,
    `ChatDelegateCall`, `ChatSpecialistHandoff`), `AgentController`,
    `ToolAllowListCheck`, `EdgeToolExecutionController`,
    `TrifectaEnforcementService`, AG-UI (`agents-interop`) and the demo studio
    (`agents-studio`) now depend on the port, so neither of those two modules
    needs `agents-tools`.
  - `tools.ToolReplaySafety`: `replaySafeVerdict`, implemented by
    `AgentToolGovernance`, used by `VerifierEscalationRouter`.
  - `tools.ToolSearchToolNames`: the `search_tools`/`call_tool` names, which
    `SearchToolsToolCallback.NAME` and `CallToolToolCallback.NAME` now alias.
    The turn path and the hook runtime only compared tool names.
- **Stayed in `agents` (shared tool vocabulary, split packages):** step events
  (`AgentStepEventEmitter`, `AgentStepEventPayloads`,
  `AgentStepEventRedactor`), the tool-call log (`ToolCallLog*`,
  `AgentToolCallStats`), `StepJournal*` and `EffectJournal`, the whole
  `tools.classification` package with `TrifectaEnforcementService` (the agent
  service enforces the trifecta on save), `ToolDescriptor`, `ResolvedToolList`,
  `AgentToolSelection` with what it wraps (`NamespacedMcpToolCallback`,
  `SanitizedMcpToolCallback`, `McpToolArgumentGuard`, `McpToolOutputSanitizer`,
  `ToolRelevanceSelector`, `ToolRelevanceScoring`),
  `TenantContextPropagatingToolCallback` (the characterisation harness uses
  it), `e2b.SandboxFileRegistry` and its helpers, `code.ToolSandboxClient` with
  its DTOs (skills run scripts through it), `context.RestoreToolResultToolCallback`
  (context compaction), and the ports other modules implement: `WebToolPort`,
  `BrowserToolPort`, `scheduling.ScheduleTaskToolFactory`,
  `coding.CodingTaskToolFactory` and the five `messaging.*Port` interfaces
  (implemented in `omnichannel`, which therefore needs no new dependency).
- **Moved:** 207 main files and 100 test files, packages unchanged. Bouncy
  Castle moved with `tools.x402`. `agents` went from 742 to 535 main files by
  the module-size count. `agents-browser`, `app`, `app-jobs`, `app-worker`,
  `brief` and `workflow` depend on `agents-tools`; `agents-studio` and
  `payments` only in test scope.

References from `agents-tools` back into turn-side packages (legal while the
turn path is in `agents`; `agents-turn` will depend on `agents-tools`, so each
needs a kernel port or a move before that cut):

- `AgentToolProvider` takes a `turn.TurnContext`.
- `ToolResolverConfiguration`, `AgentCustomToolResolver` and
  `AgentCrewBrowserToolResolver` take `ObjectProvider<AgentChatService>`.
- `ToolSourceConfiguration` uses `context.ChatContextCompaction`.
- `AgentToolGovernance` and `AgentToolGuard` use `hooks.AgentToolHooks` /
  `AgentHookRuntime`.
- `AgentKnowledgeToolResolver`, `ToolResolverConfiguration` and
  `AgentToolCallApprovalExecutor` use `memory.AgentMemoryService`,
  `TenantMemoryService`, `SessionMemoryService`, `ChatTurn` and the
  remember-fact tools.

### 5.3 `agents-turn` cut (step 6, second half)

A jdeps scan of `agents/target/classes` and `agents-tools/target/classes` gave
the boundary:

- **No turn class referenced `agents-tools`.** The turn path reached tools only
  through `tools.AgentToolPort` (section 5.2).
- **Nine `agents-tools` classes referenced turn classes:** `AgentToolProvider`
  (`TurnContext`), `ToolResolverConfiguration`, `AgentCustomToolResolver` and
  `AgentCrewBrowserToolResolver` (`AgentChatService`), `ToolSourceConfiguration`
  (`ChatContextCompaction`), `AgentToolGovernance` and `AgentToolGuard` (hooks),
  `AgentKnowledgeToolResolver` and `AgentToolCallApprovalExecutor` (memory and
  the remember-fact tools).

So the cut makes `agents-tools` depend on `agents-turn`, not the reverse that
section 3 planned. That needs no new port; the planned direction would have
needed ports for the chat call, compaction, hooks and memory. The turn path
still never sees a tool implementation class, only the kernel port.

**Moved to `agents-turn`** (packages unchanged): 283 main files, 152 test files
and the built-in skill resources (`src/main/resources/skills`, same classpath
location):

- the turn-side packages of section 3: `turn`, `turn.quality`, `turn.cache`,
  `edge`, `usermemory`, `memory` (with `memory.importer`), `context`, `hooks`,
  `turnjournal`, `resumable`, `branching`, `decision`, `gateway` and `assist`;
- `skills`, `experiment`, `vision` and `loadtest`, which phase A left in
  `agents` because the turn path calls them directly;
- `share` (the public share chat calls `AgentChatService`; its vision
  endpoint calls `vision`);
- `collab` and `crew` minus their vocabulary: the `CollaboratorToolFactory`
  and `SpecialistHandoffService` ports take an `AgentChatService`, so they
  sit with it. `agents-interop` implements them, as before;
- `core.AgentSessionAccessGate` (it reads `SessionMemoryService`),
  `tools.AgentToolSelection` (it names the skill tools) and
  `tools.context.RestoreToolResultToolCallback` (it decodes
  `context.ToolResultRestoreIds`).

**Stayed in the kernel** (split packages):

- `turn.TurnContext` and `turn.ChatTurnRequest`, the turn's value types.
  `AgentToolPort` takes a `TurnContext`, so the port can stay in `agents`.
- `crew.HandoffChain`, `HandoffStatus` and `SpecialistCandidate` (also in
  `AgentToolPort` signatures), and `collab.RunBudget` and `RunBudgetCaps`
  (`AgentRunBudgetService` reads them).
- The MCP wrappers and relevance selector that `AgentToolSelection` uses.

**The chat endpoint.** `AgentController` (kernel) served `POST
/api/agents/{agentId}/chat` over `AgentChatService`. The endpoint moved to a
new controller, `turn.AgentChatStreamController` in `agents-turn`, with the
same path, body, headers and SSE format. This was the smaller change: a port
for "start a chat stream" would have needed the responder, resumable streams
and access checks behind it, while the endpoint is 30 lines.

- Its class-level mapping is the full path `/api/agents/{agentId}/chat`, so
  it shares no base path with another controller and needs no
  `entity-name-lint` allowlist entry.
- `OpenApiControllerAliases` publishes it as `AgentController`, so the
  operation keeps the id `AgentController_chat` and its SDK method.
  `docs/api/openapi.json` is unchanged.
- `ChatBranchController` (edit and regenerate) now delegates to it instead of
  to `AgentController`.
- `AgentController` keeps `SESSION_ID_HEADER` and `STREAM_ERROR_MARKER`.
  `midStreamFailureMessage` is gone from it; callers (`AgentShareController`,
  `ChatFailedReply`, `ChatTurnDispatcher`, A2A and AG-UI) call
  `AgentChatStreamResponder.midStreamFailureMessage`, where the logic already
  was.

**Wiring.** `agents-turn` is in the `core/pom.xml` module list and dependency
management, in `app`, `app-jobs` and `app-worker`, and in the `core/Dockerfile`
pom list. Modules that call the turn depend on it: `agents-tools`,
`agents-chatport` (session memory), `agents-interop`, `agents-jobs`,
`agents-studio`, `brief` (assist, experiments), `mcpserver`, `omnichannel`,
`platform` (session memory), `templates` (assist), `voice` and `workflow`.
`quality` needs nothing: its tests use their own `AgentChatService` stub.

**What did not change:** bean names, entity names, tables, REST paths,
configuration keys, `application-shape-*.yml` class names, the async-hop
allowlist and the frozen ArchUnit store. No class changed package and no
package was created.

### 5.4 Module map after step 6

Main and test `.java` files, counted 2026-10-16 after the `agents-turn` cut:

| Module | Main | Test | Depends on (agents family) |
|---|---:|---:|---|
| `agents` (kernel) | 255 | 75 | `agents-budget`, `agentspi` |
| `agents-turn` | 284 | 152 | `agents` |
| `agents-tools` | 207 | 100 | `agents`, `agents-turn` |
| `agents-browser` | 134 | 65 | `agents`, `agents-tools` |
| `agents-jobs` | 132 | 76 | `agents`, `agents-turn`, `agents-browser` |
| `agents-studio` | 100 | 47 | `agents`, `agents-turn` (`agents-tools` in test scope) |
| `agents-interop` | 52 | 32 | `agents`, `agents-turn`, `agents-budget` |
| `agents-chatport` | 21 | 9 | `agents`, `agents-turn` |
| `agentspi` | 15 | 0 | none |
| `agents-budget` | 11 | 3 | none |

Every module is under the 300 cap; `core/agents` left
`scripts/ci/module-size-baseline.txt`. Step 7 (dropping the split packages)
remains.

## 6. Enforcement

- **Maven** forbids the reverse edge: `agents` has no dependency on any module
  split out of it, so a reference back fails to compile.
- **Gate:** `scripts/ci/check-module-size.sh` (in `make gates` and
  `static-gates.yml`) counts main `.java` files per `core/*` module and classes
  directly in `com.agenticai.core.agents`, compared with the checked-in
  baseline `scripts/ci/module-size-baseline.txt` that may only shrink (the same
  ratchet style and `--update` flag as the file size check). Only entries over
  their limit are baselined (modules over 300, the root package over 0); a new
  module must stay at or below 300.
- **ArchUnit:** `StructureRulesTest` and `ModuleBoundaryRulesTest` keep
  working unchanged, because they import by package from the whole classpath.
  The frozen store can only shrink (AGENTS.md rule 13).

## 7. Consequences

- More modules in the reactor and more lines in each app shape's pom and the
  Dockerfile. `dockerfile-poms.sh` catches a forgotten Dockerfile line; a
  forgotten app-shape dependency shows up as a missing bean at boot, so run
  `scripts/ci/boot-check.sh` after each cut.
- Split packages are a transitional state. They are invisible to Spring and
  to ArchUnit, but they would block a later move to JPMS until step 7 is done.
- Tests that exercised code in both halves moved to the higher module (for
  example `SkillDistillationServiceTest` and `DeepResearchJobFlowTest` now run
  in `agents-jobs`), and keep their package so package-private access still
  works.

## 8. Addendum: omnichannel-social (2026-10-16)

`core/omnichannel` was the second module over the 300 main-file cap (308). The
Slack, Discord, Microsoft Teams and X adapters (71 main files, 14 tests) moved
to the new `core/omnichannel-social`, leaving `omnichannel` at 238 and removing
it from `module-size-baseline.txt`.

- **Why this cut:** the four packages (`slack`, `discord`, `teams`, `x`) have no
  inbound references from the rest of `omnichannel`, so no port interface or
  event was needed. They depend on `omnichannel` (sessions, dedup, triggers,
  the `ChannelTextSend` and adapter contracts, `meta` for `SlackCredential`),
  never the reverse. Meta, Twilio, email, Telegram and WhatsApp voice stay:
  they reference each other (`meta` and `twilio` and `whatsapp`), so splitting
  them would need ports.
- **Unchanged:** Java packages (`com.agenticai.core.omnichannel.{slack,discord,teams,x}`),
  bean names, REST paths, entities, tables and config keys. Each package lives
  wholly in one module, so there are no split packages.
- **Wiring:** `core/pom.xml`, `core/Dockerfile` and the `app`, `app-jobs` and
  `app-worker` poms.
- **Tests:** `ChannelSendPortImplTest` exercises every channel adapter, so it
  moved to `omnichannel-social` and keeps its package.
## 8. Addendum: core/workflow split (333 to 280 main files)
`core/workflow` was over the 300-file module cap. Two cohesive parts left it,
both with unchanged Java packages, bean names, REST paths, tables and config keys:
- `core/workflow-expression` (package `workflow.expression`, 23 files): the
  expression language. It has no reference to the rest of workflow, so it is a
  leaf that `workflow` depends on (not the other way round, a deliberate
  exception to "the new module depends on workflow": a leaf cannot create a
  cycle and 9 workflow classes use it directly). Its tests and the golden
  template fixtures moved with it; the web help test reads its sources from the
  new path.
- `core/workflow-portability` (package `workflow.portability`, 30 files): DSL
  export/import, Dify and n8n importers and their controllers. Nothing in
  `workflow` references it, so it depends on `workflow` and `workflow-expression`
  and is wired into `app`, `app-jobs` and `app-worker`. The `agents-studio`
  edge (agent importer helpers) moved from `workflow` to this module.
Not cut: `poll`, `share`, `diagram`, `version` and `humaninput` are each
referenced from `WorkflowController`, `WorkflowTriggerConfigService` and similar
root classes, so moving them needs a port per package first.
