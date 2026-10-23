package com.agenticai.core.quality.dataset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant-authored set of golden cases for OFFLINE regression evaluation of
 * one agent - the dataset half of the eval-datasets workstream (migration
 * V213, which carries the full placement/FK reasoning in its header).
 *
 * <p>Tenant-scoped entity: plain {@code @Table} with NO {@code schema}
 * attribute, routed to the tenant's schema by {@code TenantSchemaResolver}
 * via the connection's {@code search_path} like every other tenant table
 * (and deliberately UNLIKE V152's {@code agent_quality_record}, which sits
 * stranded in {@code public} - see the migration header).
 *
 * <p>{@code agentId} is deliberately not a foreign key - same choice as
 * {@code agent_job.agent_id}: a dataset (and its run history) must outlive
 * the agent definition, and a replay against a vanished agent fails with a
 * real error message instead of a constraint-violation 500 at delete time.
 */
@Entity
@Table(name = "eval_dataset")
public class EvalDataset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * The pass bar in [0,1] a run's aggregate score is judged against.
     * Snapshotted onto every {@link EvalRun} at trigger time so editing this
     * value never retroactively flips a historical verdict - the same
     * "a baseline must not silently rewrite its own bar" reasoning
     * {@code com.agenticai.core.quality.eval.TokenBaseline} documents at length.
     */
    // columnDefinition matches V213's NUMERIC(4,3) exactly - Hibernate's default DDL type for a
    // bare `double` is float(53), which fails schema validation (ddl-auto=validate) against the
    // migration's actual NUMERIC(4,3) column on boot: "wrong column type ... found [numeric],
    // but expecting [float(53)]". The field stays a primitive double (not BigDecimal) since
    // nothing needs decimal-exact arithmetic here, just the DDL type declared to match reality.
    @Column(name = "pass_threshold", nullable = false, columnDefinition = "numeric(4,3)")
    private double passThreshold;

    /**
     * The dataset's OWN replay mode, when it has one: {@code TRUE} pins the full pipeline (the agent's tools run in
     * the eval sandbox), {@code FALSE} pins a quick text-only replay (the tools are withheld), and {@code NULL}
     * (the default of a dataset created without a choice) pins nothing - the run takes the workspace's default
     * ({@code quality.eval.defaultMode} over {@code app.quality.eval.default-mode}, the full pipeline as shipped).
     * A run that names a mode itself overrides the pin. V670 made the column nullable and set every dataset that
     * held the old default (FALSE) to NULL, so existing datasets follow the new default instead of staying
     * tool-less by accident.
     */
    @Column(name = "tool_execution_enabled")
    private Boolean toolExecutionEnabled;

    /**
     * For a run in the full pipeline: whether a tool call that would wait
     * for a human is approved by the eval sandbox instead (never for the
     * excluded tools, and only for tenants the operator declared
     * non-production; see {@link EvalSandboxPolicy}). Without it a gated call
     * is held exactly as in production and the case sees an unanswered
     * approval (no approval request is recorded and nobody is notified).
     * Meaningless for a quick run, which has no tool calls. V667; false for
     * every earlier dataset.
     */
    @Column(name = "auto_approve_tools", nullable = false)
    private boolean autoApproveTools;

    /**
     * Keycloak subject of the author. Opaque string, never FK'd - same
     * reasoning as {@code user_permission_grant.keycloak_user_id} (V199):
     * {@code app_user} only has rows for the original signup admin, so an FK
     * would reject datasets authored by anyone invited later.
     */
    @Column(name = "created_by", length = 255)
    private String createdBy;

    /**
     * SHA-256 hex of the dataset's evaluation-relevant content (threshold,
     * tool-execution flag, and every case in canonical order) - V343. Every
     * mutation path in {@code EvalDatasetService} recomputes it, and each
     * {@link EvalRun} snapshots it at trigger time ({@code dataset_hash}):
     * the promote gate compares the two, so a dataset edited between run and
     * promote makes the run stale (it never tested these cases) instead of
     * silently qualifying. NULL until first computed - the hash is a pure
     * function of current content, so a lazy compute is identical to an
     * eager one and pre-V343 rows need no backfill.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EvalDataset() {
        // JPA
    }

    public EvalDataset(
        UUID agentId,
        String name,
        String description,
        double passThreshold,
        Boolean toolExecutionEnabled,
        String createdBy
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.passThreshold = passThreshold;
        this.toolExecutionEnabled = toolExecutionEnabled;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public EvalDataset(
        UUID agentId,
        String name,
        String description,
        double passThreshold,
        Boolean toolExecutionEnabled,
        boolean autoApproveTools,
        String createdBy
    ) {
        this(agentId, name, description, passThreshold, toolExecutionEnabled, createdBy);
        this.autoApproveTools = autoApproveTools;
    }

    /**
     * Intention-named update (house style: behavior on the entity, not
     * anemic setters) - the only mutation the console offers. Bumps
     * {@code updatedAt} itself so no caller can forget to.
     */
    public void updateDefinition(
        String name, String description, double passThreshold, Boolean toolExecutionEnabled
    ) {
        updateDefinition(name, description, passThreshold, toolExecutionEnabled, this.autoApproveTools);
    }

    /** As above, also setting the sandbox auto-approval of the dataset's tool replays. */
    public void updateDefinition(
        String name, String description, double passThreshold, Boolean toolExecutionEnabled,
        boolean autoApproveTools
    ) {
        this.name = name;
        this.description = description;
        this.passThreshold = passThreshold;
        this.toolExecutionEnabled = toolExecutionEnabled;
        this.autoApproveTools = autoApproveTools;
        this.updatedAt = Instant.now();
    }

    /**
     * Rewrites {@link #contentHash} after any mutation that changes what a
     * run of this dataset would measure (definition edits and case
     * add/update/delete - see the field's javadoc). Caller computes; the
     * entity only records. {@code updatedAt} bumps too: a case edit IS a
     * dataset edit for "when did this definition last change" purposes.
     */
    public void markContentHash(String contentHash) {
        this.contentHash = contentHash;
        this.updatedAt = Instant.now();
    }

    /** Nullable until the first computation - see the field's javadoc. */
    public String getContentHash() {
        return contentHash;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public double getPassThreshold() {
        return passThreshold;
    }

    /** The dataset's own mode choice as stored: {@code TRUE} full, {@code FALSE} quick, {@code null} none. */
    public Boolean getToolExecutionEnabled() {
        return toolExecutionEnabled;
    }

    /** The mode this dataset pins, or empty when it follows the workspace's default; see the field's javadoc. */
    public java.util.Optional<EvalMode> pinnedMode() {
        if (toolExecutionEnabled == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(toolExecutionEnabled ? EvalMode.FULL : EvalMode.QUICK);
    }

    /** See the field's javadoc; only meaningful for a run in the full pipeline (the dataset must not pin quick). */
    public boolean isAutoApproveTools() {
        return autoApproveTools;
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
}
