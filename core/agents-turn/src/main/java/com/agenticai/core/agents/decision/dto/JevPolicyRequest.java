package com.agenticai.core.agents.decision.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * PUT /api/jev/policy body - the tenant's overrides over the platform's Jev
 * defaults. Every field nullable: absent means "inherit", which is also how
 * a field gets RESET to the platform value (there is no delete - a policy of
 * all-nulls IS the inherit state).
 *
 * <p>{@code simpleThreshold} is deliberately bounded narrow: a tenant setting
 * it near 0 would let Jev downgrade almost every "probably complex" turn to
 * the economy model on a coin-flip noul - silently degrading quality. 0.5 is
 * the meaningful-agreement floor (Jev is more sure it's simple than not);
 * 0.99 is "only downgrade when almost certain".
 */
public record JevPolicyRequest(
    Boolean costRouting,
    Boolean agentRouting,
    @DecimalMin(value = "0.5", message = "simpleThreshold must be at least 0.5 - below that Jev's 'yes' is weaker than a coin flip")
    @DecimalMax(value = "0.99", message = "simpleThreshold must be at most 0.99 - above that nothing ever qualifies as simple")
    Double simpleThreshold
) {
}
