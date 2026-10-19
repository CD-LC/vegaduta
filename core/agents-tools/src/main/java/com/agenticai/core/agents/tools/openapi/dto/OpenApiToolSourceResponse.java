package com.agenticai.core.agents.tools.openapi.dto;

import com.agenticai.core.agents.tools.openapi.OpenApiToolSource;
import java.time.Instant;
import java.util.UUID;

/**
 * What the console sees for a source. Deliberately NO ciphertext and no plaintext: {@code
 * hasSecret} + {@code secretPreview} (last four chars) is everything the UI needs to render "key
 * stored ...4242".
 */
public record OpenApiToolSourceResponse(
        UUID id,
        String name,
        String toolPrefix,
        String specUrl,
        String specTitle,
        String specVersion,
        String baseUrl,
        String authType,
        String authHeaderName,
        // OAuth2 client-credentials settings - all non-secret (the client SECRET
        // is only ever represented by hasSecret/secretPreview below).
        String oauth2TokenUrl,
        String oauth2ClientId,
        String oauth2Scope,
        String oauth2TokenStyle,
        boolean hasSecret,
        String secretPreview,
        boolean enabled,
        long toolCount,
        long enabledToolCount,
        Instant lastRefreshedAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {
    public static OpenApiToolSourceResponse from(
            OpenApiToolSource source, long toolCount, long enabledToolCount) {
        return new OpenApiToolSourceResponse(
                source.getId(),
                source.getName(),
                source.getToolPrefix(),
                source.getSpecUrl(),
                source.getSpecTitle(),
                source.getSpecVersion(),
                source.getBaseUrl(),
                source.getAuthType() == null ? "NONE" : source.getAuthType().name(),
                source.getAuthHeaderName(),
                source.getOauth2TokenUrl(),
                source.getOauth2ClientId(),
                source.getOauth2Scope(),
                source.getOauth2TokenStyle(),
                source.hasSecret(),
                source.getSecretPreview(),
                source.isEnabled(),
                toolCount,
                enabledToolCount,
                source.getLastRefreshedAt(),
                source.getLastError(),
                source.getCreatedAt(),
                source.getUpdatedAt());
    }
}
