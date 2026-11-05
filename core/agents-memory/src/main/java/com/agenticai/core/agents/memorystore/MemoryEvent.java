package com.agenticai.core.agents.memorystore;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One row of the append-only {@code memory_event} log. It carries ids, a kind, an actor, statuses
 * and a machine-readable reason code, never memory text: {@code reason} must be a token of letters,
 * digits, {@code _ = ;} with no whitespace (for example {@code KIND=EPISODIC;SOURCES=2}), so a
 * sentence cannot be passed through it by mistake.
 */
public record MemoryEvent(
        UUID id,
        UUID itemId,
        UUID agentId,
        Type event,
        MemoryActor actor,
        MemoryItemStatus fromStatus,
        MemoryItemStatus toStatus,
        String reason,
        Instant at) {

    /** The event vocabulary (REFUSED is added to the ADR's list for refused proposals). */
    public enum Type {
        CREATED,
        APPROVED,
        REJECTED,
        EDITED,
        REVERTED,
        EXPIRED,
        ERASED,
        PINNED,
        UNPINNED,
        REFUSED
    }

    private static final Pattern REASON = Pattern.compile("[A-Za-z0-9_=;.]{0,200}");

    public MemoryEvent {
        if (id == null || agentId == null || event == null || actor == null || at == null) {
            throw new IllegalArgumentException("id, agentId, event, actor and at are required");
        }
        if (reason != null && !REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("memory_event reason must be a code, not text");
        }
    }

    public static MemoryEvent of(
            UUID itemId,
            UUID agentId,
            Type event,
            MemoryActor actor,
            MemoryItemStatus from,
            MemoryItemStatus to,
            String reason,
            Instant at) {
        return new MemoryEvent(UUID.randomUUID(), itemId, agentId, event, actor, from, to, reason, at);
    }
}
