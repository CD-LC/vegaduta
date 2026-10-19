package com.agenticai.core.platform.abuse;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase P5 (Fraud & Anomaly) - one recent {@code ABUSE_ANOMALY} issue,
 * flattened with its owning tenant for the admin "recent anomalies" read.
 * Mirrors the shape of {@code PlatformIssueSummary} (the platform observability
 * page's issue row), scoped to abuse anomalies.
 *
 * @param principal the flagged principal ({@code tenantId:userId}, the issue's {@code sourceId})
 */
public record AbuseAnomalyEntry(
    UUID tenantId,
    String tenantName,
    UUID issueId,
    String principal,
    IssueSeverity severity,
    IssueStatus status,
    String title,
    int occurrenceCount,
    Instant firstSeenAt,
    Instant lastSeenAt
) {}
