package com.agenticai.core.agents.turn;

import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.security.TenantPlan;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-turn RL arm selector (single-threaded: used only on the request thread inside {@code
 * chat()}). With no dimension set it is a straight pass-through to {@link RlPolicyPort#selectArm},
 * i.e. the exact call chat() has always made. With a set (flag {@code
 * app.chat.hotpath.rl-batch-select}) the FIRST selection issues ONE {@link RlPolicyPort#selectArms}
 * for all dimensions the turn may need and later selections read that map; the guards around each
 * selection in chat() are untouched, so which arm is applied, and the bandit's exploration, is
 * decided exactly as before. A dimension the batch did not answer (or a port returning null) falls
 * back to a plain {@code selectArm}.
 */
final class RlArmPrefetch {

    private final EnumSet<PolicyDimension> dimensions;
    private final TenantPlan knownPlan;
    private Map<PolicyDimension, Optional<RlPolicyPort.ArmSelection>> fetched;
    private boolean fetchAttempted;

    RlArmPrefetch(EnumSet<PolicyDimension> dimensions) {
        this(dimensions, null);
    }

    /**
     * {@code knownPlan} is the turn's already-resolved plan, handed to the port so the up to five
     * selections of one Playground turn do not each read it again ({@code null}: the port reads it,
     * as before).
     */
    RlArmPrefetch(EnumSet<PolicyDimension> dimensions, TenantPlan knownPlan) {
        this.dimensions = dimensions;
        this.knownPlan = knownPlan;
    }

    Optional<RlPolicyPort.ArmSelection> select(
            RlPolicyPort port, UUID agentId, PolicyDimension dimension) {
        if (dimensions != null && dimensions.contains(dimension)) {
            if (!fetchAttempted) {
                fetchAttempted = true;
                fetched =
                        knownPlan == null
                                ? port.selectArms(agentId, dimensions, null)
                                : port.selectArms(agentId, dimensions, null, knownPlan);
            }
            Optional<RlPolicyPort.ArmSelection> arm =
                    fetched == null ? null : fetched.get(dimension);
            if (arm != null) {
                return arm;
            }
        }
        return knownPlan == null
                ? port.selectArm(agentId, dimension, null)
                : port.selectArm(agentId, dimension, null, knownPlan);
    }
}
