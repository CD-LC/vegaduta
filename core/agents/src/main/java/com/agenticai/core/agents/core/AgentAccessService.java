package com.agenticai.core.agents.core;

import com.agenticai.core.security.EditLockPolicy;
import com.agenticai.core.security.EditLockedException;
import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TtlCache;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceGrant;
import com.agenticai.core.sharing.ResourceType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may touch an agent and what the tenant's plan allows: per-user access (owner/grants via
 * {@code core/sharing}), the FREE-tier edit-lock, the agent-count cap and the platform-LLM
 * question. Every mutating path in the agents module goes through here, so the rules live in one
 * place. Behavior moved verbatim from {@link AgentService}, which keeps delegating methods.
 */
@Service
public class AgentAccessService {

    private final AgentRepository repository;
    private final TenantLimits tenantLimits;
    private final ResourceAccessService resourceAccessService;

    /**
     * The plan answer {@link #isEditable} needs, shared across every agent in a request and across
     * requests (registered under {@link HotpathCaches#TENANT_PLAN}, evicted on any tenant-row
     * write; cross-pod staleness is bounded by the 30s TTL).
     */
    private final TtlCache<TenantPlan> planCache =
            new TtlCache<>(HotpathCaches.TENANT_PLAN, Duration.ofSeconds(30), 10_000);

    public AgentAccessService(
            AgentRepository repository,
            TenantLimits tenantLimits,
            ResourceAccessService resourceAccessService) {
        this.repository = repository;
        this.tenantLimits = tenantLimits;
        this.resourceAccessService = resourceAccessService;
    }

    /** Tenant-scoped lookup with no per-user check; 404s when missing or cross-tenant. */
    @Transactional(readOnly = true)
    public Agent getEntity(UUID agentId) {
        return repository.findById(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
    }

    /**
     * Loads {@code agentId} and enforces {@code core/sharing} access at {@code required} level
     * using the entity's own {@code createdBy}; a null {@code createdBy} (every agent that
     * predates sharing) always passes. Denial is a 404, indistinguishable from not-found.
     */
    public Agent requireAccess(UUID agentId, PermissionLevel required) {
        Agent agent = getEntity(agentId);
        resourceAccessService.requireAccess(
                ResourceType.AGENT, agentId, agent.getCreatedBy(), required);
        return agent;
    }

    /**
     * FREE-tier edit-lock - every mutating setter calls this first. No tenant context (no HTTP
     * request) is left unlocked.
     */
    public void assertEditable(Agent agent) {
        String tenantId = currentTenantId();
        if (tenantId == null) {
            return;
        }
        TenantPlan plan = tenantLimits.plan(tenantId);
        if (EditLockPolicy.isLocked(plan, agent.getCreatedAt())) {
            throw new EditLockedException(
                    "Editing is a Premium feature - this agent keeps working as-is (chat, run, and"
                            + " delete are still available), or upgrade your plan to change it."
                            + " Free-tier agents can be edited for "
                            + EditLockPolicy.FREE_TIER_GRACE_WINDOW.toHours()
                            + " hours after creation, or duplicated into "
                            + "a fresh editable copy at any time.",
                    "free_tier_grace_window_expired",
                    agent.getCreatedAt() == null
                            ? null
                            : agent.getCreatedAt().plus(EditLockPolicy.FREE_TIER_GRACE_WINDOW));
        }
    }

    /**
     * True unless this is a FREE-plan tenant's agent past its 24h grace window - see {@link
     * EditLockPolicy}. Uses the shared plan cache.
     */
    /** The caller's effective share level for {@code AgentResponse.accessLevel} (display hint; null when unresolved). */
    public String accessLevelOf(Agent agent) {
        return AgentAccessLevel.of(resourceAccessService, agent);
    }

    public boolean isEditable(Agent agent) {
        String tenantId = currentTenantId();
        if (tenantId == null) {
            return true;
        }
        return !EditLockPolicy.isLocked(
                planCache.get(TtlCache.key(tenantId, "plan"), () -> tenantLimits.plan(tenantId)),
                agent.getCreatedAt());
    }

    /**
     * Throws {@link TenantLimitExceededException} when the current tenant is already at its plan's
     * agent cap. No tenant context is not capped.
     */
    public void requireAgentSlot() {
        String tenantId = currentTenantId();
        if (tenantId == null) {
            return;
        }
        Optional<Integer> maxAgents = tenantLimits.maxAgents(tenantId);
        if (maxAgents.isPresent() && repository.count() >= maxAgents.get()) {
            throw new TenantLimitExceededException(
                    "The "
                            + tenantLimits.plan(tenantId)
                            + " plan allows up to "
                            + maxAgents.get()
                            + " agents at a time - upgrade your plan, or delete an existing agent,"
                            + " to add another.");
        }
    }

    /** Resources shared with the caller; the list-level agent filter reads the AGENT grants. */
    public List<ResourceGrant> listSharedWithMe() {
        return resourceAccessService.listSharedWithMe();
    }

    /**
     * The current tenant's plan; {@link TenantPlan#UNLIMITED} when there is no tenant context
     * (callers with no request context are never plan-gated).
     */
    public TenantPlan currentPlan() {
        String tenantId = currentTenantId();
        return tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
    }

    /** Current tenant id, or null with no request context. */
    public String currentTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(null);
    }

    /**
     * Whether this tenant's turns run on the platform's pooled models rather than its own BYOK key
     * - the condition that makes the curated model catalog a hard allowlist.
     */
    public boolean usesPlatformLlm(String tenantId) {
        return tenantLimits.usesPlatformPool(tenantId);
    }
}
