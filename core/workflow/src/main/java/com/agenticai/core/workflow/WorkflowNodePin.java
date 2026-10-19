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
 * A node's pinned output (V374): a frozen value that stands in for the node in draft and test runs. One row
 * per {@code (workflow, node)}. The data is text, usually JSON, stored exactly as given. See
 * {@link WorkflowPinStore} for the limits and {@link WorkflowPinService} for who may read and write it.
 */
@Entity
@Table(name = "workflow_node_pin")
@IdClass(WorkflowNodePin.Key.class)
public class WorkflowNodePin {

    @Id
    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Id
    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "data_text", nullable = false, columnDefinition = "TEXT")
    private String dataText;

    @Column(name = "size_chars", nullable = false)
    private int sizeChars;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "source_run_id", length = 64)
    private String sourceRunId;

    @Column(name = "pinned_by", length = 200)
    private String pinnedBy;

    @Column(name = "pinned_at", nullable = false)
    private Instant pinnedAt;

    @Column(name = "config_hash", length = 128)
    private String configHash;

    protected WorkflowNodePin() {
        // JPA
    }

    public WorkflowNodePin(
        UUID workflowId, String nodeId, String dataText, String source, String sourceRunId, String pinnedBy,
        String configHash
    ) {
        this.workflowId = workflowId;
        this.nodeId = nodeId;
        replace(dataText, source, sourceRunId, pinnedBy, configHash);
    }

    /** Overwrites this pin in place (re-pinning a node replaces its row). */
    public void replace(String dataText, String source, String sourceRunId, String pinnedBy, String configHash) {
        this.dataText = dataText;
        this.sizeChars = dataText.length();
        this.source = source;
        this.sourceRunId = sourceRunId;
        this.pinnedBy = pinnedBy;
        this.configHash = configHash;
        this.pinnedAt = Instant.now();
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getDataText() {
        return dataText;
    }

    public int getSizeChars() {
        return sizeChars;
    }

    public String getSource() {
        return source;
    }

    public String getSourceRunId() {
        return sourceRunId;
    }

    public String getPinnedBy() {
        return pinnedBy;
    }

    public Instant getPinnedAt() {
        return pinnedAt;
    }

    public String getConfigHash() {
        return configHash;
    }

    /** Composite primary key. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID workflowId;
        private String nodeId;

        public Key() {
            // JPA
        }

        public Key(UUID workflowId, String nodeId) {
            this.workflowId = workflowId;
            this.nodeId = nodeId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(workflowId, k.workflowId) && Objects.equals(nodeId, k.nodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workflowId, nodeId);
        }
    }
}
