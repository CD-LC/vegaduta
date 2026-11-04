package com.agenticai.core.agents.memory.spi;

import java.util.List;
import java.util.UUID;

/**
 * One row a derived memory is built from (lineage, {@code memory_item_source}). For a
 * {@code memory_item} source the store ignores {@code trust} and {@code sourceTaint} and reads the
 * authoritative values from its own row, so a caller cannot claim a better trust than the source
 * has. For any other table (agent_memory, tenant_memory, knowledge_chunks, turn) the caller states
 * them; a missing trust parses as QUARANTINED.
 */
public record MemorySourceRef(String table, String key, MemoryTrustLevel trust, List<String> sourceTaint) {

    public static final String MEMORY_ITEM = "memory_item";

    public MemorySourceRef {
        if (table == null || table.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("table and key are required");
        }
        trust = trust == null ? MemoryTrustLevel.QUARANTINED : trust;
        sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
    }

    /** A reference to another unified-memory item; its trust is read from the store. */
    public static MemorySourceRef item(UUID id) {
        return new MemorySourceRef(MEMORY_ITEM, id.toString(), MemoryTrustLevel.QUARANTINED, List.of());
    }
}
