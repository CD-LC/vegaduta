package com.agenticai.core.admin.sso;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A bearer token for the tenant's SCIM-lite provisioning endpoint ({@code
 * /scim/v2/Users}). Only the SHA-256 hash is stored - a DB read must never
 * yield a usable token. The plaintext is shown exactly once, at mint time.
 */
@Entity
@Table(schema = "control_plane", name = "tenant_scim_token")
public class TenantScimToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_schema", nullable = false, length = 64)
    private String tenantSchema;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isActive() {
        return revokedAt == null;
    }

    public UUID getId() { return id; }
    public String getTenantSchema() { return tenantSchema; }
    public void setTenantSchema(String v) { tenantSchema = v; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { tokenHash = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { label = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant v) { lastUsedAt = v; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant v) { revokedAt = v; }
}
