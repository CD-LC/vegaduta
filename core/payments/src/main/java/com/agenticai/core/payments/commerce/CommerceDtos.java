package com.agenticai.core.payments.commerce;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response records for the commerce-mandate surface. */
public final class CommerceDtos {

    private CommerceDtos() {
    }

    public record CreateIntentRequest(
        @NotNull UUID agentId,
        @Size(max = 2000) String intentText,
        @Size(max = 50) List<@Size(max = 128) String> categories,
        @Positive long maxTotalMinor,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull Instant expiresAt
    ) {
    }

    public record CartItem(
        @Size(max = 128) String sku,
        @NotBlank @Size(max = 200) String name,
        @Positive long qty,
        @Positive long unit_price_minor
    ) {
    }

    public record CreateCartRequest(
        @NotEmpty List<@Valid @NotNull Map<String, Object>> items,
        @Size(max = 256) String payee,
        Instant expiresAt
    ) {
    }

    public record RevokeRequest(
        @Size(max = 512) String reason
    ) {
    }

    public record MandateResponse(
        UUID id, String kind, UUID intentMandateId, UUID agentId, String buyerUserId,
        String currency, Long maxTotalMinor, Long cartTotalMinor, Object items,
        String payee, Object categories, String intentText, String status,
        String canonicalHashSha256, String keyId, boolean signatureValid,
        String buyerAuthJson, Instant authorizedAt, Long walletDebitRef,
        Instant expiresAt, Instant createdAt, Instant revokedAt, String revokeReason
    ) {
    }

    public record EventResponse(
        Long id, String eventType, String actor, String detail, Instant createdAt
    ) {
    }
}
