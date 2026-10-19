package com.agenticai.core.billing.resolution;

/**
 * The end-state a {@link ResolutionEvent} records for one conversation - the
 * outcome vocabulary of the resolution meter (tenant schema V280). See that
 * migration's header for the full semantics of each value; in short:
 * {@link #RESOLVED_AI} is the one outcome an outcome-priced plan bills.
 */
public enum ResolutionOutcome {
    /** The agent resolved the conversation with no human takeover - the billable outcome. */
    RESOLVED_AI,
    /** A human operator resolved it - reported honestly, never AI-billable. */
    RESOLVED_HUMAN,
    /** Handed to / held by a human at close time. */
    ESCALATED,
    /** Explicit negative signal or operator-marked unresolved. */
    UNRESOLVED,
    /** Ended before the agent meaningfully engaged. */
    ABANDONED
}
