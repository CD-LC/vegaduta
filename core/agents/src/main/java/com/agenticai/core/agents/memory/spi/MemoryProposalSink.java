package com.agenticai.core.agents.memory.spi;

import java.util.Optional;

/**
 * Write port. Can only create PENDING or QUARANTINED proposals; there is no method or status
 * value here that produces an ACTIVE or TRUSTED item. Empty when the feature is off or the
 * proposal was refused.
 */
public interface MemoryProposalSink {

    Optional<MemoryProposalReceipt> propose(MemoryWriteProposal proposal);
}
