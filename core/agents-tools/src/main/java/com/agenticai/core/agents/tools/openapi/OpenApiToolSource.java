package com.agenticai.core.agents.tools.openapi;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One imported OpenAPI 3 / Swagger 2 document and the settings every tool generated from it shares:
 * where to call ({@code baseUrl}) and how to authenticate. The "fastest tool-onboarding path"
 * sibling of {@link com.agenticai.core.agents.tools.script.UserToolScript} (SpEL) and {@link
 * com.agenticai.core.agents.tools.code.CustomCodeTool} (sandboxed code): no code is authored at all
 * - each operation in the document becomes an {@link OpenApiTool}, and {@link OpenApiToolCallback}
 * exposes those to agents through exactly the same in-process Spring AI {@code ToolCallback} path
 * the other two use, so nothing else needs wiring.
 *
 * <p><b>The credential is never plaintext.</b> {@code encryptedSecret} is AES-GCM via {@code
 * EncryptionService} (the platform's single credential chokepoint - same as {@code tenant_secret}
 * and every provider credential table) and is decrypted only inside {@link OpenApiToolExecutor}
 * right before the auth header is written. No endpoint ever returns it; the console gets {@code
 * secretPreview} (last four characters) at most. It may also hold a {@code {{secret.NAME}}} vault
 * reference instead of a literal value, resolved tenant-scoped at call time.
 *
 * <p>TENANT-schema table (V232) - schema-per-tenant means the schema is the tenancy, so there is no
 * tenant_id column, same as {@code user_tool_script}.
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(
        name = "openapi_tool_source",
        uniqueConstraints = @UniqueConstraint(columnNames = "tool_prefix"))
public class OpenApiToolSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 128)
    private String name;

    // Lowercase slug prefixed onto every generated tool name so two imported
    // APIs exposing the same operationId never collide - validated in
    // OpenApiToolSourceService, immutable after create (agents may already
    // rely on the literal tool names).
    @Column(name = "tool_prefix", nullable = false, length = 32)
    private String toolPrefix;

    @Column(name = "spec_url", columnDefinition = "TEXT")
    private String specUrl;

    @Column(name = "spec_text", nullable = false, columnDefinition = "TEXT")
    private String specText;

    @Column(name = "spec_title", length = 256)
    private String specTitle;

    @Column(name = "spec_version", length = 64)
    private String specVersion;

    @Column(name = "base_url", nullable = false, columnDefinition = "TEXT")
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 24)
    private OpenApiAuthType authType;

    @Column(name = "auth_header_name", length = 128)
    private String authHeaderName;

    // OAuth2 client-credentials settings - only meaningful when authType is
    // OAUTH2_CLIENT_CREDENTIALS; all four are NULL otherwise. tokenUrl is a
    // full URL validated exactly like baseUrl (public http(s)); tokenStyle is
    // FORM (x-www-form-urlencoded body, e.g. Availity) or JSON (a JSON body,
    // e.g. Optum /apip/auth/v2/token). The client SECRET is NOT here - it is
    // encrypted_secret below, the same field the other auth modes use.
    @Column(name = "oauth2_token_url", columnDefinition = "TEXT")
    private String oauth2TokenUrl;

    @Column(name = "oauth2_client_id", length = 512)
    private String oauth2ClientId;

    @Column(name = "oauth2_scope", length = 512)
    private String oauth2Scope;

    @Column(name = "oauth2_token_style", length = 8)
    private String oauth2TokenStyle;

    @Column(name = "encrypted_secret", columnDefinition = "TEXT")
    private String encryptedSecret;

    @Column(name = "secret_preview", length = 16)
    private String secretPreview;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Owning side of the generated tools: orphanRemoval so a refresh that
    // drops an operation from the document deletes its tool row, and a
    // source delete takes every tool with it (the FK is ON DELETE CASCADE
    // too, as a backstop). LAZY - the chat-turn path never loads tools via
    // this collection (see OpenApiToolRepository#findRunnable).
    @OneToMany(
            mappedBy = "source",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<OpenApiTool> tools = new ArrayList<>();

    protected OpenApiToolSource() {
        // JPA
    }

    public OpenApiToolSource(
            String name,
            String toolPrefix,
            String specUrl,
            String specText,
            String specTitle,
            String specVersion,
            String baseUrl,
            OpenApiAuthType authType,
            String authHeaderName,
            String oauth2TokenUrl,
            String oauth2ClientId,
            String oauth2Scope,
            String oauth2TokenStyle,
            String encryptedSecret,
            String secretPreview,
            String createdBy) {
        this.name = name;
        this.toolPrefix = toolPrefix;
        this.specUrl = specUrl;
        this.specText = specText;
        this.specTitle = specTitle;
        this.specVersion = specVersion;
        this.baseUrl = baseUrl;
        applyAuthSettings(
                authType,
                authHeaderName,
                oauth2TokenUrl,
                oauth2ClientId,
                oauth2Scope,
                oauth2TokenStyle);
        this.encryptedSecret = encryptedSecret;
        this.secretPreview = secretPreview;
        this.createdBy = createdBy;
        this.enabled = true;
        Instant now = Instant.now();
        this.createdAt = now;
        this.lastRefreshedAt = now;
        this.updatedAt = now;
    }

    public void updateSettings(
            String name,
            String baseUrl,
            OpenApiAuthType authType,
            String authHeaderName,
            String oauth2TokenUrl,
            String oauth2ClientId,
            String oauth2Scope,
            String oauth2TokenStyle) {
        this.name = name;
        this.baseUrl = baseUrl;
        applyAuthSettings(
                authType,
                authHeaderName,
                oauth2TokenUrl,
                oauth2ClientId,
                oauth2Scope,
                oauth2TokenStyle);
        this.updatedAt = Instant.now();
    }

    /**
     * Auth fields are switched as a unit so an OAuth2 source can never end up carrying an API-key
     * header name, or vice versa - fields that do not belong to the selected mode are nulled, not
     * left stale.
     */
    private void applyAuthSettings(
            OpenApiAuthType authType,
            String authHeaderName,
            String oauth2TokenUrl,
            String oauth2ClientId,
            String oauth2Scope,
            String oauth2TokenStyle) {
        this.authType = authType;
        boolean oauth2 = authType == OpenApiAuthType.OAUTH2_CLIENT_CREDENTIALS;
        this.authHeaderName = authType == OpenApiAuthType.API_KEY_HEADER ? authHeaderName : null;
        this.oauth2TokenUrl = oauth2 ? oauth2TokenUrl : null;
        this.oauth2ClientId = oauth2 ? oauth2ClientId : null;
        this.oauth2Scope = oauth2 ? oauth2Scope : null;
        this.oauth2TokenStyle = oauth2 ? oauth2TokenStyle : null;
    }

    /** Replaces the stored credential. {@code null} ciphertext clears it. */
    public void replaceSecret(String encryptedSecret, String secretPreview) {
        this.encryptedSecret = encryptedSecret;
        this.secretPreview = secretPreview;
        this.updatedAt = Instant.now();
    }

    public void recordRefresh(
            String specUrl, String specText, String specTitle, String specVersion) {
        this.specUrl = specUrl;
        this.specText = specText;
        this.specTitle = specTitle;
        this.specVersion = specVersion;
        this.lastError = null;
        Instant now = Instant.now();
        this.lastRefreshedAt = now;
        this.updatedAt = now;
    }

    public void recordRefreshFailure(String error) {
        this.lastError = error;
        this.updatedAt = Instant.now();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getToolPrefix() {
        return toolPrefix;
    }

    public String getSpecUrl() {
        return specUrl;
    }

    public String getSpecText() {
        return specText;
    }

    public String getSpecTitle() {
        return specTitle;
    }

    public String getSpecVersion() {
        return specVersion;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public OpenApiAuthType getAuthType() {
        return authType;
    }

    public String getAuthHeaderName() {
        return authHeaderName;
    }

    public String getOauth2TokenUrl() {
        return oauth2TokenUrl;
    }

    public String getOauth2ClientId() {
        return oauth2ClientId;
    }

    public String getOauth2Scope() {
        return oauth2Scope;
    }

    /** {@code FORM} or {@code JSON}; null unless authType is OAuth2. */
    public String getOauth2TokenStyle() {
        return oauth2TokenStyle;
    }

    /**
     * Package-private on purpose: {@link OpenApiToolExecutor} is the only reader, so there is
     * exactly one decrypt path. A public getter would be one accidental {@code
     * ResponseDto.from(source)} away from leaking ciphertext (and, with the key, plaintext).
     */
    String getEncryptedSecret() {
        return encryptedSecret;
    }

    public boolean hasSecret() {
        return encryptedSecret != null && !encryptedSecret.isBlank();
    }

    public String getSecretPreview() {
        return secretPreview;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OpenApiTool> getTools() {
        return tools;
    }
}
