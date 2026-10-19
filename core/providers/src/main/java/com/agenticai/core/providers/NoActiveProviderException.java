package com.agenticai.core.providers;

/**
 * Thrown when a caller needs "any active LLM provider" (e.g. the Ask-AI
 * drafting helper - see {@code AssistService} in {@code core/agents}) and the
 * tenant hasn't activated one yet. Maps to HTTP 400, distinct from {@link
 * ProviderNotActiveException} which is about one specific, already-chosen
 * provider (e.g. an agent's configured provider going inactive).
 */
public class NoActiveProviderException extends RuntimeException {

    public NoActiveProviderException() {
        super("No active LLM provider - activate one on the Providers page first. "
            + "(Self-hosted with no hosted LLM pool: add your own provider key there, or have an operator "
            + "enable a hosted provider; see app.llm.default-source.)");
    }

    protected NoActiveProviderException(String message) {
        super(message);
    }
}
