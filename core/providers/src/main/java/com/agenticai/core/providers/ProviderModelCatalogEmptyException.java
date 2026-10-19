package com.agenticai.core.providers;

/**
 * Thrown when a caller needs a real model id for an already-active provider
 * (e.g. {@code AssistService}'s "Ask AI" helper or {@code
 * WorkflowGraphGeneratorService}, both picking the first entry off {@link
 * ProviderModelCatalogService#listModels}) but that provider's model catalog
 * is empty - a transient catalog-refresh failure, or genuinely no models
 * synced yet for that provider. Not anything the tenant themselves
 * misconfigured (contrast {@link NoActiveProviderException}, which is about
 * no provider being activated at all) - the tenant already has an active
 * BYOK credential for this exact provider, the catalog cache is just empty.
 *
 * <p>Maps to HTTP 503 in {@code GlobalExceptionHandler}, same "the platform
 * isn't ready to serve this specific request right now" reasoning as {@code
 * FreeTierProviderPoolEmptyException}. Previously both call sites silently
 * fell back to an Anthropic-only default model id regardless of the active
 * provider, which - for any non-Anthropic provider with an empty catalog -
 * just traded one failure for a worse, less obvious one further downstream
 * (the exact "Anthropic model id sent to a non-Anthropic gateway" 404 both
 * call sites' {@code resolveModelFor} methods were originally written to
 * avoid).
 */
public class ProviderModelCatalogEmptyException extends RuntimeException {

    public ProviderModelCatalogEmptyException(ProviderName provider) {
        super("No models are available for " + provider + " right now. Please try again in a moment, "
            + "or contact support if this persists.");
    }
}
