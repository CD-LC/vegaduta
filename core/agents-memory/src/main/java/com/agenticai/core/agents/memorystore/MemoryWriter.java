package com.agenticai.core.agents.memorystore;

/** Who produced an item's text (the {@code memory_item_writer_chk} values). */
public enum MemoryWriter {
    AGENT,
    HUMAN,
    IMPORT,
    DISTILLER,
    SYSTEM;

    /** Unknown stored values fail closed to AGENT (the least trusted author). */
    public static MemoryWriter parse(String stored) {
        for (MemoryWriter writer : values()) {
            if (writer.name().equals(stored)) {
                return writer;
            }
        }
        return AGENT;
    }
}
