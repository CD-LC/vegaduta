package com.agenticai.core.providers;

/**
 * Tenant-facing "what is the platform-hosted LLM pool currently using"
 * view - direct feedback: "application should show what platform llm is
 * configured." The counterpart to admin-only {@link
 * PlatformHostedLlmProviderResponse} (which lists every row in the pool,
 * gated behind {@code ROLE_platform-admin}): this exposes only the single
 * row {@link PlatformHostedLlmProviderService#resolveTopPriorityEnabled}
 * would actually route the next FREE-tier (or {@code usePlatformLlm}) chat
 * call to, trimmed to just {@code label}/{@code model} - no {@code baseUrl},
 * {@code id}, {@code priority}, or region, since those are admin operational
 * detail a tenant has no use for. Deliberately never touches the encrypted
 * API key (unlike {@code resolveTopPriorityEnabled}, which decrypts it for
 * actual chat-client construction) - see {@link
 * PlatformHostedLlmProviderService#getActiveSummary} for why this can't
 * just reuse that method.
 *
 * <p>{@code available} is {@code false} whenever the pool's master switch
 * ({@code HostedLlmPoolSettings#isEnabled}) is off or no row is enabled -
 * same condition {@code resolveTopPriorityEnabled} checks, kept in sync
 * deliberately so this view never claims a provider is active when a real
 * chat call would actually hit {@code FreeTierProviderPoolEmptyException}.
 */
public record PlatformHostedLlmActiveResponse(
    boolean available,
    String label,
    String model
) {

    private static final PlatformHostedLlmActiveResponse UNAVAILABLE =
        new PlatformHostedLlmActiveResponse(false, null, null);

    public static PlatformHostedLlmActiveResponse unavailable() {
        return UNAVAILABLE;
    }

    public static PlatformHostedLlmActiveResponse from(PlatformHostedLlmProvider provider) {
        return new PlatformHostedLlmActiveResponse(true, provider.getLabel(), provider.getModel());
    }
}
