package com.agenticai.core.agents.hosted;

/**
 * Thrown when a FREE-plan tenant tries to chat but no {@code
 * PlatformHostedLlmProvider} row is enabled - a platform-operator
 * configuration gap (nobody has added a Groq/Together/etc. key to the
 * Ecosystem Maintenance page yet), not something the tenant themselves can
 * fix. Deliberately a distinct exception from {@code NoActiveProviderException}
 * (which is about a tenant's own missing BYOK activation) - the fix here is
 * "the platform admin needs to configure a hosted provider," a different
 * audience and action entirely. Maps to HTTP 503 in {@code
 * GlobalExceptionHandler}: the platform itself isn't ready to serve this
 * request, not a client mistake.
 */
public class FreeTierProviderPoolEmptyException extends RuntimeException {

    public FreeTierProviderPoolEmptyException() {
        super("No AI model is available: no hosted LLM provider is configured. An operator can enable one "
            + "under Platform Admin (hosted LLM pool), or set app.llm.default-source=byok and add your own "
            + "provider key on the Providers page.");
    }
}
