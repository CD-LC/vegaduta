package com.agenticai.core.agents.memory.spi;

import java.util.Set;

/**
 * Read port. A source returns only TRUSTED and ACTIVE items (the type of {@link MemoryItemView}
 * rejects anything else) and must repeat that predicate in its own query. Implementations never
 * throw for store errors: they return {@link MemoryRecallResult#empty(String)}.
 */
public interface MemoryRecallSource {

    /** Stable short name for logs and metrics. */
    String name();

    /** The kinds this source can return. */
    Set<MemoryKind> kinds();

    MemoryRecallResult recall(MemoryRecallRequest request);
}
