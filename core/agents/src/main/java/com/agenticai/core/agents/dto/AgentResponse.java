package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.EdgeRoutingPolicy;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.modality.TtsModelProvider;
import com.agenticai.core.providers.modality.VideoModelProvider;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import com.agenticai.core.security.ExperienceMode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record AgentResponse(
    UUID id,
    String name,
    String systemPrompt,
    ProviderName providerName,
    String model,
    // Direct feedback: "set a fallback provider/model so future provider
    // outages degrade gracefully instead of failing the run" - both null
    // means "no fallback configured" (today's unchanged behavior: a
    // provider failure surfaces to the user/workflow as a hard error). See
    // Agent.setFallbackProvider's javadoc and AgentChatService's
    // callWithProviderFallback for the read path.
    ProviderName fallbackProviderName,
    String fallbackModel,
    // Fallback observability follow-up (#955) - "per-agent fallback counter
    // on LLM telemetry." fallbackTriggeredCount/fallbackFailedCount are 0
    // for every agent that has never had its fallback fire; lastFallbackAt
    // is null until the first time it does. See Agent's own field javadoc
    // for why these are only ever mutated via atomic repository UPDATEs.
    long fallbackTriggeredCount,
    long fallbackFailedCount,
    Instant lastFallbackAt,
    int maxTokens,
    double temperature,
    double topP,
    // Direct feedback: "in agents there should be simple and manual tuning
    // selection" - never null, see TuningMode's javadoc. SIMPLE means
    // temperature/topP/maxTokens above were auto-inferred from systemPrompt
    // rather than set directly; the web console hides the manual sliders
    // and shows these as a read-only "auto-tuned" summary when this is SIMPLE.
    TuningMode tuningMode,
    boolean knowledgeBaseEnabled,
    // "Like every other llm as anthropic, gemini - user should be able to
    // attach sarvam to agents...keep it as like other llm providers" -
    // explicit per-agent opt-in for Sarvam's translate/transcribe/speak
    // tools, same "off by default" shape as knowledgeBaseEnabled above.
    // Still needs the tenant to have activated a Sarvam credential too -
    // see AgentToolProvider#resolveSarvamTools.
    boolean sarvamEnabled,
    // Master Roadmap 2026 item #14 fast-follow (#1071) - explicit per-agent
    // opt-in for the E2B run_code tool, same "off by default" shape as
    // sarvamEnabled above. Still needs the tenant to have an ACTIVE E2B
    // credential + configured code-execution template too - see
    // AgentToolProvider#resolveE2bTools.
    boolean e2bEnabled,
    // Wires AgentToolProvider#resolveCodeWorkspaceTools - explicit per-agent
    // opt-in for the code-intel write/execute tool surface (create/apply/
    // run/diff/delete_code_workspace), same "off by default" shape as
    // e2bEnabled above. This flag alone is not enough - see
    // allowedCodeIntelRepoIds below, which (unlike
    // allowedKnowledgeCollectionIds) is deny-all when empty.
    boolean codeWorkspaceEnabled,
    // Master Roadmap 2026 item #18 (cost optimization) - explicit per-agent
    // opt-in for exact-match LLM response caching, same "off by default"
    // shape as sarvamEnabled above. See Agent.responseCachingEnabled's
    // javadoc for why this is opt-in rather than automatic.
    boolean responseCachingEnabled,
    // Gated semantic (embedding-similarity) response caching - a SEPARATE,
    // stronger opt-in than responseCachingEnabled above, never implied by it.
    // See Agent.semanticResponseCachingEnabled / SemanticResponseCacheService.
    boolean semanticResponseCachingEnabled,
    // #1077 fast-follow (Master Roadmap #18, cost optimization: routing) -
    // the "routing" half response-caching's own javadoc deliberately left
    // open. economyProviderName null means "not configured" regardless of
    // costRoutingEnabled's value - see Agent.setCostRouting's javadoc for
    // why the two are allowed to be independently set (enabled=true with no
    // economy model configured yet is a valid, no-op state, not an error).
    boolean costRoutingEnabled,
    ProviderName economyProviderName,
    String economyModel,
    // Per-agent Jev sensitivity override (V334) - null inherits the tenant
    // jev_policy.simple_threshold, else the platform default. Only consulted
    // when costRoutingEnabled and a Jev key resolves for the tenant.
    Double costRoutingJevThreshold,
    // Shared cross-agent memory tier (#847) - explicit per-agent opt-in,
    // same "off by default" shape as knowledgeBaseEnabled/sarvamEnabled
    // above. When true, this agent both contributes to and automatically
    // recalls from the tenant-wide shared memory pool (TenantMemoryService),
    // alongside - never instead of - its own private memory.
    boolean shareMemoryWithTenant,
    // Face recognition (2026-08-06) - explicit per-agent consent gate for
    // BOTH face-enrollment (knowledge-base photo uploads) and on-device
    // face-matching (camera turns). See Agent.biometricConsentEnabled's
    // javadoc for why this is a stricter opt-in than the cost/scope flags
    // above - the underlying data is biometric/special-category.
    boolean biometricConsentEnabled,
    // Edge Wave 2 (EDGE-B, migration V190) - server-declared routing policy
    // for on-device turns. Null means "no policy, device-local preferences
    // decide" (the pre-existing behavior for every agent) - see
    // EdgeRoutingPolicy's javadoc, incl. the honest client-side-enforcement
    // limitation. Set via PATCH /api/agents/{id}/edge-routing-policy.
    EdgeRoutingPolicy edgeRoutingPolicy,
    // Autonomy/Guardrails Strategy Stage A - empty means "no restriction,
    // every normally-resolved tool is available", not "no tools at all".
    Set<String> allowedToolNames,
    // Knowledge Stage F1 - empty means "no restriction, every collection
    // this tenant has is searchable", not "no collections at all".
    Set<UUID> allowedKnowledgeCollectionIds,
    // Migration V103 - empty means "no repo granted" (deny-all), the
    // OPPOSITE convention from allowedKnowledgeCollectionIds above - see
    // Agent.allowedCodeIntelRepoIds' javadoc. Only matters once
    // codeWorkspaceEnabled is true.
    Set<String> allowedCodeIntelRepoIds,
    // Null means "inherit the tenant's current default" - see
    // Agent.experienceMode's javadoc. The web console resolves this against
    // GET /api/tenant/settings' experienceMode to decide whether to show
    // this agent's manual tool/knowledge editors.
    ExperienceMode experienceMode,
    // Agent Intelligence research doc (July 2026) - never null, see
    // ReasoningDepth's javadoc. FAST unless a tenant-admin opted this agent
    // into CAREFUL/RIGOROUS via PATCH /api/agents/{id}/reasoning-depth.
    ReasoningDepth reasoningDepth,
    // #815 Governance follow-up - never null, see AgentAutonomyTier's
    // javadoc. SEMI_AUTONOMOUS unless a tenant-admin opted this agent into
    // SUPERVISED/FULLY_AUTONOMOUS via PATCH /api/agents/{id}/autonomy-tier.
    AgentAutonomyTier autonomyTier,
    // Empty means "no collaborators, no delegation tool offered" (today's
    // unchanged behavior for every pre-existing agent), not "cannot ever
    // delegate" - see Agent.collaboratorAgentIds' javadoc.
    Set<UUID> collaboratorAgentIds,
    Instant createdAt,
    Instant updatedAt,
    // Multi-provider voice/video/text architecture foundation (migration V148+).
    // voiceModelProvider nullable - when null, VoiceCallController falls back to
    // the hardcoded default chain. When set explicitly, this agent's voice turns
    // route through the selected provider. Voice provider selection is a Premium feature.
    VoiceModelProvider voiceModelProvider,
    String voiceModel,
    // Multi-provider video/video-to-text foundation (migration V149+).
    // videoModelProvider nullable - when null, video processing falls back to the
    // default chain. When set explicitly, this agent's video turns route through
    // the selected provider. Video provider selection is a Premium feature.
    VideoModelProvider videoModelProvider,
    String videoModel,
    // Text-to-speech (TTS) provider selection - Stage 2 of the multi-provider
    // modality architecture (migration V196), the output-side twin of
    // voiceModelProvider/voiceModel above. ttsProvider nullable - when null,
    // VoiceCallController falls back to the existing hardcoded
    // ElevenLabs-then-Sarvam chain. When set explicitly, this agent's voice
    // replies are synthesized through the selected provider first. Same
    // Premium-feature gating as voiceModelProvider - see AgentService#create/
    // #updateTtsModel.
    TtsModelProvider ttsProvider,
    String ttsModel,
    // Subscription Plan & GTM Strategy doc, Section 4 - server-computed via
    // EditLockPolicy so the web console never has to duplicate the 24h
    // grace-window/plan business logic itself: false means every mutating
    // PATCH endpoint for this agent will reject with 403 (chat/run/view/
    // delete are unaffected either way). Always true for PREMIUM/UNLIMITED.
    boolean editable,
    // Multimodal playground contract - which attachment kinds this agent's
    // currently-resolved provider+model accepts as chat input, plus the two
    // hard inline-attachment limits. Resolved by ModelModalitiesService (see
    // its javadoc for the config-override > catalog > name-heuristics order
    // and honest limits); null only for internal/non-request construction
    // paths that have no service in scope (AgentResponse.from(agent[,
    // editable]) - every HTTP response built through AgentService carries a
    // real value).
    AgentModalitiesView modalities,
    // Workflow -> Agent structured parameter passing (migration V170) - read-only,
    // auto-generated by AgentSchemaService whenever this agent's tool/knowledge/model
    // scope changes (see AgentService's schemaService.refresh(agent) call sites). Both
    // null for any agent that predates this feature and hasn't been re-saved since -
    // a workflow's Agent node config UI treats null the same as "no structured mapping
    // available yet, plain-text mode only".
    String inputSchemaJson,
    String outputSchemaJson,
    // xAI Agent Tools API (2026-09-23, migration V239) - explicit per-agent
    // opt-in for xAI's server-side tools (web_search/x_search/code_execution)
    // on GROK-provider agents; ignored by every other provider path. See
    // Agent.xaiServerToolsEnabled.
    boolean xaiServerToolsEnabled,
    // Extra sampling controls (migration V331) - null / empty = provider default.
    Integer topK,
    java.util.List<String> stopSequences,
    Double frequencyPenalty,
    Double presencePenalty,
    Integer seed,
    // Audit trail (migration V151 created_by / V393 updated_by) - the
    // creator's and last editor's subject strings. Both nullable: pre-V151
    // agents have no createdBy, and updatedBy is null until the first
    // versioned save after V393 or for writers with no request context.
    String createdBy,
    String updatedBy,
    // Caller's effective share level on this agent: "EDITOR" (owner, admin or EDITOR grant),
    // "RUNNER" or "VIEWER". Null when not resolved (internal construction paths, grant-based MCP
    // reads). Display hint only - every endpoint still enforces its own level.
    String accessLevel,
    // The agent's own runtime-limit overrides (migration V671): tool-step ceiling, timeouts, tool-output
    // cap, retrieval top-k/min-score, continuation passes. Fields the agent does not override are
    // omitted; the whole object is omitted when it overrides nothing. The values in force are at
    // GET /api/agents/{id}/runtime-limits. Set via PUT on that path.
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    RuntimeLimits runtimeLimits
) {
    /** Internal/non-request callers (no tenant plan to resolve) - always editable. */
    public static AgentResponse from(Agent agent) {
        return from(agent, true);
    }

    public static AgentResponse from(Agent agent, boolean editable) {
        return from(agent, editable, null);
    }

    public static AgentResponse from(Agent agent, boolean editable, AgentModalitiesView modalities) {
        return from(agent, editable, modalities, null);
    }

    public static AgentResponse from(
        Agent agent, boolean editable, AgentModalitiesView modalities, String accessLevel
    ) {
        return new AgentResponse(
            agent.getId(),
            agent.getName(),
            agent.getSystemPrompt(),
            agent.getProviderName(),
            agent.getModel(),
            agent.getFallbackProviderName(),
            agent.getFallbackModel(),
            agent.getFallbackTriggeredCount(),
            agent.getFallbackFailedCount(),
            agent.getLastFallbackAt(),
            agent.getMaxTokens(),
            agent.getTemperature(),
            agent.getTopP(),
            agent.getTuningMode(),
            agent.isKnowledgeBaseEnabled(),
            agent.isSarvamEnabled(),
            agent.isE2bEnabled(),
            agent.isCodeWorkspaceEnabled(),
            agent.isResponseCachingEnabled(),
            agent.isSemanticResponseCachingEnabled(),
            agent.isCostRoutingEnabled(),
            agent.getEconomyProviderName(),
            agent.getEconomyModel(),
            agent.getCostRoutingJevThreshold(),
            agent.isShareMemoryWithTenant(),
            agent.isBiometricConsentEnabled(),
            agent.getEdgeRoutingPolicy(),
            agent.getAllowedToolNames(),
            agent.getAllowedKnowledgeCollectionIds(),
            agent.getAllowedCodeIntelRepoIds(),
            agent.getExperienceMode(),
            agent.getReasoningDepth(),
            agent.getAutonomyTier(),
            agent.getCollaboratorAgentIds(),
            agent.getCreatedAt(),
            agent.getUpdatedAt(),
            agent.getVoiceModelProvider(),
            agent.getVoiceModel(),
            agent.getVideoModelProvider(),
            agent.getVideoModel(),
            agent.getTtsProvider(),
            agent.getTtsModel(),
            editable,
            modalities,
            agent.getInputSchemaJson(),
            agent.getOutputSchemaJson(),
            agent.isXaiServerToolsEnabled(),
            agent.getTopK(),
            agent.getStopSequences(),
            agent.getFrequencyPenalty(),
            agent.getPresencePenalty(),
            agent.getSeed(),
            agent.getCreatedBy(),
            agent.getUpdatedBy(),
            accessLevel,
            agent.getRuntimeLimits().isEmpty() ? null : agent.getRuntimeLimits()
        );
    }
}
