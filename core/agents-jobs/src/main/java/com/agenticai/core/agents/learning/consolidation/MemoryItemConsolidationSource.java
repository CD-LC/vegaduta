package com.agenticai.core.agents.learning.consolidation;

import com.agenticai.core.agents.memorystore.MemoryItem;
import com.agenticai.core.agents.memorystore.MemoryItemConsolidationReader;
import com.agenticai.core.agents.memorystore.MemoryWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ConsolidationItemSource} over the unified store's read-only {@link MemoryItemConsolidationReader}.
 * The reader is optional so the jobs shape starts whether or not the store beans are present; without
 * it no item is consolidated.
 */
@Component
public class MemoryItemConsolidationSource implements ConsolidationItemSource {

    private final ObjectProvider<MemoryItemConsolidationReader> reader;

    public MemoryItemConsolidationSource(ObjectProvider<MemoryItemConsolidationReader> reader) {
        this.reader = reader;
    }

    @Override
    public List<UUID> agents(int limit) {
        MemoryItemConsolidationReader r = reader.getIfAvailable();
        return r == null ? List.of() : r.agentIds(limit);
    }

    @Override
    public List<Item> items(UUID agentId) {
        MemoryItemConsolidationReader r = reader.getIfAvailable();
        if (r == null) {
            return List.of();
        }
        List<Item> out = new ArrayList<>();
        for (MemoryItemConsolidationReader.Row row : r.activeItems(agentId, 5000)) {
            MemoryItem m = row.item();
            out.add(new Item(
                    m.id(),
                    m.agentId(),
                    m.kind(),
                    m.content(),
                    m.generation(),
                    m.modelGenerated(),
                    m.humanConfirmed(),
                    m.writer() == MemoryWriter.HUMAN,
                    row.pinned(),
                    row.importance(),
                    row.useCount(),
                    m.createdAt(),
                    row.lastUsedAt(),
                    m.approvedAt()));
        }
        return out;
    }

    @Override
    public Set<UUID> sourcesOfPendingProposals(UUID agentId) {
        MemoryItemConsolidationReader r = reader.getIfAvailable();
        return r == null ? Set.of() : r.sourcesOfPendingProposals(agentId);
    }
}
