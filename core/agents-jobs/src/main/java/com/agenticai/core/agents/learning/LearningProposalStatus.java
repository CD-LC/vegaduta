package com.agenticai.core.agents.learning;

/** Lifecycle of a proposal. Only PENDING proposals can be decided; PENDING ones older than the TTL become EXPIRED. */
public enum LearningProposalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
}
