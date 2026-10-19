package com.agenticai.core.agents.decision;

import com.agenticai.core.providers.capability.Capability;
import java.util.Map;

/**
 * The DECISION capability's policy schema - the typed view over a {@code
 * capability_policy.overrides} jsonb row ({@code capability='decision'}).
 * Every field is a nullable override: null means "inherit the platform
 * default," and each consumer combines a field with its own platform flag so
 * a platform switch still works for tenants who never set anything.
 *
 * <p>Precedence is agent > tenant > platform: {@code simpleThreshold} is the
 * tenant-wide override, and {@code agent.cost_routing_jev_threshold} can
 * narrow it further per agent. (The field/column names keep their {@code jev}
 * names for wire/migration compatibility; the schema is the capability's,
 * not the vendor's - a second decision provider answers to the same knobs.)
 */
public record DecisionPolicyView(Boolean costRouting, Boolean agentRouting, Double simpleThreshold) {

    /** The no-tenant-context / no-row view - everything inherited. */
    public static final DecisionPolicyView INHERIT = new DecisionPolicyView(null, null, null);

    /** The capability this view decodes {@code capability_policy} rows for. */
    public static final Capability CAPABILITY = Capability.DECISION;

    /**
     * Decode a stored overrides map into the typed view - unknown keys are
     * ignored (the validator rejects them at write time; a stale/foreign key
     * in an old row shouldn't break reads) and wrong-typed values degrade to
     * null-inherit rather than throwing, same fail-open posture as the rest
     * of the decision path.
     */
    public static DecisionPolicyView from(Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return INHERIT;
        }
        return new DecisionPolicyView(
            asBoolean(overrides.get("costRouting")),
            asBoolean(overrides.get("agentRouting")),
            asDouble(overrides.get("simpleThreshold"))
        );
    }

    private static Boolean asBoolean(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
