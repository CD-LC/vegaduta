# Settings catalog

The settings catalog is the list of behaviour settings a tenant admin or an agent editor may change in the console without
touching the deployment: how many tool calls a turn may make, how long approvals wait, how strict the safety checks are.
Each entry is declared once in code, carries its own type, unit, bounds, risk and scopes, and defaults from the property
object the runtime already reads, so there is one source of truth. Module: `core/settings` (package
`com.agenticai.core.settings`; it depends only on `security` and `audit`, so any module can read a setting).

## Resolution

Lowest first: the code default, the platform value (the deployment's `application*.yml` / environment), the **tenant**
override, the **agent** override. The higher layer wins, with two rules on top:

* **Pinned (locked).** `app.settings.locked.<key>=true` makes the key the platform value for everybody. The API returns
  `locked: true` with a reason, a write is a 409 `SETTING_LOCKED`, a stored override is ignored. See
  [configuration.md](configuration.md#settings-catalog-appsettings).
* **Security keys can only be stricter.** For an entry with risk `security` a tenant or agent value is accepted only if it is
  at least as strict as the value it inherits (stricter direction is declared per entry: higher number, lower number, or
  later in the ordered `enumValues`), needs a tenant admin and a typed reason, and at read time the strictest layer wins,
  so a value stored earlier stops applying when the operator tightens the platform value. A rejected attempt is audited as
  `SETTING_CHANGE_REJECTED`.

`source` in the effective values is `default` (nothing configured, the code default), `platform` (the deployment sets the
property), `tenant` or `agent`. An overridden value also carries `inherited`: what a reset would give.

## API

| Call | Who | Notes |
|---|---|---|
| `GET /api/settings/catalog` | any member | every entry: `key, group, labelKey, descriptionKey, helpKey, type, unit, min, max, step, enumValues, risk, scopes, default, minRole, restartRequired, docsAnchor` plus `strictness` and `platformProperty` |
| `GET /api/settings/effective?agentId=` | any member (VIEWER on the agent) | `{values: {key: {value, source, locked, lockedReason?, inherited?}}}` |
| `PUT /api/settings/tenant/{key}` body `{value, reason?}` | tenant admin | `reason` required for risk `high` and `security` |
| `DELETE /api/settings/tenant/{key}` | tenant admin | back to the inherited value; `?reason=` is **required** for risk `high` and `security` (400 `SETTING_INVALID` without it), optional otherwise |
| `PUT /api/agents/{agentId}/settings/{key}` body `{value, reason?}` | EDITOR on the agent (plus tenant admin when `minRole` is `TENANT_ADMIN`) | |
| `DELETE /api/agents/{agentId}/settings/{key}` | as above | |
| `GET /api/settings/{key}/history?scope=tenant\|agent&agentId=&limit=` | tenant admin / EDITOR on the agent | newest first: `version, action, value, previousValue, actor, at, reason` |
| `GET /api/settings/history?scope=all\|tenant\|agent&agentId=&since=&limit=&offset=` | tenant admin; with `scope=agent&agentId=` EDITOR on that agent | the history of **every** key in one call, newest first. `scope=all` (default) is the workspace's own changes and every agent's, `tenant` the workspace's, `agent` every agent's (or one agent's with `agentId`). `since` (ISO-8601 instant, inclusive) keeps newer rows. Body `{entries: [{key, scope, agentId?, version, action, value, previousValue, reason, actor, at}], total, offset, limit, hasMore}`; `limit` defaults to `app.settings.bulk-history-default-limit` and is capped at `bulk-history-max-limit` |
| `GET /api/agents/{agentId}/settings/history?since=&limit=&offset=` | EDITOR on the agent | the same page shape for one agent; an agent of another workspace is a 404 |

Writes answer `{key, effective, version, unchanged}`. Errors: `400 {code: "SETTING_INVALID", key, message, min?, max?, enumValues?}`
(type, bounds, enum, missing reason on a write **or a reset**, security value weaker than inherited, scope not allowed),
`409 {code: "SETTING_LOCKED"}` (the operator pinned the key) or `409 {code: "SETTING_CONFLICT"}` (another writer changed the same key at the
same moment and this one lost; nothing was stored, reload and try again),
`404 {code: "SETTING_UNKNOWN"}` for an unknown key, `403` for a missing role, `404` for an agent that is not in the caller's tenant.
Value types: `int`, `long`, `decimal`, `percent` are JSON numbers; `duration` is a whole number of the entry's `unit` (`s` or `ms`);
`bool`; `enum`, `string`, `text` are strings. `minRole` is the least role that may change the entry at agent scope; at tenant
scope it is always a tenant admin.

Every change writes a row to `setting_override_history` (immutable: a trigger refuses UPDATE and DELETE; migration V668), an
audit entry `SETTING_UPDATED` / `SETTING_RESET` with actor, scope, old value, new value and reason, and evicts the cache of the
replica that took it; other replicas converge within `app.settings.cache-ttl`. Writers of one key are serialised by a
per-key database lock, so versions are consecutive; if the store still refuses a duplicate version the caller gets the 409
above, never a 500.

When an agent is deleted its overrides go with it (migration V669, a trigger on `agent`): the current rows are removed and each
one gets a `RESET` row in the history (reason `agent deleted`, actor `system:agent-deleted`), so the record of what the agent
ran with stays complete.

The seven runtime limits (`agent.limits.*`) are stored where they always were (`tenant_runtime_limits` and the `agent` table,
V671) and `/api/agents/{id}/runtime-limits` and `/api/agent-runtime/limits` keep working: a write through either is visible in
the other and to the runtime. Only their history rows live in the new table.

## Console: Admin > Runtime settings

`/admin/runtime-settings` (web app `web/app/admin/runtime-settings`) is the workspace screen over this API. It renders whatever
the catalog says, grouped by the entry's `group`, and adds: search, a "changed in this workspace only" filter, a separated
security area (groups with a `security` entry; their values can only be made stricter), reset of a whole group, export and
import of the workspace overrides as JSON (the import is validated against the catalog and shows a diff before it writes;
high-risk and security changes still ask for their typed reason), and a change-history tab that merges the history of every
workspace setting, one request per page, workspace and agent changes together with a Level column and a filter for it. A reset of
a high-risk or security setting asks for the same typed reason as a save, in the same confirmation (a group reset asks once). On a
security setting the field and the confirmation say which direction is stricter (from the catalog's `strictness`). A member without
the tenant-admin role sees the values read-only. A setting can be linked to with
`/admin/runtime-settings#<key>` (for example `#approvals.expiry.ttl`) and a group with `#group-<group>`; use these links in
docs and error messages.

The export file is `{"format": "vegaduta.settings-overrides", "version": 1, "scope": "tenant", "exportedAt": ..., "overrides": {"<key>": <value>}}`
with durations as whole numbers in the entry's own `unit`.

## Finding a control: "Where do I control...?"

`/help/controls` (web app `web/app/help/controls`) answers "where do I change X?". It lists the places in the console that
control agent behaviour (Runtime settings, Instructions, Approvals, Guardrails, Turn inspector and an agent's Behavior and
limits tab) and, built from `GET /api/settings/catalog`, every runtime setting with a link to change it for the workspace
(`/admin/runtime-settings#<key>`) and, where the entry has the `agent` scope, on an agent
(`/agents?section=behavior&focus=<key>`). A new catalog entry shows up there without any edit to the page.

Each section of that page has an `id` equal to the entry's `docsAnchor`, so `/help/controls#agent-runtime-limits` and
`/help/controls#behaviour-settings` open the matching part of the list; give a new entry one of those anchors (or a new
one, with `controls.anchors.<anchor>.title` and `.intro` in `web/messages/en.json`) so it is grouped. The control pages
also carry a breadcrumb and links to each other (`web/app/components/controls`).

### Deep links from an agent

An agent's Behavior and limits tab links to the pages that hold the rest of what shapes that one agent, each opened
already scoped to it: `/admin/instructions?agent=<agentId>` (the prompt registry at agent scope; an agent that does not
exist or is not visible falls back to the workspace scope with a notice, and `?agent=` follows the scope picker so the
address can be shared), `/admin/approvals?agent=<agentId>` (the approvals queue filtered to the agent) and
`/agents/turns?agentId=<agentId>` (the turn timeline with the agent filled in; the journal lists turns per chat session,
so a session or turn id is still needed to list turns). A link is only offered when the person holds the target page's
scope. `/agents?section=behavior&focus=<key>` without an agent lands on the agents list, and the choice of agent keeps
`section` and `focus`. `web/app/components/controls/navigation.linkIntegrity.test.ts` asserts that every internal href in
the navigation model resolves to an existing page and that the query parameters it sends are read by that page.

## Reading a setting in code

```java
@Autowired SettingsResolver settings;          // bean of type DefaultSettingsResolver

int max = settings.getInt("chat.finish.maxContinuations", SettingContext.forAgent(agentId));
Duration ttl = settings.getDuration("approvals.expiry.ttl", SettingContext.tenant());
boolean on = settings.getBool("chat.cache.eligibleWithTools", agentId);
GuardrailFailMode mode = settings.getEnum("guardrails.failMode", SettingContext.tenant(), GuardrailFailMode.class);
SettingValue v = settings.get("routing.simple.maxChars", SettingContext.forAgent(agentId)); // v.source() says where it came from
```

`SettingsResolver` never throws because of the store (a store error returns the platform value and logs a warning); an unknown
key is a programming error. The tenant comes from the calling thread's `TenantContext`; a context that names another tenant gets
the platform value only. For tests: `SettingsResolver.platformOnly(registry)` or `InMemorySettingsStore` with
`DefaultSettingsResolver`. Run hot paths with a value read once per turn, not per token.

On the chat turn path use `BehaviourSettings` (agents-turn, package `agents.tunables`) instead: `behaviour.integer(key, context.scope(),
agentId, platformSupplier)` reads inside the turn's tenant scope, so the override applies on the streamed, buffered, delegated and
continuation paths whichever thread asks, and answers the platform value when there is no catalog or the read fails. Resolve the
settings of a pass once (see `TurnFinishLimits`), not per token.

## Adding a key

1. In the module that owns the behaviour, declare it in a `SettingsContributor` bean:
   `SettingDefinition.builder("area.name", "group", SettingType.INT).unit(..).range(min, max).risk(..).scopes(..).platform(props::getX).property("app.x.y").build()`.
   The platform supplier must read the SAME property object the runtime reads. Security entries also set a `Strictness` and are
   tenant-admin only. No key may be a prefix of another.
2. Add `settings.<key>.label`, `.description` and `.help` (and `settings.groups.<group>.label` for a new group) to
   `web/messages/en.json` and every other locale file. `SettingsCatalogContractTest` fails otherwise.
3. Make the use site read the resolver instead of the property object (table below).
4. Add the key to the table below.

## Keys, properties and use sites

Types: i = int, d = duration (unit shown), b = bool, e = enum, s = string, x = decimal. Scopes: T tenant, A agent. Risk:
L low, M medium, H high, S security. **Use site** is the code that must consult `SettingsResolver` for the key to take effect;
`wired` means it already does (the runtime limits go through `AgentRuntimeSettingsResolver`).

| Key | Type, range | Risk | Scopes | Platform property | Use site |
|---|---|---|---|---|---|
| `agent.limits.maxToolSteps` | i 1-500 | M | T,A | `app.guardrails.max-tool-calls-per-turn` | wired (runtime resolver) |
| `agent.limits.turnTimeoutSeconds` | d s 10-3600 | M | T,A | `app.llm.http.buffered-turn-timeout` | wired |
| `agent.limits.toolTimeoutSeconds` | d s 1-1800 (0 = none) | L | T,A | `app.agent-runtime.defaults.tool-timeout-seconds` | wired |
| `agent.limits.toolOutputMaxChars` | i 500-200000 | M | T,A | `app.tools.output.max-chars` | wired |
| `agent.limits.retrievalTopK` | i 1-50 | L | T,A | `app.agent-runtime.defaults.retrieval-top-k` | wired |
| `agent.limits.retrievalMinScore` | x 0-1 | L | T,A | `app.agent-runtime.defaults.retrieval-min-score` | wired |
| `agent.limits.continuationPasses` | i 0-16 | L | T,A | `app.chat.continuation.max-passes` | wired |
| `approvals.continuation.enabled` | b | M | T,A | `app.approvals.continuation.enabled` | agents-turn `ApprovalContinuationLauncher` (**wired**, per agent: the agent comes from the request's replay envelope, `ApprovalContinuationFactory#requestedAgentId`); agents-jobs `AgentJobApprovalSweep`, `AgentJobApprovalResume` (**wired** in agents-jobs, per agent) |
| `approvals.continuation.jobsContinueAfterDenial` | b | M | T,A | `app.approvals.continuation.jobs-continue-after-denial` | agents-jobs `AgentJobApprovalSweep` (**wired**, per agent) |
| `approvals.expiry.ttl` | d s 0-2592000 (0 = never) | H | T | `app.approvals.expiry.ttl` | approval `ApprovalExpiryJob` (**wired**: the sweep reads the TTL per tenant; with the platform TTL off it still visits tenants, since any may have opted in) |
| `chat.finish.enabled` | b | L | T,A | `app.chat.finish.enabled` | **wired**: agents-turn `TurnFinishLimits` (streamed `FastStreamTurn`, buffered `BufferedTurnCalls`) |
| `chat.finish.maxContinuations` | i 0-10 | L | T,A | `app.chat.finish.max-continuations` | **wired**: agents-turn `TurnFinishLimits` |
| `chat.finish.maxToolCallRetries` | i 0-5 | L | T,A | `app.chat.finish.max-tool-call-retries` | **wired**: agents-turn `TurnFinishLimits` |
| `chat.retry.maxAttempts` | i 1-6 | L | T | `app.llm.retry.max-attempts` | **wired**: chat `LlmRetryPolicy` (non-streaming calls through `SpringRetryLlmRetry`, on the calling thread's tenant; the streamed pre-first-token retry through `StreamRetry`, in the turn's tenant scope) |
| `chat.cache.eligibleWithTools` | b | M | T,A | `app.chat.cache.eligible-with-tools` | **wired**: agents-turn `ChatCachePolicy` (preflight read, dispatcher write) |
| `chat.cache.contextTurns` | i 1-10 | L | T,A | `app.chat.cache.context-turns` | **wired**: agents-turn `ChatCachePolicy` (turn key and the branch regenerate's evict key) |
| `chat.followUp.stickyToolsEnabled` | b | L | T,A | `app.chat.follow-up.sticky-tools-enabled` | **wired**: agents `FollowUpTools` via `ChatClientSelection` |
| `chat.followUp.stickyToolsWindow` | d s 60-604800 | L | T,A | `app.chat.follow-up.sticky-tools-window` | **wired**: agents `FollowUpTools` via `ChatClientSelection` |
| `chat.continuation.heuristicEnabled` | b | L | T,A | `app.chat.continuation.heuristic-enabled` | **wired**: agents-turn `TurnContinuation` (streamed `FastStreamContinuation`, buffered `BufferedTurnContinuation`) |
| `chat.toolLoop.resultClearing.enabled` | b | L | T,A | `app.chat.tools.result-clearing.enabled` | **wired**: the turn puts its value on the run's `ToolLoopTranscript` (`ChatTurnExecutor#newTranscript`), the process-wide tool loop applies it for that run |
| `chat.toolLoop.resultClearing.thresholdChars` | i 500-200000 | L | T,A | `app.chat.tools.result-clearing.threshold-chars` | **wired**: as above |
| `routing.simple.maxChars` | i 0-2000 | L | T,A | `app.routing.simple.max-chars` | **wired**: agents-turn `QueryComplexityClassifier` |
| `routing.simple.maxClauses` | i 1-10 | L | T,A | `app.routing.simple.max-clauses` | **wired**: agents-turn `QueryComplexityClassifier` |
| `memory.recall.agentTopK` | i 1-50 | L | T,A | `app.memory.limits.agent-recall-top-k` | **wired**: agents-turn `AgentMemoryService` |
| `memory.recall.tenantTopK` | i 1-50 | L | T,A | `app.memory.limits.tenant-recall-top-k` | **wired**: agents-turn `TenantMemoryService` for the chat turn's shared recall (per agent); other callers without an agent (agents-tools `AgentKnowledgeToolResolver`) read the workspace value |
| `memory.agent.maxMemories` | i 1-5000 | M | T,A | `app.memory.limits.agent-max-memories` | **wired**: agents-turn `AgentMemoryService` |
| `aux.useAgentModel` | b | M | T | `app.auxiliary-model.use-agent-model` | **wired**: agents-turn `AuxiliaryModelResolver` |
| `guardrails.failMode` | e open<closed | S | T,A | `app.guardrails.fail-mode` | guardrails `GuardedToolCallback`, `SemanticGuardrailService` (**wired** through `GuardrailSettings.failClosed(..)`); agents-turn `ChatGuardrailGate` (**wired**: the inbound moderation check, the inbound and the outbound content-guardrail checks ask `GuardrailSettings.failClosed(agentId)` for the turn's agent; test `ChatGuardrailGateFailModeTest`) |
| `guardrails.toolVerifier.failMode` | e open<inherit<closed | S | T,A | `app.guardrails.tool-verifier.fail-mode` | guardrails `ChatClientToolCallVerifierService` (**wired**; `inherit` follows the resolved `guardrails.failMode`) |
| `guardrails.toxicity.blockScore` | i 0-100, lower = stricter | S | T,A | `app.guardrails.toxicity.block-score` | guardrails `ToxicityGuardrail` (**wired**) |
| `guardrails.jailbreak.maxScannedChars` | i 1000-200000, higher = stricter | S | T,A | `app.guardrails.jailbreak.max-scanned-chars` | guardrails `JailbreakDetectorGuardrail` (**wired**, read per call) |
| `guardrails.judge.maxInputChars` | i 500-100000, higher = stricter | S | T,A | `app.guardrails.judge.max-input-chars` | guardrails `SemanticGuardrailService`, `ToxicityGuardrail`, `ChatClientToolCallVerifierService`, `PolicyPackSemanticClassifier` (**wired** through `GuardrailSettings.truncateForJudge`) |
| `guardrails.supervised.readOnlyCalls` | e auto-allow<gate | S | T,A | `app.guardrails.supervised.gate-read-only` | **wired**: agents-tools `AgentToolGovernance#supervisedAutoAllowsReadOnly` through `GuardrailSettings.supervisedGatesReadOnly(..)`, read once per tool of a SUPERVISED agent per turn. `gate` (platform default `true`; a workspace or agent can only choose this) asks a human before every call; `auto-allow` (only where the platform value is `false`, the `personal` profile) lets a call run without approval ONLY when its tool has an explicit read-only classification row, the agent is positively judged not to hold the lethal trifecta and nothing else asks for approval; tests `SupervisedReadOnlyE2ETest` (turn level), `GuardrailSettingsTest` |
| `quality.judge.model` | s | M | T | `app.quality.eval.judge-model` | **wired**: quality `JudgeChatClientFactory#resolveJudge` (the workspace's value replaces the deployment's explicit judge model; an eval run's own `judgeModel` and the workspace's AI Model Control choice still come first) |
| `quality.judge.callTimeoutSeconds` | d s 5-300 | L | T | `app.quality.eval.judge-call-timeout-seconds` | **wired**: quality `JudgeChatClientFactory#callTimeout` (every judge call and the preflight probe) |
| `quality.eval.defaultRepeats` | i 1-5 | M | T | `app.quality.eval.default-repeats` | **wired**: quality `EvalRunService#triggerRun` (a run that names no `repeats`; an explicit count still wins) |
| `quality.eval.defaultMode` | e full,quick | M | T | `app.quality.eval.default-mode` | **wired**: quality `EvalRunService#effectiveDefaultMode` (the mode of a run that names none and whose dataset pins none: `full` is the production pipeline in the eval sandbox, `quick` withholds the agent's tools; not a security setting, the sandbox rules (what may really run, who may auto-approve) stay with the operator) |
| `delegation.maxBranches` | i 1-32 | M | T,A | `app.agents.collaboration.max-branches` | **wired**: agents-interop `DelegateToTeamToolCallback`, `RunSubtasksToolCallback` (the cap and the `maxItems` of the offered schema) and `DefaultCollaboratorToolFactory` (the swarm verifier's repair-round cap), through `ResolvedCollaborationLimits`, for the calling agent (`CollaboratorToolFactory#delegateToTeam` takes `callerAgentId`) |
| `delegation.branchTimeoutSeconds` | d s 10-3600 | L | T,A | `app.agents.collaboration.branch-timeout-seconds` | **wired**: agents-interop `DelegateToTeamToolCallback` (including the verifier's repair round), `RunSubtasksToolCallback`, through `ResolvedCollaborationLimits` |
| `knowledge.search.candidatePoolMultiplier` | i 1-20 | L | T | `app.knowledge.search.candidate-pool-multiplier` | wired: knowledge `KnowledgeSearchService` (read per search for the calling workspace) |
| `knowledge.search.candidatePoolMax` | i 5-500 | M | T | `app.knowledge.search.candidate-pool-max` | wired: knowledge `KnowledgeSearchService` |
| `memory.graph.recallMaxFacts` | i 1-100 | L | T,A | `app.graph-memory.recall.max-facts` | wired: graphmemory `GraphMemoryService` (agent recall resolves for the agent, the shared tier for the workspace) |
| `memory.graph.recallMaxPaths` | i 10-2000 | M | T,A | `app.graph-memory.recall.max-paths` | wired: graphmemory `GraphMemoryService` |
| `rl.policy.ucb1ExplorationConstant` | x 0-10 | M | T,A | `rl.policy.ucb1-exploration-constant` | wired: rl `ContextualBanditService` (UCB1 policies only, resolved for the agent) |
| `rl.policy.defaultExplorationRate` | x 0-1 | M | T | `rl.policy.default-exploration-rate` | wired: rl `PolicyStore` (the starting rate of a policy created after the override; existing policies keep their row's rate) |
| `providers.credential.rejectedCooldown` | d s 0-604800 (0 = off) | M | T | `app.providers.rejected-cooldown-hours` (hours; the catalog value is seconds) | wired: providers `ProviderCredentialResolver` (background callers), `ProviderService` (the "paused until" in the providers listing) |
| `agent.steps.graceCalls` | i 0-50 | M | T,A | `app.guardrails.step-limit.grace-calls` | **wired**: agents-turn `AgentRuntimeSettingsResolver#forAgent` resolves it per turn and carries it on the turn's `AgentRuntimeSettings`; agents-tools `AgentToolGuard` hands it to `GuardedToolCallback` and `ToolArgPolicyCallback`; the tool-loop round backstop (`toolLoopRounds`) outlasts it |
| `agent.steps.supervisedDivisor` | i 1-10 | L | T,A | `app.guardrails.step-limit.supervised-divisor` | **wired**: `AgentRuntimeSettingsResolver#forAgent` (tier scaling of the base ceiling; an agent's own `maxToolSteps` is still used exactly as set) |
| `agent.steps.supervisedMinSteps` | i 1-50 | L | T,A | `app.guardrails.step-limit.supervised-min-steps` | **wired**: as above |
| `agent.steps.autonomousMultiplier` | i 1-5 | M | T,A | `app.guardrails.step-limit.autonomous-multiplier` | **wired**: as above |
| `chat.semanticCache.similarityThreshold` | x 0.85-1 | M | T,A | `app.chat.semantic-cache.similarity-threshold` | **wired**: agents-turn `SemanticResponseCacheService#get` (for the agent, on the turn's thread) |
| `chat.semanticCache.ttl` | d s 300-2592000 | L | T,A | `app.chat.semantic-cache.ttl-hours` (hours; the catalog value is seconds) | **wired**: `SemanticResponseCacheService#put` |
| `chat.semanticCache.maxEntriesPerAgent` | i 10-5000 | L | T,A | `app.chat.semantic-cache.max-entries-per-agent` | **wired**: `SemanticResponseCacheService` prune after a write |
| `chat.followUp.maxStickyTools` | i 0-20 | L | T,A | `app.chat.follow-up.max-sticky-tools` | **wired**: agents-turn `ChatClientSelection` builds the sticky policy per turn; `FollowUpTools#stickyTools` applies it |
| `chat.followUp.keepPrimaryModel` | b | L | T,A | `app.chat.follow-up.keep-primary-model` | **wired**: `ChatClientSelection` -> `FollowUpTools#keepsPrimaryModel` |
| `chat.toolLedger.enabled` | b | L | T | `app.chat.tool-ledger.enabled` | **wired** (workspace-wide: the stored record is replayed from the session history, which has no agent): agents-tools `AgentToolGuard` (recording, `ToolLedgerOverrides#effective`) and agents-turn `SessionMemoryService` (replay to the model and to the rolling summary) |
| `chat.toolLedger.replayEnabled` | b | L | T | `app.chat.tool-ledger.replay-enabled` | **wired**: `SessionMemoryService` |
| `chat.toolLedger.replayTurns` | i 0-20 | L | T | `app.chat.tool-ledger.replay-turns` | **wired**: `SessionMemoryService` |
| `chat.toolLedger.maxEntriesPerTurn` | i 0-100 | L | T | `app.chat.tool-ledger.max-entries-per-turn` | **wired**: `AgentToolGuard` (recording) |
| `chat.toolLedger.maxReplayCharsPerTurn` | i 200-10000 | L | T | `app.chat.tool-ledger.max-replay-chars-per-turn` | **wired**: `SessionMemoryService` |
| `chat.attachments.maxImageMb` | i 1-30 (MB) | M | T,A | `app.chat.limits.attachments.max-image-bytes` (bytes; the catalog value is whole megabytes) | **wired**: agents-turn `ChatAttachmentService#upload` (a platform value that is not whole megabytes is kept exactly until someone sets their own); also capped by `spring.servlet.multipart.max-file-size` |
| `chat.attachments.maxAudioMb` | i 1-30 (MB) | M | T,A | `app.chat.limits.attachments.max-audio-bytes` | **wired**: as above |
| `chat.attachments.maxVideoMb` | i 1-30 (MB) | M | T,A | `app.chat.limits.attachments.max-video-bytes` | **wired**: as above |
| `chat.attachments.maxDocumentMb` | i 1-30 (MB) | M | T,A | `app.chat.limits.attachments.max-document-bytes` | **wired**: as above (documents and spreadsheets) |
| `memory.tenant.maxMemories` | i 10-5000 | M | T | `app.memory.limits.tenant-max-memories` | **wired**: agents-turn `TenantMemoryService` (prune after a write; shared memory belongs to the workspace) |
| `memory.edge.maxFacts` | i 1-100 | L | T,A | `app.memory.limits.edge-memory-max-facts` | **wired**: agents-turn `EdgeToolExecutionController` (memory endpoint for on-device sessions) |
| `skills.prompt.maxSkills` | i 1-200 | L | T | `app.skills.prompt.max-skills` | **wired** (workspace-wide: `SkillPromptBlock` is a static helper called from several modules with only the skill list in hand): `SkillPromptProperties#active()` applies the calling thread's tenant overlay installed by `SkillPromptConfiguration` |
| `skills.prompt.maxBlockChars` | i 1000-30000 | L | T | `app.skills.prompt.max-block-chars` | **wired**: as above |

Two rules about who may change the new keys, because the reviewer asked for them to be explicit:

- **The four `agent.steps.*` keys need a workspace administrator** (`minRole` TENANT_ADMIN, at the agent scope too). They
  define how far the ceiling of a SUPERVISED agent is tightened (for example an agent that the trifecta enforcement
  downgraded to SUPERVISED) and how many calls are answered past it. An agent editor can still set that agent's own
  `agent.limits.maxToolSteps`, but cannot set the divisor to 1 and the minimum to 50 to undo the tier. They stay
  medium/low risk and not stricter-only: an administrator may loosen them.
- **The `chat.attachments.max*Mb` keys can be raised above the platform value, up to 30 MB, and are not clamped to it.**
  That is the purpose of the control (an owner who works with large documents or recordings). The ceiling of 30 MB is the
  size of the servlet container's multipart cap (`spring.servlet.multipart.max-file-size`, `application-knowledge.yml`), which
  rejects a larger upload before the service sees it, so a workspace can use up to the operator's own cap and never exceed
  it. They are medium risk because an upload is held in memory for the request and is stored. This differs from the class B
  rows below: a bound is class B when no value inside its allowed range is safe for a workspace to choose, which is not true
  here, because the catalog range already ends at the container's hard cap. An operator who wants a lower ceiling lowers the
  multipart cap or pins the key (`app.settings.locked.chat.attachments.maxImageMb: true`).

The catalog stores and validates values for every row, and every row is wired: `SettingsWiringGuardTest` (module `app`) fails
the build when a catalog key has no call site in main code, so a key that the console accepts but nothing reads cannot ship
again. The only exceptions are keys read by a generic mechanism that builds the key at runtime (the seven `agent.limits.*`,
through `AgentRuntimeSettingsResolver`); they are listed with their reason in the test, and the test fails when an entry stops
being true. `aux.model` and per-tool
`tools.output.maxChars`/`knowledge.retrieval.topK` are not separate keys: the model of background calls is chosen per purpose
(`app.auxiliary-model.<purpose>`), and tool output and retrieval top-k are `agent.limits.toolOutputMaxChars` and
`agent.limits.retrievalTopK`.

The delegation keys are read through `ResolvedCollaborationLimits`: the tools are built on the request thread but run their
branches (and a verifier's repair round) on other threads, so each read applies the tenant captured when the tool was
resolved for the duration of the lookup and restores the thread's own afterwards. The agent is the one whose turn offers the
tool. The quality keys are workspace-level and read on the thread that triggers the run or the judge call (the eval worker
installs the run's tenant), so a background judge call with no tenant uses the platform value.

The knowledge, graph-memory, rl and providers keys follow one pattern: a `SettingsContributor` in the owning module reads
the SAME property object the runtime reads, and the use site holds an optional `SettingsResolver` (setter-injected, so
constructors and callers are unchanged) and asks it for the value of the calling workspace (and agent, where the key has
agent scope). With no resolver, or when it cannot answer, the use site falls back to the property value, so a search, a
recall or a credential lookup never fails because a setting cannot be read. Deliberately NOT in the catalog: provider
endpoints and per-provider model defaults (`app.llm.endpoints.*`, `app.models.*`) and the platform-wide ops thresholds in
`core/platform` (circuit breaker, demo sandbox, landing events), which are operator decisions for the whole deployment, not
workspace behaviour; and the reward values of `rl.feedback.*`, which are a modelling choice that should not differ between
workspaces sharing one preference export.

### How the security keys are read at the use site

The `guardrails.*` keys are read through `GuardrailSettings` (module `guardrails`), not by calling the resolver directly. The
guardrail classes are built with `new` on many paths, so, like `GuardrailFailMode`, the resolver is held in a static and handed
over once at startup (`GuardrailSettingsConfiguration`); without one, every method answers the platform value. On top of the
catalog's own rule (a write that is not stricter is refused; the resolver takes the stricter layer) `GuardrailSettings` checks
once more at the use site that the answer is never weaker than the platform value: a weaker value is ignored and logged once
per key and value (`DefaultSettingsResolver` logs the same for a stored row, so an operator sees which workspace holds it).
If the settings store or the resolver fails, the platform value applies and a warning is logged; a settings outage never
switches a guardrail off and never stops a turn. In agents-turn, `ChatGuardrailGate` should call
`GuardrailSettings.failClosed(agentId)` where it reads `GuardrailFailMode.isClosed()`.

`approvals.expiry.ttl` is read per tenant inside the expiry sweep (the sweep runs with each tenant's context set), and
`approvals.continuation.*` per job, for the job's agent, in the parked-job sweep and the resume builder (agents-jobs).

## Triage of the properties that had no control

Every behaviour-changing property of the agent runtime work (the rows added to [configuration.md](configuration.md) for the
settings catalog, the prompt registry, approval continuation, the turn journal, tool limits and the conversation-aware turn)
was looked at once and put into one of these classes. "No control" means no screen: the property is still read from
`application*.yml` / the environment as documented in configuration.md.

| Class | Meaning |
|---|---|
| **A** | In the settings catalog: an admin changes it in Admin > Runtime settings (and, where the entry has the agent scope, on the agent's Behavior and limits tab). The last column is the catalog key; its use site is in the table of keys above. |
| **A\*** | Chosen in the console through an existing screen instead of a catalog entry (here: AI Model Control, one row per background purpose; the tenant's row is the first choice of `AuxiliaryModelResolver`). |
| **B** | Deployment-only. The reason column starts with a short label that says why, and then says it for this property: **secret** (a credential; never shown in a console), **endpoint** / **infrastructure** (where the platform talks to, replica topology, storage, scheduler cadence), **timeout** / **cache** / **polling cadence** / **retry policy** (transport and staleness behaviour of the deployment), **request limit** / **sizing bound** / **capacity bound** / **safety bound** (a platform bound that would weaken protection if a workspace could raise it, or a size that protects memory), **security allow-list** / **security policy** (needs stricter-only semantics, so it is not a plain number), **feature switch** (an operator opt-in of a surface that costs money or widens egress), **wording** (model-facing or user-facing text kept in `application.yml`, or an alias of a prompt-registry key), **locale default**, **model data**, **modelling constant** / **algorithm constant** / **estimate constant** (an internal parameter with no meaning an owner could judge). |
| **B\*** | Deployment-only for now, and a plausible control: it could not be wired and tested properly in this pass (the reason says what is missing). Not an oversight; the next candidates. |
| **D** | Deferred: cleanup-owned. Its use site is in code another workstream was changing at the same time (turn continuation, finish notices, the turn journal, approval continuation, the verifier context, tool-loop guards); not touched here. |

Every class B row was reviewed by hand and carries its own reason; the label at its start (see the table above) says why. A
property filed under a family label is in that family for a reason that is true of it, for example a timeout is a timeout
of the deployment's transport, not a safety bound. The review changed some earlier classifications: a credential is a
**secret** (`app.langfuse.auth-header`), a request-size guard is a **request limit** (`app.agent-turn.limits.*`, including
the number of tool calls on one assistant message), locale and cache settings say so, and the token-estimate constants of
context compaction are **estimate constants**, no longer candidates for a control.

The list was produced from the configuration.md rows added by the runtime work; names that are API field names
(`maxToolSteps`, `turnTimeoutSeconds`, ...) rather than properties are not listed. Every catalog entry (class A) is also in the
table of keys above. The background model per purpose (`app.auxiliary-model.<purpose>.provider` / `.model`) keeps its
platform choice in the yml; a workspace chooses its own on **AI Model Control** (rows: Context compaction, Session summary,
Ask-AI assist, Evals / LLM-as-judge), which `AuxiliaryModelResolver` reads before the platform choice.

| Property | Class | Catalog key or reason |
|---|---|---|
| `app.a2a.client.allow-insecure-loopback` | B | security policy: lets the outbound A2A client use plain http to a loopback host (local development only); a workspace must not be able to open it, and a stricter-only control is pointless because the safe value is false |
| `app.a2a.client.allowed-hosts` | B | security allow-list: the egress allow-list of the outbound A2A client (the SSRF boundary: empty means no host is reachable); only the operator decides which hosts the platform may call |
| `app.a2a.client.connect-timeout` | B | timeout: TCP connect budget (5s) of the outbound A2A HTTP client; a network property of the deployment with no workspace-visible meaning |
| `app.a2a.client.enabled` | B | feature switch: master switch of the outbound agent-to-agent client (off by default): turning on calls to external agents is an operator decision about egress |
| `app.a2a.client.max-calls-per-turn` | B | safety bound: fan-out cap on `a2a_call_agent` calls in one turn (a looping or injected agent must not fan out); only a stricter-only per-workspace override would be safe, and the catalog has none for it |
| `app.a2a.client.max-response-bytes` | B | sizing bound: hard cap (1 MiB) on one remote response body; protects memory, raising it per workspace would raise the platform's memory exposure |
| `app.a2a.client.max-result-chars` | B | sizing bound: characters of a remote agent's answer handed to the model (20000); the per-workspace way to cap what a tool result puts in the context is `agent.limits.toolOutputMaxChars` |
| `app.a2a.client.max-wait` | B | timeout: total time one `a2a_call_agent` call may wait for the remote task (120s); holds a worker, bounded by the turn timeout (`agent.limits.turnTimeoutSeconds`) that is already a catalog key |
| `app.a2a.client.poll-interval` | B | polling cadence: how often a remote task is polled (2s); sets the load on the remote server and has no meaning for a workspace |
| `app.a2a.client.request-timeout` | B | timeout: per HTTP request of the outbound A2A client (card, JWKS, send, poll; 30s); a network property of the deployment |
| `app.a2a.client.require-signed-card` | B | security policy: refuse remote agent cards whose signature does not verify (default true); a workspace could only weaken it |
| `app.a2a.enabled` | B | feature switch: platform-wide kill switch of the inbound A2A server surface; an operator decision for every workspace |
| `app.a2a.max-concurrent-async-tasks` | B | capacity bound: non-blocking A2A turns running at once on one JVM (32); platform capacity, shared by all workspaces |
| `app.a2a.max-tasks` | B | capacity bound: tasks held in memory across all workspaces (10000); a cross-workspace memory cap that one workspace must not move |
| `app.a2a.require-pinned-key` | B | deployment policy: boot fails when A2A signing is on but no signing key is pinned (an ephemeral key would give every replica a different marketplace identity); a startup check, not workspace behaviour |
| `app.agent-jobs.approval.expired-failure-message` | B | wording: failure text of a job whose approval request expired; one text in `application.yml` for the deployment (no registry key) |
| `app.agent-jobs.approval.replay-grace` | B | timing constant: how long an approved but not yet executed request is left alone before the sweep resumes the parked job (PT90S); it must outlast the approve-then-replay window of the approval executor, so it is tied to deployment latency, not to a workspace |
| `app.agent-jobs.durability.requeue-zero-work-orphans` | D | deferred: cleanup-owned (turn-journal enable switch and retention) |
| `app.agent-jobs.durability.step-journal-enabled` | D | deferred: cleanup-owned (turn-journal enable switch and retention) |
| `app.agent-jobs.quiesce-worker-wait` | B | rollout behaviour: how long a shutting-down replica waits for job workers before releasing leases (PT3S, kept inside the 30 s SIGTERM grace); belongs to the deployment's shutdown sequence |
| `app.agent-jobs.run-now-mode` | B | rollout behaviour: whether a submitted job runs in this JVM (`inline`) or is only queued for another deployment shape (`enqueue`); an architecture choice per deployment shape |
| `app.agent-lab.free-plan-enabled` | B | plan gating: whether tenants stored as FREE may run Agent Lab knockout/consistency runs under the FREE caps; the platform's plan policy, not a workspace choice |
| `app.agent-lab.with-tools.enabled` | B | feature switch: lets an Agent Lab run use an agent's real tools (a call may park for approval); enables side effects from a lab run, so an operator opt-in (off by default) |
| `app.agent-runtime.bounds.<setting>.min` | B | range limit: the lower end of the range the catalog accepts for that runtime setting; the range is the platform envelope around the per-workspace controls, so a workspace cannot move it |
| `app.agent-runtime.bounds.continuation-passes.min` | B | range limit: lower end of the range a workspace or agent may set for continuation passes (`agent.limits.*`); the envelope around the catalog entry, so it stays with the operator |
| `app.agent-runtime.bounds.max-tool-steps.min` | B | range limit: lower end of the range a workspace or agent may set for tool calls per turn (`agent.limits.*`); the envelope around the catalog entry, so it stays with the operator |
| `app.agent-runtime.bounds.retrieval-min-score.min` | B | range limit: lower end of the range a workspace or agent may set for retrieval minimum score (`agent.limits.*`); the envelope around the catalog entry, so it stays with the operator |
| `app.agent-runtime.bounds.retrieval-top-k.min` | B | range limit: lower end of the range a workspace or agent may set for retrieval top-k (`agent.limits.*`); the envelope around the catalog entry, so it stays with the operator |
| `app.agent-runtime.bounds.tool-output-max-chars.min` | B | range limit: lower end of the range a workspace or agent may set for tool output characters (`agent.limits.*`); the envelope around the catalog entry, so it stays with the operator |
| `app.agent-runtime.bounds.tool-timeout-seconds.min` | B | range limit: lower end of the range a workspace or agent may set for tool timeout (`agent.limits.*`); the envelope around the catalog entry, so it stays with the operator |
| `app.agent-runtime.bounds.turn-timeout-seconds.min` | B | range limit: lower end of the range a workspace or agent may set for turn timeout (`agent.limits.*`); the envelope around the catalog entry, so it stays with the operator |
| `app.agent-runtime.cache-ttl` | B | cache: how long a replica serves a workspace's runtime defaults from memory (10s) before re-reading; a staleness window of the cache itself, which is what makes the per-workspace values cheap to read |
| `app.agent-runtime.defaults.retrieval-min-score` | A | `agent.limits.retrievalMinScore` |
| `app.agent-runtime.defaults.retrieval-top-k` | A | `agent.limits.retrievalTopK` |
| `app.agent-runtime.defaults.tool-timeout-seconds` | A | `agent.limits.toolTimeoutSeconds` |
| `app.agent-runtime.tool-timeout-message` | B | wording: what the model is told when a tool call is stopped by its timeout; one model-facing text in `application.yml` |
| `app.agent-turn.limits.max-messages` | B | request limit: messages in one `POST /api/completions/agent-turn` conversation (400); an API request-size guard of the endpoint |
| `app.agent-turn.limits.max-tool-calls-per-message` | B | request limit: tool calls on one assistant message of the same request (32, 400 on excess); an API request-size guard, not wording and not a per-workspace limit |
| `app.agent-turn.limits.max-tools` | B | request limit: tool definitions a client may offer in one request (40, 400 on excess); an API request-size guard |
| `app.agent-turn.limits.max-total-chars` | B | request limit: characters of message content in one request (600000, 413 on excess); an API request-size guard that protects memory |
| `app.agent-turn.repair.invalid-arguments-message` | B | wording: result handed to the model for a call whose arguments were unusable; model-facing text in `application.yml` |
| `app.agent-turn.repair.invalid-reason-cut-off` | B | wording: the `{reason}` inserted when arguments were cut off by the output limit; text in `application.yml` |
| `app.agent-turn.repair.invalid-reason-malformed` | B | wording: the `{reason}` inserted when arguments were not valid JSON; text in `application.yml` |
| `app.agent-turn.repair.max-attempts` | B | retry bound: extra model calls one hosted-loop turn may spend repairing a tool call with unusable arguments (1); each is billed to the platform's hosted model, so it is capped by the operator |
| `app.agent-turn.repair.skipped-message` | B | wording: result handed to the model for the other calls of the same reply, none of which ran; model-facing text in `application.yml` |
| `app.agent-turn.repair.unusable-calls-notice` | B | wording: client-facing text returned when the calls are still unusable after the last attempt; text in `application.yml` |
| `app.agent.share.report-revoke-threshold` | B | abuse policy: reports after which a public agent share link is revoked automatically (3); the platform's abuse handling for public links, which a workspace must not raise |
| `app.agents.collaboration.branch-timeout-seconds` | A | `delegation.branchTimeoutSeconds` |
| `app.agents.collaboration.max-answer-chars` | B | sizing constant: characters of one branch's answer handed back to the calling model (4000); a context-size constant duplicated in both delegation tools |
| `app.agents.collaboration.max-branches` | A | `delegation.maxBranches` |
| `app.agents.collaboration.max-role-chars` | B | sizing constant: characters kept of a `run_subtasks` worker's role label (80); a display bound |
| `app.agents.default-guardrails` | B | new-agent default: guardrails a newly created agent starts with (`jailbreak`); a deployment default for new agents; guardrail strictness of existing agents is the `guardrails.*` catalog entries |
| `app.agents.inferred-max-tokens-floor` | B | algorithm constant: least output-token value the Simple-mode tuning proposes (128); an inference bound, not a limit anyone is held to |
| `app.agents.max-tokens-ceiling` | B | safety bound: most output tokens an agent may be given (8192); it bounds spend and the hosted pool's reservation, so a workspace cannot raise it |
| `app.approvals.continuation.approver-unknown` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.claim-ttl` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.completed-message` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.completed-no-output-text` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.completed-note` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.denied-message` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.denied-note` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.enabled` | A | `approvals.continuation.enabled` |
| `app.approvals.continuation.failed-message` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.failed-note` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.jobs-continue-after-denial` | A | `approvals.continuation.jobsContinueAfterDenial` |
| `app.approvals.continuation.max-arguments-chars` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.max-note-result-chars` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.max-other-pending` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.max-result-chars` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.max-sticky-tools` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.no-reason-text` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.other-pending-header` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.result-not-recorded-text` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.result-shortened-suffix` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.sticky-tools-window` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.continuation.turn-timeout` | D | deferred: cleanup-owned (ApprovalContinuation*; the on/off switches are already catalog keys) |
| `app.approvals.expiry.batch-size` | B | sweep sizing: requests expired per tenant per sweep (200); throughput of the expiry job, the workspace-visible control is `approvals.expiry.ttl` |
| `app.approvals.expiry.expired-reason` | B | wording: rejection reason recorded on an expired request and read back by the agent; model-facing text in `application.yml` |
| `app.approvals.expiry.initial-delay` | B | scheduler cadence: delay after startup before the first expiry sweep (PT1M); scheduler timing of the deployment |
| `app.approvals.expiry.sweep-interval` | B | scheduler cadence: how often the expiry sweep runs (PT5M, one replica at a time); scheduler timing of the deployment, the workspace-visible control is `approvals.expiry.ttl` |
| `app.approvals.expiry.ttl` | A | `approvals.expiry.ttl` |
| `app.auxiliary-model.assist.provider` | A\* | AI Model Control page (tenant row per purpose: this pass added compaction and session-summary rows; assist and eval-judge existed); platform choice stays deployment-level |
| `app.auxiliary-model.compaction.provider` | A\* | AI Model Control page (tenant row per purpose: this pass added compaction and session-summary rows; assist and eval-judge existed); platform choice stays deployment-level |
| `app.auxiliary-model.judge.provider` | A\* | AI Model Control page (tenant row per purpose: this pass added compaction and session-summary rows; assist and eval-judge existed); platform choice stays deployment-level |
| `app.auxiliary-model.session-summary.provider` | A\* | AI Model Control page (tenant row per purpose: this pass added compaction and session-summary rows; assist and eval-judge existed); platform choice stays deployment-level |
| `app.auxiliary-model.use-agent-model` | A | `aux.useAgentModel` |
| `app.billing.invoice.seller.name` | B | identity: legal name of the supplier printed on every tax invoice; the operator's own company identity |
| `app.browser.secret-name-prefix` | B | naming convention: prefix of the Vault secret names that `browser_totp` and browser recipes may read (`BROWSER_`); a namespace convention that keeps those tools away from other secrets, so it is not widened per workspace |
| `app.chat.cache.context-turns` | A | `chat.cache.contextTurns` |
| `app.chat.cache.eligible-with-tools` | A | `chat.cache.eligibleWithTools` |
| `app.chat.cache.key-on-conversation` | B | compatibility switch: `false` keys the exact-match chat cache on the message alone, as before conversation-aware keys; kept only for rollback, the workspace-visible cache controls are `chat.cache.*` |
| `app.chat.continuation.default-locale` | D | deferred: cleanup-owned (TurnContinuation heuristic and nudge text) |
| `app.chat.continuation.heuristic-enabled` | A | `chat.continuation.heuristicEnabled` |
| `app.chat.continuation.heuristic-providers` | D | deferred: cleanup-owned (TurnContinuation heuristic and nudge text) |
| `app.chat.continuation.heuristic-scope` | D | deferred: cleanup-owned (TurnContinuation heuristic and nudge text) |
| `app.chat.continuation.heuristic-tool-grades` | D | deferred: cleanup-owned (TurnContinuation heuristic and nudge text) |
| `app.chat.continuation.intent.en` | D | deferred: cleanup-owned (TurnContinuation heuristic and nudge text) |
| `app.chat.continuation.max-passes` | A | `agent.limits.continuationPasses` |
| `app.chat.continuation.nudge.en` | D | deferred: cleanup-owned (TurnContinuation heuristic and nudge text) |
| `app.chat.continuation.tail-chars` | D | deferred: cleanup-owned (TurnContinuation heuristic and nudge text) |
| `app.chat.directives.artifacts.agent-ids` | B | rollout list: agents that opt in to the Artifacts directive before the all-agents switch; a boot-time operator rollout list |
| `app.chat.directives.artifacts.enabled` | B | feature switch: adds the Artifacts convention to the system prompt (off, it costs prompt tokens); a standing directive switched by the operator |
| `app.chat.directives.memory.enabled` | B | safety rail: operator kill switch for the standing memory directive; the property says that standing directives are safety rails, so they have no per-workspace override |
| `app.chat.escalation.low-confidence-markers` | B | retired alias: deprecated alias of the prompt-registry list `detector.escalation.low_confidence_markers`, which workspaces edit in Admin > Instructions |
| `app.chat.finish.continue-nudge` | D | deferred: cleanup-owned (finish notices and nudge text) |
| `app.chat.finish.empty-reply-message` | D | deferred: cleanup-owned (finish notices and nudge text) |
| `app.chat.finish.enabled` | A | `chat.finish.enabled` |
| `app.chat.finish.malformed-tool-call-notice` | D | deferred: cleanup-owned (finish notices and nudge text) |
| `app.chat.finish.max-continuations` | A | `chat.finish.maxContinuations` |
| `app.chat.finish.max-tool-call-retries` | A | `chat.finish.maxToolCallRetries` |
| `app.chat.finish.refusal-notice` | D | deferred: cleanup-owned (finish notices and nudge text) |
| `app.chat.finish.retry-tool-call-nudge` | D | deferred: cleanup-owned (finish notices and nudge text) |
| `app.chat.finish.truncated-notice` | D | deferred: cleanup-owned (finish notices and nudge text) |
| `app.chat.follow-up.keep-primary-model` | A | `chat.followUp.keepPrimaryModel` |
| `app.chat.follow-up.max-sticky-tools` | A | `chat.followUp.maxStickyTools` |
| `app.chat.follow-up.short-message-chars` | B\* | candidate for a later pass: a message of at most 40 characters counts as a follow-up (0 turns the follow-up rules off); it is read before the per-turn policy exists, so there is no workspace or agent to resolve against yet; the other `chat.followUp.*` knobs are in the catalog |
| `app.chat.follow-up.sticky-tools-enabled` | A | `chat.followUp.stickyToolsEnabled` |
| `app.chat.follow-up.sticky-tools-window` | A | `chat.followUp.stickyToolsWindow` |
| `app.chat.limits.attachments.max-audio-bytes` | A | `chat.attachments.maxAudioMb` |
| `app.chat.limits.attachments.max-document-bytes` | A | `chat.attachments.maxDocumentMb` |
| `app.chat.limits.attachments.max-image-bytes` | A | `chat.attachments.maxImageMb` |
| `app.chat.limits.attachments.max-video-bytes` | A | `chat.attachments.maxVideoMb` |
| `app.chat.limits.completed-event-excerpt-chars` | B | sizing constant: characters of the answer carried by the 'agent message completed' event to tenant webhooks (500); a payload-size bound |
| `app.chat.limits.directive-cache.failure-ttl` | B | retired: superseded by `app.prompts.cache.failure-ttl`: nothing reads this key any more, so a control would do nothing |
| `app.chat.limits.directive-cache.max-tenants` | B | retired: superseded by `app.prompts.cache.max-tenants`: nothing reads this key any more, so a control would do nothing |
| `app.chat.limits.directive-cache.ttl` | B | retired: superseded by `app.prompts.cache.ttl`: nothing reads this key any more, so a control would do nothing |
| `app.chat.limits.economy-aux-max-tokens` | B | sizing constant: upper bound on the output tokens of the economy model's auxiliary calls (1024); a cost bound of platform-run helper calls |
| `app.chat.limits.economy-aux-min-tokens` | B | sizing constant: lower bound of the same (256), so a small agent cap cannot truncate a verifier's verdict |
| `app.chat.limits.escalation.low-confidence-leading-chars` | B | detector constant: a low-confidence marker counts only within the first 240 characters of a reply; a detector heuristic |
| `app.chat.limits.escalation.low-confidence-max-chars` | B | detector constant: a marker counts only in a reply shorter than 400 characters; a detector heuristic |
| `app.chat.limits.escalation.registration-ttl` | B | cache: how long a cheap client stays registered as escalatable (PT10M); bookkeeping of the escalation mechanism |
| `app.chat.limits.share-turn-timeout` | B | timeout: how long a visitor of a shared agent waits for one reply (PT45S); a visitor of a shared agent is not a member of the workspace, so the operator bounds how long the turn may hold a worker (the owner-facing turn limit is `agent.limits.turnTimeoutSeconds`) |
| `app.chat.parity.fast-buffering-triggers` | B | algorithm constant: which tool families make a FAST turn buffer its reply instead of streaming it (`GENERATION_TOOLS,MCP_TOOLS`); an internal rule of the streaming path |
| `app.chat.parity.fast-buffering-mode` | B | algorithm constant: whether a held FAST turn streams up to its first link (`FROM_FIRST_LINK`) or is held whole (`WHOLE_REPLY`); an internal rule of the streaming path |
| `app.chat.parity.fast-hold-markers` | B | detector data: text that starts the held part of a streamed reply (`http,www.`); must cover the invented-link guard |
| `app.chat.local-model.stable-tools` | B | deployment fact: whether a model on the person's own machine sees an unchanged tool set every turn (true); depends on how the install serves its models |
| `app.chat.local-model.providers` | B | deployment fact: bring-your-own providers that run on the person's machine (`OLLAMA,LOCAL_OPENAI`) |
| `app.providers.small-model-max-billions` | B | sizing heuristic: parameter size at or below which a pool row runs in small-model mode (8.0); the row's own control overrides it |
| `app.chat.parity.verifier-history-window` | D | deferred: cleanup-owned (RIGOROUS verifier context) |
| `app.chat.parity.verifier-max-evidence-chars` | D | deferred: cleanup-owned (RIGOROUS verifier context) |
| `app.chat.parity.verifier-min-revision-ratio` | D | deferred: cleanup-owned (RIGOROUS verifier context) |
| `app.chat.parity.verifier-ratio-applies-above-chars` | D | deferred: cleanup-owned (RIGOROUS verifier context) |
| `app.chat.resilience.depth-timeout-degraded-notice` | B | wording: step-event note when a timed-out turn is answered again at fast depth; text in `application.yml` |
| `app.chat.resilience.depth-timeout-partial-notice` | B | wording: note shown when a buffered turn ran out of time after tools ran (not shown to the model); text in `application.yml` |
| `app.chat.resilience.hosted-self-heal-cooldown` | B | cadence: least time between two repair attempts for a hosted-pool row whose model id the provider reports gone (PT10M); protects the provider catalog from repair storms |
| `app.chat.resilience.list-none` | B | wording: word that stands in for an empty list in the timeout notice; text in `application.yml` |
| `app.chat.resilience.replay-after-journaled-effects` | B | safety policy: whether a turn that already ran tools is replayed on another provider when every tool was read-only or journaled (true); it decides whether a side effect can repeat, so it is not widened per workspace |
| `app.chat.resilience.replay-blocked-notice` | B | wording: note when a provider failure was not retried elsewhere because tools had run; text in `application.yml` |
| `app.chat.resilience.retry-at-fast-when-no-tool-ran` | B | behaviour switch: a careful or rigorous turn that times out before any tool ran is answered again at fast depth (true); a failure-handling default of the deployment, with a metric (`chat.degraded.depth_timeout`) the operator watches |
| `app.chat.resilience.structured-call-timeout` | B | timeout: how long a structured agent call such as a workflow node waits for the reply (PT2M); a platform timeout for non-interactive calls |
| `app.chat.resilience.thought-signature-degrade` | D | deferred: cleanup-owned (FastStreamRecovery thought-signature degrade) |
| `app.chat.resilience.thought-signature-evidence-chars` | D | deferred: cleanup-owned (FastStreamRecovery thought-signature degrade) |
| `app.chat.resilience.thought-signature-notice` | D | deferred: cleanup-owned (FastStreamRecovery thought-signature degrade) |
| `app.chat.resilience.tool-idle-ceiling` | B | timeout: how long one tool may run before the stream idle timer counts it as hung (PT30M); a platform ceiling above every tool's own timeout (`agent.limits.toolTimeoutSeconds` is the workspace control) |
| `app.chat.semantic-cache.max-entries-per-agent` | A | `chat.semanticCache.maxEntriesPerAgent` |
| `app.chat.semantic-cache.similarity-threshold` | A | `chat.semanticCache.similarityThreshold` |
| `app.chat.semantic-cache.ttl-hours` | A | `chat.semanticCache.ttl` |
| `app.chat.small-model.browser-family-quota` | B | algorithm constant: tools a browsing request keeps on a small-model turn (4: navigate, read, click and type only work as a set) |
| `app.chat.small-model.intent-boost` | B | algorithm constant: score bonus of a tool that serves a signalled intent (50); a ranking weight inside the small-model tool selector |
| `app.chat.small-model.named-tool-score` | B | algorithm constant: score of a tool the message names outright (1000); a ranking weight |
| `app.chat.small-model.tool-cap` | B | sizing heuristic: tools offered on one small-model turn (6); keeps a small model's prompt short, tuned with the other small-model scores |
| `app.chat.tool-ledger.cut-marker` | B | wording: marker appended where the tool record is cut; model-facing text in `application.yml` |
| `app.chat.tool-ledger.enabled` | A | `chat.toolLedger.enabled` |
| `app.chat.tool-ledger.max-argument-chars` | B | sizing constant: characters of one call's arguments shown in the tool record; the record's size knobs that matter to owners (`chat.toolLedger.*`) are in the catalog |
| `app.chat.tool-ledger.max-entries-per-turn` | A | `chat.toolLedger.maxEntriesPerTurn` |
| `app.chat.tool-ledger.max-replay-chars-per-turn` | A | `chat.toolLedger.maxReplayCharsPerTurn` |
| `app.chat.tool-ledger.replay-enabled` | A | `chat.toolLedger.replayEnabled` |
| `app.chat.tool-ledger.replay-turns` | A | `chat.toolLedger.replayTurns` |
| `app.chat.tools.budget-exhausted-message` | D | deferred: cleanup-owned (tool-loop and tool-argument guard text and checks) |
| `app.chat.tools.hard-stop-reply` | D | deferred: cleanup-owned (tool-loop and tool-argument guard text and checks) |
| `app.chat.tools.max-rounds-per-invocation` | B | safety bound: model rounds one invocation may run before the soft stop (0 derives it from the call ceiling); a loop backstop behind `agent.limits.maxToolSteps` |
| `app.chat.tools.max-unknown-calls-per-invocation` | D | deferred: cleanup-owned (tool-loop and tool-argument guard text and checks) |
| `app.chat.tools.result-clearing.enabled` | A | `chat.toolLoop.resultClearing.enabled` |
| `app.chat.tools.result-clearing.keep-last` | D | deferred: cleanup-owned (LoggingToolCallback / result clearing text) |
| `app.chat.tools.result-clearing.restore-hint` | D | deferred: cleanup-owned (LoggingToolCallback / result clearing text) |
| `app.chat.tools.result-clearing.threshold-chars` | A | `chat.toolLoop.resultClearing.thresholdChars` |
| `app.chat.tools.text-call-recovery.apply-to-unmeasured-rows` | D | deferred: cleanup-owned (GeminiMixedContent / text-call recovery) |
| `app.chat.tools.text-call-recovery.enabled` | D | deferred: cleanup-owned (GeminiMixedContent / text-call recovery) |
| `app.chat.tools.text-call-recovery.providers` | D | deferred: cleanup-owned (GeminiMixedContent / text-call recovery) |
| `app.chat.tools.text-call-recovery.scope` | D | deferred: cleanup-owned (GeminiMixedContent / text-call recovery) |
| `app.chat.tools.text-call-recovery.tool-grades` | D | deferred: cleanup-owned (GeminiMixedContent / text-call recovery) |
| `app.coding-harness.enabled` | B | feature switch: master switch of the sandboxed coding-task harness (off by default); runs repository code, an operator opt-in |
| `app.coding-harness.network-enabled` | B | security policy: whether harness test commands keep a network (weaker than the default empty network namespace); only the operator may weaken the sandbox |
| `app.context.compaction.enabled` | B\* | candidate for a later pass: whether a long prompt is compacted before it overflows the window (on by default since 2026-10-23); compaction is configured once per ChatClient advisor, not per turn, so there is no per-workspace read site yet |
| `app.context.compaction.media-tokens` | B | estimate constant: tokens one attached image, audio or video costs in the compaction estimate (1000); an estimate, not a limit |
| `app.context.compaction.message-overhead-tokens` | B | estimate constant: tokens every message costs beyond its text in the estimate (4) |
| `app.context.compaction.placeholder-tokens` | B | estimate constant: estimated tokens of a cleared-result placeholder (48); a result under twice this is never cleared |
| `app.context.compaction.response-overhead-tokens` | B | estimate constant: tokens a tool call or response costs beyond its text in the estimate (6) |
| `app.context.compaction.restore-result-max-chars` | B\* | candidate for a later pass: characters of one tool result `restore_tool_result` returns (16384); read inside the same once-per-advisor compaction setup, so no per-workspace read site yet |
| `app.context.compaction.summary-frame-tokens` | B | estimate constant: tokens of a summary message beyond its body (40) |
| `app.context.compaction.window-rules` | B | model data: context window by model name for models whose provider catalog gives none; reference data about models, the same for every workspace |
| `app.crypto.kms.aws.region` | B | infrastructure: AWS region of the KMS key that wraps tenant data keys; deployment infrastructure |
| `app.crypto.kms.azure.algorithm` | B | infrastructure: key-wrap algorithm used with the Azure Key Vault key; recorded with each wrapped key, so changing it never strands data; deployment infrastructure |
| `app.crypto.kms.gcp.endpoint` | B | infrastructure: Cloud KMS endpoint (a non-Google endpoint is refused unless the test flag is set); deployment infrastructure and a data-egress boundary |
| `app.datasource.token-refresh-skew` | B | infrastructure: how long before expiry a database access token is renewed (2m); database credential plumbing |
| `app.delegation.issuer` | B | identity: `iss` claim of the delegation tokens the platform issues to agents; the platform's token identity, so changing it per workspace would break verification |
| `app.demo.studio.max-workflows-per-session` | B | demo limit: workflows one anonymous demo Studio session may create (10); abuse limit of the public demo |
| `app.demo.telegram.webhook-base-url` | B | infrastructure: public HTTPS base URL Telegram calls for the demo bot; deployment hostname |
| `app.devices.claim.rate-limit.max-attempts-per-ip` | B | abuse limit: claim attempts one IP may make in the window before refusal (10); anti-brute-force on an unauthenticated endpoint, and a platform setting already overrides it at run time |
| `app.embeddings.model` | B | infrastructure: embedding model id sent to the OpenAI-compatible provider; changing it invalidates every stored vector, so it is chosen once per deployment together with `app.embeddings.dimensions` |
| `app.embeddings.ollama.document-prefix` | B | infrastructure: prefix put before each stored passage sent to Ollama; part of the embedding model contract of the deployment |
| `app.encryption.key` | B | secret: deployment secret from which stored tenant credentials are encrypted; identical on every replica, never shown or set in a console |
| `app.evals.free-plan-enabled` | B | plan gating: opens the read-only quality surface to FREE tenants; the platform's plan policy |
| `app.graph-memory.arbitration.max-context-facts` | B | sizing constant: existing facts shown to the model that decides how a new fact relates to them (30); an internal prompt-size bound |
| `app.graph-memory.forget.scan-max-pages` | B | sizing constant: pages the forget scan walks (20); a work bound on one forget request |
| `app.graph-memory.forget.scan-page` | B | sizing constant: rows per page of that scan (500) |
| `app.graph-memory.recall.max-facts` | A | `memory.graph.recallMaxFacts` |
| `app.graph-memory.recall.max-paths` | A | `memory.graph.recallMaxPaths` |
| `app.guardrails.approval-park.awaiting-message` | B | wording: what the model reads when its call is held for approval; compatibility alias of the prompt `toolresult.park.awaiting`, which workspaces edit in the prompt registry |
| `app.guardrails.approval-park.goal-cut-marker` | B | wording: marker appended to a stored goal cut at the maximum; model-facing text in `application.yml` |
| `app.guardrails.approval-park.max-goal-chars` | B | sizing bound: characters of the user's message stored in the approval's replay envelope (4000); bounds the size of stored rows |
| `app.guardrails.approval-park.max-queued-calls` | B | safety bound: calls after a parked turn that are recorded as their own pending approvals (5); bounds how many approval requests one turn can create |
| `app.guardrails.approval-park.queue-full-message` | B | wording: what the model reads when the queue is full; compatibility alias of `toolresult.park.queue_full` |
| `app.guardrails.approval-park.queued-message` | B | wording: what the model reads for a call queued behind a parked turn; compatibility alias of `toolresult.park.queued` |
| `app.guardrails.approval-park.record-failed-message` | B | wording: what the model reads when the approval request itself could not be recorded; compatibility alias of `toolresult.park.record_failed` |
| `app.guardrails.fail-mode` | A | `guardrails.failMode` |
| `app.guardrails.implementation` | B | architecture choice: `semantic` (pattern layer plus an LLM judge) or `pattern` only; selects the guardrail engine of the deployment. The workspace-level strictness controls are the `guardrails.*` catalog entries |
| `app.guardrails.jailbreak.max-scanned-chars` | A | `guardrails.jailbreak.maxScannedChars` |
| `app.guardrails.judge.max-input-chars` | A | `guardrails.judge.maxInputChars` |
| `app.guardrails.judge.max-tokens` | B | judge tuning: output-token cap of guardrail judge calls (empty inherits the agent's cap); shared by every workspace's judge calls, and a cap that is too low truncates a verdict |
| `app.guardrails.judge.model` | B | judge tuning: model id for guardrail judge calls, on the agent's own provider; deployment choice, workspaces pick background models on AI Model Control |
| `app.guardrails.judge.temperature` | B | judge tuning: sampling temperature of guardrail judge calls; a modelling choice shared by all judges |
| `app.guardrails.judge.truncation-marker` | B | wording: marker appended to judge input cut at the input maximum; model-facing text in `application.yml` |
| `app.guardrails.max-tool-calls-per-turn` | A | `agent.limits.maxToolSteps` |
| `app.guardrails.run-budget.usd` | B | spend cap: platform cap on what one agent run may spend (2.0 USD, 0 = none); a financial safety limit of the platform; workspaces have their own budget controls and may only live inside this cap |
| `app.guardrails.step-limit.autonomous-multiplier` | A | `agent.steps.autonomousMultiplier` |
| `app.guardrails.step-limit.exhausted-message` | D | deferred: cleanup-owned (tool-loop and tool-argument guard text and checks) |
| `app.guardrails.step-limit.grace-calls` | A | `agent.steps.graceCalls` |
| `app.guardrails.step-limit.supervised-divisor` | A | `agent.steps.supervisedDivisor` |
| `app.guardrails.step-limit.supervised-min-steps` | A | `agent.steps.supervisedMinSteps` |
| `app.guardrails.supervised.gate-read-only` | A | `guardrails.supervised.readOnlyCalls` |
| `app.guardrails.tenant-rules.max-regex-steps` | B | safety bound: characters a tenant guardrail regex may read in one evaluation (5000000); guards the platform against catastrophic backtracking from tenant-authored patterns |
| `app.guardrails.tenant-rules.regex-timeout-ms` | B | safety bound: wall-clock bound of one tenant guardrail regex evaluation (250 ms); the same protection against tenant-authored patterns |
| `app.guardrails.tool-verifier.context-messages` | D | deferred: cleanup-owned (RIGOROUS verifier context) |
| `app.guardrails.tool-verifier.fail-mode` | A | `guardrails.toolVerifier.failMode` |
| `app.guardrails.tool-verifier.unavailable-reason` | D | deferred: cleanup-owned (RIGOROUS verifier context) |
| `app.guardrails.toxicity.block-score` | A | `guardrails.toxicity.blockScore` |
| `app.jev.base-url` | B | infrastructure: endpoint of the Jev decision model service; a deployment endpoint |
| `app.jev.model` | B | infrastructure: model id requested from the Jev service; deployment choice |
| `app.keycloak.allowed-azp` | B | security allow-list: Keycloak client ids whose tokens core accepts; widening it admits another client, so only the operator sets it |
| `app.knowledge.search.candidate-pool-max` | A | `knowledge.search.candidatePoolMax` |
| `app.knowledge.search.candidate-pool-min` | B | algorithm constant: least candidates fetched before fusion (20); the pool's multiplier and maximum are catalog entries, this floor is a retrieval constant |
| `app.knowledge.search.candidate-pool-multiplier` | A | `knowledge.search.candidatePoolMultiplier` |
| `app.knowledge.search.rrf-k` | B | algorithm constant: reciprocal-rank-fusion constant k (60); a ranking-formula parameter whose effect on a workspace is not interpretable |
| `app.knowledge.vector-index.initial-delay-ms` | B | scheduler cadence: delay after startup before the first pgvector index maintenance pass; keeps it off the startup path |
| `app.knowledge.vector-index.interval-ms` | B | scheduler cadence: time between index maintenance passes (6 hours); database maintenance of the deployment |
| `app.landing-events.batch-enabled` | B | infrastructure: buffers the landing-page beacon in memory instead of the synchronous Redis path; a throughput choice of the deployment |
| `app.langfuse.auth-header` | B | secret: the `Authorization` value (Basic base64 of the Langfuse public and secret key) that the Langfuse API client sends; a credential of the deployment, never shown or set in a console |
| `app.llm.endpoints.<vendor>.models-path` | B | endpoint: path of a vendor's model-list endpoint (default `/v1/models`, Gemini `/v1beta/models`); an API detail of the provider |
| `app.llm.endpoints.anthropic.base-url` | B | endpoint: base URL of the anthropic API; where the platform sends model traffic and provider keys, so changing it is a data-egress and credential-exposure decision of the operator (tests and private gateways) |
| `app.llm.endpoints.anthropic.models-path` | B | endpoint: path of the anthropic model-list endpoint under the base URL; an API detail of the provider |
| `app.llm.endpoints.deepseek.base-url` | B | endpoint: base URL of the deepseek API; where the platform sends model traffic and provider keys, so changing it is a data-egress and credential-exposure decision of the operator (tests and private gateways) |
| `app.llm.endpoints.deepseek.models-path` | B | endpoint: path of the deepseek model-list endpoint under the base URL; an API detail of the provider |
| `app.llm.endpoints.gemini.base-url` | B | endpoint: base URL of the gemini API; where the platform sends model traffic and provider keys, so changing it is a data-egress and credential-exposure decision of the operator (tests and private gateways) |
| `app.llm.endpoints.gemini.completions-path` | B | endpoint: path of the Gemini OpenAI-compatible chat endpoint; an API detail of the provider |
| `app.llm.endpoints.gemini.models-path` | B | endpoint: path of the gemini model-list endpoint under the base URL; an API detail of the provider |
| `app.llm.endpoints.grok-language-models-path` | B | endpoint: path of the Grok language-model listing; an API detail of the provider |
| `app.llm.endpoints.grok.base-url` | B | endpoint: base URL of the grok API; where the platform sends model traffic and provider keys, so changing it is a data-egress and credential-exposure decision of the operator (tests and private gateways) |
| `app.llm.endpoints.grok.models-path` | B | endpoint: path of the grok model-list endpoint under the base URL; an API detail of the provider |
| `app.llm.endpoints.groq.base-url` | B | endpoint: base URL of the groq API; where the platform sends model traffic and provider keys, so changing it is a data-egress and credential-exposure decision of the operator (tests and private gateways) |
| `app.llm.endpoints.groq.models-path` | B | endpoint: path of the groq model-list endpoint under the base URL; an API detail of the provider |
| `app.llm.endpoints.meta.base-url` | B | endpoint: base URL of the meta API; where the platform sends model traffic and provider keys, so changing it is a data-egress and credential-exposure decision of the operator (tests and private gateways) |
| `app.llm.endpoints.meta.models-path` | B | endpoint: path of the meta model-list endpoint under the base URL; an API detail of the provider |
| `app.llm.endpoints.mistral.base-url` | B | endpoint: base URL of the mistral API; where the platform sends model traffic and provider keys, so changing it is a data-egress and credential-exposure decision of the operator (tests and private gateways) |
| `app.llm.endpoints.mistral.models-path` | B | endpoint: path of the mistral model-list endpoint under the base URL; an API detail of the provider |
| `app.llm.endpoints.openai.base-url` | B | endpoint: base URL of the openai API; where the platform sends model traffic and provider keys, so changing it is a data-egress and credential-exposure decision of the operator (tests and private gateways) |
| `app.llm.endpoints.openai.models-path` | B | endpoint: path of the openai model-list endpoint under the base URL; an API detail of the provider |
| `app.llm.endpoints.openrouter-auth-key-path` | B | endpoint: path of the OpenRouter key-info endpoint; an API detail of the provider |
| `app.llm.endpoints.openrouter.base-url` | B | endpoint: base URL of the openrouter API; where the platform sends model traffic and provider keys, so changing it is a data-egress and credential-exposure decision of the operator (tests and private gateways) |
| `app.llm.endpoints.openrouter.models-path` | B | endpoint: path of the openrouter model-list endpoint under the base URL; an API detail of the provider |
| `app.llm.endpoints.sarvam.base-url` | B | endpoint: base URL of the sarvam API; where the platform sends model traffic and provider keys, so changing it is a data-egress and credential-exposure decision of the operator (tests and private gateways) |
| `app.llm.endpoints.sarvam.models-path` | B | endpoint: path of the sarvam model-list endpoint under the base URL; an API detail of the provider |
| `app.llm.http.buffered-turn-timeout` | A | `agent.limits.turnTimeoutSeconds` |
| `app.llm.http.stream-idle-timeout` | B | timeout: how long a model may be silent before a fast-depth stream ends (90s); a transport timeout tuned to provider behaviour, and while a tool runs `app.chat.resilience.tool-idle-ceiling` applies instead |
| `app.llm.judge.max-excerpt-length` | B | sizing constant: characters of the user message and answer echoed into the monitoring judge prompt and recorded issue (600) |
| `app.llm.judge.sample-rate` | B | monitoring cost: fraction of live turns the background quality judge samples (0.05); it costs the platform model calls and never changes a reply; already adjustable at run time by a platform admin through a platform setting |
| `app.llm.judge.sample-rate-cache-ms` | B | cache: how long that platform setting is reused (10000 ms); read on every turn, so it is cached |
| `app.llm.request-snapshot-store.key-prefix` | B | infrastructure: Redis key prefix of the request inspector's copies |
| `app.llm.request-snapshot-store.max-per-session` | B | retention bound: model calls kept per session in the request inspector (80); memory and Redis footprint |
| `app.llm.request-snapshot-store.max-sessions` | B | retention bound: sessions kept in memory by the request inspector (300) |
| `app.llm.request-snapshot-store.mode` | B | infrastructure: `redis` or in-memory storage of the inspector's copies; follows the replica topology |
| `app.llm.request-snapshot-store.ttl` | B | retention bound: how long an inspector copy is kept (6h); the copies hold redacted prompts, so retention is the operator's |
| `app.llm.request-snapshot-store.write-queue-size` | B | infrastructure: copies waiting for the Redis write (256); a back-pressure bound |
| `app.llm.request-snapshot.max-call-chars` | B | display bound: characters of one tool call's arguments or result kept in the inspector (600) |
| `app.llm.request-snapshot.max-message-chars` | B | display bound: characters of one message kept in the inspector (40000) |
| `app.llm.request-snapshot.max-schema-chars` | B | display bound: characters of one tool schema kept in the inspector (4000) |
| `app.llm.request-snapshot.max-tools` | B | display bound: tool definitions kept in the inspector (200) |
| `app.llm.request-snapshot.max-total-chars` | B | display bound: characters of all messages kept in the inspector (300000) |
| `app.llm.request-snapshot.truncation-marker` | B | wording: marker appended to an inspector message that was cut; display text, not model-facing |
| `app.llm.retry.honor-retry-after` | B | retry policy: wait at least what the provider's Retry-After asks (true); a transport-level rule shared by all workspaces, the workspace-visible control is `chat.retry.maxAttempts` |
| `app.llm.retry.initial-backoff` | B | retry policy: wait before the first retry (1s), doubling after |
| `app.llm.retry.jitter` | B | retry policy: fraction of each backoff that is randomised (0.5) so callers do not retry together |
| `app.llm.retry.max-attempts` | A | `chat.retry.maxAttempts` |
| `app.llm.retry.max-backoff` | B | retry policy: longest the exponential backoff grows to (10s) |
| `app.llm.retry.max-retry-after` | B | retry policy: a provider asking to wait longer than 30s is not retried here, so the fallback provider takes over |
| `app.llm.retry.retry-rate-limited` | B | retry policy: whether HTTP 429 is retried (true); `false` sends a rate limit straight to the provider fallback |
| `app.llm.retry.stream-pre-first-token` | B | retry policy: retry a streaming turn that failed before its first token (true); never after a token was sent or a tool ran |
| `app.llm.spend-counter.reconcile-initial-delay-ms` | B | scheduler cadence: delay after startup before the first tick of the spend-counter reconciler |
| `app.llm.spend-counter.reconcile-tick-ms` | B | scheduler cadence: how often the reconciler checks whether a pass is due |
| `app.mcp-server.expose-agents.enabled` | B | feature switch: exposes agents and workflows as tools on the inbound MCP server (off by default, needs the `mcpserver` profile); an operator decision about the public surface |
| `app.mcp.max-stdio-connections-per-tenant` | B | safety bound: local (STDIO) MCP servers one workspace may keep connected (5); each is a child process on the platform's hosts, so the operator caps it (the refusal says to ask the operator) |
| `app.mcp.oauth.cimd.path` | B | endpoint: path of the platform's OAuth client metadata document; a published URL that remote servers have registered |
| `app.mcp.oauth.enabled` | B | feature switch: enables the OAuth connect flow for remote MCP servers (off: the endpoints answer 404); an operator decision |
| `app.mcp.oauth.provider-state-ttl-seconds` | B | timeout: lifetime of a pending OAuth state (600s); an anti-replay window of the flow |
| `app.mcp.oauth.store` | B | infrastructure: `memory` or `redis` for pending OAuth state; follows the replica topology (redis is required with multiple replicas) |
| `app.mcp.registry-adopt-interval-ms` | B | scheduler cadence: how often an unowned registration is adopted by a replica (60s); replica coordination |
| `app.mcp.registry-heartbeat-ms` | B | scheduler cadence: how often a replica renews its leases (15s); replica coordination |
| `app.mcp.registry-poll-ms` | B | scheduler cadence: how often a replica drains its command queue (250 ms); replica coordination |
| `app.mcp.registry-route-tool-calls` | B | infrastructure: routes a tool call to the replica that owns the connection; follows the replica topology |
| `app.mcp.registry-store` | B | infrastructure: `memory` or `redis` ownership registry of MCP clients; follows the replica topology |
| `app.mcp.registry-tool-call-timeout` | B | timeout: how long a routed MCP tool call waits for the owning replica (PT60S); inter-replica transport |
| `app.mcpserver.async-tasks.enabled` | B | feature switch: adds the long-running task tools to the inbound MCP server's tool list (off by default); an operator decision about the public surface |
| `app.memory.blocks.enabled` | B | feature switch: whether memory blocks are read and given to agents (true); the size limits of blocks are separate properties and a workspace-level on/off was not requested |
| `app.memory.import.enabled` | B | feature switch: lets a member import memory facts exported from another assistant (also needs self-service memory); an operator opt-in to an import surface |
| `app.memory.limits.agent-max-memories` | A | `memory.agent.maxMemories` |
| `app.memory.limits.agent-recall-top-k` | A | `memory.recall.agentTopK` |
| `app.memory.limits.edge-memory-max-facts` | A | `memory.edge.maxFacts` |
| `app.memory.limits.session-fold-batch` | B | sizing constant: out-of-window turns folded into the rolling summary at a time (40); one summarisation call per batch, a cost and quality trade-off inside the platform |
| `app.memory.limits.session-max-stored-entries` | B | storage bound: turns kept in a session's Redis list (400): the hard storage bound and the ceiling of the replay window; raising it per workspace would raise Redis usage |
| `app.memory.limits.tenant-max-memories` | A | `memory.tenant.maxMemories` |
| `app.memory.limits.tenant-recall-top-k` | A | `memory.recall.tenantTopK` |
| `app.models.context-windows.<model-id>` | B | model data: overrides the catalog's context window for one model; reference data about the model, the same for every workspace |
| `app.models.default-preference-keywords` | B | algorithm constant: keywords that rank models when choosing a default (`flash`, `mini`, `haiku`, ...); selection heuristic of the model catalog |
| `app.models.defaults.<provider>` | B | model data: the default model for each provider (what new agents get); platform-curated catalog data |
| `app.models.hosted-pool-auto-keywords` | B | algorithm constant: keywords for the hosted pool's automatic model choice; selection heuristic of the hosted pool |
| `app.models.retired.<model-id>` | B | model data: retired model id mapped to its replacement; platform-curated catalog data |
| `app.observability.genai-attrs` | B | observability: which OpenTelemetry GenAI attribute names model-call spans carry (`legacy`, `new` or `both`); depends on the operator's tracing backend |
| `app.platform.jobs.run-now-allowlist` | B | security allow-list: scheduled jobs a platform admin may trigger on demand; an allow-list of operator actions |
| `app.platform.reliability.cache-ttl-seconds` | B | cache: how long the reliability console's dependency probes are cached (30s); platform-admin console |
| `app.platform.reliability.enabled` | B | feature switch: the platform-admin reliability console; platform operations |
| `app.platform.reliability.probe-timeout-ms` | B | timeout: timeout of one dependency probe (2000 ms); platform operations |
| `app.platform.reliability.slo.aggregate-via-redis` | B | infrastructure: aggregate SLO samples across replicas through Redis; follows the replica topology |
| `app.platform.reliability.slo.redis-publish-seconds` | B | scheduler cadence: how often a replica publishes its SLO samples to Redis (300s) |
| `app.platform.reliability.slo.retention-hours` | B | retention bound: hours of SLO samples kept (48) |
| `app.platform.reliability.slo.sample-interval-seconds` | B | scheduler cadence: how often SLO samples are taken (60s) |
| `app.prompts.cache.failure-ttl` | B | cache: how long built-in defaults stand in after a failed override load (5s) before the next attempt |
| `app.prompts.cache.max-tenants` | B | cache: tenants cached before the prompt-override cache is cleared (10000); memory bound |
| `app.prompts.cache.ttl` | B | cache: how long a replica serves a tenant's stored prompt overrides from memory (30s); a staleness window of the cache |
| `app.prompts.chars-per-token` | B | estimate constant: characters per token for the estimate in the prompt preview (4.0); a rule of thumb shown to every admin, not a tokenizer |
| `app.prompts.default-locale` | B | locale default: locale of the built-in prompt texts and of a context that names none (`en`); the shipped texts are in that language, so changing it only makes sense together with shipping files for the new locale (a workspace's own wording per locale is a prompt-registry override, docs/prompts.md) |
| `app.prompts.default-max-length` | B | sizing bound: longest override accepted for a prompt key whose resource declares no `maxLength` (8000); a validation bound of the registry API |
| `app.prompts.history-default-limit` | B | API paging: rows of override history returned when the caller names no limit (50) |
| `app.prompts.history-max-limit` | B | API paging: most history rows one call returns (500) |
| `app.prompts.list-max-limit` | B | API paging: most rows one `GET /api/prompts` call returns (500) |
| `app.prompts.system-prompt-token-budget` | B | preview threshold: token budget the prompt preview flags a prompt against (0 = no budget); a display threshold of the preview, which has no effect on a turn |
| `app.providers.rejected-cooldown-hours` | A | `providers.credential.rejectedCooldown` |
| `app.providers.sarvam.max-tokens-ceiling` | B | provider limit: most output tokens the Sarvam client sends (4096); a limit of that provider's API |
| `app.quality.eval.auto-approve.actor` | B | audit label: name that auto-approvals are attributed to in logs (never a person) |
| `app.quality.eval.auto-approve.excluded-tools` | B | security allow-list: tools never auto-approved in an eval run (shell, payments, deletes, ...); a deny-list that may only be loosened by the operator |
| `app.quality.eval.auto-approve.tenant-ids` | B | security allow-list: tenants the operator declares non-production, the only ones that may use auto-approve in evals; a workspace must not declare itself |
| `app.quality.eval.calibration.enabled` | B | feature switch: judge calibration (two judge calls per labelled example); costs model calls, off by default; an operator opt-in |
| `app.quality.eval.default-mode` | A | `quality.eval.defaultMode` |
| `app.quality.eval.default-repeats` | A | `quality.eval.defaultRepeats` |
| `app.quality.eval.judge-call-timeout-seconds` | A | `quality.judge.callTimeoutSeconds` |
| `app.quality.eval.judge-model` | A | `quality.judge.model` |
| `app.quality.eval.sandbox.read-only-tools` | B | security allow-list: tool names the operator vouches are read-only, so an unclassified tool may run for real in an eval; only the operator can widen what an eval is allowed to really execute, and it never overrides a declared classification |
| `app.quality.trajectory-eval.enabled` | B | feature switch: trajectory graders in evals (off by default); an operator opt-in |
| `app.replica-mode` | B | infrastructure: `single` or `multi`; declares the replica topology so the startup check can warn about per-JVM state |
| `app.replica-mode-strict` | B | infrastructure: makes those startup warnings a boot failure |
| `app.routing.simple.accented-latin-simple` | B | routing rule: whether a message with accented Latin letters can be 'simple' (false, because the signal words shipped are English); a language-coverage rule of the shipped word lists, the per-workspace size limits are `routing.simple.*` in the catalog |
| `app.routing.simple.clause-boundary` | B | routing rule: regular expression where a sentence ends; language-coverage rule of the classifier |
| `app.routing.simple.max-chars` | A | `routing.simple.maxChars` |
| `app.routing.simple.max-clauses` | A | `routing.simple.maxClauses` |
| `app.routing.simple.non-latin-simple` | B | routing rule: whether a message in a non-Latin script can be 'simple' (false); same language-coverage rule |
| `app.routing.simple.signal-words.en` | B | routing rule: English words that mark a request as not simple; language data of the classifier |
| `app.sandbox.data-analysis.artifact-sweep-ms` | B | scheduler cadence: how often old charts, workbooks and scripts are deleted from object storage (600000 ms) |
| `app.scheduled-tasks.delivery-channels` | B | security allow-list: channels a scheduled task result may be delivered to; the operator's list of channels the platform is set up and permitted to send on |
| `app.scheduling.max-concurrent-locked-jobs` | B | capacity bound: scheduled jobs holding an advisory lock at once per JVM (4); database connection protection |
| `app.settings.cache-max-entries` | B | cache: (tenant, agent) override sets cached before the settings cache is cleared (20000); memory bound of this settings system |
| `app.settings.cache-ttl` | B | cache: how long a replica serves stored setting overrides from memory (10s); a change reaches other replicas within it |
| `app.settings.history-default-limit` | B | API paging: rows of setting history returned when the caller names no limit (50) |
| `app.settings.history-max-limit` | B | API paging: most setting-history rows one call returns (200) |
| `app.settings.locked-reasons.<key>` | B | settings governance: the text shown next to a key the operator pinned; part of the operator's lock mechanism |
| `app.settings.locked.<key>` | B | settings governance: pins a catalog key to the platform value for every workspace and agent; the operator's lock itself, which is why it is not a setting |
| `app.settings.reason-max-length` | B | API validation: longest reason a change may carry (500) |
| `app.sidecar.shared-secret` | B | secret: shared secret the knowledge sidecar expects from the platform; never shown or set in a console |
| `app.skills.distillation.max-episodes-scanned` | B | work bound: recent rewarded episodes read per distillation pass (500); a work bound on a background job |
| `app.skills.distillation.max-goal-chars` | B | sizing constant: characters of one example goal (160) |
| `app.skills.distillation.max-jobs-scanned` | B | work bound: recent succeeded jobs read per pass (500) |
| `app.skills.distillation.max-proposals-per-run` | B | work bound: draft skills staged per pass (5), the rest reported as skipped |
| `app.skills.distillation.max-sample-goals` | B | sizing constant: example goals kept per pattern (3) |
| `app.skills.distillation.max-sequence-length` | B | algorithm constant: tool sequences longer than 8 are ignored by the distiller |
| `app.skills.distillation.min-distinct-runs` | B | algorithm constant: distinct sessions a tool sequence must appear in before it is distilled (3) |
| `app.skills.distillation.min-episode-reward` | B | algorithm constant: reward at which an episode counts as a success (0.60) |
| `app.skills.distillation.min-sequence-length` | B | algorithm constant: tool sequences shorter than 2 are ignored |
| `app.skills.prompt.history-turns` | B | tuning constant: earlier user messages the sticky 'skills stay on' gate remembers per session (6) |
| `app.skills.prompt.max-block-chars` | A | `skills.prompt.maxBlockChars` |
| `app.skills.prompt.max-description-words` | B | sizing constant: words of a skill's description kept in the index (30), enough to keep a 'Use when...' trigger; the owner-visible skill-index size is `skills.prompt.maxBlockChars` |
| `app.skills.prompt.max-skills` | A | `skills.prompt.maxSkills` |
| `app.skills.prompt.max-tracked-sessions` | B | cache: sessions the skill gate tracks before the least recently used are forgotten (2000); memory bound |
| `app.skills.prompt.names-only-headroom` | B | sizing constant: extra room past the budget that bare skill names may use before the list is cut (2000) |
| `app.storage.legacy-unprefixed-fallback` | B | migration switch: whether storage reads may retry the bare, un-prefixed key after a tenant-scoped miss (compatibility with pre-prefix data); it crosses a tenant isolation boundary, so it is an operator migration flag |
| `app.swarm.verifier.enabled` | B | feature switch: the swarm goal verifier, one extra model call after the branches (off by default); a cost-bearing operator opt-in |
| `app.teams.service-url-allowed-hosts` | B | security allow-list: hosts a Microsoft Teams `serviceUrl` may point to before the platform replies; the SSRF guard of the Teams integration |
| `app.tool-arg-policies.enabled` | D | deferred: cleanup-owned (tool-loop and tool-argument guard text and checks) |
| `app.tools.auto-selection.max-tools-per-turn` | B\* | candidate for a later pass: most tools offered in one turn when automatic selection is on (12; 24 in the `personal` profile); tool selection lives in the kernel module `agents`, which has no settings dependency |
| `app.tools.auto-selection.unscorable-uses-full-budget` | B | language rule: a message in a non-Latin script that matches no tool gets the full tool budget instead of the no-match budget (true); a language-coverage rule of the selector, not a workspace preference |
| `app.tools.classification.confirmation-gate` | B | security policy: sends a tool that is both irreversible and consequential to the human approval gate (true); `false` opts out, so only the operator may set it |
| `app.tools.classification.confirmation-gate-exempt-tools` | B | security allow-list: tools exempt from that gate; an exemption list that only the operator may extend |
| `app.tools.classification.trifecta-enforcement` | B | security policy: what happens to an agent holding private data, untrusted content and an outbound channel at once (`off`, `warn`, `require-approval`, `block`); security policy that needs stricter-only semantics and is not a catalog entry |
| `app.tools.code.generate.max-tokens` | B | sizing bound: output tokens of the code-generation tool's call (2048); its own key because a program needs more room than a chat reply |
| `app.tools.effect-journal.enabled` | B | safety rail: records write and send tool calls while in flight so a resumed job does not repeat them (true); turning it off only loses resume-from-journal safety |
| `app.tools.mcp-discovery-cache-ttl-seconds` | B | cache: how long a replica reuses the discovered MCP tool list (300s); a latency fix for the agents screen |
| `app.tools.mcp.request-timeout-seconds` | B | timeout: seconds one request to a workspace's registered MCP server may wait (60); transport timeout |
| `app.tools.mcp.test-timeout-seconds` | B | timeout: the same for the 'test connection' action (20) |
| `app.tools.openapi.connect-timeout-seconds` | B | timeout: connect time of an OpenAPI tool call or spec fetch (10); transport timeout |
| `app.tools.openapi.max-response-bytes` | B | sizing bound: bytes of an API reply that are read (1 MiB); protects memory |
| `app.tools.openapi.max-result-chars` | B | sizing bound: characters of an API reply handed to the model (16000); the workspace-level control for tool output size is `agent.limits.toolOutputMaxChars` |
| `app.tools.openapi.max-spec-redirects` | B | security bound: redirects followed when fetching a spec (3), each re-checked against the SSRF guard |
| `app.tools.openapi.request-timeout-seconds` | B | timeout: seconds an OpenAPI call may take for its reply (30); transport timeout |
| `app.tools.output.max-chars` | A | `agent.limits.toolOutputMaxChars` |
| `app.tools.output.per-tool.<tool name>` | B | per-tool override: a tool's own output cap in place of the global one, for a tool that pages (more room) or is noisy (less); the global value is already `agent.limits.toolOutputMaxChars`, a per-tool map is operator tuning of the tool set |
| `app.tools.output.truncation-marker` | B | wording: text appended to a cut tool result; model-facing text in `application.yml` |
| `app.tutor.enabled` | B | feature switch: maps the Learn Anything tutor routes (off: 404); a product surface switched by the operator |
| `app.tutor.reminders.enabled` | B | feature switch: spaced-repetition reminders for tutor study plans (off) |
| `app.vision.enabled` | B | feature switch: server-side vision (frames and sessions); off answers 503 |
| `app.workflow.generator.max-tokens` | B | sizing bound: output tokens of the workflow generator (4096); its own key because a JSON workflow graph needs more than a chat reply |
| `app.workflow.output-dedup.ttl-minutes` | B | cache: minutes in which a repeated workflow output is suppressed (10) |
| `app.workflow.portability.enabled` | B | feature switch: import and export of workflows from other tools (off by default); an operator opt-in |
| `app.workflow.portability.max-payload-bytes` | B | sizing bound: largest workflow document accepted for import (2 MiB); request-size guard |
| `app.x402.allowed-payees` | B | security allow-list: EVM addresses an x402 payment may be made to (empty: every payment refused); money leaves the platform, so only the operator lists payees |
| `app.x402.tenant-daily-budget-usdc` | B | spend cap: most USDC one workspace may spend through x402 in 24 hours (5); a financial limit, so a workspace must not raise it |
| `code-intel.timeout-millis` | B | timeout: per-call timeout of code-intelligence retrieval and graph reads (15000 ms); transport timeout |
| `fraud-detection.hcaptcha.enabled` | B | security policy: the hCaptcha bot-defence integration (true); turning it off removes a platform protection, so only the operator may |
| `mcp.registry.base-url` | B | endpoint: base URL of the official MCP registry |
| `mcp.registry.cache-ttl-seconds` | B | cache: how long an identical registry search result page is cached (120s) |
| `mcp.registry.catalog-max-pages` | B | safety bound: pages walked per catalog refresh (300), so a broken cursor cannot loop |
| `mcp.registry.catalog-max-servers` | B | safety bound: servers held in memory from the catalog (30000) |
| `mcp.registry.catalog-page-size` | B | sizing constant: page size used when walking the whole catalog (100) |
| `mcp.registry.catalog-ttl-seconds` | B | cache: how long a fetched catalog snapshot is fresh (6 hours) |
| `mcp.registry.enabled` | B | feature switch: the 'Browse registry' search; an operator decision about egress to the registry |
| `mcp.registry.page-size` | B | UI paging: results per page of the browse UI (30) |
| `mcp.registry.read-timeout-millis` | B | timeout: read timeout for one registry page (60000 ms) |
| `mcp.registry.timeout-millis` | B | timeout: TCP connect timeout to the registry (8000 ms) |
| `mcp.registry.warm-on-startup` | B | startup behaviour: start the first catalog fetch at application startup (true) |
| `openai.api-key` | B | secret: OpenAI key of the deployment-level Whisper provider (environment variable); never shown or set in a console |
| `privacy.retention.enabled` | B | feature switch: the nightly data-retention purge (true); `false` stops all retention for every workspace, so it is an operator and compliance decision; each workspace's own retention rules are separate |
| `rl.feedback.default-rating` | B | modelling constant: stars assumed for a rating signal without one (3); part of how feedback becomes reward, which must be the same for every workspace sharing a preference export |
| `rl.feedback.edit-reward` | B | modelling constant: reward credited to a policy arm when the person edited the reply (0.0) |
| `rl.feedback.export-desirable-min-rating` | B | modelling constant: lowest rating an exported training example may carry to be labelled desirable |
| `rl.feedback.export-undesirable-max-rating` | B | modelling constant: highest rating an exported example may carry to be labelled undesirable (2) |
| `rl.feedback.resolved-reward` | B | modelling constant: reward when the person marks the conversation resolved (1.0) |
| `rl.feedback.thumbs-down-reward` | B | modelling constant: reward on a thumbs-down (0.0) |
| `rl.feedback.thumbs-up-reward` | B | modelling constant: reward on a thumbs-up (1.0) |
| `rl.feedback.unresolved-reward` | B | modelling constant: reward when the person marks the conversation not resolved (0.0) |
| `rl.policy.default-exploration-rate` | A | `rl.policy.defaultExplorationRate` |
| `rl.policy.gen-param-presets.<name>.temperature` | B | modelling constant: temperature of a generation-parameter arm (PRECISE 0.2, BALANCED 0.7, CREATIVE 1.0); the arms of the bandit are a modelling choice |
| `rl.policy.reasoning-depth-arms` | B | modelling constant: the reasoning-depth arms (FAST, CAREFUL, RIGOROUS), hardcoded in the policy store |
| `rl.policy.ucb1-exploration-constant` | A | `rl.policy.ucb1ExplorationConstant` |
| `rl.reward.clip-max` | B | modelling constant: upper clip of the composite reward fed to the bandit (1.0) |
| `rl.reward.clip-min` | B | modelling constant: lower clip of the composite reward (0.0) |
| `rl.reward.cost-weight` | B | modelling constant: default weight of the COST reward component (0.2) |
| `rl.reward.human-weight` | B | modelling constant: default weight of the HUMAN reward component (1.0) |
| `rl.reward.judge-weight` | B | modelling constant: default weight of the JUDGE reward component (0.6) |
| `rl.reward.latency-weight` | B | modelling constant: default weight of the LATENCY reward component (0.2) |
| `rl.reward.safety-veto-cap` | B | modelling constant: hard ceiling a SAFETY violation puts on the final reward (0.1); a safety floor, not a preference |
| `rl.reward.task-success-weight` | B | modelling constant: default weight of the TASK_SUCCESS reward component (1.0) |
| `rl.selection.server-turn-ref` | B | behaviour switch: whether turns without a client `turnRef` may be varied by the bandits (false); changes what the learning layer is allowed to experiment on, an operator decision |
| `spring.datasource.hikari.maximum-pool-size` | B | infrastructure: database connection pool size (10); sized to the database and the replica count |
| `tool-sandbox.connect-timeout-millis` | B | timeout: TCP connect budget to the tool-sandbox service (5000 ms) |
| `tool-sandbox.default-memory-mb` | B | sandbox default: memory ceiling applied when a tool does not name one (128 MB) |
| `tool-sandbox.default-timeout-ms` | B | sandbox default: wall clock applied when a tool does not name one (5000 ms) |
| `tool-sandbox.e2b-execution` | B | infrastructure: runs Python custom tools in the tenant's own E2B sandbox instead of the sandbox service; execution backend of the deployment |
| `tool-sandbox.max-memory-mb` | B | safety bound: hard ceiling a tenant's requested memory is clamped into (512 MB); raising it per workspace would raise the host's exposure |
| `tool-sandbox.max-source-bytes` | B | safety bound: largest tenant-authored program accepted (64 KiB) |
| `tool-sandbox.max-timeout-ms` | B | safety bound: hard ceiling a tenant's requested timeout is clamped into (15000 ms) |
