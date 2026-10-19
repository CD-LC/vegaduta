package com.agenticai.core.agents.guardrail;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentGuardrailsRequest;
import com.agenticai.core.agents.dto.AgentGuardrailsResponse;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for managing per-agent guardrail configuration.
 *
 * <p><b>GET /api/agents/{id}/guardrails</b> - Get current guardrails + available options
 * <p><b>PATCH /api/agents/{id}/guardrails</b> - Update guardrails (with plan gating)
 */
@RestController
@RequestMapping("/api/agents/{agentId}/guardrails")
public class AgentGuardrailController {

    private final AgentService agentService;
    private final AgentGuardrailService guardrailService;

    public AgentGuardrailController(
        AgentService agentService,
        AgentGuardrailService guardrailService
    ) {
        this.agentService = agentService;
        this.guardrailService = guardrailService;
    }

    /**
     * GET /api/agents/{id}/guardrails - Retrieve the agent's current guardrail
     * configuration and available options for the tenant's plan.
     *
     * @param agentId the agent to query
     * @return guardrails response with enabled, available, and locked guardrails
     */
    @GetMapping
    public AgentGuardrailsResponse getAgentGuardrails(@PathVariable UUID agentId) {
        // Cross-user resource sharing retrofit (Round 2) - VIEWER: guardrail
        // config is config, same bar as every other agent config read.
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.VIEWER);

        Set<String> enabledGuardrails = agent.getEnabledGuardrails();
        Set<ContentGuardrailType> available = guardrailService.getAvailableGuardrailsForPlan();
        Set<ContentGuardrailType> locked = guardrailService.getLockedGuardrailsForPlan();

        Set<String> availableIds = available.stream()
            .map(ContentGuardrailType::getId)
            .collect(Collectors.toSet());

        Set<String> lockedIds = locked.stream()
            .map(ContentGuardrailType::getId)
            .collect(Collectors.toSet());

        Map<String, String> descriptions = guardrailService.getGuardrailDescriptions();

        return new AgentGuardrailsResponse(
            enabledGuardrails,
            availableIds,
            lockedIds,
            descriptions
        );
    }

    /**
     * PATCH /api/agents/{id}/guardrails - Update the agent's enabled guardrails.
     *
     * <p>Validates that all requested guardrails are available for the tenant's plan.
     * Returns 403 if attempting to enable a guardrail locked by plan tier.
     *
     * @param agentId the agent to update
     * @param request the guardrails to enable
     * @return updated guardrails response
     * @throws GuardrailNotAvailableForPlanException if guardrail not available for plan
     */
    @PatchMapping
    public AgentGuardrailsResponse updateAgentGuardrails(
        @PathVariable UUID agentId,
        @RequestBody AgentGuardrailsRequest request
    ) {
        guardrailService.updateAgentGuardrails(agentId, request.getGuardrails());

        // Return updated response
        return getAgentGuardrails(agentId);
    }
}
