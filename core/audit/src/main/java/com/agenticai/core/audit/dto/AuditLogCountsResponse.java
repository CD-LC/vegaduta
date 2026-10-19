package com.agenticai.core.audit.dto;

import com.agenticai.core.audit.AuditOutcome;
import java.time.Instant;
import java.util.Map;

/**
 * {@code GET /api/audit-log/counts}: how many audit entries of one action type fall in a window, per outcome.
 *
 * <p>The filters and the RESOLVED window ({@code from} inclusive, {@code to} exclusive) are echoed back, so a
 * caller that relied on the defaults can caption its numbers with the real range. {@code byOutcome} always
 * carries every {@link AuditOutcome} (zero when none), and {@code total} is their sum.
 */
public record AuditLogCountsResponse(
    String actionType,
    String targetId,
    String actorId,
    String detailContains,
    Instant from,
    Instant to,
    long total,
    Map<AuditOutcome, Long> byOutcome
) {
}
