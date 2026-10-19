package com.agenticai.core.workflow.comment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** One canvas comment or reply - see {@code tenant/V367__workflow_comment.sql}. */
@Entity
@Table(name = "workflow_comment")
public class WorkflowComment {

    @Id
    private UUID id;

    /**
     * Optimistic lock (JAVA-007, migration tenant/V369.1): concurrent edit/resolve
     * of one comment fails with a 409 instead of silently overwriting. Wrapper type
     * on purpose: null marks a never-persisted entity for Spring Data (the id is
     * assigned in the constructor, so it cannot).
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "node_id", length = 128)
    private String nodeId;

    @Column(name = "anchor_x")
    private Double anchorX;

    @Column(name = "anchor_y")
    private Double anchorY;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "mentions", columnDefinition = "TEXT")
    private String mentions;

    @Column(name = "author_id", nullable = false, length = 255)
    private String authorId;

    @Column(name = "author_name", length = 255)
    private String authorName;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowComment() {
    }

    public WorkflowComment(
        UUID workflowId, UUID parentId, String nodeId, Double anchorX, Double anchorY, String body,
        String mentions, String authorId, String authorName
    ) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.parentId = parentId;
        this.nodeId = nodeId;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.body = body;
        this.mentions = mentions;
        this.authorId = authorId;
        this.authorName = authorName;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void edit(String newBody, String newMentions) {
        this.body = newBody;
        this.mentions = newMentions;
        this.updatedAt = Instant.now();
    }

    public void setResolved(boolean resolved, String by) {
        this.resolved = resolved;
        this.resolvedBy = resolved ? by : null;
        this.resolvedAt = resolved ? Instant.now() : null;
        this.updatedAt = Instant.now();
    }

    /** Soft delete: the row stays so its replies keep their thread. */
    public void markDeleted() {
        this.body = null;
        this.mentions = null;
        this.deletedAt = Instant.now();
        this.updatedAt = this.deletedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public Double getAnchorX() {
        return anchorX;
    }

    public Double getAnchorY() {
        return anchorY;
    }

    public String getBody() {
        return body;
    }

    public String getMentions() {
        return mentions;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public boolean isResolved() {
        return resolved;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
