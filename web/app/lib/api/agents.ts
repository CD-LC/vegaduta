import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import { isKnownMissing, rememberMissing } from "./negativeCache";
import type { RlPolicyArm, AgentCapabilityEntry, AgentResponse, AgentGuardrailsResponse, GuardrailRuleResponse, ToolCallLogEntry, RlPolicy } from "./types";

/** How long "the health sweep has not scored this agent yet" (404) is trusted. */
export const AGENT_HEALTH_MISSING_TTL_MS = 10 * 60_000;
import type { AgentHealthScore, AgentSpecialtiesResponse, AgentSpecialty } from "../agentInsights";
import type { AgentReliabilitySummary, ResolvedTool } from "../../components/agents/AgentWorkbench/types";

/** Generation-param update body. maxTokens/temperature/topP are always sent together; the rest are optional. */
export interface AgentGenerationParamsInput {
  maxTokens: number | null;
  temperature: number | null;
  topP: number | null;
  /** integer 1..1000 or null */
  topK?: number | null;
  /** max 8 entries, each max 64 chars */
  stopSequences?: string[];
  /** -2..2 or null */
  frequencyPenalty?: number | null;
  /** -2..2 or null */
  presencePenalty?: number | null;
  seed?: number | null;
  /** true = reset all five extra sampling params server-side before applying the rest */
  clearSamplingParams?: boolean;
}

/** New optional generation fields as they appear on an agent response. */
export interface AgentSamplingFields {
  topK?: number | null;
  stopSequences?: string[] | null;
  frequencyPenalty?: number | null;
  presencePenalty?: number | null;
  seed?: number | null;
}

export interface AgentImprovementSuggestion {
  id: string;
  agentId: string;
  kind: string;
  title: string;
  rationale: string | null;
  suggestedText: string | null;
  status: string;
  appliedRef: string | null;
  createdAt: string;
  decidedAt: string | null;
  decidedBy: string | null;
}

export const agentsApi = {
  // Agents (Stage 3) + knowledge-base attach (Stage 6) + tool-call log (Stage 4)
  listAgents: () => apiFetch(`/api/agents`).then(toJson<AgentResponse[]>),
  createAgent: (payload: object) =>
    apiFetch(`/api/agents`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson<AgentResponse>),
  // Subscription Plan & GTM Strategy doc, Section 4 - a FREE tenant's only
  // way to change a locked (24h+) agent: duplicate it into a fresh,
  // editable copy. Available to every plan.
  duplicateAgent: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/duplicate`, { method: "POST" }).then(toJson<AgentResponse>),
  // Unlimited-plan capability - change which provider/model an existing
  // agent talks to, same edit-lock gating as every other after-creation
  // PATCH below (FREE-tier agents past their 24h grace window get a 403
  // with an "upgrade or duplicate" message).
  updateAgentModel: (agentId: string, providerName: string, model: string) =>
    apiFetch(`/api/agents/${agentId}/model`, {
      method: "PATCH",
      ...jsonBody({ providerName, model }),
    }).then(toJson<AgentResponse>),
  // Consumer-facing capability: "consumers should be able to edit the agent
  // prompt" - same edit-lock gating as updateAgentModel above (a locked
  // FREE-tier agent past its 24h grace window gets the same 403).
  updateAgentPrompt: (agentId: string, systemPrompt: string) =>
    apiFetch(`/api/agents/${agentId}/prompt`, {
      method: "PATCH",
      ...jsonBody({ systemPrompt }),
    }).then(toJson<AgentResponse>),
  // Direct feedback: "set a fallback provider/model so future provider
  // outages degrade gracefully instead of failing the run" - pass
  // providerName: null to clear a previously-configured fallback.
  setAgentFallback: (agentId: string, providerName: string, model: string) =>
    apiFetch(`/api/agents/${agentId}/fallback`, {
      method: "PATCH",
      ...jsonBody({ providerName, model }),
    }).then(toJson<AgentResponse>),
  // #1077 fast-follow (Master Roadmap #18, cost optimization: routing) -
  // opt this agent into RouteLLM/NotDiamond-style per-turn routing to a
  // cheaper "economy" model. Pass economyProviderName: null to clear a
  // previously-configured economy model, same shape as setAgentFallback above.
  // jevThreshold (V334, persisted field name): this agent's own bar for the
  // decision provider's "is this turn simple" second opinion - null inherits
  // the tenant policy / platform default.
  setAgentCostRouting: (agentId: string, enabled: boolean, economyProviderName: string, economyModel: string, jevThreshold?: number) =>
    apiFetch(`/api/agents/${agentId}/cost-routing`, {
      method: "PATCH",
      ...jsonBody({ enabled, economyProviderName, economyModel, jevThreshold: jevThreshold ?? null }),
    }).then(toJson<AgentResponse>),
  // Voice provider selection is a Premium feature - change which speech-to-text
  // provider an agent uses (e.g., Sidecar Whisper, Sarvam). Pass voiceModelProvider:
  // null to clear and revert to the default fallback chain. 403s on FREE plan.
  updateAgentVoiceModel: (agentId: string, voiceModelProvider: string, voiceModel: string) =>
    apiFetch(`/api/agents/${agentId}/voice-model`, {
      method: "PATCH",
      ...jsonBody({ voiceModelProvider, voiceModel }),
    }).then(toJson<AgentResponse>),
  // TTS provider selection (Stage 2, 2026-08-09) - output-side twin of
  // updateAgentVoiceModel above: change which text-to-speech provider an
  // agent's voice REPLIES are synthesized through (e.g. ElevenLabs, Sarvam).
  // Pass ttsProvider: null to clear and revert to the default fallback
  // chain. 403s on FREE plan, same as the voice/video provider endpoints.
  updateAgentTtsModel: (agentId: string, ttsProvider: string, ttsModel: string) =>
    apiFetch(`/api/agents/${agentId}/tts-model`, {
      method: "PATCH",
      ...jsonBody({ ttsProvider, ttsModel }),
    }).then(toJson<AgentResponse>),
  // Video provider selection is a Premium feature - change which video-to-text
  // provider an agent uses for video transcription (e.g., Sidecar Whisper, OpenAI Whisper).
  // Pass videoModelProvider: null to clear and revert to the default fallback chain. 403s on FREE plan.
  updateAgentVideoModel: (agentId: string, videoModelProvider: string, videoModel: string) =>
    apiFetch(`/api/agents/${agentId}/video-model`, {
      method: "PATCH",
      ...jsonBody({ videoModelProvider, videoModel }),
    }).then(toJson<AgentResponse>),
  // Direct feedback: "give this as an extra feature instead of [changing]
  // the simple workflow... make it available in premium and unlimited" -
  // one-click Consumer/Professional/Expert preset. 403s on FREE plan (same
  // "upgrade your plan" shape as every other Premium-gated call here).
  setAgentTier: (agentId: string, tier: string) =>
    apiFetch(`/api/agents/${agentId}/tier`, {
      method: "PATCH",
      ...jsonBody({ tier }),
    }).then(toJson<AgentResponse>),
  // Unlimited-plan capability - hard delete, reachable even for a
  // FREE-tier agent past its edit-lock window ("chat, run, and delete are
  // still available"). 409 if the agent is still connected to a channel.
  deleteAgent: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}`, { method: "DELETE" }),
  setAgentKnowledgeBase: (agentId: string, enabled: boolean) =>
    apiFetch(`/api/agents/${agentId}/knowledge-base`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson<AgentResponse>),
  // The plug-and-play capability catalog (replaced the per-provider
  // setAgentSarvam/setAgentE2b/setAgentXaiServerTools/setAgentCodeWorkspace
  // calls + their dedicated endpoints). The list is whatever the backend's
  // AgentCapability registry says applies to THIS agent - the UI renders it
  // verbatim and toggles by key, so a new capability needs zero frontend
  // change. Keys are server-defined (e.g. "sarvam-tools", "code-workspace").
  listAgentCapabilities: (agentId: string): Promise<AgentCapabilityEntry[]> =>
    apiFetch(`/api/agents/${agentId}/capabilities`).then(toJson<AgentCapabilityEntry[]>),
  setAgentCapability: (agentId: string, capabilityKey: string, enabled: boolean) =>
    apiFetch(`/api/agents/${agentId}/capabilities/${encodeURIComponent(capabilityKey)}`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson),
  // Migration V103 - opt-in per-agent repo allow-list for the code-intel
  // write/execute tools. Unlike setAgentKnowledgeAccess/setAgentToolAccess,
  // an empty/cleared array here means DENY-ALL, not unrestricted - see
  // AgentCodeIntelRepoAccessUpdateRequest's javadoc. Only matters once the
  // "code-workspace" capability has been enabled (setAgentCapability).
  setAgentCodeIntelRepoAccess: (agentId: string, allowedCodeIntelRepoIds: string[]) =>
    apiFetch(`/api/agents/${agentId}/code-intel-repo-access`, {
      method: "PATCH",
      ...jsonBody({ allowedCodeIntelRepoIds }),
    }).then(toJson<AgentResponse>),
  // Shared cross-agent memory tier (#847) - explicit per-agent opt-in, same
  // shape as setAgentKnowledgeBase/setAgentCapability above. When true, this
  // agent both contributes to and automatically recalls from the tenant-wide
  // shared memory pool, alongside - never instead of - its own private memory.
  setAgentShareMemory: (agentId: string, enabled: boolean) =>
    apiFetch(`/api/agents/${agentId}/share-memory`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson<AgentResponse>),
  // Agent Intelligence research doc (July 2026) - opt an existing agent into
  // rigorous, independently-verified tool calls. One of FAST/CAREFUL/
  // RIGOROUS/PLANNED - never null, defaults to FAST until changed here.
  setAgentReasoningDepth: (agentId: string, reasoningDepth: string) =>
    apiFetch(`/api/agents/${agentId}/reasoning-depth`, {
      method: "PATCH",
      ...jsonBody({ reasoningDepth }),
    }).then(toJson<AgentResponse>),
  // #815 Governance follow-up - opt an existing agent into a stricter or
  // looser autonomy posture. One of SUPERVISED/SEMI_AUTONOMOUS/
  // FULLY_AUTONOMOUS - never null, defaults to SEMI_AUTONOMOUS until changed
  // here. Drives the tool-call step ceiling and whether tool calls need
  // human sign-off regardless of the tenant's per-action-type approval
  // policy - see AgentAutonomyTier's javadoc.
  setAgentAutonomyTier: (agentId: string, autonomyTier: string) =>
    apiFetch(`/api/agents/${agentId}/autonomy-tier`, {
      method: "PATCH",
      ...jsonBody({ autonomyTier }),
    }).then(toJson<AgentResponse>),
  // Per-agent run cap (core AgentRunBudgetController). The body carries the
  // platform cap, the agent's own cap, the effective cap, the highest value a
  // save accepts and a per-sub-agent cost estimate - all JSON numbers. The cap
  // bounds delegation tools and deep research only, and is an estimate, not
  // billing. Quiet on 404 so an older core (no route) hides the field; quiet
  // on 403/5xx too, because the field hides on ANY read failure and it is
  // read on every agent select - a toast there would be noise for a control
  // that simply is not shown.
  getAgentRunBudget: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/run-budget`).then(toJsonQuietOn([403, 404, 500, 502, 503, 504])),
  // runBudgetUsd null clears the agent's cap (back to the platform cap). The
  // server rejects rather than clamps, so 400 (bad value), 409 (per-agent
  // caps switched off), 423 (agent is edit-locked) and 404 (caller may not
  // edit this agent) are shown inline by the field, not as a global toast.
  setAgentRunBudget: (agentId: string, runBudgetUsd: number | null) =>
    apiFetch(`/api/agents/${agentId}/run-budget`, {
      method: "PATCH",
      ...jsonBody({ runBudgetUsd }),
    }).then(toJsonQuietOn([400, 404, 409, 423])),

  // Agent lifecycle hooks (core/agents hooks, AgentHooksController). The GET carries this agent's own hooks, the
  // workspace defaults that also apply, the allowed webhook hosts and whether the deployment enforces hooks at all.
  // Quiet on 404/403/5xx so an older core (no route) just hides the card, like the run-cap field above.
  getAgentHooks: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/hooks`).then(toJsonQuietOn([403, 404, 500, 502, 503, 504])),
  // Replaces the whole list ([] clears it). 400 (a field-level message), 403 (needs the guardrails permission), 404,
  // 409 (hooks switched off on this deployment) and 423 (edit-locked) are shown inline by the card, not as a toast.
  setAgentHooks: (agentId: string, hooks: unknown[]) =>
    apiFetch(`/api/agents/${agentId}/hooks`, {
      method: "PUT",
      ...jsonBody({ hooks }),
    }).then(toJsonQuietOn([400, 403, 404, 409, 423])),

  // Per-agent content guardrails (core/agents AgentGuardrailController). The
  // GET is the whole picker in one call: what this agent has enabled, what its
  // tenant's plan makes available, and what a higher plan would unlock - the
  // response was always shaped for a checkbox list with upgrade locks, there
  // just was no screen rendering it until /admin/guardrails and the agent
  // workbench card.
  getAgentGuardrails: (agentId: string) => apiFetch(`/api/agents/${agentId}/guardrails`).then(toJson<AgentGuardrailsResponse>),
  // Sends the complete desired set, not a delta - the server replaces
  // Agent.enabledGuardrails wholesale (AgentGuardrailService#updateAgentGuardrails),
  // so [] is how a caller turns everything off. A 403 here means the set
  // contained something this plan does not include.
  setAgentGuardrails: (agentId: string, guardrails: unknown) =>
    apiFetch(`/api/agents/${agentId}/guardrails`, {
      method: "PATCH",
      ...jsonBody({ guardrails }),
    }).then(toJson<AgentGuardrailsResponse>),
  // Agent health snapshot (core AgentImprovementController). Resolves to null
  // on 404 - the hourly sweep simply has not scored this agent yet, which is
  // a normal state, not an error.
  // The 404 is remembered for a while (negativeCache.ts): the chip is mounted
  // on every agent view, and re-asking on each mount logged a 404 in the
  // console per agent per page load. The sweep runs hourly, so a few minutes
  // of "not scored yet" staleness costs nothing.
  getAgentHealthScore: (agentId: string): Promise<AgentHealthScore | null> => {
    const key = `agent-health:${agentId}`;
    if (isKnownMissing(key)) return Promise.resolve(null);
    return apiFetch(`/api/agents/${agentId}/improvements/health`).then((res) => {
      if (res.status !== 404) return toJson<AgentHealthScore>(res);
      rememberMissing(key, AGENT_HEALTH_MISSING_TTL_MS);
      return null;
    });
  },
  // Crew handoff: the roles this agent is registered as a specialist for.
  // Improvement suggestions (AgentImprovementController): list, generate
  // (spends LLM budget, EDITOR), accept, dismiss.
  listAgentImprovements: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/improvements`).then(toJson<AgentImprovementSuggestion[]>),
  generateAgentImprovements: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/improvements/generate`, { method: "POST" }).then(
      toJson<AgentImprovementSuggestion[]>
    ),
  acceptAgentImprovement: (agentId: string, suggestionId: string) =>
    apiFetch(`/api/agents/${agentId}/improvements/${suggestionId}/accept`, { method: "POST" }).then(
      toJson<AgentImprovementSuggestion>
    ),
  dismissAgentImprovement: (agentId: string, suggestionId: string) =>
    apiFetch(`/api/agents/${agentId}/improvements/${suggestionId}/dismiss`, { method: "POST" }).then(
      toJson<AgentImprovementSuggestion>
    ),
  getAgentSpecialties: (agentId: string): Promise<AgentSpecialtiesResponse> =>
    apiFetch(`/api/agents/${agentId}/specialties`).then(toJson<AgentSpecialtiesResponse>),
  // Replaces the agent's whole set of roles (PUT, needs manage-agents).
  setAgentSpecialties: (agentId: string, specialties: AgentSpecialty[]): Promise<AgentSpecialtiesResponse> =>
    apiFetch(`/api/agents/${agentId}/specialties`, {
      method: "PUT",
      ...jsonBody({ specialties }),
    }).then(toJson<AgentSpecialtiesResponse>),
  // Distinct role tags already used in this workspace (autocomplete roster).
  listAgentSpecialtyTags: (): Promise<string[]> => apiFetch(`/api/agents/specialties/tags`).then(toJson<string[]>),

  // Tenant-authored guardrail rules (core/guardrails GuardrailRuleController).
  // The list returns BOTH the platform's seeded DEFAULT_TEMPLATE presets (every
  // plan, read-only, never editable by anyone) and this workspace's own CUSTOM
  // rules - ruleType on each row is what tells them apart. Reads are open to
  // every member; create/update/delete are tenant-admin (SecurityConfig) AND
  // Premium-or-above (GuardrailRuleService), so a write can 403 for either
  // reason and the panel reports the server's own message rather than guessing
  // which gate fired.
  listGuardrailRules: () => apiFetch("/api/guardrails/rules").then(toJson<GuardrailRuleResponse[]>),
  createGuardrailRule: (rule: unknown) =>
    apiFetch("/api/guardrails/rules", {
      method: "POST",
      ...jsonBody(rule),
    }).then(toJson<GuardrailRuleResponse>),
  updateGuardrailRule: (id: string, rule: unknown) =>
    apiFetch(`/api/guardrails/rules/${id}`, {
      method: "PUT",
      ...jsonBody(rule),
    }).then(toJson<GuardrailRuleResponse>),
  // 204 No Content - deliberately not .then(toJson), which would throw on an
  // empty body (see deleteAgent and the other DELETEs in this file).
  deleteGuardrailRule: (id: string) => apiFetch(`/api/guardrails/rules/${id}`, { method: "DELETE" }),
  // Face recognition (knowledge-base photo enrollment) - explicit per-agent
  // opt-in, same shape as setAgentShareMemory/setAgentReasoningDepth above.
  // Off by default; enrollFace/listFaceEnrollments below only make sense once
  // this has been turned on for the agent.
  setAgentBiometricConsent: (agentId: string, enabled: boolean) =>
    apiFetch(`/api/agents/${agentId}/biometric-consent`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson<AgentResponse>),
  listToolCalls: (agentId: string) => apiFetch(`/api/agents/${agentId}/tool-calls`).then(toJson<ToolCallLogEntry[]>),

  // Task #959 ("Agent performance: response time + reliability + failure
  // handling") - per-agent reliability panel: average response time,
  // success rate, tool-call reliability, fallback usage, and a short recent-
  // failure list. Backed by AgentReliabilityService, which reads straight
  // off the same llm_call_log/tool_call_log rows the rest of this file's
  // telemetry wrappers already use - see that service's javadoc.
  getAgentReliability: (agentId: string) => apiFetch(`/api/agents/${agentId}/reliability`).then(toJson<AgentReliabilitySummary>),

  // Reinforcement-learning feedback loop (Agent RL Build Plan) - the frontend
  // half of a per-turn reward signal a contextual-bandit policy learns from.
  //
  // sendTurnFeedback is deliberately fire-and-forget: it resolves to a plain
  // { ok, status } and NEVER throws, so an in-chat thumbs-up/down can't crash
  // the Playground and - unlike every toJson-routed call above - does NOT raise
  // the global error toast on a transient RL-endpoint failure. TurnFeedback.tsx
  // reads `ok` to revert its optimistic selection and show a quiet "not saved"
  // hint instead. body is
  // { sessionId, turnRef, signal, rating?, comment?, editedAnswer? }.
  sendTurnFeedback: (agentId: string, body: unknown) =>
    apiFetch(`/api/agents/${agentId}/turns/feedback`, {
      method: "POST",
      ...jsonBody(body),
    })
      .then((res) => ({ ok: res.ok, status: res.status }))
      .catch(() => ({ ok: false, status: 0 })),

  // Agent Learning dashboard read - reward trend, feedback tallies, the
  // bandit arms table, and current per-dimension policy state. Returns null
  // (rather than toJson's toast-then-throw) on any non-2xx, so the dashboard
  // degrades to a calm "no learning data yet" state while the endpoint is
  // still 404ing, instead of toasting on every poll.
  getRlSummary: (agentId: string) =>
    apiFetch(`/api/rl/agents/${agentId}/summary`).then((res) => (res.ok ? toJson(res) : null)),

  // The authoritative per-dimension policy list backing the admin controls -
  // same calm-degradation posture as getRlSummary (null on non-2xx).
  getRlPolicies: (agentId: string) =>
    apiFetch(`/api/rl/agents/${agentId}/policies`).then((res) => (res.ok ? toJson<RlPolicy[]>(res) : null)),

  // Admin-only policy write (enable/disable exploration + exploration rate for
  // one bandit dimension). Unlike the two reads above this DOES go through
  // toJson - a tenant-admin who just clicked "Save" should see a real error if
  // it fails, same posture as every other admin mutation in this file. body is
  // { enabled, explorationRate }.
  updateRlPolicy: (agentId: string, dimension: string, body: unknown) =>
    apiFetch(`/api/rl/agents/${agentId}/policies/${encodeURIComponent(dimension)}`, {
      method: "PUT",
      ...jsonBody(body),
    }).then(toJson<RlPolicy>),

  // ---- RL bandit ARMS (the option set a policy explores between) ----
  // A policy with zero arms cannot select anything, so enabling it is a silent
  // no-op - which is exactly what happened in production (MODEL_ROUTE enabled,
  // zero arms, 97 episodes, 0 attributed). These three endpoints back the
  // admin arms editor in AgentLearningPanel so that state is both visible and
  // fixable from the console.
  //
  // Read: same calm-degradation posture as getRlPolicies (null on non-2xx, no
  // toast) so a backend that doesn't expose the route yet renders as
  // "unavailable" rather than toasting on every agent switch. Callers MUST
  // distinguish null (couldn't read) from [] (read fine, genuinely no arms) -
  // only the latter is the "this can't learn" diagnosis.
  listRlArms: (agentId: string, dimension: string): Promise<RlPolicyArm[] | null> =>
    apiFetch(`/api/rl/agents/${agentId}/policies/${encodeURIComponent(dimension)}/arms`).then((res) =>
      res.ok ? toJson<RlPolicyArm[]>(res) : null
    ),

  // Writes go through toJson deliberately. The backend validates that the
  // referenced provider/model is actually usable by this tenant (e.g. a BYOK
  // tenant with no credential for that provider) and returns a 4xx whose
  // { error } body explains why - toJson surfaces that verbatim as the thrown
  // Error's message, which the arms editor pins inline next to the form rather
  // than replacing with a generic "couldn't add arm".
  createRlArm: (
    agentId: string,
    dimension: string,
    body: { armKey: string; armConfigJson: string; contextBucket?: string | null }
  ): Promise<RlPolicyArm> =>
    apiFetch(`/api/rl/agents/${agentId}/policies/${encodeURIComponent(dimension)}/arms`, {
      method: "POST",
      ...jsonBody(body),
    }).then(toJson<RlPolicyArm>),

  // 204 on success - toJson already maps that to null.
  deleteRlArm: (agentId: string, dimension: string, armId: string): Promise<null> =>
    apiFetch(
      `/api/rl/agents/${agentId}/policies/${encodeURIComponent(dimension)}/arms/${encodeURIComponent(armId)}`,
      { method: "DELETE" }
    ).then(toJson<null>),

  // NDJSON trajectory export (V395) - one line per episode with steps inlined
  // (action, tool args, result, answer, reward): the training-format view the
  // offline optimizer consumes. The route is pinned to PERM_MANAGE_AGENTS
  // (raw conversation text), and non-admins shouldn't see the button that
  // calls it - but if it somehow 403s anyway, toJson surfaces the backend's
  // { error } body verbatim like every other admin call in this file.
  getRlTrajectoriesJsonl: (agentId: string, limit = 500): Promise<string> =>
    apiFetch(`/api/rl/agents/${agentId}/export/trajectories?limit=${limit}`).then(async (res: Response) => {
      if (!res.ok) {
        await toJson(res); // throws with the shared error handling
      }
      return res.text();
    }),

  // Long-term agent memory ("smarter, more dynamic agents" roadmap) - read-only
  // list + forget-one, scoped by subjectKey (see auth.getUserId()'s comment
  // for why that's the JWT sub claim, not the display name, when security is
  // on). Returns [] rather than erroring when the "knowledge" profile is off
  // (AgentController's own graceful-degradation posture), so the panel can
  // render "no memories yet" instead of an error box in that case.
  listMemories: (agentId: string, subjectKey: string) =>
    apiFetch(`/api/agents/${agentId}/memories?subjectKey=${encodeURIComponent(subjectKey)}`).then(toJson<unknown[]>),
  forgetMemory: (agentId: string, memoryId: string) =>
    apiFetch(`/api/agents/${agentId}/memories/${memoryId}`, { method: "DELETE" }).then(toJson),

  // Autonomy/Guardrails Strategy Stage A - opt-in per-agent tool allow-list.
  // Empty/absent array clears the restriction (AgentToolAccessUpdateRequest's
  // semantics) - the panel sends [] rather than omitting the field so
  // "cleared" is an explicit user action, not an accidental omission.
  setAgentToolAccess: (agentId: string, allowedToolNames: string[]) =>
    apiFetch(`/api/agents/${agentId}/tool-access`, {
      method: "PATCH",
      ...jsonBody({ allowedToolNames }),
      // 400 is quiet: a block-mode policy rejection is shown inline on the Tools tab (and any other 400 by the
      // caller's own error line), so the global toast would only repeat it.
    }).then(toJsonQuietOn<AgentResponse>([400])),

  // Lethal-trifecta check for the agent's CURRENT allow-list
  // (LethalTrifectaDetector.Report: privateDataTools / untrustedContentTools /
  // egressTools / unclassifiedTools - four string arrays), plus what core does
  // about it: enforcement (off|warn|require-approval|block), enforced, gated.
  // A block-mode PATCH of the allow-list answers 400 with the policy message
  // (see isTrifectaBlockError in AgentTrifectaWarning). Read it from this
  // GET, never from the PATCH's X-Agent-Warning header: that header is not
  // CORS-exposed. Quiet on 404 so an older core (no route) or an agent the
  // caller cannot see hides the warning instead of raising a toast. Quiet on
  // 403/5xx as well: the warning hides on ANY failure and is read on every
  // agent select, so an advisory that is not shown must not toast either.
  getAgentToolTrifecta: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/tool-access/trifecta`, { skipGetCache: true }).then(
      toJsonQuietOn([403, 404, 500, 502, 503, 504])
    ),

  // Stage B - the dropdown tool picker: every tool this agent could
  // currently be granted (built-ins + whatever MCP servers are enabled),
  // resolved live server-side rather than a static frontend list, since MCP
  // tool names are only known once their server responds.
  // 20s ceiling (see apiFetch's own comment) - direct feedback: this used to
  // spin on "Loading available tools…" indefinitely if an MCP server was
  // slow/unreachable, with nothing bounding the wait from the browser side.
  listAvailableTools: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/available-tools`, { timeoutMs: 20000 }).then(toJson<ResolvedTool[]>),

  // Knowledge Stage F1 - opt-in per-agent knowledge-collection allow-list,
  // same empty-clears-restriction semantics as setAgentToolAccess above.
  setAgentKnowledgeAccess: (agentId: string, allowedKnowledgeCollectionIds: string[]) =>
    apiFetch(`/api/agents/${agentId}/knowledge-access`, {
      method: "PATCH",
      ...jsonBody({ allowedKnowledgeCollectionIds }),
    }).then(toJson<AgentResponse>),

  // Opt-in
  // per-agent collaborator list ("specialists working together"), same
  // empty-clears-restriction semantics as setAgentToolAccess/
  // setAgentKnowledgeAccess above. Each id must be another existing agent in
  // this tenant - the backend rejects self-reference or an unknown id.
  setAgentCollaborators: (agentId: string, collaboratorAgentIds: string[]) =>
    apiFetch(`/api/agents/${agentId}/collaborators`, {
      method: "PATCH",
      ...jsonBody({ collaboratorAgentIds }),
    }).then(toJson<AgentResponse>),

  // Direct feedback: "customer should be able to select the temperature, p
  // and all other details" - tunes an existing agent's generation params
  // after creation (creation-time values still go through createAgent's own
  // payload above). All three always sent together - see
  // AgentGenerationParamsUpdateRequest's javadoc for why.
  // The optional sampling extras (topK, stopSequences, frequencyPenalty,
  // presencePenalty, seed) travel on the same call; undefined is omitted from
  // the JSON body, null clears a nullable field.
  setAgentGenerationParams: (
    agentId: string,
    { maxTokens, temperature, topP, topK, stopSequences, frequencyPenalty, presencePenalty, seed, clearSamplingParams }: AgentGenerationParamsInput,
  ) =>
    apiFetch(`/api/agents/${agentId}/generation-params`, {
      method: "PATCH",
      ...jsonBody({ maxTokens, temperature, topP, topK, stopSequences, frequencyPenalty, presencePenalty, seed, clearSamplingParams }),
    }).then(toJson<AgentResponse>),

  // Master Roadmap #18 - exact-match LLM response caching opt-in. Body {enabled};
  // returns the updated AgentResponse. Same edit-lock gating as other PATCHes.
  setAgentResponseCaching: (agentId: string, enabled: boolean) =>
    apiFetch(`/api/agents/${agentId}/response-caching`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson<AgentResponse>),
  // Separate, stronger opt-in: embedding-similarity response caching.
  setAgentSemanticResponseCaching: (agentId: string, enabled: boolean) =>
    apiFetch(`/api/agents/${agentId}/semantic-response-caching`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson<AgentResponse>),

  // Per-agent Simple/Advanced override - null clears it, back to inheriting
  // the tenant's current default (see getTenantSettings above).
  setAgentExperienceMode: (agentId: string, experienceMode: string) =>
    apiFetch(`/api/agents/${agentId}/experience-mode`, {
      method: "PATCH",
      ...jsonBody({ experienceMode }),
    }).then(toJson<AgentResponse>),

  // Direct feedback: "in agents there should be simple and manual tuning
  // selection - in simple it automatically selects the temp, tokens, p
  // based on the given system prompt". Switching to SIMPLE triggers a
  // backend LLM call that re-infers and overwrites this agent's generation
  // params - the returned AgentResponse already reflects the new values, so
  // the panel doesn't need a separate refetch.
  setAgentTuningMode: (agentId: string, tuningMode: string) =>
    apiFetch(`/api/agents/${agentId}/tuning-mode`, {
      method: "PATCH",
      ...jsonBody({ tuningMode }),
    }).then(toJson<AgentResponse>),

  // Per-agent tool ARGUMENT policies (core ToolArgPolicyController). The body
  // carries enabled (the deployment flag), the stored policy document, and the
  // size limits a save is validated against. Quiet on 403/404/5xx: the card
  // hides on any read failure and is read on every agent select, so a toast
  // there would be noise for a control that simply is not shown.
  getAgentToolPolicies: (agentId: string) =>
    apiFetch(`/api/agents/${agentId}/tool-policies`).then(toJsonQuietOn([403, 404, 500, 502, 503, 504])),
  // PUT replaces the whole document; an empty `policies` list clears it. The
  // server rejects (400) with a precise reason, 409 when the feature is off
  // on this deployment, 423 when the agent is edit-locked, 404 when the
  // caller may not edit it - the card shows those inline, not as a toast.
  setAgentToolPolicies: (agentId: string, document: unknown) =>
    apiFetch(`/api/agents/${agentId}/tool-policies`, {
      method: "PUT",
      ...jsonBody(document),
    }).then(toJsonQuietOn([400, 404, 409, 423])),
};
