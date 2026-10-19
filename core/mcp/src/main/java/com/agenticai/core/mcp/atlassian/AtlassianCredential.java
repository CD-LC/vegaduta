package com.agenticai.core.mcp.atlassian;

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
 * A tenant's own Atlassian (Confluence/Jira) OAuth connection - the
 * per-tenant outcome of an Authorization Code + PKCE consent redirect
 * against the ONE platform-wide OAuth client ({@link AtlassianPlatformOAuthClient}),
 * unlike {@code XCredential} which is BYOK (each tenant owns its own client
 * id/secret too). See V203's migration comment.
 *
 * <p>{@code encryptedRefreshToken} is ciphertext only, decrypted transiently
 * by {@link AtlassianOAuthService#currentAccessToken}, which mints a fresh
 * access token from it on demand rather than storing one - the same
 * "no long-lived access token at rest" posture as {@code XCredential} and
 * {@code GoogleCredential}. Whether Atlassian rotates refresh tokens on
 * every use (like X) or issues a long-lived reusable one (like Google) is
 * unconfirmed at design time - {@code currentAccessToken} persists a
 * rotated token defensively whenever one comes back, which is correct
 * either way.
 */
@Entity
@Table(name = "atlassian_credential")
public class AtlassianCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "encrypted_refresh_token", columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AtlassianCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AtlassianCredential() {
        // JPA
    }

    /** OAuth consent completed - the only way this entity is ever first created (there is no earlier "app registered, no account yet" step, unlike XCredential). */
    public AtlassianCredential(String encryptedRefreshToken) {
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.status = AtlassianCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Re-consent after a prior revoke/expiry - reactivates an existing row rather than creating a second one. */
    public void reconnect(String encryptedRefreshToken) {
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.status = AtlassianCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    /** Called after every access-token refresh that returned a new refresh token - see class javadoc. */
    public void rotateRefreshToken(String encryptedRefreshToken) {
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.updatedAt = Instant.now();
    }

    public void revoke() {
        this.status = AtlassianCredentialStatus.REVOKED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEncryptedRefreshToken() {
        return encryptedRefreshToken;
    }

    public AtlassianCredentialStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
