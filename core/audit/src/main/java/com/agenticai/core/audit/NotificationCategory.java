package com.agenticai.core.audit;

/**
 * Notification bell (tenant-facing sibling of the admin Issues portal,
 * {@link PlatformIssue}) - which bucket a {@link Notification} falls into.
 *
 * <p>{@link #WORKFLOW} is a 5th category beyond the originally-requested
 * minimum (SYSTEM/TOOL_FAILURE/BILLING/SAFETY): {@code IssueSource.WORKFLOW_RUN}
 * is already the single largest existing failure source ({@code
 * WorkflowExecutionEngine} records a {@link PlatformIssue} there on every run
 * that reaches FAILED) and deserves its own bucket rather than being forced
 * into TOOL_FAILURE or SYSTEM.
 */
public enum NotificationCategory {
    SYSTEM,
    TOOL_FAILURE,
    BILLING,
    SAFETY,
    WORKFLOW,

    /**
     * Ambient agents (sentinel schedules): a watch run that found something
     * worth a human's attention, or a proposed action parked behind an
     * {@code agent.proposal} approval request. Its own bucket rather than
     * WORKFLOW because the escalation source is a schedule verdict, not a
     * workflow failure, and the digest UI groups on it.
     */
    AGENT
}
