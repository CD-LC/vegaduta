package com.agenticai.core.omnichannel.x;

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
 * A tenant's own X (Twitter) connection - a genuine hybrid of BYOK and OAuth, unlike every other
 * channel credential in this codebase:
 *
 * <ul>
 *   <li><b>BYOK, like Twilio/Meta/Slack/Discord</b>: X's DM endpoints require at minimum the paid
 *       "Basic" API access tier, purchased and rate-limited per X Developer App - so, exactly like
 *       Twilio, each tenant must own and pay for their own X Developer App, not share a
 *       platform-wide one.
 *   <li><b>OAuth 2.0 Authorization Code + PKCE, like Google</b>: unlike Twilio/Meta/Slack/Discord's
 *       static Bot/API tokens, DM access additionally requires a real user-context OAuth consent
 *       redirect (scopes {@code dm.read}, {@code dm.write}, {@code tweet.read}, {@code users.read},
 *       {@code offline.access}) - there is no static "app-only" token that can read or send DMs.
 * </ul>
 *
 * <p>So activation is two steps, tracked by {@link #status}: {@link
 * XCredentialStatus#APP_REGISTERED} once {@code clientId}/{@code clientSecret} are stored (the
 * tenant pasted these from their own X Developer Portal app, which they must have already added
 * this platform's fixed callback URL to as an authorized redirect URI), then {@link
 * XCredentialStatus#ACTIVE} once {@link XOAuthService#connect} completes the consent redirect and a
 * refresh token exists.
 *
 * <p><b>Unlike every other channel here, there is no {@link
 * com.agenticai.core.tenancy.ExternalAccountProvider} entry for X</b> - every other channel needs
 * one because an inbound webhook has to resolve "which tenant is this?" from an unverified
 * third-party-supplied identifier. X has no inbound webhook at all (see {@code XDmPollingJob}'s
 * javadoc): this platform polls FOR each tenant, driven by {@code TenantSchemaIterator} iterating
 * tenants it already knows about, so there is never a moment where tenant identity needs to be
 * resolved FROM an external identifier.
 *
 * <p>{@code encryptedRefreshToken} is ciphertext only, same as Google's. Unlike Google's refresh
 * tokens (long-lived, reusable across many refreshes), <b>X rotates the refresh token on every
 * use</b> - {@link XOAuthService#currentAccessToken} persists the newly-returned refresh token
 * immediately after every refresh call, or the previous one would become invalid and silently break
 * the connection on the very next poll.
 *
 * <p>{@code lastDmEventId} is {@code XDmPollingJob}'s cursor - the most recent {@code dm_events} id
 * already processed for this tenant, so each poll only acts on genuinely new events instead of
 * replaying the whole conversation history every cycle.
 */
@Entity
@Table(name = "x_credential")
public class XCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "encrypted_client_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedClientSecret;

    @Column(name = "x_user_id", length = 32)
    private String xUserId;

    @Column(name = "x_username", length = 255)
    private String xUsername;

    @Column(name = "encrypted_refresh_token", columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    @Column(name = "last_dm_event_id", length = 32)
    private String lastDmEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private XCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    protected XCredential() {
        // JPA
    }

    /** Step 1: app registration (BYOK) - no account connected yet. */
    public XCredential(String clientId, String encryptedClientSecret) {
        this.clientId = clientId;
        this.encryptedClientSecret = encryptedClientSecret;
        this.status = XCredentialStatus.APP_REGISTERED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void reregisterApp(String clientId, String encryptedClientSecret) {
        this.clientId = clientId;
        this.encryptedClientSecret = encryptedClientSecret;
        this.updatedAt = Instant.now();
    }

    /** Step 2: OAuth consent completed. */
    public void connect(String xUserId, String xUsername, String encryptedRefreshToken) {
        this.xUserId = xUserId;
        this.xUsername = xUsername;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.status = XCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    /**
     * Called after every access-token refresh - see class javadoc for why this must happen on every
     * single refresh, not just at connect time.
     */
    public void rotateRefreshToken(String encryptedRefreshToken) {
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.updatedAt = Instant.now();
    }

    public void revoke() {
        this.status = XCredentialStatus.REVOKED;
        this.updatedAt = Instant.now();
    }

    public void advancePollCursor(String lastDmEventId) {
        this.lastDmEventId = lastDmEventId;
        this.lastPolledAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getEncryptedClientSecret() {
        return encryptedClientSecret;
    }

    public String getXUserId() {
        return xUserId;
    }

    public String getXUsername() {
        return xUsername;
    }

    public String getEncryptedRefreshToken() {
        return encryptedRefreshToken;
    }

    public String getLastDmEventId() {
        return lastDmEventId;
    }

    public XCredentialStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }
}
