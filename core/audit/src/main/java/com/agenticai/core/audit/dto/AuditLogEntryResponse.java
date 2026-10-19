package com.agenticai.core.audit.dto;

import com.agenticai.core.audit.AuditLogEntry;
import com.agenticai.core.audit.AuditOutcome;
import java.time.Instant;
import java.util.UUID;

public record AuditLogEntryResponse(
    UUID id,
    String actorId,
    String actionType,
    String targetType,
    String targetId,
    AuditOutcome outcome,
    String detail,
    Instant createdAt
) {
    public static AuditLogEntryResponse from(AuditLogEntry entry) {
        return new AuditLogEntryResponse(
            entry.getId(),
            entry.getActorId(),
            entry.getActionType(),
            entry.getTargetType(),
            entry.getTargetId(),
            entry.getOutcome(),
            entry.getDetail(),
            entry.getCreatedAt()
        );
    }
}
