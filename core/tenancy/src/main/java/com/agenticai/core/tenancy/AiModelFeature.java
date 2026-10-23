package com.agenticai.core.tenancy;

/**
 * The catalog of AI-touching feature areas the "AI Model Control" settings
 * page lets a tenant override a preferred provider/model for - one row per
 * value here, rendered by {@code GET /api/tenant/ai-model-preferences}
 * whether or not the tenant has actually set an override yet (see {@code
 * TenantAiModelPreferenceController}).
 *
 * <p><b>{@link #DEFAULT} is the only value actually wired into a live
 * routing decision as of this change.</b> {@code LlmGatewayService#resolveClient}
 * - the single seam every outbound LLM call in this platform funnels through
 * (see that class's own javadoc) - resolves {@link #DEFAULT}'s stored
 * override for every call whose {@code GatewayCallSpec#featureKey} is unset,
 * which today is EVERY existing call site ({@code AgentChatService}, {@code
 * AssistService}, {@code WorkflowNodeExecutor}, {@code
 * WorkflowGraphGeneratorService}, {@code WorkflowExecutionParamsInferenceService},
 * {@code McpStdioSelfHealer}, {@code JudgeChatClientFactory} - core/agents,
 * core/workflow, core/mcp, core/quality, all outside this change's scope).
 * A tenant's {@link #DEFAULT} override therefore takes effect immediately,
 * platform-wide, with no other module touched.
 *
 * <p>The other, more granular values below are captured, validated, and
 * returned by the same GET/PUT surface (so the settings page can already
 * render a per-feature row for each), but only take effect once the owning
 * call site is updated to pass its own {@code featureKey} into {@code
 * GatewayCallSpec} - a change to core/agents, core/workflow, core/mcp, and
 * core/quality respectively, outside this task's core/tenancy + core/platform
 * + core/chat scope. Flagged as the natural follow-up, not silently implied
 * to already work; see this repo's cross-agent coordination doc.
 */
public enum AiModelFeature {

    /**
     * Fallback bucket: applies to every {@code LlmGatewayService#resolveClient}
     * call whose spec doesn't name a more specific feature - see this enum's
     * own javadoc for why that is, today, every call site in the platform.
     */
    DEFAULT("default", "Default (applies to every AI call that doesn't set a more specific feature below)"),

    AGENT_CHAT("agent-chat", "Agent chat & Live Vision"),

    ASSIST("assist", "Ask-AI assist"),

    WORKFLOW_INFERENCE("workflow-inference", "Workflow node/graph/parameter inference"),

    MCP_SELF_HEAL("mcp-self-heal", "MCP connection self-heal"),

    EVAL_JUDGE("eval-judge", "Evals / LLM-as-judge"),

    /**
     * The summary that folds the older turns of a long prompt into one (context compaction). Read by {@code
     * AuxiliaryModelResolver} (core/agents-turn), whose first choice for the background call is this row; with none it
     * falls through to the platform's {@code app.auxiliary-model.compaction.*}, the agent's own model and the provider
     * default.
     */
    COMPACTION("compaction", "Context compaction (long-conversation summaries)"),

    /** The rolling summary of turns that left a session's replay window; read by {@code AuxiliaryModelResolver} like {@link #COMPACTION}. */
    SESSION_SUMMARY("session-summary", "Session summary (older turns of a conversation)");

    private final String key;
    private final String label;

    AiModelFeature(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** The wire/storage key - what {@code GatewayCallSpec#featureKey} and the JSON bag both use. */
    public String key() {
        return key;
    }

    /** Human-readable label for the settings page. */
    public String label() {
        return label;
    }

    /** @return the matching value, or {@code null} if {@code key} names no known feature (an unrecognized/future key). */
    public static AiModelFeature fromKey(String key) {
        for (AiModelFeature feature : values()) {
            if (feature.key.equals(key)) {
                return feature;
            }
        }
        return null;
    }
}
