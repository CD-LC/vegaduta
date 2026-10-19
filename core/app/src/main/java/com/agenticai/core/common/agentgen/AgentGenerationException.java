package com.agenticai.core.common.agentgen;

/**
 * 422 for the prompt-to-agent endpoint - the model produced a spec that
 * could not be turned into a real agent (unparseable JSON, no
 * systemPrompt). Distinct from validation errors on the REQUEST itself
 * (400s via bean validation on {@code AgentGenerationRequest}).
 */
public class AgentGenerationException extends RuntimeException {

    public AgentGenerationException(String message) {
        super(message);
    }
}
