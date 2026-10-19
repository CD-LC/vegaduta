package com.agenticai.core.workflow.version;

import com.agenticai.core.workflow.WorkflowStatus;
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
 * One immutable, append-only snapshot of a workflow's ENTIRE saved state
 * (migration V273) - the workflow sibling of {@code
 * com.agenticai.core.agents.version.AgentVersion}, and the answer to the
 * audit gap "only DRAFT/ACTIVE - agents got versioning, workflows didn't".
 *
 * <p>A row is written AFTER every mutation {@code WorkflowService} makes
 * that a human could later want to undo or diff (create, update, restore),
 * so the newest row describes the definition the workflow is running right
 * now - the same "newest row IS the live config" convention {@code
 * AgentVersion} documents, and deliberately NOT {@code PromptVersion}'s
 * "history only holds superseded states" convention, for the same reason:
 * a version list should read as a genuine timeline, and "what is live now"
 * must itself be a row or run-version stamping has nothing to point at.
 *
 * <p>Immutable by Java shape - protected no-arg constructor for JPA,
 * private all-args constructor, one static factory, getters only. Nothing
 * rewrites {@link #getGraphJson()}: a stored snapshot is a historical
 * fact. Unlike {@code AgentVersion} there is no label column - a
 * workflow's own {@code status} (DRAFT/ACTIVE/ARCHIVED) is already the
 * "which of these is live" pointer, and duplicating it as a second
 * pointer would invite the two to disagree.
 *
 * <p>No {@code tenantId} column: rows live in the tenant's own Postgres
 * schema, so isolation comes from the schema itself - same convention as
 * {@code WorkflowDefinition}, {@code WorkflowRun} and {@code
 * agent_version}.
 */
@Entity
@Table(name = "workflow_version")
public class WorkflowVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    /**
     * Human-facing ordinal ("v7"), not an identity and not a lock -
     * deliberately NOT UNIQUE for the same reason {@code agent_version}
     * documents: {@code workflow_definition} carries no {@code @Version}
     * column, so two genuinely concurrent edits are already
     * last-writer-wins on the definition row, and a unique constraint here
     * would convert that pre-existing race from "one edit silently
     * overwrote the other" into "a tenant's save returns 500".
     */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    /** The whole graph document - TEXT, never rewritten, the only thing a restore reads. */
    @Column(name = "graph_json", nullable = false, columnDefinition = "TEXT")
    private String graphJson;

    /**
     * The non-graph definition state at snapshot time (V342): schedule
     * cron/enabled, reasoning/tuning modes, caching/cost-routing flags,
     * economy provider/model and the execution limits - serialized as a
     * flat JSON object by {@link WorkflowVersionService} (and by V342's
     * {@code jsonb_build_object} on SQL-backfilled rows, same key names).
     * The trigger configuration is NOT here on purpose: a trigger is a
     * graph node, already inside {@link #graphJson}. Read by the version
     * diff and by {@code WorkflowService#restoreVersion}, which applies it
     * back onto the live definition (V273-era rows without it restore
     * graph + name/description only, as before).
     */
    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    /**
     * SHA-256 (hex) of {@link #graphJson} as stored - publish-time value
     * dedupe, computed inside {@link #snapshot} so no writer can forget it
     * (migration V342; matches {@code encode(sha256(graph_json::bytea),'hex')}
     * on backfilled rows).
     */
    @Column(name = "bundle_hash", length = 64)
    private String bundleHash;

    /**
     * The STAGING/PRODUCTION pointer (V342) - deliberately the same "one
     * mutable cell on an otherwise immutable row" design {@code
     * AgentVersion} documents: a label is by definition a pointer that
     * moves, and migration V342's partial unique index on
     * {@code (workflow_id, label)} makes at-most-one-holder an invariant.
     * Mutated only through {@link #assignLabel}/{@link #clearLabel}, which
     * are package-private so {@link WorkflowVersionService} remains the
     * only code that moves a pointer.
     *
     * <p>Unlike {@link #status} (the workflow's own lifecycle state copied
     * at snapshot time), {@code label} is the DEPLOYMENT pointer - which
     * snapshot runtime resolution treats as production.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "label", length = 32)
    private com.agenticai.core.agents.version.VersionLabel label;

    // Display copies of three values that are the point of a version LIST:
    // it renders without parsing N graph documents. If they ever disagreed
    // with the snapshots they describe, graph_json wins - a restore reads
    // only that.
    @Column(name = "workflow_name", nullable = false, length = 200)
    private String workflowName;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** The workflow's own status at snapshot time - see this class's javadoc for why no separate label exists. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowStatus status;

    @Column(name = "change_summary", length = 500)
    private String changeSummary;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowVersion() {
        // JPA
    }

    private WorkflowVersion(
        UUID workflowId, int versionNumber, String graphJson, String detailsJson, String workflowName,
        String description, WorkflowStatus status, String changeSummary, String createdBy
    ) {
        this.workflowId = workflowId;
        this.versionNumber = versionNumber;
        this.graphJson = graphJson;
        this.detailsJson = detailsJson;
        this.bundleHash = com.agenticai.core.agents.version.VersionHashing.sha256Hex(graphJson);
        this.workflowName = workflowName;
        this.description = description;
        this.status = status;
        this.changeSummary = truncate(changeSummary, 500);
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /**
     * The only way to create a version row. Stamps {@code createdAt}
     * itself - a caller cannot backdate history.
     *
     * @param changeSummary short, human-readable "what caused this
     *     version" ("Workflow created", "Updated", "Restored from v3").
     *     Trimmed to the column's 500 chars rather than rejected: a
     *     version row is worth more than a perfect summary.
     */
    static WorkflowVersion snapshot(
        UUID workflowId, int versionNumber, String graphJson, String detailsJson, String workflowName,
        String description, WorkflowStatus status, String changeSummary, String createdBy
    ) {
        return new WorkflowVersion(
            workflowId, versionNumber, graphJson, detailsJson, workflowName,
            description, status, changeSummary, createdBy);
    }

    /** Pointer moves only - see the {@code label} field's javadoc. */
    void assignLabel(com.agenticai.core.agents.version.VersionLabel label) {
        this.label = label;
    }

    /** Called on the PREVIOUS holder before a label is assigned elsewhere (flushed first - the partial unique index must never see two holders). */
    void clearLabel() {
        this.label = null;
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    public UUID getId() { return id; }
    public UUID getWorkflowId() { return workflowId; }
    public int getVersionNumber() { return versionNumber; }
    public String getGraphJson() { return graphJson; }
    /** Nullable for V273-era rows; see the field's javadoc for what it holds. */
    public String getDetailsJson() { return detailsJson; }
    public String getBundleHash() { return bundleHash; }
    /** Nullable - at most two labelled rows per workflow. */
    public com.agenticai.core.agents.version.VersionLabel getLabel() { return label; }
    public String getWorkflowName() { return workflowName; }
    public String getDescription() { return description; }
    public WorkflowStatus getStatus() { return status; }
    public String getChangeSummary() { return changeSummary; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
