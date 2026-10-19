package com.agenticai.core.workflow.humaninput;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A paused workflow run's request for structured human input - the
 * "collect a form mid-run and resume" half of the Approval node's
 * approve/reject-only contract. Created by {@code
 * WorkflowNodeExecutor#executeHumanInputNode} at halt time; resolved by
 * {@code WorkflowInputRequestService#submit}, which feeds the submitted
 * values back into the run as the resumed node's output.
 *
 * <p>{@code formSchemaJson} snapshots the node's form definition AT HALT
 * TIME - the submit path validates against this copy, not the (possibly
 * since-edited) graph, so the human always answers the exact form the run
 * actually presented. {@code nodeInput} is the resume envelope (the
 * upstream output this node consumed), carried verbatim for the same
 * reason {@code OrchestratorService#authorizeSensitiveNode} records it on
 * approval payloads: nothing else persists it, and the resume needs it.
 *
 * <p>Tenant-schema entity (no {@code schema=} on {@code @Table}), persisted
 * via migration V271 ({@code workflow_input_request}).
 */
@Entity
@Table(name = "workflow_input_request")
public class WorkflowInputRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_name", length = 255)
    private String workflowName;

    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    @Column(name = "node_label", length = 255)
    private String nodeLabel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_schema", columnDefinition = "jsonb")
    private String formSchemaJson;

    @Column(name = "node_input", columnDefinition = "TEXT")
    private String nodeInput;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WorkflowInputRequestStatus status;

    @Column(name = "requested_by", length = 255)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submitted_values", columnDefinition = "jsonb")
    private String submittedValuesJson;

    @Column(name = "submitted_by", length = 255)
    private String submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    protected WorkflowInputRequest() {
        // JPA
    }

    public WorkflowInputRequest(
        UUID runId, UUID workflowId, String workflowName, String nodeId, String nodeLabel,
        String formSchemaJson, String nodeInput, String requestedBy
    ) {
        this.runId = runId;
        this.workflowId = workflowId;
        this.workflowName = workflowName;
        this.nodeId = nodeId;
        this.nodeLabel = nodeLabel;
        this.formSchemaJson = formSchemaJson;
        this.nodeInput = nodeInput;
        this.status = WorkflowInputRequestStatus.PENDING;
        this.requestedBy = requestedBy;
        this.createdAt = Instant.now();
    }

    public void markSubmitted(String valuesJson, String submittedBy) {
        this.status = WorkflowInputRequestStatus.SUBMITTED;
        this.submittedValuesJson = valuesJson;
        this.submittedBy = submittedBy;
        this.submittedAt = Instant.now();
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

    public String getWorkflowName() {
        return workflowName;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeLabel() {
        return nodeLabel;
    }

    public String getFormSchemaJson() {
        return formSchemaJson;
    }

    public String getNodeInput() {
        return nodeInput;
    }

    public WorkflowInputRequestStatus getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getSubmittedValuesJson() {
        return submittedValuesJson;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
