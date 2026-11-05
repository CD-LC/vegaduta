package com.agenticai.core.agents.memory.spi;

import java.util.UUID;

/** What a sink created: an id and a status that is PENDING or QUARANTINED, never ACTIVE. */
public record MemoryProposalReceipt(UUID id, MemoryProposalStatus status) {

    public MemoryProposalReceipt {
        if (id == null || status == null) {
            throw new IllegalArgumentException("id and status are required");
        }
    }
}
