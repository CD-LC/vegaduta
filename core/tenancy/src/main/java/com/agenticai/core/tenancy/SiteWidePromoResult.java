package com.agenticai.core.tenancy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@link SiteWidePromoService#grantUnlimitedToAllActiveTenants} -
 * summarizes what happened so the triggering admin can confirm coverage
 * (e.g. against {@code SELECT count(*) FROM control_plane.tenant WHERE status
 * = 'ACTIVE'}) without a separate follow-up query, and carries what {@link
 * SiteWidePromoService#sendPromoEmails} needs if the caller opts to send.
 */
public record SiteWidePromoResult(
    List<SiteWidePromoService.GrantedTenant> grantedTenants,
    List<UUID> skippedAlreadyUnlimitedTenantIds,
    Instant grantedAt,
    Instant expiresAt,
    String reason
) {
}
