package com.agenticai.core.agents.guardrail;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for managing per-agent guardrail configuration with plan gating.
 *
 * <p><b>Plan Tier Availability:</b>
 *
 * <ul>
 *   <li><b>FREE:</b> PROFANITY, JAILBREAK (basic input filtering)
 *   <li><b>PREMIUM:</b> + PII, POLICY_VIOLATION (extended safety)
 *   <li><b>UNLIMITED:</b> + TOXICITY, CUSTOM (full suite with semantic checks)
 * </ul>
 *
 * <p><b>Gating Logic:</b> Attempting to enable a guardrail not available for the tenant's plan
 * throws {@link GuardrailNotAvailableForPlanException}. This check runs both at update time and at
 * evaluation time.
 */
@Service
public class AgentGuardrailService {

    private static final Logger log = LoggerFactory.getLogger(AgentGuardrailService.class);

    private final TenantLimits tenantLimits;
    private final AgentRepository agentRepository;
    // Cross-user resource sharing retrofit (Round 2) - this service loads the
    // Agent entity directly (agentRepository, not AgentService), so the EDITOR
    // check has to happen here rather than being inherited from a shared
    // AgentService method.
    private final ResourceAccessService resourceAccessService;

    // Mapping of guardrail type to required plan tier
    private static final Map<ContentGuardrailType, TenantPlan> GUARDRAIL_PLAN_REQUIREMENTS =
            new HashMap<>();

    static {
        // FREE tier guardrails
        GUARDRAIL_PLAN_REQUIREMENTS.put(ContentGuardrailType.PROFANITY, TenantPlan.FREE);
        GUARDRAIL_PLAN_REQUIREMENTS.put(ContentGuardrailType.JAILBREAK, TenantPlan.FREE);

        // PREMIUM tier guardrails
        GUARDRAIL_PLAN_REQUIREMENTS.put(ContentGuardrailType.PII, TenantPlan.PREMIUM);
        GUARDRAIL_PLAN_REQUIREMENTS.put(ContentGuardrailType.POLICY_VIOLATION, TenantPlan.PREMIUM);

        // UNLIMITED tier guardrails
        GUARDRAIL_PLAN_REQUIREMENTS.put(ContentGuardrailType.TOXICITY, TenantPlan.UNLIMITED);
        GUARDRAIL_PLAN_REQUIREMENTS.put(ContentGuardrailType.CUSTOM, TenantPlan.UNLIMITED);
    }

    public AgentGuardrailService(
            TenantLimits tenantLimits,
            AgentRepository agentRepository,
            ResourceAccessService resourceAccessService) {
        this.tenantLimits = tenantLimits;
        this.agentRepository = agentRepository;
        this.resourceAccessService = resourceAccessService;
    }

    /**
     * Updates the enabled guardrails for an agent, with plan gating validation.
     *
     * @param agentId the agent to update
     * @param guardrailIds set of guardrail types to enable
     * @throws GuardrailNotAvailableForPlanException if any guardrail is not available for the
     *     tenant's plan
     * @throws AgentNotFoundException if agent not found
     */
    public void updateAgentGuardrails(UUID agentId, Set<String> guardrailIds) {
        String tenantId =
                TenantContext.current()
                        .map(ctx -> ctx.getTenantId())
                        .orElseThrow(() -> new IllegalStateException("No tenant context"));

        // Pre-existing bug fix (unrelated to the resource-sharing retrofit -
        // found only because it blocked compilation): AgentNotFoundException
        // only has a UUID constructor (see that class), not a String one.
        Agent agent =
                agentRepository
                        .findById(agentId)
                        .orElseThrow(() -> new AgentNotFoundException(agentId));

        // Cross-user resource sharing retrofit (Round 2) - EDITOR: changing
        // enabled guardrails is a config mutation, same bar as every other
        // agent PATCH endpoint.
        resourceAccessService.requireAccess(
                ResourceType.AGENT, agentId, agent.getCreatedBy(), PermissionLevel.EDITOR);

        // Validate all guardrails against plan
        TenantPlan plan = tenantLimits.plan(tenantId);
        validateGuardrailsForPlan(guardrailIds, plan);

        // Update and persist. updatedBy is recorded here directly - this
        // service saves through agentRepository, not AgentService's
        // saveVersioned choke point (see the constructor comment for why it
        // loads the entity itself), so the V393 audit field has to be set at
        // this call site too.
        agent.setEnabledGuardrails(guardrailIds);
        agent.setUpdatedBy(TenantContext.current().map(ctx -> ctx.getSubject()).orElse(null));
        agentRepository.save(agent);

        log.info("Updated guardrails for agent {}: {} (plan: {})", agentId, guardrailIds, plan);
    }

    /**
     * Retrieves the guardrails available for the tenant's current plan.
     *
     * @return set of available guardrail types
     */
    public Set<ContentGuardrailType> getAvailableGuardrailsForPlan() {
        String tenantId =
                TenantContext.current()
                        .map(ctx -> ctx.getTenantId())
                        .orElseThrow(() -> new IllegalStateException("No tenant context"));

        TenantPlan plan = tenantLimits.plan(tenantId);
        Set<ContentGuardrailType> available = new HashSet<>();

        for (ContentGuardrailType type : ContentGuardrailType.values()) {
            TenantPlan required = GUARDRAIL_PLAN_REQUIREMENTS.get(type);
            if (required == null || isPlanlevelSufficient(required, plan)) {
                available.add(type);
            }
        }

        return available;
    }

    /**
     * Retrieves guardrails that are locked (require higher plan) for the tenant's current plan.
     *
     * @return set of locked guardrail types
     */
    public Set<ContentGuardrailType> getLockedGuardrailsForPlan() {
        String tenantId =
                TenantContext.current()
                        .map(ctx -> ctx.getTenantId())
                        .orElseThrow(() -> new IllegalStateException("No tenant context"));

        TenantPlan plan = tenantLimits.plan(tenantId);
        Set<ContentGuardrailType> locked = new HashSet<>();

        for (ContentGuardrailType type : ContentGuardrailType.values()) {
            TenantPlan required = GUARDRAIL_PLAN_REQUIREMENTS.get(type);
            if (required != null && !isPlanlevelSufficient(required, plan)) {
                locked.add(type);
            }
        }

        return locked;
    }

    /**
     * Validates that all requested guardrails are available for the given plan.
     *
     * @throws GuardrailNotAvailableForPlanException if any guardrail is not available
     */
    public void validateGuardrailsForPlan(Set<String> guardrailIds, TenantPlan plan) {
        if (guardrailIds == null || guardrailIds.isEmpty()) {
            return;
        }

        for (String guardrailId : guardrailIds) {
            try {
                ContentGuardrailType type = ContentGuardrailType.fromId(guardrailId);
                TenantPlan required = GUARDRAIL_PLAN_REQUIREMENTS.get(type);

                if (required != null && !isPlanlevelSufficient(required, plan)) {
                    throw new GuardrailNotAvailableForPlanException(guardrailId, required.name());
                }
            } catch (IllegalArgumentException ex) {
                log.warn("Unknown guardrail type: {}", guardrailId);
                // Don't fail on unknown guardrails - they may be future types
            }
        }
    }

    /**
     * Enforcement-time counterpart of {@link #validateGuardrailsForPlan} - resolves an agent's
     * stored guardrail id strings into the set of {@link ContentGuardrailType}s that should
     * ACTUALLY be evaluated for this tenant's current plan. Unlike the validate method (which
     * throws, because it runs at config-write time where rejecting the request is the right
     * answer), this silently DROPS any type the plan doesn't include: the stored config can
     * legitimately contain locked types (tenant configured them on PREMIUM, then downgraded to
     * FREE), and enforcement must not evaluate - nor bill the extra semantic-check LLM calls for -
     * guardrails the current plan doesn't grant.
     *
     * <p>Takes {@code tenantId} explicitly (never {@link TenantContext}) because the chat path
     * calls this from stream-completion callbacks where the request's ThreadLocal context is not
     * guaranteed to be present. A {@code null} tenantId (possible on some omnichannel/webhook
     * turns) degrades to the FREE-tier set via {@link ContentGuardrailType#isFreeTierAvailable()} -
     * the conservative floor every plan includes.
     */
    public Set<ContentGuardrailType> resolveEnforceableGuardrails(
            Set<String> guardrailIds, String tenantId) {
        return resolveEnforceableGuardrails(guardrailIds, tenantId, null);
    }

    /**
     * As {@link #resolveEnforceableGuardrails(Set, String)} for a caller that already resolved the
     * tenant's effective plan for this turn: {@code knownPlan} is used as is and costs no lookup.
     * {@code null} reads the plan, exactly as the two-argument form does.
     */
    public Set<ContentGuardrailType> resolveEnforceableGuardrails(
            Set<String> guardrailIds, String tenantId, TenantPlan knownPlan) {
        Set<ContentGuardrailType> types = resolveGuardrailTypes(guardrailIds);
        if (types.isEmpty()) {
            return types;
        }
        if (tenantId == null) {
            types.removeIf(type -> !type.isFreeTierAvailable());
            return types;
        }
        TenantPlan plan = knownPlan != null ? knownPlan : tenantLimits.plan(tenantId);
        types.removeIf(
                type -> {
                    TenantPlan required = GUARDRAIL_PLAN_REQUIREMENTS.get(type);
                    return required != null && !isPlanlevelSufficient(required, plan);
                });
        return types;
    }

    /**
     * Converts a set of guardrail type strings to {@link ContentGuardrailType} enums.
     *
     * @param guardrailIds set of guardrail ID strings
     * @return set of ContentGuardrailType enums
     */
    public Set<ContentGuardrailType> resolveGuardrailTypes(Set<String> guardrailIds) {
        Set<ContentGuardrailType> types = new HashSet<>();
        if (guardrailIds != null) {
            for (String id : guardrailIds) {
                try {
                    types.add(ContentGuardrailType.fromId(id));
                } catch (IllegalArgumentException ex) {
                    log.warn("Skipping unknown guardrail type: {}", id);
                }
            }
        }
        return types;
    }

    /** Returns true if the given current plan meets or exceeds the required plan level. */
    private boolean isPlanlevelSufficient(TenantPlan required, TenantPlan current) {
        // Plan hierarchy: FREE < PREMIUM < UNLIMITED
        return current.ordinal() >= required.ordinal();
    }

    /** Gets a human-readable description for each guardrail type. */
    public Map<String, String> getGuardrailDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        for (ContentGuardrailType type : ContentGuardrailType.values()) {
            descriptions.put(type.getId(), type.getDisplayName());
        }
        return descriptions;
    }
}
