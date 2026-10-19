package com.agenticai.core.secrets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One named secret in a tenant's vault (table {@code tenant_secret}, V222 -
 * unqualified name, so it lives in each tenant's own schema).
 *
 * <p><b>The value never leaves this object as plaintext by accident.</b> The
 * ciphertext field is package-private to read ({@link #encryptedValue()}) and
 * there is deliberately no {@code getValue()}, no Lombok, and a
 * {@link #toString()} that prints the preview rather than the payload. The one
 * supported way to obtain plaintext is
 * {@link SecretVaultService#resolveForRun(String, String)}, which audits the
 * access; that is enforced by there being no other decrypt call site.
 *
 * @see SecretReferenceResolver for how a secret reaches a tool without ever
 *      passing through the model's context.
 */
@Entity
@Table(name = "tenant_secret")
public class TenantSecret {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private SecretScope scope;

    /** Null for {@link SecretScope#TENANT}; enforced by a CHECK constraint in V222. */
    @Column(name = "owner_user_id", length = 255)
    private String ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private SecretKind kind;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "encrypted_value", nullable = false, columnDefinition = "TEXT")
    private String encryptedValue;

    @Column(name = "value_preview", length = 16)
    private String valuePreview;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SecretStatus status;

    /**
     * May tenant-authored sandbox code receive this value (V228)?
     *
     * <p>Default false, and that default is the security property. A program
     * holding a plaintext value can always return it - transformed past any
     * redaction - so granting a secret to a code tool is equivalent to
     * disclosing it to everyone who can author one. Making that an explicit
     * per-secret decision keeps the blast radius of a stolen admin session to
     * the secrets somebody deliberately opted in, rather than the whole vault.
     */
    @Column(name = "code_tool_access", nullable = false)
    private boolean codeToolAccess;

    /**
     * Destination hosts this secret may be sent to by a URL-bound carrier
     * ({@code http_request}, workflow HTTP node) - comma-separated patterns,
     * see {@link SecretHostPolicy} (V365.4, SEC-006). Null/empty: never
     * resolved for a URL-bound carrier (fail closed).
     */
    @Column(name = "allowed_hosts", columnDefinition = "TEXT")
    private String allowedHosts;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantSecret() {
        // JPA
    }

    TenantSecret(
        String name,
        SecretScope scope,
        String ownerUserId,
        SecretKind kind,
        String description,
        String encryptedValue,
        String valuePreview,
        Instant expiresAt,
        boolean codeToolAccess,
        String createdBy
    ) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.scope = scope;
        this.ownerUserId = ownerUserId;
        this.kind = kind;
        this.description = description;
        this.encryptedValue = encryptedValue;
        this.valuePreview = valuePreview;
        this.status = SecretStatus.ACTIVE;
        this.expiresAt = expiresAt;
        this.codeToolAccess = codeToolAccess;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Replaces the value in place, keeping the row's id, name and audit
     * history. Rotation is an update rather than a delete+create precisely so
     * that every agent, tool and MCP server already referencing
     * {@code {{secret.NAME}}} keeps working across a rotation with no edits.
     */
    /**
     * Changes only the code-tool opt-in. Separate from {@link #rotate} so that
     * replacing a credential cannot silently widen its reach, and vice versa.
     */
    void setCodeToolAccess(boolean allowed) {
        this.codeToolAccess = allowed;
        this.updatedAt = Instant.now();
    }

    /** A null {@code newDescription} leaves the stored description unchanged. */
    void rotate(String newEncryptedValue, String newPreview, Instant newExpiresAt, String newDescription) {
        if (newDescription != null) {
            this.description = newDescription;
        }
        this.encryptedValue = newEncryptedValue;
        this.valuePreview = newPreview;
        this.expiresAt = newExpiresAt;
        this.status = SecretStatus.ACTIVE;
        this.rotatedAt = Instant.now();
        this.updatedAt = this.rotatedAt;
    }

    void updateMetadata(String description, Instant expiresAt) {
        this.description = description;
        this.expiresAt = expiresAt;
        this.updatedAt = Instant.now();
    }

    void revoke() {
        this.status = SecretStatus.REVOKED;
        this.updatedAt = Instant.now();
    }

    void markUsed() {
        this.lastUsedAt = Instant.now();
    }

    /**
     * Swaps the ciphertext for an equivalent one under a different encryption
     * key. Used only by {@link SecretRotationService}.
     *
     * <p>Deliberately does NOT touch {@code updatedAt}, {@code rotatedAt} or
     * {@code valuePreview}: the secret VALUE has not changed, only how it is
     * wrapped. Stamping rotatedAt here would make a key rotation look like a
     * credential rotation in the UI and send people hunting for a change that
     * never happened.
     */
    void replaceCiphertext(String reencrypted) {
        this.encryptedValue = reencrypted;
    }

    /**
     * True when this secret cannot currently be resolved. Expiry is evaluated
     * here rather than filtered in SQL so that an expired secret produces a
     * precise, actionable "expired on ..." error at the call site instead of
     * an indistinguishable "no such secret".
     */
    public boolean isResolvable() {
        return status == SecretStatus.ACTIVE
            && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public SecretScope getScope() {
        return scope;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public SecretKind getKind() {
        return kind;
    }

    public String getDescription() {
        return description;
    }

    public String getValuePreview() {
        return valuePreview;
    }

    public SecretStatus getStatus() {
        return status;
    }

    public boolean isCodeToolAccess() {
        return codeToolAccess;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
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

    /**
     * Package-private on purpose: {@link SecretVaultService} is the only class
     * that may hand ciphertext to {@code EncryptionService}. Widening this to
     * public would create a second decrypt path with no audit row.
     */
    String encryptedValue() {
        return encryptedValue;
    }

    /** Normalised destination-host patterns (never null). See {@link SecretHostPolicy}. */
    public List<String> getAllowedHosts() {
        return SecretHostPolicy.fromColumn(allowedHosts);
    }

    /** Replaces the destination allow-list; {@code patterns} must already be normalised. */
    void setAllowedHosts(List<String> patterns) {
        this.allowedHosts = SecretHostPolicy.toColumn(patterns);
        this.updatedAt = Instant.now();
    }

    /** Never prints the ciphertext or the value - logging an entity is a common accident. */
    @Override
    public String toString() {
        return "TenantSecret{name=" + name + ", scope=" + scope + ", status=" + status + "}";
    }
}
