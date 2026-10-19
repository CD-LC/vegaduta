package com.agenticai.core.tenancy.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One tenant-admin-issued REST API key (V483): a machine credential for the
 * whole {@code /api/**} surface - CI, partner systems, scripts that cannot
 * run an OIDC login flow. The bearer is {@code vdak_...}; this row holds only
 * its SHA-256 hash plus an 8-char display prefix - the plaintext is shown
 * exactly once at mint/rotate and never persisted (the same posture {@code
 * McpServerAccessGrant} documents for {@code vmcp_} keys; hash-only rather
 * than hash+encrypt because the platform never needs the plaintext back).
 *
 * <p>{@code permissionsJson} is a JSON array of catalog permission names
 * ({@code Permission} values WITHOUT the {@code PERM_} authority prefix),
 * validated at create time as a subset of the issuer's own effective
 * permissions - a key can never carry a scope its issuer did not hold.
 *
 * <p>No {@code tenantId} column - same as every other tenant-owned entity:
 * isolation is which tenant's Postgres schema the row physically lives in
 * (see {@code McpServerAccessGrant}'s javadoc for the full contract).
 */
@Entity
@Table(name = "tenant_api_key")
public class TenantApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "api_key_hash", nullable = false, unique = true, length = 64)
    private String apiKeyHash;

    @Column(name = "api_key_display_prefix", nullable = false, length = 16)
    private String apiKeyDisplayPrefix;

    @Column(name = "permissions_json", nullable = false, columnDefinition = "TEXT")
    private String permissionsJson;

    /** The issuing admin's subject - audit attribution only, never the key's own subject. */
    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantApiKey() {
        // JPA
    }

    public TenantApiKey(String name, String apiKeyHash, String apiKeyDisplayPrefix,
                        String permissionsJson, String createdBy, Instant expiresAt) {
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.apiKeyDisplayPrefix = apiKeyDisplayPrefix;
        this.permissionsJson = permissionsJson;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.enabled = true;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rotateKey(String newApiKeyHash, String newApiKeyDisplayPrefix) {
        this.apiKeyHash = newApiKeyHash;
        this.apiKeyDisplayPrefix = newApiKeyDisplayPrefix;
        this.updatedAt = Instant.now();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public void recordUsage() {
        this.lastUsedAt = Instant.now();
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getApiKeyDisplayPrefix() {
        return apiKeyDisplayPrefix;
    }

    public String getPermissionsJson() {
        return permissionsJson;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
