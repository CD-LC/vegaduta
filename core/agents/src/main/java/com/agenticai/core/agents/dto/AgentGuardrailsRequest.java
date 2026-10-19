package com.agenticai.core.agents.dto;

import java.util.Set;

/**
 * Request to update an agent's enabled guardrails.
 */
public class AgentGuardrailsRequest {

    /** Set of guardrail types to enable (e.g., "profanity", "jailbreak", "pii"). */
    private Set<String> guardrails;

    public AgentGuardrailsRequest() {
    }

    public AgentGuardrailsRequest(Set<String> guardrails) {
        this.guardrails = guardrails;
    }

    public Set<String> getGuardrails() {
        return guardrails;
    }

    public void setGuardrails(Set<String> guardrails) {
        this.guardrails = guardrails;
    }
}
