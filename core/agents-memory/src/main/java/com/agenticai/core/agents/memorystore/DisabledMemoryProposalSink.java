package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryProposalReceipt;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import java.util.Optional;

/**
 * The sink until the store exists (WP3): refuses every proposal. A store-backed sink replaces it.
 */
public class DisabledMemoryProposalSink implements MemoryProposalSink {

    @Override
    public Optional<MemoryProposalReceipt> propose(MemoryWriteProposal proposal) {
        return Optional.empty();
    }
}
