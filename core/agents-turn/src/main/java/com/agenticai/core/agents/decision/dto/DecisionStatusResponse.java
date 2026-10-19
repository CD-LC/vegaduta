package com.agenticai.core.agents.decision.dto;

import com.agenticai.core.agents.decision.DecisionModel;
import com.agenticai.core.providers.capability.ProviderCredentialResponse;

/**
 * The "is the decision capability configured, and whose key is it" answer
 * {@code GET /api/capabilities/decision/status} returns - the generic version
 * of {@code JevStatusResponse}, same four fields. {@code providerKey} names
 * the vendor actually serving the capability so the console can render
 * "TypeSafe Jev" today and whoever comes next tomorrow without a shape
 * change.
 *
 * @param enabled           any registered decision provider is enabled (platform flag)
 * @param configured        a usable key resolves for this tenant right now
 * @param providerKey       the provider_key of the model that would serve a call (null when none)
 * @param source            which key wins for this tenant (null when none)
 * @param tenantCredential  the tenant's own stored credential for the active provider, when one exists
 */
public record DecisionStatusResponse(
    boolean enabled,
    boolean configured,
    String providerKey,
    DecisionModel.KeySource source,
    ProviderCredentialResponse tenantCredential
) {
}
