package com.agenticai.core.agents.decision.dto;

import com.agenticai.core.agents.decision.DecisionPolicyView;
import java.time.Instant;
import java.util.Map;

/**
 * The tenant's decision policy as the console renders it - nullable fields
 * echoed verbatim so the UI can show "Inherit" distinctly from an explicit
 * on/off. A tenant with no policy row at all gets the all-null response,
 * which is byte-identical to "row exists, all inherited" - by design, since
 * the two states behave identically. Compat shim over the {@code
 * capability_policy} row for {@code capability='decision'}.
 */
public record JevPolicyResponse(
    Boolean costRouting,
    Boolean agentRouting,
    Double simpleThreshold,
    Instant updatedAt
) {
    /** From a stored {@code capability_policy} overrides map + its row's updatedAt. */
    public static JevPolicyResponse from(Map<String, Object> overrides, Instant updatedAt) {
        DecisionPolicyView view = DecisionPolicyView.from(overrides);
        return new JevPolicyResponse(
            view.costRouting(),
            view.agentRouting(),
            view.simpleThreshold(),
            updatedAt
        );
    }

    /** The no-row response: every field inherited. */
    public static JevPolicyResponse inherit() {
        return new JevPolicyResponse(null, null, null, null);
    }
}
