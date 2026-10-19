package com.agenticai.core.rl.api;

import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.policy.ContextualBanditService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Default {@link RlPolicyPort} - a thin adapter over {@link ContextualBanditService}, mapping the
 * internal {@link PolicyArm} entity onto the flat {@link ArmSelection} the orchestrator sees so the
 * runtime never touches an {@code @Entity} directly.
 *
 * <p><b>Plan gate (2026-07-27 billing audit):</b> adaptive/bandit-driven routing is a PREMIUM+
 * feature, opt-in per tenant - a FREE tenant must always get fixed/default routing, even when an
 * admin has an {@code AgentPolicy} enabled for the agent (that enablement is currently org-wide,
 * not plan-aware). So this port, not {@link ContextualBanditService} itself, is the place to
 * enforce it: {@link #selectArm} short-circuits to empty ("no decision - use your own default", the
 * port's existing contract for "nothing to apply here") for a FREE tenant before ever consulting
 * the bandit, regardless of dimension. Reads the plan the same read-only way {@code core.agents}'
 * {@code AgentService} does - {@link TenantContext}'s thread-local tenantId through {@link
 * TenantLimits#plan}, both already depended on by this module's {@code security} dependency - no
 * new module dependency, no change to {@code TenantPlan}/{@code TenantLimits} themselves. Missing
 * tenant context (no request in flight, e.g. an internal/offline caller) is treated as
 * unrestricted, same permissive default {@code AgentService} uses in that case.
 */
@Service
public class DefaultRlPolicyPort implements RlPolicyPort {

    private final ContextualBanditService banditService;
    private final TenantLimits tenantLimits;

    public DefaultRlPolicyPort(ContextualBanditService banditService, TenantLimits tenantLimits) {
        this.banditService = banditService;
        this.tenantLimits = tenantLimits;
    }

    @Override
    public Optional<ArmSelection> selectArm(
            UUID agentId, PolicyDimension dimension, String contextBucket) {
        return selectArm(agentId, dimension, contextBucket, null);
    }

    @Override
    public Optional<ArmSelection> selectArm(
            UUID agentId, PolicyDimension dimension, String contextBucket, TenantPlan knownPlan) {
        if (isFreeTenant(knownPlan)) {
            return Optional.empty();
        }
        return banditService
                .selectArm(agentId, dimension, contextBucket)
                .map(arm -> new ArmSelection(arm.getId(), arm.getArmKey(), arm.getArmConfigJson()));
    }

    /**
     * One plan check and one policy query for all dimensions; see {@link
     * ContextualBanditService#selectArms}.
     */
    @Override
    public java.util.Map<PolicyDimension, Optional<ArmSelection>> selectArms(
            UUID agentId, java.util.Set<PolicyDimension> dimensions, String contextBucket) {
        return selectArms(agentId, dimensions, contextBucket, null);
    }

    @Override
    public java.util.Map<PolicyDimension, Optional<ArmSelection>> selectArms(
            UUID agentId,
            java.util.Set<PolicyDimension> dimensions,
            String contextBucket,
            TenantPlan knownPlan) {
        java.util.Map<PolicyDimension, Optional<ArmSelection>> out =
                new java.util.EnumMap<>(PolicyDimension.class);
        if (isFreeTenant(knownPlan)) {
            dimensions.forEach(d -> out.put(d, Optional.empty()));
            return out;
        }
        banditService
                .selectArms(agentId, dimensions, contextBucket)
                .forEach(
                        (d, arm) ->
                                out.put(
                                        d,
                                        arm.map(
                                                a ->
                                                        new ArmSelection(
                                                                a.getId(),
                                                                a.getArmKey(),
                                                                a.getArmConfigJson()))));
        return out;
    }

    @Override
    public void reward(UUID armId, double reward) {
        banditService.update(armId, reward);
    }

    /**
     * True only when the current request's tenant is on the FREE plan. No tenant in context (no
     * request thread-local set) is treated the same way {@code AgentService} treats it elsewhere -
     * permissively, as unrestricted - rather than silently disabling RL for every internal caller.
     */
    private boolean isFreeTenant(TenantPlan knownPlan) {
        if (knownPlan != null) {
            return knownPlan == TenantPlan.FREE;
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return false;
        }
        return tenantLimits.plan(tenantId) == TenantPlan.FREE;
    }
}
