package com.agenticai.core.agents.crew;

/**
 * Terminal (and one non-terminal) state of a single hop in a {@link HandoffChain}, persisted in
 * {@code agent_handoff_audit.status} and read back through {@code @Enumerated(EnumType.STRING)} -
 * see that migration's CHECK constraint, which must be kept in sync with these names.
 */
public enum HandoffStatus {
    /** Row written at dispatch time, before the specialist's turn has completed - see {@link AgentHandoffAuditService#recordStart}. */
    PENDING,
    /** The specialist's turn completed and returned an answer. */
    SUCCESS,
    /** The specialist's turn threw (provider error, guardrail block, step-limit, etc.). */
    FAILED,
    /** Refused before dispatch: {@link HandoffChain#isAtMaxDepth()} was already true. */
    DEPTH_EXCEEDED,
    /** Refused before dispatch: the candidate agent already appears on the chain's path. */
    CYCLE_DETECTED,
    /** Refused before dispatch: no {@code agent_specialist} row matched the requested capability tag. */
    NO_SPECIALIST_FOUND
}
