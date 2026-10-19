package com.agenticai.core.mcp.oauth;

import com.agenticai.core.mcp.oauth.McpOAuthConnectionStore.McpOAuthConnection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One tenant's OAuth connection to one resource (see V424). Lives in the tenant
 * schema, so the tenant is implicit. Every secret column is ciphertext written
 * by {@code EncryptionService} before it gets here.
 */
@Entity
@Table(name = "mcp_oauth_connection")
public class McpOAuthConnectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "resource_url", nullable = false, length = 1024)
    private String resourceUrl;

    @Column(nullable = false, length = 512)
    private String issuer;

    @Column(name = "authorization_endpoint", nullable = false, columnDefinition = "TEXT")
    private String authorizationEndpoint;

    @Column(name = "token_endpoint", nullable = false, columnDefinition = "TEXT")
    private String tokenEndpoint;

    @Column(name = "client_id", nullable = false, length = 512)
    private String clientId;

    @Column(name = "encrypted_client_secret", columnDefinition = "TEXT")
    private String encryptedClientSecret;

    @Column(name = "token_endpoint_auth_method", length = 64)
    private String tokenEndpointAuthMethod;

    @Column(name = "redirect_uri", columnDefinition = "TEXT")
    private String redirectUri;

    @Column(columnDefinition = "TEXT")
    private String scope;

    @Column(name = "encrypted_refresh_token", columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    @Column(name = "encrypted_access_token", columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected McpOAuthConnectionEntity() {
        // JPA
    }

    public McpOAuthConnectionEntity(String resourceUrl) {
        this.resourceUrl = resourceUrl;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Copies every mutable field of {@code c} onto this row (the resource URL is the key and never changes). */
    public void apply(McpOAuthConnection c) {
        this.issuer = c.issuer();
        this.authorizationEndpoint = c.authorizationEndpoint();
        this.tokenEndpoint = c.tokenEndpoint();
        this.clientId = c.clientId();
        this.encryptedClientSecret = c.encryptedClientSecret();
        this.tokenEndpointAuthMethod = c.tokenEndpointAuthMethod();
        this.redirectUri = c.redirectUri();
        this.scope = c.scope();
        this.encryptedRefreshToken = c.encryptedRefreshToken();
        this.encryptedAccessToken = c.encryptedAccessToken();
        this.accessTokenExpiresAt = c.accessTokenExpiresAt();
        this.updatedAt = Instant.now();
    }

    public McpOAuthConnection toConnection(String tenantId) {
        return new McpOAuthConnection(tenantId, resourceUrl, issuer, authorizationEndpoint, tokenEndpoint, clientId,
            encryptedClientSecret, tokenEndpointAuthMethod, redirectUri, scope, encryptedRefreshToken,
            encryptedAccessToken, accessTokenExpiresAt);
    }

    public UUID getId() {
        return id;
    }

    public String getResourceUrl() {
        return resourceUrl;
    }
}
