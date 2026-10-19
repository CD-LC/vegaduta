package com.agenticai.core.providers;

/**
 * Thrown when an agent is configured for a provider that has no ACTIVE
 * credential stored — either it was never activated, or its last validation
 * failed. Maps to HTTP 400.
 *
 * <p>{@link #getMessage()} is what {@link
 * com.agenticai.core.common.GlobalExceptionHandler#handleNotActive} returns
 * verbatim as the API error body — there is no separate translation step for
 * this exception the way {@link com.agenticai.core.chat.ProviderErrorTranslator}
 * provides for upstream model-call failures. It previously read "No active
 * credential for provider PLATFORM_HOSTED. Activate it via POST /api/providers
 * first." — an internal enum value plus a literal HTTP method and route,
 * surfaced verbatim in the Playground chat as if it were the assistant
 * talking. Kept human-readable and provider-name-free here instead; the
 * provider is still named in server logs by whichever caller catches this
 * (e.g. {@code AgentChatService}), just not in the message a tenant sees.
 */
public class ProviderNotActiveException extends RuntimeException {

    public ProviderNotActiveException(ProviderName providerName) {
        super("This agent isn't connected to an active AI provider yet. "
            + "Ask a workspace admin to connect one in Providers, then try again.");
    }
}
