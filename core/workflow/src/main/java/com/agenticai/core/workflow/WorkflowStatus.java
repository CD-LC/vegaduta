package com.agenticai.core.workflow;

/**
 * ACTIVE is the only status a real trigger (a channel webhook, a schedule)
 * is ever allowed to run against - see {@link OrchestratorService#validate}.
 * DRAFT graphs can still be run manually from the canvas ("Play" button) for
 * iteration, since a workflow author needs to test before flipping it live.
 */
public enum WorkflowStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
