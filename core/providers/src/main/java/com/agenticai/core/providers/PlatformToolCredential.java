package com.agenticai.core.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One platform-wide, admin-settable credential for a tool-capability
 * provider that has no per-tenant-BYOK-only path today - see V174's
 * migration comment for the full "why one generic table, not two" rationale.
 * {@code toolKey} is a short constant ({@link PlatformToolKey}), not an
 * auto-increment id - there is exactly one row per tool by design.
 */
@Entity
@Table(name = "platform_tool_credential", schema = "control_plane")
public class PlatformToolCredential {

    @Id
    @Column(name = "tool_key", length = 32)
    private String toolKey;

    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(name = "extra_config", length = 255)
    private String extraConfig;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected PlatformToolCredential() {
    }

    public PlatformToolCredential(String toolKey, String encryptedApiKey, String extraConfig, String updatedBy) {
        this.toolKey = toolKey;
        this.encryptedApiKey = encryptedApiKey;
        this.extraConfig = extraConfig;
        this.enabled = true;
        this.updatedAt = Instant.now();
        this.updatedBy = updatedBy;
    }

    public void update(String encryptedApiKey, String extraConfig, String updatedBy) {
        this.encryptedApiKey = encryptedApiKey;
        this.extraConfig = extraConfig;
        this.enabled = true;
        this.updatedAt = Instant.now();
        this.updatedBy = updatedBy;
    }

    public void deactivate(String updatedBy) {
        this.enabled = false;
        this.updatedAt = Instant.now();
        this.updatedBy = updatedBy;
    }

    public String getToolKey() {
        return toolKey;
    }

    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public String getExtraConfig() {
        return extraConfig;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
