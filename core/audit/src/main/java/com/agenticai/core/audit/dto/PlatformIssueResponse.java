package com.agenticai.core.audit.dto;

import com.agenticai.core.audit.IssueCategory;
import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.IssueStatus;
import com.agenticai.core.audit.PlatformIssue;
import java.time.Instant;
import java.util.UUID;

public record PlatformIssueResponse(
    UUID id,
    IssueSource source,
    String sourceId,
    IssueCategory category,
    IssueSeverity severity,
    IssueStatus status,
    String title,
    String message,
    int occurrenceCount,
    Instant firstSeenAt,
    Instant lastSeenAt,
    String acknowledgedBy,
    Instant acknowledgedAt,
    Instant resolvedAt
) {
    public static PlatformIssueResponse from(PlatformIssue issue) {
        return new PlatformIssueResponse(
            issue.getId(),
            issue.getSource(),
            issue.getSourceId(),
            issue.getCategory(),
            issue.getSeverity(),
            issue.getStatus(),
            issue.getTitle(),
            issue.getMessage(),
            issue.getOccurrenceCount(),
            issue.getFirstSeenAt(),
            issue.getLastSeenAt(),
            issue.getAcknowledgedBy(),
            issue.getAcknowledgedAt(),
            issue.getResolvedAt()
        );
    }
}
