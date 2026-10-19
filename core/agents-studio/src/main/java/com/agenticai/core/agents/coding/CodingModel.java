package com.agenticai.core.agents.coding;

/**
 * One text-in, text-out model call for the coding harness. Production adapts
 * {@code AgentLabCompletionService} (the agent's own model, tool-less, no memory, budget and rate-limit
 * gates firing on every call); tests supply scripted fakes.
 */
@FunctionalInterface
public interface CodingModel {

    String complete(String systemPrompt, String userMessage);
}
