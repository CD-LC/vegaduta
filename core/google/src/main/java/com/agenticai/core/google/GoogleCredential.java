package com.agenticai.core.google;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A connected Google account - one platform-level OAuth app (see {@code GoogleOAuthService}'s
 * javadoc for why this is platform-level, not BYOK like Twilio/Meta), same schema-per-tenant shape
 * as {@code TwilioCredential}/{@code MetaWhatsAppCredential} - no {@code tenant_id} column needed,
 * since the table this maps to is a physically different table per tenant schema.
 *
 * <p><b>Per-end-user credentials (V215)</b>: this was one row per tenant until V215 added {@link
 * #subjectKey}. It now holds at most one row per person plus at most one tenant-wide row, enforced
 * by two partial unique indexes:
 *
 * <ul>
 *   <li>{@code subjectKey == null} - the legacy tenant-wide account an admin connected. This is the
 *       fallback every caller without a personal row resolves to, which is what makes the change
 *       invisible to existing tenants.
 *   <li>{@code subjectKey != null} - one end user's own Google account, keyed on the Keycloak
 *       {@code sub} claim {@code TenantContextFilter} puts into {@code TenantContext}. That is the
 *       identical value {@code AgentController} derives its agent-memory {@code subjectKey} from,
 *       so identity means the same thing in both subsystems by construction rather than by
 *       convention.
 * </ul>
 *
 * <p>The subject is deliberately an opaque {@code VARCHAR(255)} with no foreign key, matching
 * {@code control_plane.user_permission_grant}'s {@code keycloak_user_id}: an FK to {@code app_user}
 * would silently fail for everyone an admin later invited, because {@code app_user} only has rows
 * for the original signup admin.
 *
 * <p>{@code encryptedRefreshToken} is ciphertext only (AES-256-GCM via the existing {@link
 * com.agenticai.core.crypto.EncryptionService}). Google access tokens are deliberately NOT
 * persisted anywhere - they're short- lived (~1 hour) and cheap to mint on demand from the refresh
 * token (see {@code GoogleOAuthService#currentAccessToken}), so storing one would just be one more
 * thing that could go stale for no benefit.
 *
 * <p>{@code grantedScopes} is the space-separated {@code scope} string Google's token response
 * actually returned - not assumed to equal what was requested, since a user can deselect individual
 * scopes in Google's consent screen. The four Google tool callbacks each check this before
 * attempting a call they don't have permission for, rather than discovering the gap via a 403 from
 * Google.
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(name = "google_credential")
public class GoogleCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Keycloak {@code sub} of the end user this row belongs to, or {@code null} for the tenant-wide
     * account (see the class javadoc). Nullable on purpose: {@code null} IS the
     * backward-compatibility story - every pre-V215 row keeps it {@code null} and keeps resolving
     * exactly as before. Never mutated after construction: a credential cannot change owners, only
     * be revoked and re-consented, so there is deliberately no setter and {@link #reconnect} does
     * not touch it.
     */
    @Column(name = "subject_key", length = 255)
    private String subjectKey;

    @Column(name = "connected_email", nullable = false, length = 255)
    private String connectedEmail;

    @Column(name = "encrypted_refresh_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    @Column(name = "granted_scopes", nullable = false, columnDefinition = "TEXT")
    private String grantedScopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoogleCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GoogleCredential() {
        // JPA
    }

    /**
     * The tenant-wide account - {@code subjectKey} stays {@code null}. Kept as the three-argument
     * shape every pre-V215 caller (and the module's test suite) already uses, so "tenant-wide"
     * remains the default one gets without asking for anything else.
     */
    public GoogleCredential(
            String connectedEmail, String encryptedRefreshToken, String grantedScopes) {
        this(null, connectedEmail, encryptedRefreshToken, grantedScopes);
    }

    /**
     * A specific end user's account when {@code subjectKey} is non-null (the Keycloak {@code sub}
     * carried through the encrypted OAuth {@code state} - see {@code
     * GoogleOAuthService#personalAuthorizeUrl}), or the tenant-wide account when it is {@code
     * null}.
     */
    public GoogleCredential(
            String subjectKey,
            String connectedEmail,
            String encryptedRefreshToken,
            String grantedScopes) {
        this.subjectKey = subjectKey;
        this.connectedEmail = connectedEmail;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.grantedScopes = grantedScopes;
        this.status = GoogleCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void reconnect(
            String connectedEmail, String encryptedRefreshToken, String grantedScopes) {
        this.connectedEmail = connectedEmail;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.grantedScopes = grantedScopes;
        this.status = GoogleCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void revoke() {
        this.status = GoogleCredentialStatus.REVOKED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    /**
     * @return the owning end user's Keycloak {@code sub}, or {@code null} for the tenant-wide
     *     account.
     */
    public String getSubjectKey() {
        return subjectKey;
    }

    /**
     * True for the legacy/admin-connected account every caller without a personal row falls back
     * to.
     */
    public boolean isTenantWide() {
        return subjectKey == null;
    }

    public String getConnectedEmail() {
        return connectedEmail;
    }

    public String getEncryptedRefreshToken() {
        return encryptedRefreshToken;
    }

    public String getGrantedScopes() {
        return grantedScopes;
    }

    public boolean hasScope(String scope) {
        return grantedScopes != null && List.of(grantedScopes.split(" ")).contains(scope);
    }

    public GoogleCredentialStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
