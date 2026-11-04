package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;

/** One lineage row ({@code memory_item_source}): what a derived item was built from, and that source's trust then. */
public record MemoryItemSource(String sourceTable, String sourceKey, MemoryTrustLevel sourceTrust) {

    public MemoryItemSource {
        if (sourceTable == null || sourceTable.isBlank() || sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceTable and sourceKey are required");
        }
        if (sourceTable.length() > 24 || sourceKey.length() > 128) {
            throw new IllegalArgumentException("lineage reference too long");
        }
        sourceTrust = sourceTrust == null ? MemoryTrustLevel.QUARANTINED : sourceTrust;
    }
}
