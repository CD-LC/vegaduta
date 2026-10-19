package com.agenticai.core.agents.trust;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response records for the delegation + manifest surface. */
public final class DelegationDtos {

    private DelegationDtos() {
    }

    /** agentId comes from the path - never the body (path is authoritative). */
    public record CreateGrantRequest(
        @NotBlank String audience,
        @NotEmpty List<@NotBlank String> scopes,
        @Positive Long maxTokenTtlSeconds,
        @NotNull Instant expiresAt,
        UUID walletId,
        /** Lifetime spend cap in minor units across all tokens; pay:* only. */
        @Positive Long spendCapMinor
    ) {
    }

    public record MintTokenRequest(
        @Positive Long ttlSeconds
    ) {
    }

    public record IntrospectRequest(
        @NotBlank @Size(max = 4096) String token
    ) {
    }

    public record RevokeRequest(
        String reason
    ) {
    }

    public record GrantResponse(
        UUID id, UUID agentId, String grantedBy, String audience, List<String> scopes,
        long maxTokenTtlSeconds, Instant expiresAt, String status, UUID walletId,
        Long spendCapMinor, long spentMinor,
        Instant createdAt, Instant revokedAt, String revokeReason
    ) {
    }

    public record MintedTokenResponse(
        String token, String jti, Instant expiresAt, UUID grantId
    ) {
    }

    static GrantResponse toGrantResponse(DelegationGrant g, List<String> scopes) {
        return new GrantResponse(
            g.getId(), g.getAgentId(), g.getGrantedBy(), g.getAudience(), scopes,
            g.getMaxTokenTtlSeconds(), g.getExpiresAt(), g.getStatus().name(),
            g.getWalletId(), g.getSpendCapMinor(), g.getSpentMinor(),
            g.getCreatedAt(), g.getRevokedAt(), g.getRevokeReason());
    }
}
