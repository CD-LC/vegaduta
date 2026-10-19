package com.agenticai.core.admin.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * One granted {@link Permission} for one user - see {@code
 * UserPermissionService} for how rows here are created/removed, and {@code
 * DefaultPermissionBundles} for how a user's initial rows are seeded from
 * their roles.
 *
 * <p>{@code tenantId}/{@code keycloakUserId} are plain strings, not FKs -
 * matches how {@code TenantContext} and {@code KeycloakAdminClient} already
 * treat both values as opaque, compared against the JWT's {@code tenant_id}
 * claim and Keycloak's own user store, never joined against {@code
 * control_plane.tenant} or {@code control_plane.app_user} ({@code AppUser}
 * rows only exist for the original signup admin per that entity's own
 * javadoc - a FK there would silently fail to grant permissions to anyone
 * an admin later invites).
 */
@Entity
@Table(
    name = "user_permission_grant",
    schema = "control_plane",
    uniqueConstraints = @UniqueConstraint(columnNames = {"keycloak_user_id", "permission"})
)
public class UserPermissionGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 255)
    private String tenantId;

    @Column(name = "keycloak_user_id", nullable = false, length = 255)
    private String keycloakUserId;

    // Permission#name(), not @Enumerated on this field - so a future catalog
    // rename doesn't need an entity migration in lockstep.
    @Column(nullable = false, length = 64)
    private String permission;

    @Column(name = "granted_by", nullable = false, length = 255)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    protected UserPermissionGrant() {
        // JPA
    }

    public UserPermissionGrant(String tenantId, String keycloakUserId, String permission, String grantedBy) {
        this.tenantId = tenantId;
        this.keycloakUserId = keycloakUserId;
        this.permission = permission;
        this.grantedBy = grantedBy;
        this.grantedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getKeycloakUserId() {
        return keycloakUserId;
    }

    public String getPermission() {
        return permission;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
