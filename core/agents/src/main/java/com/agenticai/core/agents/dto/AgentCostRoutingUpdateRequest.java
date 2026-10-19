package com.agenticai.core.agents.dto;

import com.agenticai.core.providers.ProviderName;

/**
 * PATCH /api/agents/{id}/cost-routing - #1077 fast-follow (Master Roadmap
 * #18, cost optimization: routing), the "routing" half response-caching's
 * own javadoc deliberately left open. Lets a tenant-admin opt an agent into
 * RouteLLM/NotDiamond-style per-turn routing to a cheaper "economy" model
 * for turns {@code QueryComplexityClassifier} judges simple enough to trust
 * to it - see {@code AgentChatService#buildChatClientFor} for the read path.
 *
 * <p>Same nullable-provider shape as {@link AgentFallbackUpdateRequest}:
 * {@code economyProviderName == null} clears any previously-configured
 * economy model regardless of {@code enabled}'s value. When {@code
 * economyProviderName} is non-null, {@code economyModel} is required - see
 * {@link com.agenticai.core.agents.core.AgentService#setCostRouting}.
 */
public record AgentCostRoutingUpdateRequest(
    boolean enabled,
    ProviderName economyProviderName,
    String economyModel,
    // Per-agent Jev sensitivity (V334): minimum noul probability before a
    // "probably complex" turn may route to the economy model. null = inherit
    // the tenant policy's simpleThreshold, else the platform default (0.75).
    // Same [0.5, 0.99] bounds as JevPolicyRequest - below 0.5 Jev's "yes" is
    // weaker than a coin flip; above 0.99 nothing ever qualifies.
    @jakarta.validation.constraints.DecimalMin(value = "0.5", message = "jevThreshold must be at least 0.5")
    @jakarta.validation.constraints.DecimalMax(value = "0.99", message = "jevThreshold must be at most 0.99")
    Double jevThreshold
) {
    /** Trims {@code economyModel} for the same reason {@link AgentModelUpdateRequest} trims its own - a padded model id is sent verbatim to the provider and rejected. */
    public AgentCostRoutingUpdateRequest {
        if (economyModel != null) {
            economyModel = economyModel.trim();
        }
    }
}
