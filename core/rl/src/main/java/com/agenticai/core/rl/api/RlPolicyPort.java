package com.agenticai.core.rl.api;

import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.security.TenantPlan;
import java.util.Optional;
import java.util.UUID;

/**
 * The orchestrator-facing seam for the contextual bandit. This is the ONLY type the agent runtime
 * needs to depend on to let a policy pick a safe, non-side-effecting decision for a turn (model
 * route, reasoning depth, prompt variant, tool subset, generation params) and to feed the observed
 * reward back - it deliberately hides the internal {@code ContextualBanditService}/ {@code
 * PolicyStore} split behind a stable two-method contract.
 *
 * <p>An empty {@link #selectArm} return always means "no decision - use your own default" (no
 * enabled policy, a non-explorable dimension, no enabled arms, or - see {@link
 * DefaultRlPolicyPort}'s javadoc - a FREE-plan tenant, for whom adaptive routing is gated off
 * regardless of any enabled policy). The port never itself performs a side effect; it only reports
 * which config arm to use, so wiring it into the orchestrator can never change what irreversible
 * action an agent takes - that stays entirely on the existing approval/guardrail path.
 *
 * <p>Live registration in the orchestrator (calling {@link #selectArm} before a turn and {@link
 * #reward} after it, threaded through {@code AgentChatService}) is the remaining integration step
 * and is intentionally NOT done inside this leaf module - see the module {@code pom.xml}'s
 * "EXPOSE-only" note.
 */
public interface RlPolicyPort {

    /**
     * Selects an arm for {@code (agentId, dimension, contextBucket)} by Thompson sampling, or empty
     * when the caller should use its default.
     */
    Optional<ArmSelection> selectArm(UUID agentId, PolicyDimension dimension, String contextBucket);

    /**
     * Batched {@link #selectArm} for several dimensions of one agent (Playground turns ask for up
     * to five). Same per-dimension result distribution as calling {@code selectArm} for each; the
     * default simply does that, implementations may answer with fewer queries.
     */
    default java.util.Map<PolicyDimension, Optional<ArmSelection>> selectArms(
            UUID agentId, java.util.Set<PolicyDimension> dimensions, String contextBucket) {
        java.util.Map<PolicyDimension, Optional<ArmSelection>> out =
                new java.util.EnumMap<>(PolicyDimension.class);
        for (PolicyDimension d : dimensions) {
            out.put(d, selectArm(agentId, d, contextBucket));
        }
        return out;
    }

    /**
     * {@link #selectArm(UUID, PolicyDimension, String)} for a caller that already resolved the
     * tenant's effective plan for this turn. An implementation that gates on the plan uses {@code
     * knownPlan} instead of reading it again; the default ignores it, so an implementation that
     * does not gate sees no change. {@code null} means "not resolved": read it as before.
     */
    default Optional<ArmSelection> selectArm(
            UUID agentId, PolicyDimension dimension, String contextBucket, TenantPlan knownPlan) {
        return selectArm(agentId, dimension, contextBucket);
    }

    /**
     * {@link #selectArms(UUID, java.util.Set, String)} with the caller's already-resolved plan; see
     * above.
     */
    default java.util.Map<PolicyDimension, Optional<ArmSelection>> selectArms(
            UUID agentId,
            java.util.Set<PolicyDimension> dimensions,
            String contextBucket,
            TenantPlan knownPlan) {
        return selectArms(agentId, dimensions, contextBucket);
    }

    /** Folds one observed reward in [0,1] back into the chosen arm's posterior. */
    void reward(UUID armId, double reward);

    /**
     * A selected arm, flattened for the orchestrator: the id to later reward, the human-readable
     * arm key, and the opaque {@code arm_config_json} the caller interprets for the dimension (e.g.
     * {@code {"model":"..."}} for {@link PolicyDimension#MODEL_ROUTE}).
     */
    record ArmSelection(UUID armId, String armKey, String armConfigJson) {}
}
