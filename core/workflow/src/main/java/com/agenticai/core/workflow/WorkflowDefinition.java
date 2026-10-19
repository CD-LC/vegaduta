package com.agenticai.core.workflow;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.providers.ProviderName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A saved workflow graph - schema-per-tenant like every other entity in this
 * codebase, so no explicit tenant column is needed (Hibernate's
 * multi-tenancy connection provider already scopes every query to the
 * caller's schema). {@code graphJson} is the literal JSON React Flow's
 * {@code {nodes, edges}} shape serializes to - stored as an opaque blob
 * rather than normalized into node/edge tables, matching the PRD's own
 * Section 6 call ("a versioned JSON document... persisted per tenant") and
 * this codebase's existing preference for JSON columns over premature
 * normalization (see {@code ApprovalRequest.payloadJson} for the same
 * pattern).
 */
@Entity
@Table(name = "workflow_definition")
public class WorkflowDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Optimistic lock (JAVA-007, migration tenant/V365.2): every UPDATE is
     * {@code WHERE id = ? AND version = ?}, so a concurrent writer's save fails
     * with {@code ObjectOptimisticLockingFailureException} (HTTP 409) instead
     * of silently overwriting the other writer's columns. A wrapper type on
     * purpose: {@code null} marks a never-persisted entity for Spring Data.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "graph_json", nullable = false, columnDefinition = "TEXT")
    private String graphJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Schedule trigger (#166) - the other half of "how do customers trigger
    // it" alongside the webhook: a standard 5-field cron expression,
    // interpreted in UTC (see WorkflowScheduler), plus the pre-computed next
    // fire time so the scheduler's per-minute tick can query "which
    // schedules are due" with a plain indexed comparison instead of parsing
    // every enabled workflow's cron expression on every tick.
    @Column(name = "schedule_cron", length = 64)
    private String scheduleCron;

    @Column(name = "schedule_enabled", nullable = false)
    private boolean scheduleEnabled;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    protected WorkflowDefinition() {
        // JPA
    }

    public WorkflowDefinition(String name, String description, String graphJson, WorkflowStatus status) {
        this.name = name;
        this.description = description;
        this.graphJson = graphJson;
        this.status = status;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String description, String graphJson, WorkflowStatus status) {
        this.name = name;
        this.description = description;
        this.graphJson = graphJson;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void enableSchedule(String cron, Instant nextRunAt) {
        this.scheduleCron = cron;
        this.scheduleEnabled = true;
        this.nextRunAt = nextRunAt;
        this.updatedAt = Instant.now();
    }

    public void disableSchedule() {
        this.scheduleEnabled = false;
        this.nextRunAt = null;
        this.updatedAt = Instant.now();
    }

    /** Called by {@code WorkflowScheduler} right after firing (or attempting to) - advances past this occurrence regardless of run outcome, so a failing schedule never fires on every subsequent tick. */
    public void advanceSchedule(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getGraphJson() {
        return graphJson;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getScheduleCron() {
        return scheduleCron;
    }

    public boolean isScheduleEnabled() {
        return scheduleEnabled;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "reasoning_depth", nullable = false, length = 16)
    private ReasoningDepth reasoningDepth = ReasoningDepth.FAST;

    @Enumerated(EnumType.STRING)
    @Column(name = "tuning_mode", nullable = false, length = 16)
    private TuningMode tuningMode = TuningMode.MANUAL;

    @Column(name = "step_caching_enabled", nullable = false)
    private boolean stepCachingEnabled;

    @Column(name = "cost_routing_enabled", nullable = false)
    private boolean costRoutingEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "economy_provider_name", length = 32)
    private ProviderName economyProviderName;

    @Column(name = "economy_model", length = 128)
    private String economyModel;

    @Column(name = "max_steps_per_run", nullable = false)
    private int maxStepsPerRun = 50;

    @Column(name = "step_timeout_seconds", nullable = false)
    private int stepTimeoutSeconds = 30;

    // Per-workflow retry count ("0 retries" vs "5 retries", V205) - number of
    // RE-attempts after a node's first try, not total attempts. Both
    // execution engines add 1 to this when configuring their own retry
    // policy (WorkflowExecutionEngine#executeNodeWithRetry,
    // WorkflowRunWorkflowImpl's ActivityOptions), so 0 here means "try once,
    // never retry" - never pass this raw value to Temporal's
    // RetryOptions#setMaximumAttempts, which treats 0 as UNLIMITED retries,
    // the opposite of this field's meaning.
    @Column(name = "max_retry_attempts", nullable = false)
    private int maxRetryAttempts = 2;

    // Cross-user resource sharing retrofit (Round 2), migration V151. Nullable,
    // no backfill - null means "legacy, predates this feature" (every workflow
    // created before this column existed), which core/sharing's
    // ResourceAccessService#requireAccess treats as AccessCheckResult.LEGACY_OPEN,
    // i.e. tenant-wide-open exactly like today, forever - see V151's own header
    // comment (same shape as Agent.createdBy in the sibling module). Set exactly
    // once, by WorkflowService#create right after construction - ownership
    // doesn't change hands by editing/activating/duplicating a workflow.
    @Column(name = "created_by", length = 255)
    private String createdBy;

    public ReasoningDepth getReasoningDepth() {
        return reasoningDepth;
    }

    public void setReasoningDepth(ReasoningDepth d) {
        this.reasoningDepth = d != null ? d : ReasoningDepth.FAST;
        this.updatedAt = Instant.now();
    }

    public TuningMode getTuningMode() {
        return tuningMode;
    }

    public void setTuningMode(TuningMode m) {
        this.tuningMode = m != null ? m : TuningMode.MANUAL;
        this.updatedAt = Instant.now();
    }

    public boolean isStepCachingEnabled() {
        return stepCachingEnabled;
    }

    public void setStepCachingEnabled(boolean b) {
        this.stepCachingEnabled = b;
        this.updatedAt = Instant.now();
    }

    public boolean isCostRoutingEnabled() {
        return costRoutingEnabled;
    }

    public ProviderName getEconomyProviderName() {
        return economyProviderName;
    }

    public String getEconomyModel() {
        return economyModel;
    }

    public void setCostRouting(boolean enabled, ProviderName provider, String model) {
        this.costRoutingEnabled = enabled;
        this.economyProviderName = provider;
        this.economyModel = provider == null ? null : model;
        this.updatedAt = Instant.now();
    }

    public int getMaxStepsPerRun() {
        return maxStepsPerRun;
    }

    public int getStepTimeoutSeconds() {
        return stepTimeoutSeconds;
    }

    public void setExecutionLimits(int maxSteps, int timeoutSecs) {
        this.maxStepsPerRun = Math.max(1, maxSteps);
        this.stepTimeoutSeconds = Math.max(1, timeoutSecs);
        this.updatedAt = Instant.now();
    }

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    /** Clamped to [0, 5] - see this field's own javadoc for why 0 means "never retry", not "unlimited". */
    public void setMaxRetryAttempts(int retries) {
        this.maxRetryAttempts = Math.max(0, Math.min(5, retries));
        this.updatedAt = Instant.now();
    }

    /** Nullable - null means this workflow predates the cross-user sharing feature (see this field's own javadoc). */
    public String getCreatedBy() {
        return createdBy;
    }

    /** Called exactly once, by {@code WorkflowService#create} - see {@link #createdBy}'s javadoc. */
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    /** Optimistic-lock version; {@code null} until first persisted. */
    public Long getVersion() {
        return version;
    }
}
