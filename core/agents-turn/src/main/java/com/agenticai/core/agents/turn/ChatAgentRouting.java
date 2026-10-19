package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.experiment.AgentExperimentService;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.tenancy.FeatureFlagService;
import java.util.Map;
import java.util.UUID;

/**
 * Which agent, which version and which experiment variants one turn runs with - the experiment
 * routing, the C1 version pin, the experiment-variants string and the model's document modality -
 * moved out of {@link AgentChatService} (extract-only split). Holds the service and reads its
 * setter-injected collaborators at call time, like the pipeline stages do.
 */
final class ChatAgentRouting {

    private final AgentChatService svc;

    ChatAgentRouting(AgentChatService svc) {
        this.svc = svc;
    }

    /**
     * The one call site every real chat entry point ({@link AgentChatService#chat} and {@link
     * AgentChatService#chatWithParams}) uses to route a turn to an experiment's treatment clone
     * instead of the agent the caller asked for - see {@code
     * AgentExperimentService#resolveEffectiveAgentId}'s javadoc for the bucketing/stickiness rules.
     * {@code subjectKey} is the turn's {@code sessionId} (stable per conversation); a null/blank
     * session id is handled by the experiment service itself (falls back to bucketing on the agent
     * id), so no null-check is needed here.
     *
     * <p>Never throws and never returns null when {@code agentId} is non-null: {@code
     * resolveEffectiveAgentId} already fails open on any internal error, and this wrapper
     * additionally treats "no experiment service available" (slim test context) the same way.
     */
    UUID resolveExperimentAgentId(UUID agentId, String sessionId) {
        AgentExperimentService service = svc.agentExperimentService;
        return service == null ? agentId : service.resolveEffectiveAgentId(agentId, sessionId);
    }

    /**
     * C1: pins the resolved {@code agent_version.id} for this turn (stamped onto {@code
     * llm_call_log}/{@code tool_call_log} rows by the writers that read {@code AgentVersionPin})
     * and, in explicit-publish mode, returns a detached clone running the PRODUCTION bundle rather
     * than the draft row. Never throws - a resolution failure degrades to the live entity with no
     * pin, exactly the pre-C1 path. The request thread's pin is reaped by {@code
     * TenantContextFilter}'s finally, same as {@code TenantContext} itself.
     */
    Agent pinAndApplyEffectiveAgent(UUID agentId, Agent live) {
        return pinAndApplyEffectiveAgent(agentId, live, null);
    }

    /**
     * As above; {@code activeFlags} are the turn's already-evaluated flags ({@code null}: read).
     */
    Agent pinAndApplyEffectiveAgent(UUID agentId, Agent live, Map<String, String> activeFlags) {
        try {
            var resolver = svc.versionResolver.getIfAvailable();
            return resolver == null ? live : resolver.pinAndApply(agentId, live, activeFlags);
        } catch (RuntimeException e) {
            AgentChatService.log.warn(
                    "Version resolution failed for agent {} - running the live row unpinned",
                    agentId,
                    e);
            return live;
        }
    }

    /**
     * Master Roadmap 2026 item #13 (A/B testing & feature flags, §B) - the one call site every chat
     * turn (all reasoning depths, plus delegated calls) uses to learn which experiment variants
     * apply to this tenant, formatted into the flat string {@link
     * LlmMetricsAdvisor#EXPERIMENT_VARIANTS_PARAM} expects. {@link
     * FeatureFlagService#evaluateActiveFlags} already fails open to an empty map on any error or
     * missing tenant id, so this never throws - {@code null} (no active experiments, or no tenant
     * context) means the advisor param is simply never set, identical to how a call site that never
     * threads {@code AGENT_ID_PARAM} still gets a full {@code LlmCallLog} row with a null agent.
     */
    String resolveExperimentVariants(String tenantId) {
        return formatExperimentVariants(svc.featureFlagService.evaluateActiveFlags(tenantId));
    }

    /** The flat {@code key=variant,...} string for already-evaluated flags; null when none. */
    static String formatExperimentVariants(Map<String, String> variants) {
        return variants == null || variants.isEmpty()
                ? null
                : variants.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining(","));
    }

    /** True when the agent's model natively takes documents (unknown = true: today's behaviour). */
    boolean modelTakesDocuments(Agent agent) {
        ModelModalitiesService service = svc.modelModalities;
        if (service == null) {
            return true;
        }
        try {
            return service.resolve(
                            agent.getProviderName(), svc.agentService.resolveEffectiveModel(agent))
                    .document();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
