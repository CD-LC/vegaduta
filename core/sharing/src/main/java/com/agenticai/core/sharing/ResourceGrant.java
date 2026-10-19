package com.agenticai.core.sharing;

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
 * One grant of access to an Agent or Workflow, from its owner (or a
 * tenant-admin) to a specific teammate. Maps to the {@code resource_grant}
 * table (see {@code V147__resource_sharing.sql}) - a TENANT-schema table, no
 * {@code tenant_id} column (schema-per-tenant, same convention as {@code
 * agent_share}).
 *
 * <p>Soft-revoke, not delete: {@link #revokedAt}/{@link #revokedBy} are set
 * rather than the row being removed, so revocation history survives - the
 * partial unique index on {@code (resource_type, resource_id,
 * grantee_user_id) WHERE revoked_at IS NULL} is what allows the same pair to
 * be granted, revoked, and re-granted over time without a constraint
 * violation, while still guaranteeing at most one ACTIVE grant per pair.
 */
@Entity
@Table(name = "resource_grant")
public class ResourceGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    private ResourceType resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "grantee_user_id", nullable = false, length = 255)
    private String granteeUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_level", nullable = false, length = 16)
    private PermissionLevel permissionLevel;

    @Column(name = "granted_by", nullable = false, length = 255)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 255)
    private String revokedBy;

    protected ResourceGrant() {
        // JPA
    }

    public ResourceGrant(
        ResourceType resourceType, UUID resourceId, String granteeUserId, PermissionLevel permissionLevel, String grantedBy
    ) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.granteeUserId = granteeUserId;
        this.permissionLevel = permissionLevel;
        this.grantedBy = grantedBy;
        this.grantedAt = Instant.now();
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke(String revokedByUserId) {
        this.revokedAt = Instant.now();
        this.revokedBy = revokedByUserId;
    }

    public void updateLevel(PermissionLevel newLevel, String grantedByUserId) {
        this.permissionLevel = newLevel;
        this.grantedBy = grantedByUserId;
        this.grantedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getGranteeUserId() {
        return granteeUserId;
    }

    public PermissionLevel getPermissionLevel() {
        return permissionLevel;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }
}
