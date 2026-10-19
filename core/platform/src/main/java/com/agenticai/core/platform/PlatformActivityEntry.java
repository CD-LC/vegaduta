package com.agenticai.core.platform;

import com.agenticai.core.audit.AuditOutcome;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /api/platform/activity} - the platform-wide
 * counterpart to {@code AdminEventTail}'s tenant-scoped audit-log tail
 * (core/audit's {@code audit_log_entry} table, tenant-scoped like every
 * other {@code @Entity} with no explicit {@code schema=} annotation), tagged
 * with which tenant recorded it. "Activities done by client" - direct
 * feedback - reads as this: what actions actually happened, by whom, across
 * every tenant, not simulated/decorative.
 */
public record PlatformActivityEntry(
    UUID tenantId,
    String tenantName,
    UUID entryId,
    String actorId,
    String actionType,
    String targetType,
    String targetId,
    AuditOutcome outcome,
    String detail,
    Instant createdAt
) {
}
