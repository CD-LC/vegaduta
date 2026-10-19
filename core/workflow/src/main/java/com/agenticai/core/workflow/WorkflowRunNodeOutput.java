package com.agenticai.core.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One node execution's full output (V373), keyed {@code (run, node, seq)}; see {@link RunDataStore}.
 * {@code seq} is the loop / fan_out / sub_flow iteration index (0 for an ordinary node), so a retry of
 * the same execution overwrites its own row instead of adding a second one.
 */
@Entity
@Table(name = "workflow_run_node_output")
@IdClass(WorkflowRunNodeOutput.Key.class)
public class WorkflowRunNodeOutput {

    @Id
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Id
    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Id
    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "node_label")
    private String nodeLabel;

    @Column(name = "status")
    private String status;

    @Column(name = "output_text", columnDefinition = "TEXT")
    private String outputText;

    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowRunNodeOutput() {
        // JPA
    }

    public WorkflowRunNodeOutput(
        UUID runId, String nodeId, int seq, String nodeLabel, String status, String outputText, boolean truncated
    ) {
        this.runId = runId;
        this.nodeId = nodeId;
        this.seq = seq;
        this.nodeLabel = nodeLabel;
        this.status = status;
        this.outputText = outputText;
        this.truncated = truncated;
        this.createdAt = Instant.now();
    }

    public UUID getRunId() {
        return runId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getSeq() {
        return seq;
    }

    public String getNodeLabel() {
        return nodeLabel;
    }

    public String getStatus() {
        return status;
    }

    public String getOutputText() {
        return outputText;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Composite primary key. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID runId;
        private String nodeId;
        private int seq;

        public Key() {
            // JPA
        }

        public Key(UUID runId, String nodeId, int seq) {
            this.runId = runId;
            this.nodeId = nodeId;
            this.seq = seq;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && seq == k.seq && Objects.equals(runId, k.runId) && Objects.equals(nodeId, k.nodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(runId, nodeId, seq);
        }
    }
}
