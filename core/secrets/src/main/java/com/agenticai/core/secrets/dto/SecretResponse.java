package com.agenticai.core.secrets.dto;

import com.agenticai.core.secrets.SecretKind;
import com.agenticai.core.secrets.SecretScope;
import com.agenticai.core.secrets.SecretStatus;
import com.agenticai.core.secrets.TenantSecret;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the vault tells the UI about a secret. There is no {@code value} field
 * and there never will be one - a stored secret is write-only from the API's
 * point of view. {@code preview} (the last four characters) is the only part
 * of the plaintext that ever leaves the server, and it exists so a user can
 * tell two keys apart when deciding which to rotate.
 *
 * <p>{@code reference} is the string to paste into an agent, tool or MCP
 * config. Returning it here rather than making people construct it by hand is
 * what makes the indirection usable: the copyable thing on screen is the safe
 * thing, so the shortest path is also the correct one.
 */
public record SecretResponse(
    UUID id,
    String name,
    String reference,
    SecretScope scope,
    SecretKind kind,
    String description,
    String preview,
    SecretStatus status,
    /** Whether sandboxed tenant code may receive this value - see V228. */
    boolean codeToolAccess,
    /** Destination host patterns for URL-bound use (SEC-006, V365.4). Empty = never sent to a URL. */
    List<String> allowedHosts,
    Instant expiresAt,
    boolean expired,
    Instant lastUsedAt,
    Instant rotatedAt,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {

    public static SecretResponse from(TenantSecret secret) {
        return new SecretResponse(
            secret.getId(),
            secret.getName(),
            "{{secret." + secret.getName() + "}}",
            secret.getScope(),
            secret.getKind(),
            secret.getDescription(),
            secret.getValuePreview(),
            secret.getStatus(),
            secret.isCodeToolAccess(),
            secret.getAllowedHosts(),
            secret.getExpiresAt(),
            secret.getExpiresAt() != null && secret.getExpiresAt().isBefore(Instant.now()),
            secret.getLastUsedAt(),
            secret.getRotatedAt(),
            secret.getCreatedBy(),
            secret.getCreatedAt(),
            secret.getUpdatedAt());
    }
}
