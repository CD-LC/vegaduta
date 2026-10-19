package com.agenticai.core.workflow.humaninput;

/**
 * Lifecycle of a {@link WorkflowInputRequest}: {@code PENDING} while the run
 * sits {@code AWAITING_INPUT}; {@code SUBMITTED} once a human posts the
 * form. There is deliberately no EXPIRED/CANCELLED state - a run cancelled
 * (or failed) while a request is pending leaves the row PENDING, which is
 * honest: nothing ever resolved it, and the run's own status says why it
 * will never be.
 */
public enum WorkflowInputRequestStatus {
    PENDING,
    SUBMITTED
}
