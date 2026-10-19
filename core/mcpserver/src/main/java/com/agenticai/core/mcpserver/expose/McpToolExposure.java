package com.agenticai.core.mcpserver.expose;

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

/**
 * "This agent / workflow may be advertised as its own MCP tool" - one row per resource, created the
 * first time an editor flips the switch (see V425). A dumb persistence shape: the slugging and
 * authorization rules live in {@link McpToolExposureService}.
 *
 * <p>No tenant column - isolation is the tenant's own Postgres schema, same as every other
 * tenant-owned entity (see {@code McpServerAccessGrant}).
 */
@Entity
@Table(name = "mcp_tool_exposure")
public class McpToolExposure {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 16)
    private McpExposureKind kind;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    /** Allocated once and never recomputed - see {@link McpToolSlugs}. */
    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(nullable = false)
    private boolean exposed;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected McpToolExposure() {
        // JPA
    }

    public McpToolExposure(McpExposureKind kind, UUID resourceId, String toolName, boolean exposed, String createdBy) {
        this.kind = kind;
        this.resourceId = resourceId;
        this.toolName = toolName;
        this.exposed = exposed;
        this.createdBy = createdBy;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void setExposed(boolean exposed) {
        this.exposed = exposed;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public McpExposureKind getKind() {
        return kind;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getToolName() {
        return toolName;
    }

    public boolean isExposed() {
        return exposed;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
