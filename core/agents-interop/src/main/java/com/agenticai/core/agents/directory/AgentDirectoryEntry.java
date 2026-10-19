package com.agenticai.core.agents.directory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One agent's published listing in the platform-wide public directory
 * (migration V402, {@code control_plane} schema). Cross-tenant by definition:
 * the public {@code /api/directory/**} read path resolves rows with no
 * TenantContext, so the entry stores everything a verifier needs - including
 * signed snapshots taken at publish time - and never hops into the tenant
 * schema on read.
 *
 * <p>{@link #agentId} is the PK: agent ids are UUIDv4 generated inside one
 * tenant schema, so one directory entry per agent, globally.
 */
@Entity
@Table(name = "agent_directory_entry", schema = "control_plane")
public class AgentDirectoryEntry {

    public enum Status {
        PUBLISHED,
        UNPUBLISHED
    }

    @Id
    @Column(name = "agent_id")
    private UUID agentId;

    /** The owning tenant's SCHEMA NAME (TenantContext#getTenantId), not control_plane.tenant.id. */
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** JSON array of advertised capability/protocol strings. */
    @Column(name = "capabilities", columnDefinition = "TEXT")
    private String capabilities;

    @Column(name = "homepage_url", length = 1024)
    private String homepageUrl;

    /** The A2A card JSON as signed at publish time (detached-JWS inside). */
    @Column(name = "card_snapshot", nullable = false, columnDefinition = "TEXT")
    private String cardSnapshot;

    /** The signed capability manifest JSON, when signing is enabled. */
    @Column(name = "manifest_snapshot", columnDefinition = "TEXT")
    private String manifestSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PUBLISHED;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AgentDirectoryEntry() {
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    public String getHomepageUrl() {
        return homepageUrl;
    }

    public void setHomepageUrl(String homepageUrl) {
        this.homepageUrl = homepageUrl;
    }

    public String getCardSnapshot() {
        return cardSnapshot;
    }

    public void setCardSnapshot(String cardSnapshot) {
        this.cardSnapshot = cardSnapshot;
    }

    public String getManifestSnapshot() {
        return manifestSnapshot;
    }

    public void setManifestSnapshot(String manifestSnapshot) {
        this.manifestSnapshot = manifestSnapshot;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch(Instant at) {
        this.updatedAt = at;
    }
}
