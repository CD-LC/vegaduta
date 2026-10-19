package com.agenticai.core.workflow.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A registered tenant's own workflow, shared to anonymous external viewers by
 * an unguessable share id - the workflow counterpart of {@code
 * com.agenticai.core.agents.share.AgentShare}, mirrored field-for-field (see
 * that class's javadoc for the full reasoning, which applies identically
 * here with "workflow" in place of "agent"). One row per workflow (at most
 * one active share at a time, see {@code V193__workflow_share.sql}'s unique
 * constraint). Schema-per-tenant: no tenant_id column. The cross-tenant
 * "which tenant does this share id belong to" lookup lives in the
 * control-plane {@code channel_account_link} table via {@code
 * ExternalAccountProvider.WORKFLOW_SHARE} - this entity is only ever queried
 * after that lookup already resolved the caller into this schema.
 */
@Entity
@Table(name = "workflow_share")
public class WorkflowShare {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "share_id", nullable = false, length = 64)
    private String shareId;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Column(name = "viewer_count", nullable = false)
    private long viewerCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * SEC-051: when this public link stops resolving. Stamped at mint time from
     * {@code app.sharing.default-ttl}; NULL = a legacy link minted before the
     * column existed (tenant/V365.5), treated as non-expiring (and logged on use)
     * so no link already handed out goes dark on deploy.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected WorkflowShare() {
        // JPA
    }

    public WorkflowShare(UUID workflowId, String shareId) {
        this.workflowId = workflowId;
        this.shareId = shareId;
        this.revoked = false;
        this.reportCount = 0;
        this.viewerCount = 0;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void recordViewer() {
        this.viewerCount += 1;
        this.updatedAt = Instant.now();
    }

    /** @return true when this report pushed the share past {@code threshold} and it is now (newly or already) revoked. */
    public boolean report(int threshold) {
        this.reportCount += 1;
        if (this.reportCount >= threshold) {
            this.revoked = true;
        }
        this.updatedAt = Instant.now();
        return this.revoked;
    }

    public void revoke() {
        this.revoked = true;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getShareId() {
        return shareId;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public int getReportCount() {
        return reportCount;
    }

    public long getViewerCount() {
        return viewerCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Sets (or, for the owner, extends) the expiry; {@code null} = never expires. */
    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
        this.updatedAt = Instant.now();
    }

    /** SEC-051: true once {@code now} is at or past {@link #getExpiresAt}; a NULL expiry (legacy link) never expires. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
