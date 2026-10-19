package com.agenticai.core.platform;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.IssueStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /api/platform/issues} - the same deduplicated
 * failure record {@code PlatformIssueResponse} exposes on the tenant
 * console's own Issues portal (core/audit's {@code platform_issue} table -
 * despite the class name, that table is tenant-scoped, one per tenant
 * schema, same as every other {@code @Entity} in this codebase with no
 * explicit {@code schema=} annotation), tagged with which tenant it came
 * from so a platform-wide list makes sense. See {@link
 * PlatformObservabilityService#listIssues} for how these get collected.
 */
public record PlatformIssueSummary(
    java.util.UUID tenantId,
    String tenantName,
    UUID issueId,
    IssueSource source,
    String sourceId,
    IssueSeverity severity,
    IssueStatus status,
    String title,
    int occurrenceCount,
    Instant firstSeenAt,
    Instant lastSeenAt
) {
}
