package com.agenticai.core.billing.resolution;

/**
 * How a {@link ResolutionEvent}'s outcome was decided - the audit half of
 * the row. Explicit human/operator signals carry confidence 1.0; the
 * idle-close heuristic writes 0.6 so "assumed resolved" is distinguishable
 * from "told resolved" on a dispute.
 */
public enum ResolutionEvidence {
    /** Explicit {@code turn_feedback} RESOLVED signal on the session. */
    USER_RESOLVED_SIGNAL,
    /** Explicit UNRESOLVED or THUMBS_DOWN feedback. */
    FEEDBACK_NEGATIVE,
    /** Quiet-close heuristic: agent-handled conversation went idle past the configured window. */
    NO_ESCALATION_IDLE,
    /** A human operator closed the conversation. */
    OPERATOR_CLOSE,
    /** {@code handoff_state} was AWAITING_HUMAN / HUMAN at close time. */
    ESCALATION_OPEN,
    /** Recorded through {@code POST /api/tenant/resolutions}. */
    EXPLICIT_API,
    /** Reserved for an evals-judge classifier - no producer yet; declared so landing one needs no schema change. */
    JUDGE_VERDICT
}
