package com.agenticai.core.quality.dataset;

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
 * One regression run of an {@link EvalDataset} against its agent: every case
 * replayed, scored by the existing {@code core/quality} judges, aggregated,
 * and judged against the dataset's pass bar frozen at trigger time.
 *
 * <p>State transitions are enforced HERE, never in callers - the same choice
 * {@code AgentJob} made and documents: an entity whose transitions live in
 * the service layer eventually gets a second caller that forgets one guard.
 *
 * <p>Identity columns ({@code tenantId}, {@code requestedBy},
 * {@code requestedByRoles}) are snapshots taken at trigger time so the
 * executor worker thread - which has no HTTP request and therefore no
 * {@code TenantContextFilter}-populated context - can rebuild
 * {@code TenantContext} with the REQUESTER'S authority, never elevated
 * system authority. See V213's header and {@code agent_job}'s identical
 * contract.
 */
@Entity
@Table(name = "eval_run")
public class EvalRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    /** Snapshot of the dataset's agent at trigger time. */
    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /**
     * The {@code agent_version} row this run replayed - the agent's HEAD
     * snapshot at trigger time, which by convention IS the live config (V212).
     * Nullable: runs predating V260 carry no binding and simply never satisfy
     * a promote gate. Bound by {@link #bindAgentVersion} at trigger, never
     * rewritten afterward - the row records what was actually tested.
     */
    @Column(name = "agent_version_id")
    private UUID agentVersionId;

    @Column(name = "tenant_id", nullable = false, length = 255)
    private String tenantId;

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    /** Comma-joined role snapshot, same encoding as {@code agent_job}. */
    @Column(name = "requested_by_roles", length = 512)
    private String requestedByRoles;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EvalRunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private EvalRunVerdict verdict;

    /**
     * The dataset's pass bar frozen at trigger time - see V213 header.
     * columnDefinition matches V213's NUMERIC(4,3) exactly - see {@link
     * EvalDataset#passThreshold}'s javadoc for why this is required (bare
     * `double` maps to float(53) by default, which fails Hibernate's
     * ddl-auto=validate boot check against the migration's real column type).
     */
    @Column(name = "pass_threshold", nullable = false, columnDefinition = "numeric(4,3)")
    private double passThreshold;

    /**
     * Mean of the per-case aggregates that were actually measured; null when
     * nothing was measured. Honest absence, never a fabricated neutral.
     * columnDefinition matches V213's NUMERIC(4,3) - see {@link
     * EvalDataset#passThreshold}'s javadoc for why this is required.
     */
    @Column(name = "overall_score", columnDefinition = "numeric(4,3)")
    private Double overallScore;

    @Column(name = "case_count", nullable = false)
    private int caseCount;

    @Column(name = "scored_case_count", nullable = false)
    private int scoredCaseCount;

    @Column(name = "failed_assertion_count", nullable = false)
    private int failedAssertionCount;

    @Column(name = "errored_case_count", nullable = false)
    private int erroredCaseCount;

    /**
     * Which judge model scored this run - provenance, because
     * {@code JudgeChatClientFactory} picks the FIRST catalog row and a
     * catalog reorder silently changes the judge between two runs being
     * diffed. Stored so that drift is visible in the run history instead of
     * invisible inside a score delta.
     */
    @Column(name = "judge_model", length = 255)
    private String judgeModel;

    // ------------------------------------------------------------------
    // V343 - per-run judge/cost configuration, snapshotted at trigger.
    // ------------------------------------------------------------------

    /**
     * The dataset's {@code content_hash} at trigger time - the gate only
     * accepts runs whose snapshot hash still matches the dataset's CURRENT
     * hash (an edited dataset = untested content). NULL on pre-V343 rows,
     * which can never qualify a gate - honest absence, same posture as
     * {@link #agentVersionId} above.
     */
    @Column(name = "dataset_hash", length = 64)
    private String datasetHash;

    /**
     * Optional per-run judge override requested at trigger (applied through
     * {@code JudgeChatClientFactory}'s scoped override). {@link #judgeModel}
     * remains the RESOLVED provenance column - the two differ only if the
     * catalog could not honor the request, which the run history should show.
     */
    @Column(name = "requested_judge_model", length = 255)
    private String requestedJudgeModel;

    /**
     * When true, trigger refuses a judge model identical to the agent's own
     * model (self-grading bias - the C2 {@code strictJudge} rule). Persisted
     * so a reader can see WHICH assurance level a passing run was produced
     * under.
     */
    @Column(name = "strict_judge", nullable = false)
    private boolean strictJudge;

    /**
     * Replay+score repetitions per case (1..5, CHECK-constrained). A case
     * passes only when a strict majority of its repetitions pass - the
     * non-determinism guard. Costs multiply accordingly; {@link #budgetUsd}
     * is the ceiling that keeps repeats x cases bounded.
     */
    @Column(name = "repeats", nullable = false)
    private int repeats;

    /**
     * Per-run cost ceiling in USD (default $5.00, CHECK {@code > 0}). The
     * worker sums observed {@code llm_call_log.estimated_cost_usd} for the
     * run's replay sessions between cases and cancels the run once the cap
     * is exceeded - a cancelled run can never satisfy a promote gate.
     */
    @Column(name = "budget_usd", nullable = false, precision = 10, scale = 2)
    private java.math.BigDecimal budgetUsd;

    /** Observed spend accumulated during the run; null until first measured. */
    @Column(name = "cost_usd", precision = 12, scale = 6)
    private java.math.BigDecimal costUsd;

    /**
     * Optional caller-supplied idempotency key (the CI hook's
     * {@code Idempotency-Key} header). Unique per dataset when present -
     * a re-trigger with the same key returns the SAME run, never a second.
     */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    // ------------------------------------------------------------------
    // V375 - reproducibility snapshot of what the run actually replayed.
    // All nullable: pre-V375 runs (and runs triggered with the snapshot
    // switched off) carry nothing, and nothing treats that as failure.
    // ------------------------------------------------------------------

    /** The agent's model at trigger time (see {@link AgentConfigSnapshot}). */
    @Column(name = "agent_model", length = 128)
    private String agentModel;

    @Column(name = "agent_temperature", columnDefinition = "double precision")
    private Double agentTemperature;

    @Column(name = "agent_seed")
    private Integer agentSeed;

    /** SHA-256 of {@link #agentConfigJson}: equal hashes = same prompt, tools and sampling. */
    @Column(name = "agent_config_hash", length = 64)
    private String agentConfigHash;

    /** Canonical JSON of the {@link AgentConfigSnapshot} (prompt stored as a hash only). */
    @Column(name = "agent_config_json", columnDefinition = "TEXT")
    private String agentConfigJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Cooperative cancel flag - a separate column, NOT a status write, so
     * only the owning worker ever writes {@code status} for a live run (the
     * exact writer-separation reasoning {@code agent_job.cancel_requested}
     * documents; it is what lets this table go without a {@code @Version}
     * column or any optimistic-lock retry).
     */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "cancel_requested_by", length = 255)
    private String cancelRequestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected EvalRun() {
        // JPA
    }

    public EvalRun(
        UUID datasetId,
        UUID agentId,
        String tenantId,
        String requestedBy,
        String requestedByRoles,
        double passThreshold,
        int caseCount
    ) {
        this.datasetId = datasetId;
        this.agentId = agentId;
        this.tenantId = tenantId;
        this.requestedBy = requestedBy;
        this.requestedByRoles = requestedByRoles;
        this.passThreshold = passThreshold;
        this.caseCount = caseCount;
        this.status = EvalRunStatus.QUEUED;
        this.cancelRequested = false;
        this.repeats = 1;
        this.budgetUsd = java.math.BigDecimal.valueOf(5.00);
        this.createdAt = Instant.now();
    }

    /**
     * Applies the V343 trigger-time configuration before the row's first
     * save - like {@link #bindAgentVersion}, a setter only because the
     * constructor predates these columns and stays signature-stable.
     */
    public void applyRunConfig(
        String datasetHash,
        String requestedJudgeModel,
        boolean strictJudge,
        int repeats,
        java.math.BigDecimal budgetUsd,
        String idempotencyKey
    ) {
        this.datasetHash = datasetHash;
        this.requestedJudgeModel = requestedJudgeModel;
        this.strictJudge = strictJudge;
        this.repeats = repeats;
        this.budgetUsd = budgetUsd;
        this.idempotencyKey = idempotencyKey;
    }

    /** Records the V375 agent-config snapshot before the row's first save. */
    public void applyAgentSnapshot(AgentConfigSnapshot snapshot) {
        this.agentModel = snapshot.model();
        this.agentTemperature = snapshot.temperature();
        this.agentSeed = snapshot.seed();
        this.agentConfigJson = snapshot.canonicalJson();
        this.agentConfigHash = CanonicalJson.sha256Hex(this.agentConfigJson);
    }

    /**
     * RUNNING -> CANCELLED with an explanatory note (budget cap exceeded
     * mid-run). Distinct from {@link #markCancelled} only in that it records
     * WHY on {@link #errorMessage} - the console renders it and the CI
     * caller reads it.
     */
    public void cancelWithNote(String note) {
        markCancelled();
        this.errorMessage = note;
    }

    /** Accumulates observed spend - written only by the owning worker between cases. */
    public void recordCost(java.math.BigDecimal costUsd) {
        this.costUsd = costUsd;
    }

    /** QUEUED -> RUNNING, stamped by the worker as it picks the run up. */
    public void markRunning(String judgeModel) {
        assertTransitionFrom(EvalRunStatus.QUEUED);
        this.status = EvalRunStatus.RUNNING;
        this.judgeModel = judgeModel;
        this.startedAt = Instant.now();
    }

    /**
     * RUNNING -> SUCCEEDED with the aggregate + verdict. The verdict is
     * computed by {@code EvalRunService#computeVerdict} and passed in whole
     * so the rules live in one documented place rather than half here, half
     * there.
     */
    public void complete(
        Double overallScore, int scoredCaseCount, int failedAssertionCount,
        int erroredCaseCount, EvalRunVerdict verdict
    ) {
        assertTransitionFrom(EvalRunStatus.RUNNING);
        this.status = EvalRunStatus.SUCCEEDED;
        this.overallScore = overallScore;
        this.scoredCaseCount = scoredCaseCount;
        this.failedAssertionCount = failedAssertionCount;
        this.erroredCaseCount = erroredCaseCount;
        this.verdict = verdict;
        this.finishedAt = Instant.now();
    }

    /**
     * Any non-terminal state -> FAILED. Reachable from QUEUED too (executor
     * rejection, boot orphan sweep), which is why this one is not pinned to
     * a single source state like the others.
     */
    public void fail(String errorMessage) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                "EvalRun " + id + " is already terminal (" + status + ") - cannot fail it again.");
        }
        this.status = EvalRunStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = Instant.now();
    }

    /**
     * RUNNING/QUEUED -> CANCELLED - written only by the owning worker when it
     * observes {@link #cancelRequested} between cases (or at pickup).
     */
    public void markCancelled() {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                "EvalRun " + id + " is already terminal (" + status + ") - cannot cancel.");
        }
        this.status = EvalRunStatus.CANCELLED;
        this.finishedAt = Instant.now();
    }

    /** The cancel WRITER's only touch point - never writes status. */
    public void requestCancel(String requestedBy) {
        this.cancelRequested = true;
        this.cancelRequestedBy = requestedBy;
    }

    /**
     * Called once at trigger time with the agent's head {@code agent_version}
     * row (see {@link #agentVersionId}). Not part of the constructor so the
     * trigger path stays a single save - the binding is set on the managed
     * instance before persist.
     */
    public void bindAgentVersion(UUID agentVersionId) {
        this.agentVersionId = agentVersionId;
    }

    private void assertTransitionFrom(EvalRunStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                "EvalRun " + id + " expected status " + expected + " but was " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    /** Nullable - which stored configuration this run replayed. See the field's javadoc. */
    public UUID getAgentVersionId() {
        return agentVersionId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getRequestedByRoles() {
        return requestedByRoles;
    }

    public EvalRunStatus getStatus() {
        return status;
    }

    public EvalRunVerdict getVerdict() {
        return verdict;
    }

    public double getPassThreshold() {
        return passThreshold;
    }

    public Double getOverallScore() {
        return overallScore;
    }

    public int getCaseCount() {
        return caseCount;
    }

    public int getScoredCaseCount() {
        return scoredCaseCount;
    }

    public int getFailedAssertionCount() {
        return failedAssertionCount;
    }

    public int getErroredCaseCount() {
        return erroredCaseCount;
    }

    public String getJudgeModel() {
        return judgeModel;
    }

    /** Nullable - the dataset snapshot hash this run replayed (V343). */
    public String getDatasetHash() {
        return datasetHash;
    }

    /** Nullable - the judge override the caller asked for (V343). */
    public String getRequestedJudgeModel() {
        return requestedJudgeModel;
    }

    public boolean isStrictJudge() {
        return strictJudge;
    }

    public int getRepeats() {
        return repeats;
    }

    public java.math.BigDecimal getBudgetUsd() {
        return budgetUsd;
    }

    /** Nullable - observed spend, accumulated during the run (V343). */
    public java.math.BigDecimal getCostUsd() {
        return costUsd;
    }

    /** Nullable - caller-supplied idempotency key (V343). */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** Nullable (V375) - the agent's model when the run was triggered. */
    public String getAgentModel() {
        return agentModel;
    }

    /** Nullable (V375). */
    public Double getAgentTemperature() {
        return agentTemperature;
    }

    /** Nullable (V375) - the agent's fixed sampling seed, if it has one. */
    public Integer getAgentSeed() {
        return agentSeed;
    }

    /** Nullable (V375) - SHA-256 over the canonical agent config (prompt hash + tools + sampling). */
    public String getAgentConfigHash() {
        return agentConfigHash;
    }

    /** Nullable (V375) - the canonical JSON the hash covers. */
    public String getAgentConfigJson() {
        return agentConfigJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String getCancelRequestedBy() {
        return cancelRequestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
