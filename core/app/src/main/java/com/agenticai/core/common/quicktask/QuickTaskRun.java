package com.agenticai.core.common.quicktask;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable record of one Quick Task run, keyed by the same {@code sessionId}
 * every {@code ToolCallLog} row for that run is already keyed by - direct
 * feedback: "it should generate an ID of a task and we should be able to see
 * the complete log with request sent and response back - not just half of
 * it - any point of time - they should be able to view it."
 *
 * <p>Before this, {@code QuickTaskResponse}/the SSE stream's {@code done}
 * event carried the full request text, tool trace, and final answer only for
 * the lifetime of that one HTTP response or open browser tab - close the tab,
 * and it was gone (the underlying {@code ToolCallLog} rows survived in
 * Postgres, but nothing tied them back to a lookup-able id, and the request
 * text/final answer/delivery outcome were never persisted anywhere at all).
 * This entity is that missing anchor: {@link QuickTaskService} writes one row
 * per run ({@link QuickTaskRunService#start}), then updates it in place on
 * completion or failure - so {@code GET /api/quick-task/{taskId}} can
 * reconstruct the whole thing (request, every real tool call, final answer,
 * delivery note, or error) at any point after the fact, not just while the
 * SSE connection that started it is still open.
 */
@Entity
@Table(name = "quick_task_run")
public class QuickTaskRun {

    /** Same string as every {@code ToolCallLog.sessionId} for this run - the "task ID" the person sees. */
    @Id
    @Column(name = "task_id", length = 128)
    private String taskId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "requested_by_keycloak_user_id", nullable = false, length = 128)
    private String requestedByKeycloakUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private QuickTaskChannel channel;

    @Column(name = "request_message", nullable = false, columnDefinition = "TEXT")
    private String requestMessage;

    @Column(name = "result_text", columnDefinition = "TEXT")
    private String resultText;

    @Column(name = "delivery_note", columnDefinition = "TEXT")
    private String deliveryNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private QuickTaskRunStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * C1 versioning pin: the {@code agent_version.id} this run resolved at
     * start (PRODUCTION label, else NULL when the agent has no label or the
     * platform runs in auto-attach compatibility mode without one). Written
     * once at {@link QuickTaskRunService#start}; a mid-run promote can never
     * rewrite it - that's what makes the run reproducible after the fact.
     */
    @Column(name = "agent_version_id")
    private UUID agentVersionId;

    protected QuickTaskRun() {
        // JPA
    }

    public QuickTaskRun(
        String taskId, UUID agentId, String requestedByKeycloakUserId, QuickTaskChannel channel, String requestMessage, Instant startedAt
    ) {
        this.taskId = taskId;
        this.agentId = agentId;
        this.requestedByKeycloakUserId = requestedByKeycloakUserId;
        this.channel = channel;
        this.requestMessage = requestMessage;
        this.status = QuickTaskRunStatus.RUNNING;
        this.startedAt = startedAt;
    }

    public void complete(String resultText, String deliveryNote, Instant completedAt) {
        this.resultText = resultText;
        this.deliveryNote = deliveryNote;
        this.status = QuickTaskRunStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public void fail(String errorMessage, Instant completedAt) {
        this.errorMessage = errorMessage;
        this.status = QuickTaskRunStatus.FAILED;
        this.completedAt = completedAt;
    }

    public String getTaskId() {
        return taskId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getRequestedByKeycloakUserId() {
        return requestedByKeycloakUserId;
    }

    public QuickTaskChannel getChannel() {
        return channel;
    }

    public String getRequestMessage() {
        return requestMessage;
    }

    public String getResultText() {
        return resultText;
    }

    public String getDeliveryNote() {
        return deliveryNote;
    }

    public QuickTaskRunStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public UUID getAgentVersionId() {
        return agentVersionId;
    }

    public void setAgentVersionId(UUID agentVersionId) {
        this.agentVersionId = agentVersionId;
    }
}
