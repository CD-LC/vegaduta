package com.agenticai.core.platform.moderation;

import java.util.UUID;

/**
 * One review-queue row, shaped from the engine's tenant-schema entity. Differences from the
 * pre-unification response, deliberate and safe because that contract had zero callers: {@code
 * content} is the FULL flagged content (the engine's queue stores it complete, by design, so the
 * reviewer can actually judge it - not a 280-char excerpt of a copy), {@code confidence} is the
 * engine's 0-100 int (not a 0.0-1.0 double), and {@code tenantId} is the fan-out routing token the
 * decision endpoint requires back.
 */
public record ReviewItemResponse(
        UUID id,
        String tenantId,
        String packId,
        String decidingLayer,
        int confidence,
        String reason,
        String content,
        String direction,
        String status,
        String createdAt,
        String reviewedBy,
        String reviewedAt,
        String reviewNotes) {}
