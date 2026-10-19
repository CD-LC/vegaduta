package com.agenticai.core.agents.tools.classification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored classification row (table {@code tool_classification}, V224 -
 * unqualified name, so it lives in each tenant's own schema; this platform is
 * schema-per-tenant and tenant-scoped tables carry no {@code tenant_id} column,
 * see {@code V52__llm_call_log.sql}).
 *
 * <h2>Why the three dimensions are {@code String} columns and not {@code @Enumerated}</h2>
 * Every other enum-valued column in this codebase uses
 * {@code @Enumerated(EnumType.STRING)}, and this one deliberately does not. With
 * {@code @Enumerated}, a column value the running code's enum does not contain
 * makes Hibernate throw while <i>materialising the row</i> - which fails the whole
 * query, not the one row, and produces a stack trace with no tool name in it.
 * That is the wrong failure for a security table during a rolling deploy: a newer
 * pod adds a fourth trust tier and widens the CHECK constraint, an older pod then
 * reads a row it cannot parse, and instead of blocking one tool it 500s every
 * tool resolution for that tenant.
 *
 * <p>Raw strings plus {@link #decide()} turn that into exactly what the design
 * wants: an unparseable row resolves to
 * {@link ToolClassificationDecision.Reason#INVALID_DECLARATION} for that one tool
 * name, blocked, logged, visible in the console, everything else unaffected.
 * Fail-closed per row rather than fail-hard per query.
 *
 * <h2>The row is dumb; the decision lives here, not in the registry</h2>
 * {@link #decide()} is on the entity rather than in
 * {@link ToolClassificationRegistry} so that the parse-and-validate step is
 * testable without a repository, a Spring context or a database - the
 * interesting cases (unknown enum value, quarantined row, blank scope) are then
 * plain unit tests over a constructed object.
 *
 * @see ToolClassificationRegistry for resolution order and glob handling
 */
@Entity
@Table(name = "tool_classification")
public class ToolClassificationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /**
     * Either an exact tool name as {@code ToolDefinition.name()} reports it, or a
     * pattern containing a single {@code *} wildcard
     * ({@code mcp__somesrv__*}).
     *
     * <p>Patterns are distinguished by simply containing a {@code '*'} rather than
     * by a separate {@code is_pattern} flag, because a {@code '*'} cannot occur in
     * a real tool name: first-party names are {@code [a-z0-9_]+} and MCP names are
     * namespaced to {@code mcp__<server>__<tool>} by
     * {@code NamespacedMcpToolCallback}, which strips control characters and does
     * not emit {@code '*'}. A redundant flag could contradict the string, and then
     * the question "is this a pattern" would have two answers.
     */
    @Column(name = "tool_name", nullable = false, length = 255)
    private String toolName;

    /** {@link ToolTrustTier#name()}; see this class's javadoc for why it is not {@code @Enumerated}. */
    @Column(name = "trust", nullable = false, length = 32)
    private String trust;

    /** {@link ToolSensitivity#name()}. */
    @Column(name = "sensitivity", nullable = false, length = 32)
    private String sensitivity;

    /** {@link ToolDeterminism#name()}. */
    @Column(name = "determinism", nullable = false, length = 32)
    private String determinism;

    /** {@link ToolClassificationStatus#name()}. An unrecognised value blocks the tool rather than defaulting to ACTIVE. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /** Narrowest scope/permission string the caller must hold, or null. Free-form - see {@link ToolClassification#requiredScope()}. */
    @Column(name = "required_scope", length = 255)
    private String requiredScope;

    @Column(name = "irreversible", nullable = false)
    private boolean irreversible;

    @Column(name = "needs_tool_context", nullable = false)
    private boolean needsToolContext;

    /**
     * Provenance of the row itself: {@code BUILT_IN_SEED}, {@code PLATFORM_ADMIN},
     * {@code TENANT_DECLARED} or {@code MCP_IMPORT}, constrained by a CHECK in
     * V224.
     *
     * <p>Kept as a plain string with no Java enum because it never participates in
     * a security decision - it exists so the console can distinguish "the platform
     * shipped this opinion" from "your admin overrode it", and so a future
     * re-seed can safely replace {@code BUILT_IN_SEED} rows without touching
     * anything a human deliberately set.
     */
    @Column(name = "source", nullable = false, length = 32)
    private String source;

    /** Free-text justification shown next to the row in the console - "why is this EGRESS" is the question an auditor asks. */
    @Column(name = "notes", length = 1024)
    private String notes;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ToolClassificationEntity() {
        // JPA
    }

    ToolClassificationEntity(
        String toolName,
        ToolTrustTier trust,
        ToolSensitivity sensitivity,
        ToolDeterminism determinism,
        ToolClassificationStatus status,
        String requiredScope,
        boolean irreversible,
        boolean needsToolContext,
        String source,
        String notes,
        String createdBy
    ) {
        this.id = UUID.randomUUID();
        this.toolName = toolName;
        this.trust = trust.name();
        this.sensitivity = sensitivity.name();
        this.determinism = determinism.name();
        this.status = status.name();
        this.requiredScope = requiredScope;
        this.irreversible = irreversible;
        this.needsToolContext = needsToolContext;
        this.source = source;
        this.notes = notes;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Turns this row into a {@link ToolClassificationDecision}, never throwing.
     *
     * <p>Order of checks matters and is not arbitrary:
     * <ol>
     *   <li><b>Status first.</b> A quarantined row is blocked even if every other
     *       column is perfectly valid - that is what quarantine means. Checking
     *       the classification first and the status second would let a well-formed
     *       quarantined row resolve as allowed in any future refactor that
     *       forgot the second check.</li>
     *   <li><b>An unrecognised status is blocked too</b>, as
     *       {@code INVALID_DECLARATION}. The tempting alternative - treat anything
     *       that is not the literal string {@code QUARANTINED} as active - fails
     *       open on a typo, and on a future third status such as
     *       {@code PENDING_REVIEW} whose entire purpose would be to block.</li>
     *   <li><b>Then the three dimensions</b>, each parsed with a null-on-unknown
     *       helper rather than {@code Enum.valueOf}, so an unknown value is a
     *       reported reason rather than an exception.</li>
     * </ol>
     *
     * <p>{@code toolName} on the returned classification is this row's
     * {@code tool_name}, pattern included. A glob match is re-keyed to the
     * concrete tool by {@link ToolClassification#withToolName(String)} in
     * {@link ToolClassificationRegistry}, which is the only place that knows what
     * was actually asked for.
     */
    public ToolClassificationDecision decide() {
        ToolClassificationStatus parsedStatus = parse(ToolClassificationStatus.class, status);
        if (parsedStatus == null) {
            return invalid("unrecognised status '" + status + "'");
        }
        if (parsedStatus == ToolClassificationStatus.QUARANTINED) {
            return new ToolClassificationDecision.Blocked(
                toolName,
                ToolClassificationDecision.Reason.QUARANTINED,
                "Tool '" + toolName + "' is quarantined and must be cleared by an administrator before it can run.");
        }
        ToolTrustTier parsedTrust = parse(ToolTrustTier.class, trust);
        ToolSensitivity parsedSensitivity = parse(ToolSensitivity.class, sensitivity);
        ToolDeterminism parsedDeterminism = parse(ToolDeterminism.class, determinism);
        if (parsedTrust == null || parsedSensitivity == null || parsedDeterminism == null) {
            return invalid("unrecognised trust/sensitivity/determinism value in ("
                + trust + ", " + sensitivity + ", " + determinism + ")");
        }
        try {
            return new ToolClassificationDecision.Classified(new ToolClassification(
                toolName,
                parsedTrust,
                parsedSensitivity,
                parsedDeterminism,
                requiredScope,
                irreversible,
                needsToolContext));
        } catch (RuntimeException e) {
            // The record's canonical constructor is the last validator (blank
            // tool name). Caught rather than propagated for the same per-row
            // fail-closed reason the String columns exist at all.
            return invalid(e.getMessage());
        }
    }

    private ToolClassificationDecision invalid(String detail) {
        return new ToolClassificationDecision.Blocked(
            toolName,
            ToolClassificationDecision.Reason.INVALID_DECLARATION,
            "Classification row for '" + toolName + "' is unusable: " + detail);
    }

    /**
     * {@code Enum.valueOf} without the exception - returns null for an unknown or
     * null name. Deliberately exact-match and case-sensitive: a row spelled
     * {@code "egress"} is a row somebody wrote by hand without checking, and
     * quietly accepting it would make the CHECK constraint in V224 a lie.
     */
    private static <E extends Enum<E>> E parse(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /** True when {@link #getToolName()} is a wildcard pattern rather than an exact tool name. */
    public boolean isPattern() {
        return toolName != null && toolName.indexOf('*') >= 0;
    }

    public UUID getId() {
        return id;
    }

    public String getToolName() {
        return toolName;
    }

    public String getTrust() {
        return trust;
    }

    public String getSensitivity() {
        return sensitivity;
    }

    public String getDeterminism() {
        return determinism;
    }

    public String getStatus() {
        return status;
    }

    public String getRequiredScope() {
        return requiredScope;
    }

    public boolean isIrreversible() {
        return irreversible;
    }

    public boolean isNeedsToolContext() {
        return needsToolContext;
    }

    public String getSource() {
        return source;
    }

    public String getNotes() {
        return notes;
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

    @Override
    public String toString() {
        return "ToolClassificationEntity{toolName=" + toolName
            + ", " + trust + "/" + sensitivity + "/" + determinism
            + ", status=" + status + ", source=" + source + "}";
    }
}
