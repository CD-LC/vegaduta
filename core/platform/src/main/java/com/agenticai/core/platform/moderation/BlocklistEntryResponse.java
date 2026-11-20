package com.agenticai.core.platform.moderation;

import java.util.UUID;

public record BlocklistEntryResponse(
        UUID id,
        String type,
        String value,
        String reason,
        boolean active,
        String createdBy,
        String createdAt) {}
