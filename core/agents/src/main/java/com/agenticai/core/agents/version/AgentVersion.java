package com.agenticai.core.agents.version;

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
 * One immutable, append-only snapshot of an agent's ENTIRE configuration
 * (migration V212) - the whole-config sibling of {@code PromptVersion}, which has
 * only ever covered {@code systemPrompt}.
 *
 * <h2>Immutability, and the one exception</h2>
 *
 * <p>Immutable the same way {@code PromptVersion} is: enforced by Java shape
 * rather than by DB triggers or {@code updatable = false}. There is a protected
 * no-arg constructor for JPA, a private all-args constructor, one static factory,
 * and getters. Nothing anywhere can rewrite {@link #getConfigJson()} - a stored
 * snapshot is a historical fact.
 *
 * <p>The single exception is {@link #getLabel()}, whose two package-private
 * mutators exist because a label is by definition a POINTER that moves ("this
 * version is now production"). Immutability here means the SNAPSHOT never
 * changes, not that the row is frozen; modelling the pointer as a separate table
 * would be purer at the cost of a join on every list read for at most two rows
 * per agent. The mutators are package-private so the only code that can move a
 * label is {@link AgentVersionService}, which enforces the one-holder-per-label
 * invariant that migration V212's partial unique index backstops.
 *
 * <h2>The newest row IS the live config - deliberately unlike PromptVersion</h2>
 *
 * <p>{@code PromptVersion}'s javadoc states its governing invariant: "there is no
 * 'current version' row" - it snapshots the OLD prompt immediately before an
 * overwrite, so history only ever holds superseded states. This table does the
 * opposite: {@code AgentService} appends a row AFTER each config change (and one
 * at creation), so the newest row describes the configuration the agent is
 * running right now.
 *
 * <p>That divergence is required by labels, not a preference. A label names a
 * snapshot a human blessed; if the live configuration were never itself a row,
 * "mark what we are running now as production" would be unexpressible, and the
 * newest thing a tenant could point at would always be one edit stale. It also
 * makes a version list read as a genuine timeline rather than a list of things
 * that are no longer true.
 *
 * <p>Two honest caveats on "the newest row is the live config":
 * <ul>
 *   <li>Two config writers live OUTSIDE {@code AgentService} and therefore append
 *       no row - {@code AgentGuardrailService#updateAgentGuardrails} (enabled
 *       guardrails) and the agent-job result-schema endpoints
 *       ({@code resultSchemaJson}). Both fields are in the bundle and both restore
 *       correctly; it is only the change ITSELF that goes unversioned. Closing
 *       that gap means editing files this pass does not own.
 *   <li>{@code AgentService#resolveEffectiveModel}'s null-model self-heal saves the
 *       agent outside any tenant-initiated edit. It deliberately appends no row:
 *       a plain chat turn should not manufacture configuration history nobody
 *       asked for.
 * </ul>
 */
@Entity
@Table(name = "agent_version")
public class AgentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /**
     * Human-facing ordinal ("v7"), not an identity and not a lock - see migration
     * V212's own comment for why it carries no unique constraint.
     */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    private String configJson;

    /**
     * SHA-256 (lowercase hex) of {@link #configJson} as stored - the value
     * identity used by publish-time de-duplication ("an unchanged bundle
     * published twice is still one version"). Computed inside {@link
     * #snapshot} so no writer can forget it, over the same UTF-8 bytes the
     * column stores, matching the migration V342 backfill expression
     * {@code encode(sha256(config_json::bytea),'hex')}. Nullable only for
     * rows that somehow predate the column.
     */
    @Column(name = "bundle_hash", length = 64)
    private String bundleHash;

    // Display copies of three values that also live inside configJson. configJson
    // is the only thing a restore reads; if these ever disagreed with it, the
    // document wins. They exist so a version LIST renders without parsing N JSON
    // documents.
    @Column(name = "agent_name", nullable = false, length = 255)
    private String agentName;

    @Column(name = "provider_name", length = 32)
    private String providerName;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "change_summary", length = 500)
    private String changeSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "label", length = 32)
    private AgentVersionLabel label;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentVersion() {
        // JPA
    }

    private AgentVersion(
        UUID agentId,
        int versionNumber,
        String configJson,
        String agentName,
        String providerName,
        String model,
        String changeSummary,
        String createdBy
    ) {
        this.agentId = agentId;
        this.versionNumber = versionNumber;
        this.configJson = configJson;
        this.bundleHash = VersionHashing.sha256Hex(configJson);
        this.agentName = agentName;
        this.providerName = providerName;
        this.model = model;
        this.changeSummary = changeSummary;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /**
     * The only way to create a version row. Stamps {@code createdAt} itself, same
     * as {@code PromptVersion#snapshot} - a caller cannot backdate history.
     *
     * @param changeSummary short, human-readable "what caused this version",
     *     supplied by the {@code AgentService} method that made the change. Trimmed
     *     to the column's 500 chars rather than rejected: a version row is worth
     *     more than a perfect summary, and this is never a caller-supplied string.
     */
    static AgentVersion snapshot(
        UUID agentId,
        int versionNumber,
        String configJson,
        String agentName,
        String providerName,
        String model,
        String changeSummary,
        String createdBy
    ) {
        return new AgentVersion(
            agentId,
            versionNumber,
            configJson,
            agentName,
            providerName,
            model,
            truncate(changeSummary, 500),
            createdBy
        );
    }

    /** See this class's javadoc for why the label - and only the label - is mutable. */
    void assignLabel(AgentVersionLabel label) {
        this.label = label;
    }

    /**
     * Called on the PREVIOUS holder before a label is assigned elsewhere, and
     * flushed before the assignment, so the partial unique index on
     * {@code (agent_id, label)} never sees two holders even momentarily.
     */
    void clearLabel() {
        this.label = null;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    /** The whole config bundle - see {@link AgentConfigSnapshot}. Never rewritten. */
    public String getConfigJson() {
        return configJson;
    }

    /** SHA-256 hex of {@link #getConfigJson()} - see the field's javadoc. */
    public String getBundleHash() {
        return bundleHash;
    }

    /**
     * The agent's name at snapshot time. Display only: a restore cannot write it
     * back ({@code Agent} has no name setter), which is why it is surfaced
     * separately rather than left inside the document.
     */
    public String getAgentName() {
        return agentName;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getModel() {
        return model;
    }

    /** Nullable - short "what changed" line, e.g. "Restored from v3". */
    public String getChangeSummary() {
        return changeSummary;
    }

    /** Nullable, and null for almost every row - at most two labelled rows per agent. */
    public AgentVersionLabel getLabel() {
        return label;
    }

    /** Nullable - the Keycloak subject that caused this version, or null for a caller with no request context. */
    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
