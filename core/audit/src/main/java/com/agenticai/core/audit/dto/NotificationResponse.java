package com.agenticai.core.audit.dto;

import com.agenticai.core.audit.Notification;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationSeverity;
import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
    UUID id,
    NotificationCategory category,
    String title,
    String body,
    NotificationSeverity severity,
    boolean read,
    Instant createdAt,
    String link,
    UUID relatedIssueId
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
            n.getId(), n.getCategory(), n.getTitle(), n.getBody(), n.getSeverity(),
            n.isRead(), n.getCreatedAt(), n.getLink(), n.getRelatedIssueId()
        );
    }
}
