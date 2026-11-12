package com.agenticai.core.agents.memory.spi;

/**
 * The only states a write can be created in. There is deliberately no ACTIVE here: promotion is a
 * human {@code approve} in the store, never something a writer can ask for.
 */
public enum MemoryProposalStatus {
    PENDING,
    QUARANTINED
}
