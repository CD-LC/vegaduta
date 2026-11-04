package com.agenticai.core.agents.memorystore;

/** Lifecycle of a {@code memory_item} row (the {@code memory_item_status_chk} values). */
public enum MemoryItemStatus {
    PENDING,
    ACTIVE,
    REJECTED,
    SUPERSEDED,
    REVERTED;

    /** Unknown stored values fail closed to PENDING (never recalled, needs a human). */
    public static MemoryItemStatus parse(String stored) {
        for (MemoryItemStatus status : values()) {
            if (status.name().equals(stored)) {
                return status;
            }
        }
        return PENDING;
    }
}
