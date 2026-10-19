package com.agenticai.core.providers.dto;

/**
 * Backs {@code GET /api/providers/availability} - the correct signal for
 * "can this tenant create/chat with an agent at all," which {@code
 * GET /api/providers} (BYOK credentials only) is NOT, and was never meant
 * to be. Added because {@code web/}'s {@code AgentWorkbench.tsx} create-gate
 * was reading BYOK-list-length as that signal - always empty for a FREE
 * tenant by design ({@code ProviderService#activate} rejects FREE tenants
 * outright, since they "automatically use the platform-hosted LLM and do
 * not support activating your own provider"), so the gate incorrectly
 * blocked agent creation for every FREE tenant even though {@code
 * AgentService#create} itself has no such check server-side.
 *
 * <p>{@code hasByokProvider} - true if this tenant has at least one
 * activated BYOK credential (what {@code GET /api/providers}'s list length
 * already told the frontend). {@code platformHostedAvailable} - true only
 * when this tenant is actually eligible to route through the platform pool
 * (same condition {@code AgentChatService#buildChatClientFor} uses: {@code
 * plan == FREE || usePlatformLlm}) AND the pool itself has at least one
 * enabled row ({@code PlatformHostedLlmProviderService#resolveTopPriorityEnabled}).
 * {@code canCreateAgents} is the actual gate value - {@code
 * hasByokProvider || platformHostedAvailable} - {@code web/} should switch
 * to reading this field directly rather than re-deriving the OR itself.
 */
public record ProviderAvailabilityResponse(
    boolean hasByokProvider,
    boolean platformHostedAvailable,
    boolean canCreateAgents
) {
    public static ProviderAvailabilityResponse of(boolean hasByokProvider, boolean platformHostedAvailable) {
        return new ProviderAvailabilityResponse(hasByokProvider, platformHostedAvailable, hasByokProvider || platformHostedAvailable);
    }
}
