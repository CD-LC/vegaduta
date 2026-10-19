package com.agenticai.core.brief;

import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.EdgeRoutingPolicy;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentGenerationParamsUpdateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.brief.EnvoyPorts.AgentWiring;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.guardrails.procedures.ProcedureService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The agent half of {@link ServiceEnvoyPorts}: create (autonomy first, so the safety posture is on before anything
 * else can fail), update, wire (tool allow-list, knowledge, procedures, guardrails), tier and delete.
 */
@Component
class EnvoyAgentPorts {

    private static final Logger log = LoggerFactory.getLogger(EnvoyAgentPorts.class);

    private final AgentService agentService;
    private final AgentGuardrailService guardrailService;
    private final ProcedureService procedureService;
    private final ProviderCredentialResolver credentialResolver;
    private final TenantLimits tenantLimits;

    EnvoyAgentPorts(
            AgentService agentService,
            AgentGuardrailService guardrailService,
            ProcedureService procedureService,
            ProviderCredentialResolver credentialResolver,
            TenantLimits tenantLimits) {
        this.agentService = agentService;
        this.guardrailService = guardrailService;
        this.procedureService = procedureService;
        this.credentialResolver = credentialResolver;
        this.tenantLimits = tenantLimits;
    }

    // ------------------------------------------------------------------ agents

    public String createAgent(Brief.AgentSpec spec, AgentAutonomyTier tier) {
        Brief.Generation g = spec.generation();
        AgentResponse created =
                agentService.create(
                        new AgentCreateRequest(
                                spec.name(),
                                spec.systemPrompt(),
                                resolveProviderName(),
                                null,
                                g == null || g.maxTokens() == null
                                        ? null
                                        : Math.min(Math.max(g.maxTokens(), 1), 8192),
                                g == null ? null : clamp01(g.temperature()),
                                g == null ? null : clamp01(g.topP()),
                                null,
                                null,
                                parse(ReasoningDepth.class, spec.reasoningDepth()),
                                parse(TuningMode.class, spec.tuningMode()),
                                parse(EdgeRoutingPolicy.class, spec.edgeRoutingPolicy()),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        UUID id = created.id();
        try {
            // Autonomy FIRST: everything below can fail, and the safety posture must already be on
            // the agent then.
            agentService.setAutonomyTier(id, tier);
            if (Boolean.TRUE.equals(spec.shareMemoryWithTenant())) {
                agentService.setShareMemoryWithTenant(id, true);
            }
        } catch (RuntimeException e) {
            // The engine has not been told about this agent yet (we are still inside create), so
            // undo it here.
            try {
                agentService.delete(id);
            } catch (RuntimeException cleanup) {
                log.warn("Could not remove agent {} after a failed post-create step", id, cleanup);
            }
            throw e;
        }
        return id.toString();
    }

    public void updateAgent(String id, Brief.AgentSpec spec, AgentAutonomyTier tier) {
        UUID uuid = UUID.fromString(id);
        AgentResponse current = agentService.get(uuid);
        if (spec.systemPrompt() != null && !spec.systemPrompt().equals(current.systemPrompt())) {
            agentService.updateSystemPrompt(uuid, spec.systemPrompt());
        }
        Brief.Generation g = spec.generation();
        if (g != null) {
            agentService.updateGenerationParams(
                    uuid,
                    new AgentGenerationParamsUpdateRequest(
                            g.maxTokens() == null
                                    ? current.maxTokens()
                                    : Math.min(Math.max(g.maxTokens(), 1), 8192),
                            g.temperature() == null
                                    ? current.temperature()
                                    : clamp01(g.temperature()),
                            g.topP() == null ? current.topP() : clamp01(g.topP()),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
        }
        ReasoningDepth depth = parse(ReasoningDepth.class, spec.reasoningDepth());
        if (depth != null) {
            agentService.setReasoningDepth(uuid, depth);
        }
        TuningMode tuning = parse(TuningMode.class, spec.tuningMode());
        if (tuning != null) {
            agentService.setTuningMode(uuid, tuning);
        }
        EdgeRoutingPolicy edge = parse(EdgeRoutingPolicy.class, spec.edgeRoutingPolicy());
        if (edge != null) {
            agentService.setEdgeRoutingPolicy(uuid, edge);
        }
        if (spec.shareMemoryWithTenant() != null) {
            agentService.setShareMemoryWithTenant(uuid, spec.shareMemoryWithTenant());
        }
        agentService.setAutonomyTier(uuid, tier);
    }

    public List<String> wireAgent(String id, AgentWiring wiring) {
        UUID uuid = UUID.fromString(id);
        List<String> warnings = new ArrayList<>();
        agentService.setAllowedToolNames(uuid, new LinkedHashSet<>(wiring.toolNames()));
        if (wiring.knowledgeIds().isEmpty()) {
            agentService.setKnowledgeBaseEnabled(uuid, false);
        } else {
            agentService.setKnowledgeBaseEnabled(uuid, true);
            agentService.setAllowedKnowledgeCollectionIds(
                    uuid,
                    wiring.knowledgeIds().stream()
                            .map(UUID::fromString)
                            .collect(java.util.stream.Collectors.toSet()));
        }
        for (String detach : wiring.detachProcedureIds()) {
            try {
                procedureService.detach(uuid, UUID.fromString(detach));
            } catch (RuntimeException e) {
                warnings.add("Could not detach an old procedure: " + e.getMessage());
            }
        }
        int position = 0;
        for (String procedureId : wiring.procedureIds()) {
            procedureService.attach(uuid, UUID.fromString(procedureId), position++);
        }
        Set<String> guardrailIds = new LinkedHashSet<>();
        for (String kind : wiring.guardrailKinds()) {
            try {
                guardrailIds.add(
                        ContentGuardrailType.fromId(kind.toLowerCase(Locale.ROOT)).getId());
            } catch (IllegalArgumentException e) {
                warnings.add(
                        "Guardrail '"
                                + kind
                                + "' is not one the platform offers, so it was not enabled.");
            }
        }
        if (!guardrailIds.isEmpty()) {
            try {
                guardrailService.updateAgentGuardrails(uuid, guardrailIds);
            } catch (RuntimeException e) {
                // Most commonly a plan-gated guardrail: the agent is safe without it (lowest
                // autonomy is already on).
                warnings.add("Guardrails not enabled: " + e.getMessage());
            }
        }
        return warnings;
    }

    public void deleteAgent(String id) {
        agentService.delete(UUID.fromString(id));
    }

    public AgentAutonomyTier agentTier(String id) {
        return agentService.getAutonomyTier(UUID.fromString(id));
    }

    public void setAgentTier(String id, AgentAutonomyTier tier) {
        agentService.setAutonomyTier(UUID.fromString(id), tier);
    }

    private ProviderName resolveProviderName() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        // usesPlatformPool, not plan == FREE: the pool is the default for
        // every tenant, so a non-FREE tenant on it (the common case now)
        // must be labelled PLATFORM_HOSTED like the calls it actually makes.
        if (tenantId == null || tenantLimits.usesPlatformPool(tenantId)) {
            return ProviderName.PLATFORM_HOSTED;
        }
        try {
            return credentialResolver.resolveAnyActiveKey().providerName();
        } catch (RuntimeException e) {
            return ProviderName.PLATFORM_HOSTED;
        }
    }

    private static Double clamp01(Double value) {
        return value == null ? null : Math.min(1.0, Math.max(0.0, value));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
