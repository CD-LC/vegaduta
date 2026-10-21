# Big files: split plan

> **2026-10-16:** the ratchet now has a hard cap of 700 lines and fails any file over 665 lines (within 5% of the cap) unless baselined; the baseline was regenerated at that threshold and may only shrink. The tables below were measured against the earlier 1,000-line rule.

> **2026-10-21:** `scripts/ci/file-size-baseline.txt` holds no entries any more: every file the ratchet checks is at or under 665 lines (for example `Agent.java` 604, `WorkflowRunWorkflowImpl.java` 454, `AgentChatService.java` 661, `EvalRunService.java` 522). The *partial* and *remaining* rows below are therefore done; the counts in the tables are the 2026-10-15 measurements.

Generated 2026-10-15 from `main` (977b4681f); line counts re-measured on branch `next/phase` after the refactor wave (`wc -l` on the working tree, 2026-10-15). Rule of the repo when measured: a source file over 1,000 lines failed the file-size ratchet unless baselined (now: over 665, see the note above) (`scripts/ci/file-size-baseline.txt`); baselines may only shrink.
This document lists every tracked source file over 1,000 lines (generated files excluded) with the **design pattern to split it by** for the largest ones. Split by responsibility, not by line count: each new class should have one reason to change and be testable alone. Behaviour must not change: characterisation tests first, then extract.

## Rules for every split

1. Add or keep tests that pin current behaviour before moving code; run them after every extraction (a split is a pure refactor).
2. Prefer constructor injection and small collaborators; a class with more than 12 constructor parameters (ArchUnit baseline) must shrink, never grow.
3. Move, do not copy: no duplicated logic between the in-process and Temporal workflow engines.
4. Frontend: container/presentational split, one reducer or state machine per complex screen, hooks per concern, registries for per-type variants (nodes, channels, templates), data separated from logic.
5. After each split lower the baseline in `scripts/ci/file-size-baseline.txt` and run the gates (`make gates`).

**Status legend.** *done*: the pattern was applied and the file is at or under 1,000 lines (or no longer exists as one file). *partial*: the pattern was applied and the file shrank, but it is still over 1,000 lines (it stays in `scripts/ci/file-size-baseline.txt` at its new, lower size). *remaining*: not touched. "Was" is the count in the original plan; "Now" is measured today.

## Priority 1: the largest and most central (design pattern for each)

| # | File | Was | Now | Status | Pattern | Target structure |
|---|---|---:|---:|---|---|---|
| 1 | `web/app/components/agents/AgentWorkbench.tsx` | 4894 | 86 | done | Feature slices + single reducer + compound sections | `AgentWorkbench/` with `state/agentDraftReducer.ts` (one reducer + context replaces ~dozens of `useState`; explicit states clean/dirty/saving/error), `sections/{Identity,ModelAndGeneration,Tools,Knowledge,Guardrails,Autonomy,Capabilities,Versions,Share,Lab}Section.tsx` as compound `<WorkbenchSection>` children, hooks `useAgentValidation`, `useAgentSave`. The shell only lays out and routes. |
| 2 | `web/app/lib/edge/tier1.ts` | 4214 | 94 | done | Data/registry split (no logic in data) | `tier1/models/<family>.ts` pure data, `tier1/registry.ts` builds the catalogue, lazy `import()` per family, a schema test that validates every entry. Logic (selection, fit) moves to `tier1/select.ts`. |
| 3 | `web/app/components/workflow/WorkflowCanvas.tsx` | 3351 | 913 | done | Command pattern (undo/redo) + hook-per-concern + node-renderer registry | `canvas/useCanvasState` (reducer; every edit is a Command with `do/undo`), `useSelection`, `useConnections`, `useClipboard`, `useCanvasShortcuts`, `useViewport`, renderers resolved through the existing `nodeDefs` registry, `CanvasToolbar`, `MiniMap`, `EdgeRouting` util. |
| 4 | `web/app/components/agents/AgentPlayground.tsx` | 3281 | 390 | done | State machine for the chat stream + hooks + presentational components | `useChatStream` (idle -> streaming -> awaiting-approval -> error -> resumable), `useAttachments`, `useVoiceSession`, `useTranscript`; components `Composer`, `MessageList`, `ToolCallCard`, `SessionSidebar`, `LabOverlayHost`. |
| 5 | `web/app/components/workflow/NodeConfigDrawer.tsx` | 2763 | 234 | done | Registry of per-node forms (schema-driven) | `nodeConfig/forms/<NodeType>Form.tsx` registered in a `NODE_FORMS` map; shared field components; drive simple fields from descriptors in `nodeDefs.ts` so most nodes need no hand-written form. |
| 6 | `web/app/components/agents/CallPanel.tsx` | 2287 | 572 | done | Phase state machine + presentational panes | `call/useCallMachine` (setup -> dialing -> in-call -> wrap-up -> summary), panes per phase, transcript and settings as separate components. |
| 7 | `web/app/build/studio/StudioSurface.tsx` | 2264 | 632 | done | Pane components + project-session reducer | The 1,306-line `Workspace` component becomes `ChatPane`, `FilesPane`, `PreviewPane`, `DeployPane` under a `useProjectSession` reducer; page.tsx stays a thin composition root. |
| 8 | `web/app/components/agentTemplates.ts` | 2206 | 106 | done | Data-driven catalogue (JSON/TS data modules + lazy load) | One file per category under `templates/agents/`, a `registry.ts`, a schema-validation test, and lazy import on the gallery route. |
| 9 | `web/app/components/workflowTemplates.ts` | 1579 | 124 | done | Same as agentTemplates | Per-category data files + registry + schema test. |
| 10 | `web/app/lib/api/types.ts` | 1928 | 27 | done | Generate or split by domain | Prefer the generated OpenAPI types (`sdk/typescript/src/generated/types.ts`); keep only view-model types by domain in `types/{agents,workflows,...}.ts`. |
| 11 | `web/app/components/ChannelsPanel.tsx` | 1872 | 844 | done | Registry of channel adapters (one card/inspector per ChannelType) | `channels/adapters/<channel>.tsx` registered by `ChannelType`; the panel iterates the registry (adding a channel = one file). |
| 12 | `web/app/components/TourProvider.tsx` | 1813 | 877 | done | Engine vs data | Steps and audiences as data (`tour/steps/*.ts`), a small engine (`useTour`), overlays and illustrations lazy-loaded. Split `tour.test.tsx` (2,977 lines) per feature area. |
| 13 | `core/agents/src/main/java/com/agenticai/core/agents/core/AgentService.java` | 2374 | 992 | done | Facade over focused services + Settings Patch (Command) per facet | Keep `AgentService` as a thin facade; extract `AgentModelResolver` (resolveDefaultModelFor/EffectiveModel/providerServesModel), `AgentSettingsService` (an `AgentSettingsPatch` applied by one `FacetApplier` per facet instead of ~40 `setX/isX` methods: generation params, tuning, caching, capabilities, budget, autonomy, reasoning depth), `AgentAccessService` (editable/grants), `AgentCapabilityService`, `AgentVersioningService` (saveVersioned). Constructor drops from 13 to 3-5 per class. |
| 14 | `core/agents/src/main/java/com/agenticai/core/agents/core/Agent.java` | 1318 | 1054 | partial | Value objects (`@Embeddable`) instead of 107 accessors | Group into `GenerationParams`, `FallbackPolicy`, `CachingPolicy`, `CapabilityFlags`, `BudgetPolicy`, `ModalityModels`; the entity exposes the embedded objects (immutable `with...` copies), no schema change needed (same columns). |
| 15 | `core/workflow/src/main/java/com/agenticai/core/workflow/WorkflowNodeExecutor.java` | 2271 | 527 | done | Strategy registry keyed by node type (one handler per node) | `NodeHandler` SPI + `Map<WorkflowNodeType, NodeHandler>` injected as `List<NodeHandler>`; classes `AgentNodeHandler`, `KnowledgeNodeHandler`, `ConditionNodeHandler`, `SupervisorNodeHandler`, `ToolNodeHandler`, `CodeNodeHandler`, `HumanInputNodeHandler`, `VoiceMenuNodeHandler`, `DeviceControlNodeHandler`, `BulkEmailNodeHandler`, ...; shared `NodeConfigResolver`, `TemplateRenderer`, `OutputMapper`. Removes the 25-parameter constructor; fold the existing `WorkflowAiNodeHandler` and `WorkflowDataOps` into the same SPI. |
| 16 | `core/workflow/src/main/java/com/agenticai/core/workflow/WorkflowExecutionEngine.java` | 1895 | 1678 | partial | Functional core / imperative shell + State pattern for run states | Pure `RunTraversal`/`WorkflowTraversalRules` (next node, loop and fan-out resolution) shared with the Temporal engine, `RetryPolicy` + `BackoffCalculator`, `ParallelBranchRunner`, `FanOutRunner`, `RunFinalizer` with `Cancelled/Paused/Waiting/Completed` state objects; the engine only orchestrates. |
| 17 | `core/workflow/src/main/java/com/agenticai/core/workflow/temporal/WorkflowRunWorkflowImpl.java` | 1426 | 1340 | partial | Share pure rules with the in-process engine | Move deterministic helpers (nextNodeId, loopMaxIterations, fanOut*, resolveLoopItems) into `WorkflowTraversalRules`; the Temporal class keeps only workflow/activity wiring (must stay deterministic). |
| 18 | `core/workflow/src/main/java/com/agenticai/core/workflow/WorkflowService.java` | 1447 | 378 | done | Facade + capability services | `WorkflowCrudService`, `WorkflowRunService` (run/cancel/pause/list), `WorkflowTriggerService` (webhook, poll, schedule, channel), `WorkflowAccessGuard` (requireViewer/Editor), `WorkflowVersionService`; facade keeps the controller API. |
| 19 | `core/workflow/src/main/java/com/agenticai/core/workflow/WorkflowDataOps.java` | 1702 | 657 | done | Strategy per operation family | `DataSortOps`, `DataAggregateOps`, `DataFilterOps` (operator enum -> predicate), `DataJoinOps`, `DataPathOps` (setPath/tokenize), `NumberCanon`; one test class per family. |
| 20 | `core/workflow/src/main/java/com/agenticai/core/workflow/WorkflowAiNodeHandler.java` | 1387 | 563 | done | Template Method for ask-and-validate + one class per AI node | `AiClassifierHandler`, `AiExtractorHandler`, `AiReferenceResolver`, `AiOutputValidator` on a shared `AskAndValidate` template (retry, repair, coercion). |
| 21 | `core/agents-tools/src/main/java/com/agenticai/core/agents/tools/DynamicMcpConnectionManager.java` | 1752 | 931 | done | Connection state machine + factory + guard chain | `McpConnectionLifecycle` (state machine: connecting/live/errored/blocked/disconnected), `McpTransportFactory` per transport (stdio/SSE/streamable-HTTP), `McpConnectionGuards` (chain: review-blocked, guard-blocked, resolved-blocked), `McpToolCache`, `McpStdioSlots` (semaphore), `McpHealthMonitor` (scheduled), `McpDiagnostics`. |
| 22 | `core/agents-browser/src/main/java/com/agenticai/core/agents/tools/web/HttpRequestExecutor.java` | 1574 | 666 | done | Pipeline of request stages (Chain of Responsibility) | `ValidateStage -> ResolveSecretsStage -> HostPolicyStage (SSRF, egress, numeric-host refusal) -> SendStage -> RedirectStage (per-hop re-check, strip cross-origin creds) -> ReadAndRedactStage`; `X402PaymentAttempt` isolated; every stage unit-testable. |
| 23 | `core/agents-turn/src/main/java/com/agenticai/core/agents/turn/AgentChatService.java` | 1454 | 1230 | partial | Parameter Object + constructor-injected facades + prompt composer | `ChatTurnRequest` replaces 8 `chat(...)` overloads; the 11 setter injections become constructor-injected facades (`ChatPolicyFacade`, `ChatObservabilityFacade`); `ExperimentArmConfig` value object; `PromptDirectiveComposer` (Composite of prompt sections: tool-trust, agentic-completion, untrusted-data). |
| 24 | `core/agents-tools/src/main/java/com/agenticai/core/agents/tools/AgentToolProvider.java` | 1256 | 472 | done | Composite of `ToolSource`s | SPI `ToolSource` (built-in, MCP, A2A, browser, sandbox, data tables, scheduled, coding, research, skills) each contributing candidates; `ToolResolver` merges, filters (allow-list, collisions, budget cap). Setter wiring becomes a constructor-injected `List<ToolSource>`. |
| 25 | `core/chat/src/main/java/com/agenticai/core/chat/LlmMetricsAdvisor.java` | 1384 | 567 | done | Observer: one observation, many sinks | Advisor builds an `LlmCallObservation`; independent sinks `GenAiSpanRecorder`, `LlmCallLogSink`, `MicrometerLlmMetrics`, `CacheTokenMetrics`, `StreamFinalizer` subscribe; failures in a sink never affect the call. |

**Totals on 2026-10-15.** Priority 1: 21 done (`AgentService.java` counts as done at 992 lines), 4 partial (`Agent.java`, `WorkflowExecutionEngine.java`, `WorkflowRunWorkflowImpl.java`, `AgentChatService.java`), 0 remaining. Priority 2: 35 done, 1 partial, 17 remaining (all remaining ones are Java or Python; no frontend file in the list is still over 1,000 lines).

### What exists now (Priority 1)

| # | Outcome |
|---|---|
| 1 | `workbench/state/agentDraftReducer.ts`, `workbench/*Section.tsx` (Identity, ModelAndGeneration, Tools, Knowledge, Autonomy, Capabilities, Versions, Share, Lab, ...), `useAgentDraft`, `useAgentSave`, `useAgentValidation`; shell is a layout. |
| 2 | `tier1/models/<family>.ts` data, `tier1/registry.ts`, `tier1/select.ts` plus engine/session modules; `registry.test.ts` validates entries. |
| 3 | `workflow/canvas/` hooks (`useCanvasHistory`, `useCanvasEditing`, `useClipboard`, `useConnections`, `useCanvasShortcuts`, ...) and `commands.ts`; `WorkflowCanvas.tsx` is the 913-line composition root. |
| 4 | `agents/playground/` (`Composer`, `MessageList`, `AssistantTurn`, `LabOverlayHost`, ...) plus stream hooks. |
| 5 | `workflow/nodeConfig/` with `NODE_FORMS` (`registry.tsx`), `forms/` (20 files) and `descriptors.ts` (`SIMPLE_FORMS`); see [Extending](../extending.md#5-add-a-workflow-node-type). |
| 6 | `agents/call/` (`callMachine.ts`, `useCallMachine`, `CallSetupPane`, `CallLivePane`, `CallSummaryPane`, ...). |
| 7 | `ChatPane`, `FilesPane`, `PreviewPane`, `DeployPane` under `build/studio/`. |
| 8 | Data under `components/templates-data/agents/` (one file per category), `registry.ts`, `registry.test.ts`; `agentTemplates.ts` keeps the import path. |
| 9 | Same, under `components/templates-data/workflows/`. |
| 10 | `lib/api/types/<domain>.ts` (20 domain files); `types.ts` re-exports. |
| 11 | `channels/adapters/<channel>.tsx` registered in `adapters/index.ts` (`CHANNEL_ADAPTERS`, `adapterFor`); see [Extending](../extending.md#6-add-a-channel). |
| 12 | `tour/steps/*` data, `tour/overlay/`, `tour/illustrations/`; `tour.test.tsx` split into per-feature test files under `tour/`. |
| 13 | `AgentModelResolver`, `AgentSettingsService` with `AgentSettingsPatch`/`AgentSettingsFacets`, `AgentAccessService`, `AgentCapabilityService`, `AgentVersioningService` exist; `AgentService` is now just under 1,000 lines (992), still larger than the thin facade the plan describes. |
| 14 | Value objects `GenerationParams`, `FallbackPolicy`, `CachingPolicy`, `CapabilityFlags`, `BudgetPolicy`, `ModalityModels` exist; accessors on the entity remain, so the file is still over 1000 lines. |
| 15 | `NodeHandler` SPI + one handler per node type (`AgentNodeHandler`, `KnowledgeNodeHandler`, `ConditionNodeHandler`, ... and adapters for the AI, data, HTTP/media, CRM, bulk-email and data-table families), `NodeConfigResolver`, `NodeOutputMapper`; the executor takes `List<NodeHandler>`. |
| 16 | `WorkflowTraversalRules`, `RetryPolicy`, `BackoffCalculator`, `ParallelBranchRunner`, `FanOutRunner`, `RunFinalizer` extracted; the engine is still over 1,000 lines. |
| 17 | Deterministic helpers moved to `WorkflowTraversalRules` (shared with the in-process engine); the Temporal class is still over 1000 lines. |
| 18 | `WorkflowCrudService`, `WorkflowRunService`, `WorkflowTriggerService`, `WorkflowAccessGuard`; the facade keeps the controller API. |
| 19 | `DataSortOps`, `DataAggregateOps`, `DataFilterOps`, `DataJoinOps`, `DataPathOps`, `NumberCanon`. |
| 20 | `AiClassifierHandler`, `AiExtractorHandler`, `AiReferenceResolver`, `AiOutputValidator` on `AskAndValidate`. |
| 21 | `McpConnectionLifecycle`, `McpTransportFactory`, `McpConnectionGuards`, `McpToolCache`, `McpStdioSlots`, `McpHealthMonitor`, `McpDiagnostics`. |
| 22 | `HttpRequestValidation`, `HttpSecretResolution`, `HttpHostPolicy`, `HttpSendStage`, `HttpRedirectStage`, `HttpResponseReader`, `X402PaymentAttempt`; see [Extending](../extending.md#11-change-the-http-request-pipeline). |
| 23 | `ChatTurnRequest`, `ChatPolicyFacade`, `ChatObservabilityFacade`, `ExperimentArmConfig`, `PromptDirectiveComposer` exist; the service is smaller but still over 1000 lines. |
| 24 | `ToolSource` SPI, `ToolSourceConfiguration` (32 sources), `ToolResolver`; see [Extending](../extending.md#10-add-a-tool-source). |
| 25 | `LlmCallObservation`, `LlmCallObserver`, `LlmCallSink` with `GenAiSpanRecorder`, `LlmCallLogSink`, `MicrometerLlmMetrics`, `CacheTokenMetrics`, plus `StreamFinalizer`; see [Extending](../extending.md#12-add-an-llm-call-sink). |

## Priority 2: every other file over 1,000 lines

Split by feature slice: extract hooks and sub-components (frontend) or collaborators by responsibility (backend); test files are split along the same seams as the code they cover.

| File | Was | Now | Status |
|---|---:|---:|---|
| `web/app/components/tour/tour.test.tsx` | 2977 | - | done (file removed or moved) |
| `web/app/components/AppShell.tsx` | 1852 | 424 | done |
| `web/app/lib/edge/deviceTools.ts` | 1599 | 679 | done |
| `web/app/lib/localcode/index/indexer.ts` | 1573 | 601 | done |
| `core/agents-studio/src/test/java/com/agenticai/core/agents/lab/AgentLabRunServiceTestSupport.java` | 1556 | 1556 | remaining |
| `core/agents-tools/src/test/java/com/agenticai/core/agents/tools/DynamicMcpConnectionManagerTest.java` | 1553 | 242 | done |
| `web/app/components/KnowledgePanel.tsx` | 1505 | 729 | done |
| `web/app/components/ProvidersPanel.tsx` | 1484 | 703 | done |
| `web/app/components/edge/EdgeConsole.tsx` | 1476 | 755 | done |
| `web/app/components/tour/TourOverlay.tsx` | 1459 | 778 | done |
| `web/app/lib/edge/downloadManager.ts` | 1441 | 993 | done |
| `web/app/components/McpPanel.tsx` | 1431 | 784 | done |
| `web/app/components/demo-landing/DemoLanding.tsx` | 1420 | 414 | done |
| `web/app/components/tour/TourIllustrations.tsx` | 1382 | 200 | done |
| `web/app/components/ChatPanel.tsx` | 1374 | 785 | done |
| `web/app/code-intel/page.tsx` | 1354 | 625 | done |
| `web/app/lib/edge/browserAi.ts` | 1349 | 577 | done |
| `web/app/help/workflow-recipes/recipes-core-patterns.tsx` | 1337 | 12 | done |
| `web/app/components/OpenApiToolSourcesPanel.tsx` | 1284 | 585 | done |
| `web/app/components/agents/IntelligenceSourceControl.tsx` | 1261 | 744 | done |
| `web/app/components/CustomCodeToolsPanel.tsx` | 1241 | 705 | done |
| `web/app/lib/edge/tierRouter.ts` | 1232 | 635 | done |
| `web/app/lib/edge/localTools.ts` | 1227 | 349 | done |
| `core/quality/src/main/java/com/agenticai/core/quality/dataset/EvalRunService.java` | 1194 | 1194 | remaining |
| `core/brief/src/main/java/com/agenticai/core/brief/BriefApplyService.java` | 1187 | 1187 | remaining |
| `web/app/lib/edge/deviceTools.test.ts` | 1174 | 962 | done |
| `core/agents-browser/src/test/java/com/agenticai/core/agents/tools/web/HttpRequestExecutorSafeModeTestSupport.java` | 1173 | 1173 | remaining |
| `core/agents-studio/src/main/java/com/agenticai/core/agents/lab/AgentLabRunService.java` | 1158 | 1158 | remaining |
| `web/app/lib/build/blueprint.ts` | 1151 | 74 | done |
| `web/app/agents/quality/EvalDatasetsSection.tsx` | 1145 | 715 | done |
| `web/app/components/ContactsPanel.tsx` | 1141 | 621 | done |
| `core/agents-browser/src/main/java/com/agenticai/core/agents/tools/browseruse/BrowserUseSessionManager.java` | 1140 | 1140 | remaining |
| `web/app/components/workflow/RunStepInspector.tsx` | 1129 | 326 | done |
| `web/app/lib/edge/capabilities.ts` | 1123 | 806 | done |
| `web/app/admin/page.tsx` | 1122 | 327 | done |
| `web/app/build/CreateSurface.tsx` | 1113 | 537 | done |
| `core/agents-studio/src/main/java/com/agenticai/core/agents/coding/CodingTaskService.java` | 1109 | 1096 | partial |
| `web/app/platform-admin/lib/api.ts` | 1076 | 13 | done |
| `core/voice/src/main/java/com/agenticai/core/voice/VoiceCallController.java` | 1070 | 1070 | remaining |
| `core/brief/src/main/java/com/agenticai/core/brief/BriefPlannerService.java` | 1069 | 1069 | remaining |
| `web/app/components/workflow/runModel.ts` | 1064 | 817 | done |
| `core/agents-budget/src/main/java/com/agenticai/core/agents/budget/PerUserBudgetService.java` | 1045 | 1045 | remaining |
| `core/agents/src/test/java/com/agenticai/core/agents/tools/ToolRelevanceSelectorTestSupport.java` | 1041 | 1041 | remaining |
| `services/sidecar/app/render.py` | 1039 | 1039 | remaining |
| `core/workflow/src/main/java/com/agenticai/core/workflow/WorkflowExpressionResolver.java` | 1035 | 1035 | remaining |
| `core/tenancy/src/main/java/com/agenticai/core/tenancy/TenantDataErasureService.java` | 1035 | 1035 | remaining |
| `web/e2e/axon-redesign.spec.ts` | 1022 | 535 | done |
| `core/agents-interop/src/test/java/com/agenticai/core/agents/openai/OpenAiCompatControllerTestSupport.java` | 1022 | 1022 | remaining |
| `web/app/components/agents/CameraPanel.tsx` | 1020 | 915 | done |
| `core/agents-turn/src/main/java/com/agenticai/core/agents/memory/SessionMemoryService.java` | 1020 | 1020 | remaining |
| `core/knowledge/src/main/java/com/agenticai/core/knowledge/SidecarClient.java` | 1015 | 1015 | remaining |
| `core/agents-browser/src/main/java/com/agenticai/core/agents/tools/browseruse/BrowserTakeoverService.java` | 1005 | 1005 | remaining |
| `web/app/lib/edge/browserAi.test.ts` | 1001 | 892 | done |

## Over 1,000 lines now but not in the original list

Measured with the same file selection as `scripts/ci/check-file-size.sh`. Files here either were not in the original plan or grew back over the threshold:

| File | Lines | Note |
|---|---:|---|
| `web/app/platform-admin/llm-providers/page.tsx` | 1065 | was 995 in the original plan's tree; baselined |
| `web/app/components/nav/navSections.ts` | 1010 | not in the baseline: the ratchet fails on it until it is split or baselined |
| `web/app/components/dashboard/dashboard.css` | 1025 | CSS, baselined |
| `services/mcp-stdio-runner/server.js` | 1164 | JavaScript; the ratchet does not check `*.js` |

On 2026-10-15 `scripts/ci/file-size-baseline.txt` still listed files that were under the threshold. Since then the baseline has been emptied (see the note at the top); the three web files in this table are now 281, 26 and 292 lines. `services/mcp-stdio-runner/server.js` (1,173 lines) is still outside the ratchet, which checks `*.java`, `*.ts`, `*.tsx`, `*.kt`, `*.swift`, `*.py` and `*.css` but not `*.js`.
