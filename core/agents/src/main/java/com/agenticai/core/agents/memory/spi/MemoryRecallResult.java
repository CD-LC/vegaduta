package com.agenticai.core.agents.memory.spi;

import java.util.List;

/**
 * The outcome of a recall. {@code dropped} counts items cut for budget; {@code degradedReason} is
 * non-null when the source failed or is switched off (the turn proceeds without memory).
 */
public record MemoryRecallResult(List<MemoryItemView> items, int tokensUsed, int dropped, String degradedReason) {

    public MemoryRecallResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static MemoryRecallResult empty(String degradedReason) {
        return new MemoryRecallResult(List.of(), 0, 0, degradedReason);
    }
}
