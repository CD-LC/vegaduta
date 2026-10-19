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
 * One row per tenant schema describing that tenant's own identity provider
 * (BYO-IdP). {@link TenantSsoService} mirrors this into the shared Keycloak
 * realm as an IdP instance aliased {@code tenant-<schema>} - Keycloak does
 * the OIDC/SAML protocol work; this table is our source of truth for "what
 * did the tenant configure" so the admin console can render status and so
 * IdP config survives a Keycloak rebuild.
 *
 * <p>The OIDC client secret is stored AES-GCM-encrypted ({@code
 * clientSecretEncrypted}) and is NEVER returned by the read API - only
 * {@code clientSecretSet} is.
 */
@Entity
@Table(schema = "control_plane", name = "tenant_sso_config")
public class TenantSsoConfig {

    public enum ProviderType { OIDC, SAML }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_schema", nullable = false, unique = true, length = 64)
    private String tenantSchema;

    @Column(name = "provider_type", nullable = false, length = 8)
    private String providerType; // OIDC | SAML - String, not @Enumerated, matching sibling-table convention

    @Column(name = "alias", nullable = false, unique = true, length = 128)
    private String alias;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "oidc_discovery_url", length = 2048)
    private String oidcDiscoveryUrl;

    @Column(name = "oidc_authorization_url", length = 2048)
    private String oidcAuthorizationUrl;

    @Column(name = "oidc_token_url", length = 2048)
    private String oidcTokenUrl;

    @Column(name = "oidc_userinfo_url", length = 2048)
    private String oidcUserInfoUrl;

    @Column(name = "oidc_jwks_url", length = 2048)
    private String oidcJwksUrl;

    @Column(name = "oidc_issuer", length = 2048)
    private String oidcIssuer;

    @Column(name = "oidc_client_id", length = 512)
    private String oidcClientId;

    @Column(name = "client_secret_encrypted", columnDefinition = "TEXT")
    private String clientSecretEncrypted;

    @Column(name = "saml_entity_id", length = 2048)
    private String samlEntityId;

    @Column(name = "saml_sso_url", length = 2048)
    private String samlSsoUrl;

    @Column(name = "saml_x509_cert", columnDefinition = "TEXT")
    private String samlX509Cert;

    @Column(name = "saml_name_id_format", length = 128)
    private String samlNameIdFormat;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    public UUID getId() { return id; }
    public String getTenantSchema() { return tenantSchema; }
    public void setTenantSchema(String v) { tenantSchema = v; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String v) { providerType = v; }
    public String getAlias() { return alias; }
    public void setAlias(String v) { alias = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getOidcDiscoveryUrl() { return oidcDiscoveryUrl; }
    public void setOidcDiscoveryUrl(String v) { oidcDiscoveryUrl = v; }
    public String getOidcAuthorizationUrl() { return oidcAuthorizationUrl; }
    public void setOidcAuthorizationUrl(String v) { oidcAuthorizationUrl = v; }
    public String getOidcTokenUrl() { return oidcTokenUrl; }
    public void setOidcTokenUrl(String v) { oidcTokenUrl = v; }
    public String getOidcUserInfoUrl() { return oidcUserInfoUrl; }
    public void setOidcUserInfoUrl(String v) { oidcUserInfoUrl = v; }
    public String getOidcJwksUrl() { return oidcJwksUrl; }
    public void setOidcJwksUrl(String v) { oidcJwksUrl = v; }
    public String getOidcIssuer() { return oidcIssuer; }
    public void setOidcIssuer(String v) { oidcIssuer = v; }
    public String getOidcClientId() { return oidcClientId; }
    public void setOidcClientId(String v) { oidcClientId = v; }
    public String getClientSecretEncrypted() { return clientSecretEncrypted; }
    public void setClientSecretEncrypted(String v) { clientSecretEncrypted = v; }
    public String getSamlEntityId() { return samlEntityId; }
    public void setSamlEntityId(String v) { samlEntityId = v; }
    public String getSamlSsoUrl() { return samlSsoUrl; }
    public void setSamlSsoUrl(String v) { samlSsoUrl = v; }
    public String getSamlX509Cert() { return samlX509Cert; }
    public void setSamlX509Cert(String v) { samlX509Cert = v; }
    public String getSamlNameIdFormat() { return samlNameIdFormat; }
    public void setSamlNameIdFormat(String v) { samlNameIdFormat = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant v) { lastSyncedAt = v; }
}
