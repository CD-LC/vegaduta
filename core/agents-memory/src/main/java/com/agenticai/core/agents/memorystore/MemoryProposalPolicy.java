package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryProposalStatus;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;

/**
 * The status a proposal is created in. The return type has no ACTIVE value, so no input can make
 * a proposal active or trusted; promotion is a separate human approve in the store (WP3). A
 * proposal whose provenance is not TRUSTED, or that records any taint source, is QUARANTINED;
 * everything else, including every PROCEDURAL and SELF_MODEL proposal, is PENDING.
 */
public final class MemoryProposalPolicy {

    private MemoryProposalPolicy() {}

    public static MemoryProposalStatus statusFor(MemoryWriteProposal proposal) {
        if (proposal.provenance().quarantined() || !proposal.provenance().sourceTaint().isEmpty()) {
            return MemoryProposalStatus.QUARANTINED;
        }
        return MemoryProposalStatus.PENDING;
    }
}
