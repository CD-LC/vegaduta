package com.agenticai.core.platform;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the operator table - the most recent sandboxes with enough
 * identity to go find them in psql (schema name) or in the tenant roster
 * (tenant id) when something looks wrong.
 *
 * <p>Deliberately does NOT carry {@code token_hash} or {@code created_ip}: the
 * hash is a bearer credential's fingerprint and the IP is retained purely as
 * abuse forensics for the row's lifetime (see V126's header). Neither belongs
 * in a dashboard payload just because a dashboard could render it.
 *
 * @param status derived, never stored: {@code CONVERTED} &gt; {@code EXPIRED}
 *     &gt; {@code LIVE}, checked in that order, so a sandbox that converted and
 *     then expired still reads as the outcome that matters.
 */
public record DemoRecentSandbox(
    UUID id,
    UUID tenantId,
    String tenantName,
    String tenantSchema,
    String email,
    Instant createdAt,
    Instant expiresAt,
    Instant expiredAt,
    UUID convertedTenantId,
    Instant convertedAt,
    int messageCount,
    String status
) {
}
