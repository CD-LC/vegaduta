package com.agenticai.core.eventbus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The transactional-outbox row backing {@link WorkflowEventPublisher} - see
 * migration V19's header comment for the full write-then-relay rationale.
 * {@link WorkflowEventOutboxRelay} is the only thing that ever calls {@link
 * #markPublished()}.
 */
@Entity
@Table(name = "workflow_event_outbox")
public class WorkflowEventOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "run_id", nullable = false, length = 100)
    private String runId;

    @Column(name = "node_id", length = 100)
    private String nodeId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    // #913/#914 (Kafka reliability hardening, V81) - see WorkflowEventOutboxRelay's
    // javadoc for how these two fields close the "dead-letter path... Section 9
    // hardening item" gap this class's own javadoc used to flag as unbuilt.
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    protected WorkflowEventOutbox() {
        // JPA
    }

    public WorkflowEventOutbox(String eventType, String runId, String payloadJson) {
        this.eventType = eventType;
        this.runId = runId;
        this.payloadJson = payloadJson;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    /** Called by {@link WorkflowEventOutboxRelay} on every failed publish attempt - increments the retry counter and records what went wrong, both surfaced to an admin if this row eventually dead-letters. */
    public void recordFailure(String errorMessage) {
        this.retryCount++;
        this.lastError = errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getRunId() {
        return runId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
