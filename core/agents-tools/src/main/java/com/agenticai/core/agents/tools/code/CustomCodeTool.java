package com.agenticai.core.agents.tools.code;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * "Custom Code Tools" - the real-code sibling of {@code UserToolScript}.
 *
 * <p>{@code UserToolScript}'s javadoc explains at length why THAT feature was deliberately scoped
 * down to SpEL: there was no sandboxed execution engine in this codebase, and building one blind
 * was ruled out. This entity is what exists once that engine does. A tenant writes an actual Java
 * or Python program; it is compiled/parsed and then executed by the standalone {@code tool-sandbox}
 * microservice (isolated process, seccomp profile, hard wall-clock and memory ceilings), never in
 * this JVM. Core never evaluates, classloads, or {@code exec}s any part of {@link #sourceCode} -
 * its entire relationship with tenant code is "POST it to the sandbox over HTTP and read back JSON"
 * (see {@link ToolSandboxClient}). That is the whole security model, and it is why this feature
 * could ship where the SpEL one could not.
 *
 * <p>Tenant-schema entity, no {@code tenant_id} column: this platform is schema-per-tenant, so the
 * row's schema IS its tenancy (same convention as {@code UserToolScript}, {@code AgentRow}, and
 * every other tenant-scoped entity in this module).
 *
 * <p>{@link #status} is the runnability gate, not {@link #enabled}: a tool the sandbox rejected is
 * still saved (so the tenant can read {@link #validationError} in the editor and fix it) but is
 * never offered to an agent, and cannot be flipped enabled - see {@code
 * CustomCodeToolService#setEnabled}.
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(name = "custom_code_tool", uniqueConstraints = @UniqueConstraint(columnNames = "tool_name"))
public class CustomCodeTool {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // The literal tool name an agent's LLM sees and calls by - schema-safe
    // identifier, validated in CustomCodeToolService against the exact same
    // pattern UserToolScriptService uses, and unique per tenant schema.
    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CustomCodeToolLanguage language;

    // The tenant-authored program itself. Byte-capped at save time
    // (tool-sandbox.max-source-bytes) - this column is TEXT, so the cap is a
    // product/abuse bound, not a storage one.
    @Column(name = "source_code", nullable = false, columnDefinition = "TEXT")
    private String sourceCode;

    // Optional JSON Schema text shown to the LLM as this tool's inputSchema.
    // Free-form and not validated as JSON Schema server-side, same posture as
    // UserToolScript#inputSchemaJson; a permissive object schema is substituted
    // when blank - see CustomCodeToolCallback.
    @Column(name = "input_schema_json", columnDefinition = "TEXT")
    private String inputSchemaJson;

    // Prose describing what the program returns. Folded into the tool
    // description handed to the model - the sandbox returns whatever JSON the
    // program printed, so there is no output *schema* to enforce, only an
    // author's description to pass along honestly.
    @Column(name = "output_description", columnDefinition = "TEXT")
    private String outputDescription;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CustomCodeToolStatus status;

    // Joined compiler/parser diagnostics from the last REJECTED verdict;
    // cleared on the next VALIDATED one.
    @Column(name = "validation_error", columnDefinition = "TEXT")
    private String validationError;

    // Hex SHA-256 of sourceCode, recomputed on every source change. Lets the
    // editor and the sandbox agree on exactly which revision a verdict belongs
    // to without re-sending the whole program.
    @Column(name = "source_sha256", length = 64)
    private String sourceSha256;

    // Per-call ceilings handed to the sandbox. Already clamped into the
    // configured [1, max] window at save time (see CustomCodeToolService), so
    // whatever is stored here is directly usable.
    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    @Column(name = "memory_mb", nullable = false)
    private int memoryMb;

    // Bumped whenever sourceCode changes. Not an @Version optimistic-locking
    // column - it is a user-visible revision counter for the editor, and
    // annotating it would make Hibernate own it and fail unrelated saves.
    @Column(nullable = false)
    private int version;

    /**
     * Vault secret names (V222 {@code tenant_secret}) this tool may receive, declared up front
     * rather than requested at runtime.
     *
     * <p>The values are never stored here - only the names. They are resolved immediately before
     * each execution and handed to the sandbox process, so rotating a secret takes effect on the
     * very next run with no edit to this tool. An empty set means the tool gets no secrets at all,
     * which is the default and the right default.
     *
     * <p>EAGER because the execution path needs these on every single call and a lazy collection
     * here would be a guaranteed N+1 on the hot path (and an outright {@code
     * LazyInitializationException} in the detached-entity case {@code CustomCodeToolCallback} hands
     * around).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "custom_code_tool_secret", joinColumns = @JoinColumn(name = "tool_id"))
    @Column(name = "secret_name", length = 64, nullable = false)
    private Set<String> allowedSecretNames = new LinkedHashSet<>();

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomCodeTool() {
        // JPA
    }

    public CustomCodeTool(
            String toolName,
            String displayName,
            String description,
            CustomCodeToolLanguage language,
            String sourceCode,
            String inputSchemaJson,
            String outputDescription,
            String sourceSha256,
            int timeoutMs,
            int memoryMb,
            String createdBy) {
        this.toolName = toolName;
        this.displayName = displayName;
        this.description = description;
        this.language = language;
        this.sourceCode = sourceCode;
        this.inputSchemaJson = inputSchemaJson;
        this.outputDescription = outputDescription;
        this.sourceSha256 = sourceSha256;
        this.timeoutMs = timeoutMs;
        this.memoryMb = memoryMb;
        this.createdBy = createdBy;
        this.enabled = true;
        // DRAFT until the sandbox returns a verdict - the service marks it
        // VALIDATED/REJECTED in the same transaction, but a row is never
        // constructed already claiming to have passed a check it hasn't had.
        this.status = CustomCodeToolStatus.DRAFT;
        this.version = 1;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Applies an edit. {@code version} is bumped and {@code sourceSha256} replaced only when {@code
     * sourceCode} actually differs - re-saving with unchanged source (a description tweak, a
     * timeout change) must not invent a new revision.
     */
    public void update(
            String displayName,
            String description,
            CustomCodeToolLanguage language,
            String sourceCode,
            String inputSchemaJson,
            String outputDescription,
            String sourceSha256,
            int timeoutMs,
            int memoryMb) {
        boolean sourceChanged =
                !java.util.Objects.equals(this.sourceCode, sourceCode) || this.language != language;
        this.displayName = displayName;
        this.description = description;
        this.language = language;
        this.sourceCode = sourceCode;
        this.inputSchemaJson = inputSchemaJson;
        this.outputDescription = outputDescription;
        this.timeoutMs = timeoutMs;
        this.memoryMb = memoryMb;
        if (sourceChanged) {
            this.sourceSha256 = sourceSha256;
            this.version = this.version + 1;
        }
        this.updatedAt = Instant.now();
    }

    /** The sandbox accepted this revision - it becomes runnable (if also enabled). */
    public void markValidated() {
        this.status = CustomCodeToolStatus.VALIDATED;
        this.validationError = null;
        this.updatedAt = Instant.now();
    }

    /**
     * The sandbox refused this revision. Row is kept so the tenant can read {@code error} and fix
     * it.
     */
    public void markRejected(String error) {
        this.status = CustomCodeToolStatus.REJECTED;
        this.validationError = error;
        this.updatedAt = Instant.now();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    /** Enabled AND accepted by the sandbox - the only combination an agent ever sees. */
    public boolean isRunnable() {
        return enabled && status == CustomCodeToolStatus.VALIDATED;
    }

    public UUID getId() {
        return id;
    }

    public String getToolName() {
        return toolName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public CustomCodeToolLanguage getLanguage() {
        return language;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public String getInputSchemaJson() {
        return inputSchemaJson;
    }

    public String getOutputDescription() {
        return outputDescription;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public CustomCodeToolStatus getStatus() {
        return status;
    }

    public String getValidationError() {
        return validationError;
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getMemoryMb() {
        return memoryMb;
    }

    public int getVersion() {
        return version;
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

    public Set<String> getAllowedSecretNames() {
        return allowedSecretNames;
    }

    /**
     * Replaces the declared secret allow-list. Null is treated as "none" rather than "unchanged",
     * so a caller that omits the field cannot accidentally leave a stale grant in place.
     */
    public void setAllowedSecretNames(Set<String> names) {
        this.allowedSecretNames =
                names == null ? new LinkedHashSet<>() : new LinkedHashSet<>(names);
    }
}
