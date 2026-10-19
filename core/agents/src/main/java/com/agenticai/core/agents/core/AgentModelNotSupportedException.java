package com.agenticai.core.agents.core;

/**
 * Thrown by {@link AgentService#updateModel} when the requested {@code
 * (providerName, model)} pair isn't a real, known model for that provider
 * per {@link com.agenticai.core.providers.ProviderModelCatalogService}.
 *
 * <p>Closes a real production bug: before this check existed, {@code
 * updateModel} would happily persist any pair - including a provider/model
 * mismatch like {@code providerName=GEMINI, model="claude-sonnet-4-5"} (the
 * old Anthropic model string left over from before the provider was
 * switched, e.g. via the web console's "Change model" editor not resetting
 * its free-text model field when the provider dropdown changed). The
 * mismatch would only surface later, at actual chat time, as an opaque
 * upstream 404 from {@code GoogleGenAiChatModel} ("models/claude-sonnet-4-5
 * is not found") - a confusing 500 instead of an immediate, actionable 400
 * at the moment the bad pair was actually saved.
 */
public class AgentModelNotSupportedException extends RuntimeException {

    public AgentModelNotSupportedException(String message) {
        super(message);
    }
}
