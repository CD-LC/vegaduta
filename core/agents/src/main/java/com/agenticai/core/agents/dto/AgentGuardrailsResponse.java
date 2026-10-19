package com.agenticai.core.agents.dto;

import java.util.Set;

/**
 * Response showing an agent's enabled guardrails and available options.
 */
public class AgentGuardrailsResponse {

    /** Guardrails currently enabled on this agent */
    private Set<String> enabledGuardrails;

    /** All guardrails available for this agent's plan tier */
    private Set<String> availableGuardrails;

    /** Guardrails that are locked (require higher tier) */
    private Set<String> lockedGuardrails;

    /** Human-readable descriptions of each guardrail */
    private java.util.Map<String, String> guardrailDescriptions;

    public AgentGuardrailsResponse() {
    }

    public AgentGuardrailsResponse(
        Set<String> enabledGuardrails,
        Set<String> availableGuardrails,
        Set<String> lockedGuardrails,
        java.util.Map<String, String> guardrailDescriptions
    ) {
        this.enabledGuardrails = enabledGuardrails;
        this.availableGuardrails = availableGuardrails;
        this.lockedGuardrails = lockedGuardrails;
        this.guardrailDescriptions = guardrailDescriptions;
    }

    public Set<String> getEnabledGuardrails() {
        return enabledGuardrails;
    }

    public void setEnabledGuardrails(Set<String> enabledGuardrails) {
        this.enabledGuardrails = enabledGuardrails;
    }

    public Set<String> getAvailableGuardrails() {
        return availableGuardrails;
    }

    public void setAvailableGuardrails(Set<String> availableGuardrails) {
        this.availableGuardrails = availableGuardrails;
    }

    public Set<String> getLockedGuardrails() {
        return lockedGuardrails;
    }

    public void setLockedGuardrails(Set<String> lockedGuardrails) {
        this.lockedGuardrails = lockedGuardrails;
    }

    public java.util.Map<String, String> getGuardrailDescriptions() {
        return guardrailDescriptions;
    }

    public void setGuardrailDescriptions(java.util.Map<String, String> guardrailDescriptions) {
        this.guardrailDescriptions = guardrailDescriptions;
    }
}
