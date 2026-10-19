package com.agenticai.core.mcp.atlassian;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The platform's own, one-time Dynamic Client Registration (RFC 7591) with
 * Atlassian's remote MCP server - see V202's migration comment for why this
 * is a single control_plane-schema row shared by every tenant, unlike
 * {@code XCredential} (BYOK, per-tenant). {@link AtlassianPlatformClientService}
 * is the only writer; every tenant's own {@code AtlassianCredential} (tenant
 * schema) reuses this one client for its own Authorization Code + PKCE
 * consent redirect.
 *
 * <p>{@code tokenEndpointAuthMethod} and {@code redirectUri} are persisted
 * (not assumed) because both are load-bearing on every later token call -
 * see {@link AtlassianOAuthClient}'s javadoc.
 */
@Entity
@Table(name = "atlassian_oauth_client", schema = "control_plane")
public class AtlassianPlatformOAuthClient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    // Nullable: a DCR server may grant a public ("none" auth method) client
    // with no secret at all - see V202's migration comment.
    @Column(name = "encrypted_client_secret", columnDefinition = "TEXT")
    private String encryptedClientSecret;

    @Column(name = "token_endpoint_auth_method", nullable = false, length = 32)
    private String tokenEndpointAuthMethod;

    @Column(name = "redirect_uri", nullable = false, length = 512)
    private String redirectUri;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    protected AtlassianPlatformOAuthClient() {
        // JPA
    }

    public AtlassianPlatformOAuthClient(String clientId, String encryptedClientSecret, String tokenEndpointAuthMethod, String redirectUri) {
        this.clientId = clientId;
        this.encryptedClientSecret = encryptedClientSecret;
        this.tokenEndpointAuthMethod = tokenEndpointAuthMethod;
        this.redirectUri = redirectUri;
        this.registeredAt = Instant.now();
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

    public String getTokenEndpointAuthMethod() {
        return tokenEndpointAuthMethod;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }
}
