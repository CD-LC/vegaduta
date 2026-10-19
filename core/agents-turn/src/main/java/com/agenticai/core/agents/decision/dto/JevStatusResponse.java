package com.agenticai.core.agents.decision.dto;

import com.agenticai.core.agents.decision.DecisionModel;

/**
 * The "is Jev configured, and whose key is it" answer {@code GET
 * /api/jev/status} returns - the console renders "Your key", "Platform key" or
 * "Not configured" straight off {@code source} + {@code configured}. Compat
 * shim over {@code DecisionStatusResponse}: same four fields, Jev-named.
 *
 * @param enabled           {@code app.jev.enabled} platform kill-switch
 * @param configured        a usable key resolves for this tenant right now
 * @param source            which key wins for this tenant (null when none)
 * @param tenantCredential  the tenant's own stored key's status, when one exists
 */
public record JevStatusResponse(
    boolean enabled,
    boolean configured,
    DecisionModel.KeySource source,
    JevCredentialResponse tenantCredential
) {
}
