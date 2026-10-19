package com.agenticai.core.agents.guardrail;

/**
 * Thrown when attempting to enable a guardrail that is not available for
 * the tenant's current plan tier.
 */
public class GuardrailNotAvailableForPlanException extends RuntimeException {

    private final String guardrailType;
    private final String requiredPlan;

    public GuardrailNotAvailableForPlanException(
        String guardrailType,
        String requiredPlan
    ) {
        super(
            "Guardrail '" + guardrailType + "' requires " + requiredPlan + " plan or higher"
        );
        this.guardrailType = guardrailType;
        this.requiredPlan = requiredPlan;
    }

    public String getGuardrailType() {
        return guardrailType;
    }

    public String getRequiredPlan() {
        return requiredPlan;
    }
}
