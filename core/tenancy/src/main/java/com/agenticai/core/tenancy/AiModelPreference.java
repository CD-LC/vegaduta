package com.agenticai.core.tenancy;

/**
 * One stored override in a tenant's "AI Model Control" JSON bag ({@code
 * Tenant#aiModelPreferences}) - "for this feature, use this provider/model
 * instead of whatever the calling code would otherwise resolve."
 *
 * <p>{@code providerName} is a plain string here, not {@code
 * com.agenticai.core.providers.ProviderName} - core/tenancy cannot depend on
 * core/providers (that module already depends back on core/tenancy
 * transitively via core/sarvam, see providers/pom.xml's own comment; adding
 * the reverse edge would create a cycle). {@link
 * com.agenticai.core.chat.gateway.LlmGatewayService}, which DOES sit on top
 * of both modules, is what parses this string against the real enum at the
 * routing decision point - see that class's javadoc.
 *
 * @param providerName one of {@code ProviderName}'s enum constant names
 *     (e.g. {@code "ANTHROPIC"}, {@code "OPENAI"}) - validated against
 *     {@code TenantAiModelPreferenceService#KNOWN_PROVIDER_NAMES} at write
 *     time so a typo here can't silently become a permanent no-op override.
 * @param model the provider-specific model identifier (e.g. {@code
 *     "claude-sonnet-4-5-20250929"}) - opaque to this record, not validated
 *     against a catalog (same posture {@code Agent#model} already has).
 */
public record AiModelPreference(String providerName, String model) {
}
