package com.agenticai.core.google.dto;

import com.agenticai.core.google.GoogleCredential;
import java.time.Instant;
import java.util.List;

/**
 * Never includes the refresh token - see {@code GoogleCredential}'s javadoc.
 *
 * <p>{@code connectionScope} (added with V215's per-end-user credentials) says
 * whose account the caller is effectively resolving: {@code "PERSONAL"} means
 * the caller's own connected Google account, {@code "TENANT"} means the
 * admin-connected tenant-wide fallback. Additive on purpose - the status
 * endpoint keeps returning the effective credential the way it always has, so
 * the existing Integrations panel and its {@code api.ts} client keep working
 * unchanged, while a frontend that wants to offer "Connect your own account"
 * can distinguish "mine" from "the tenant's" without any new endpoint call.
 * The raw subject is deliberately NOT echoed: the caller either is that
 * subject (personal) or has no business learning which admin's row backs the
 * tenant fallback beyond the already-shown {@code connectedEmail}.
 */
public record GoogleConnectionResponse(
    String connectedEmail,
    List<String> grantedScopes,
    String status,
    Instant connectedAt,
    String connectionScope
) {
    public static GoogleConnectionResponse from(GoogleCredential credential) {
        return new GoogleConnectionResponse(
            credential.getConnectedEmail(),
            List.of(credential.getGrantedScopes().split(" ")),
            credential.getStatus().name(),
            credential.getCreatedAt(),
            credential.isTenantWide() ? "TENANT" : "PERSONAL"
        );
    }
}
