package com.agenticai.core.agents.memory.spi;

import java.util.Set;
import java.util.UUID;

/**
 * One recall: the agent, the end-user subject (isolation key, as {@code CallerSessionIds}), the
 * query text, the kinds wanted (empty = any the source offers), and caps on item count and tokens.
 * Tenant scope comes from {@code TenantContext}, not from here.
 */
public record MemoryRecallRequest(
        UUID agentId,
        String subjectKey,
        String queryText,
        Set<MemoryKind> kinds,
        int maxItems,
        int tokenBudget) {

    public MemoryRecallRequest {
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        maxItems = Math.max(0, maxItems);
        tokenBudget = Math.max(0, tokenBudget);
    }

    public boolean wants(MemoryKind kind) {
        return kinds.isEmpty() || kinds.contains(kind);
    }
}
