package com.agenticai.core.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The durable half of a Wait node (migration V434, tenant schema): when the parked run must wake, in which mode,
 * and whether a webhook-mode timeout fails the run. The pause itself is the run's ordinary PAUSED state - see
 * {@link WorkflowWaitService}.
 */
@Entity
@Table(name = "workflow_wait")
public class WorkflowWait {

    /** WAITING is live; the other three are terminal. */
    public enum Status {
        WAITING, RESUMED, CANCELLED
    }

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    /** The node the parked run continues at - checked against the run's own paused_node_id before waking it. */
    @Column(name = "resume_node_id", nullable = false, length = 100)
    private String resumeNodeId;

    @Column(nullable = false, length = 16)
    private String mode;

    @Column(name = "wake_at", nullable = false)
    private Instant wakeAt;

    @Column(name = "fail_on_timeout", nullable = false)
    private boolean failOnTimeout = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.WAITING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected WorkflowWait() {
        // JPA
    }

    public WorkflowWait(
        UUID id, UUID runId, UUID workflowId, String nodeId, String resumeNodeId, String mode, Instant wakeAt,
        boolean failOnTimeout
    ) {
        this.id = id;
        this.runId = runId;
        this.workflowId = workflowId;
        this.nodeId = nodeId;
        this.resumeNodeId = resumeNodeId;
        this.mode = mode;
        this.wakeAt = wakeAt;
        this.failOnTimeout = failOnTimeout;
        this.status = Status.WAITING;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getResumeNodeId() {
        return resumeNodeId;
    }

    public String getMode() {
        return mode;
    }

    public Instant getWakeAt() {
        return wakeAt;
    }

    public boolean isFailOnTimeout() {
        return failOnTimeout;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
