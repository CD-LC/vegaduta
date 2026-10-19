package com.agenticai.core.agents.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One unit of agent work that outlives the request that asked for it - the
 * primitive this whole workstream is built on. See {@code
 * db/migration/tenant/V207__agent_job.sql} for the column-by-column rationale
 * (why {@code agent_id} is not a foreign key, why {@code tenant_id} is
 * redundant on purpose, why cancel is a column and not a Redis flag).
 *
 * <p><b>Every status change funnels through the private {@link
 * #transitionTo}.</b> Callers never assign {@code status} directly; they call
 * an intent-named mutator ({@link #markRunning}, {@link #succeed}, ...) and an
 * illegal move throws {@link AgentJobIllegalTransitionException} rather than
 * quietly corrupting the row. The alternative - each caller checking first - is
 * how a job ends up SUCCEEDED after being CANCELLED, because there are five
 * callers and only one of them remembered.
 *
 * <p><b>No {@code @Version} column, deliberately.</b> Only the owning worker
 * ever writes {@code status} for a RUNNING job; cancel writes a separate flag
 * column, the deadline/orphan sweeps use guarded compare-and-set UPDATEs (see
 * {@link AgentJobRepository}), and the claim itself is a conditional UPDATE. So
 * there is no last-writer-wins window for optimistic locking to protect, and
 * adding a version would only introduce retry handling with nothing to retry.
 *
 * <p>Mid-flight mutators that are NOT transitions ({@link #recordProgress},
 * {@link #heartbeat}, {@link #incrementTurn}) exist for the same reason {@code
 * WorkflowRun#updateTrace} does: a running job legitimately changes without
 * changing state, and routing those through the transition guard would mean
 * inventing a self-transition the guard must then allow.
 */
@Entity
@Table(name = "agent_job")
public class AgentJob {

    /**
     * How {@code requested_by_roles} is packed into one VARCHAR. A comma-joined
     * snapshot rather than a child table or a JSON blob: it is written once at
     * enqueue, read once per execution to rebuild {@link
     * com.agenticai.core.security.TenantContext}, and never queried on.
     */
    private static final String ROLE_SEPARATOR = ",";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 255)
    private String tenantId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "goal", nullable = false, columnDefinition = "TEXT")
    private String goal;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AgentJobStatus status;

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    @Column(name = "requested_by_roles", length = 512)
    private String requestedByRoles;

    @Column(name = "subject_key", nullable = false, length = 255)
    private String subjectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 32)
    private AgentJobOrigin origin;

    @Column(name = "origin_ref", length = 255)
    private String originRef;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "progress_note", length = 500)
    private String progressNote;

    @Column(name = "progress_updated_at")
    private Instant progressUpdatedAt;

    @Column(name = "result_text", columnDefinition = "TEXT")
    private String resultText;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "result_schema_json", columnDefinition = "TEXT")
    private String resultSchemaJson;

    @Column(name = "schema_attempts", nullable = false)
    private int schemaAttempts;

    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "max_turns", nullable = false)
    private int maxTurns;

    @Column(name = "turns_used", nullable = false)
    private int turnsUsed;

    @Column(name = "max_tool_calls", nullable = false)
    private int maxToolCalls;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "cancel_requested_by", length = 255)
    private String cancelRequestedBy;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "owner_instance", length = 128)
    private String ownerInstance;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "schedule_occurrence_at")
    private Instant scheduleOccurrenceAt;

    @Column(name = "trigger_id")
    private UUID triggerId;

    @Column(name = "trigger_depth", nullable = false)
    private int triggerDepth;

    @Column(name = "parked_at")
    private Instant parkedAt;

    @Column(name = "approval_deadline_at")
    private Instant approvalDeadlineAt;

    @Column(name = "park_count", nullable = false)
    private int parkCount;

    @Column(name = "resume_reason", columnDefinition = "TEXT")
    private String resumeReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AgentJob() {
        // JPA
    }

    /**
     * The only way to build a new job. Named for the state it produces so that
     * "a job starts QUEUED, never RUNNING" is visible at every call site - the
     * durable row is written FIRST and in-memory dispatch is a latency
     * optimisation on top of it, not the mechanism (see {@link
     * AgentJobExecutor}'s javadoc: the agent_job TABLE is the queue).
     *
     * @param subjectKey  the requester's {@code PerUserBudgetService} user key.
     *     Must be a real human's key: see this parameter's treatment in {@link
     *     AgentJobService#submit} for why a synthetic per-job subject would
     *     silently mint a fresh daily spend ceiling per submission.
     * @param roles the requester's roles at submit time. Frozen here on purpose -
     *     a job executes with the authority the human had when they asked, never
     *     elevated system authority.
     */
    public static AgentJob queued(
        UUID id, String tenantId, UUID agentId, String goal,
        String subjectKey, String requestedBy, Set<String> roles, AgentJobOrigin origin,
        String originRef, int timeoutSeconds, int maxTurns, int maxToolCalls,
        String resultSchemaJson
    ) {
        AgentJob job = new AgentJob();
        job.id = id;
        job.tenantId = tenantId;
        job.agentId = agentId;
        job.goal = goal;
        job.status = AgentJobStatus.QUEUED;
        job.requestedBy = requestedBy;
        job.requestedByRoles = packRoles(roles);
        job.subjectKey = subjectKey;
        job.origin = origin;
        job.originRef = originRef;
        job.sessionId = AgentJobSession.sessionIdFor(id);
        job.timeoutSeconds = timeoutSeconds;
        job.maxTurns = maxTurns;
        job.maxToolCalls = maxToolCalls;
        job.resultSchemaJson = resultSchemaJson;
        job.createdAt = Instant.now();
        return job;
    }

    // ---------------------------------------------------------------- transitions

    /**
     * QUEUED -&gt; RUNNING. {@code deadlineAt} is stamped by the caller alongside
     * this (see {@link AgentJobRepository#claim}) rather than here, because the
     * authoritative claim is a conditional UPDATE issued by the database, not
     * an entity save - this method is the in-memory mirror of that statement so
     * the worker's loaded copy agrees with the row.
     */
    public void markRunning(String ownerInstance, Instant now) {
        transitionTo(AgentJobStatus.RUNNING);
        this.ownerInstance = ownerInstance;
        this.startedAt = now;
        this.heartbeatAt = now;
        this.attemptCount = this.attemptCount + 1;
    }

    /**
     * RUNNING -&gt; AWAITING_APPROVAL. Produced only by the deferred
     * approval-parking PR; present here so the state machine is complete and
     * testable now rather than being retrofitted later. Deliberately clears
     * {@code ownerInstance}: a parked job belongs to no worker, and leaving a
     * stale owner behind would make the orphan sweep's "is replica B still
     * running this?" question unanswerable.
     */
    public void parkForApproval(Instant now, Instant approvalDeadline) {
        parkForApproval(now, approvalDeadline, null);
    }

    /**
     * @param reason why this job stopped, in the operator's words - the ONLY
     *     writer of {@code resume_reason}, which had none before and was
     *     therefore a mapped column no code path could ever populate. Written at
     *     the park rather than at the resume because this is the only moment the
     *     cause is unambiguous; by the time a human decides, the job may have
     *     tripped more than one gate.
     */
    public void parkForApproval(Instant now, Instant approvalDeadline, String reason) {
        transitionTo(AgentJobStatus.AWAITING_APPROVAL);
        this.parkedAt = now;
        this.approvalDeadlineAt = approvalDeadline;
        this.parkCount = this.parkCount + 1;
        this.resumeReason = reason;
        this.ownerInstance = null;
        this.heartbeatAt = null;
    }

    /** AWAITING_APPROVAL -&gt; RUNNING, on a fresh worker (possibly a different replica). */
    public void resumeFromApproval(String ownerInstance, Instant now) {
        transitionTo(AgentJobStatus.RUNNING);
        this.ownerInstance = ownerInstance;
        this.heartbeatAt = now;
        this.parkedAt = null;
        this.approvalDeadlineAt = null;
    }

    public void succeed(String resultText, String resultJson, BigDecimal costUsd, int schemaAttempts, Instant now) {
        transitionTo(AgentJobStatus.SUCCEEDED);
        this.resultText = resultText;
        this.resultJson = resultJson;
        this.costUsd = costUsd;
        this.schemaAttempts = schemaAttempts;
        this.finishedAt = now;
        this.ownerInstance = null;
    }

    /**
     * @param errorCode a short stable machine token ({@code BUDGET_EXHAUSTED},
     *     {@code TIMEOUT}, {@code AGENT_NOT_FOUND}, ...). Clients branch on
     *     this; {@code errorMessage} is prose for a human and may change wording
     *     freely.
     */
    public void fail(String errorCode, String errorMessage, Instant now) {
        transitionTo(AgentJobStatus.FAILED);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.finishedAt = now;
        this.ownerInstance = null;
    }

    public void markCancelled(Instant now) {
        transitionTo(AgentJobStatus.CANCELLED);
        this.finishedAt = now;
        this.ownerInstance = null;
    }

    /**
     * The single chokepoint every status write passes through. Throwing
     * (rather than returning false, or silently no-op'ing) is deliberate: an
     * illegal transition means two code paths both believe they own this job,
     * which is a bug worth a stack trace at the moment it happens rather than a
     * confusing row hours later.
     */
    private void transitionTo(AgentJobStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new AgentJobIllegalTransitionException(id, status, next);
        }
        this.status = next;
    }

    // ------------------------------------------------- mid-flight, non-transition

    /**
     * Denormalized "latest note" for the operator list. The full history is a
     * separate table written by the observability PR; this column exists so
     * rendering 50 rows of "what is it doing right now" is one query.
     */
    public void recordProgress(String note, Instant now) {
        this.progressNote = note;
        this.progressUpdatedAt = now;
    }

    public void heartbeat(Instant now) {
        this.heartbeatAt = now;
    }

    /**
     * Durable on the row, not an in-memory counter, so a job resumed on another
     * replica after a park or a crash cannot reset its own turn budget by
     * starting from zero.
     */
    public void incrementTurn() {
        this.turnsUsed = this.turnsUsed + 1;
    }

    /**
     * Mirrors {@link AgentJobRepository#requestCancel}'s columns onto a loaded
     * copy. Only used when a caller has just issued that UPDATE and wants the
     * entity it is about to return to the client to agree with the row, rather
     * than re-reading it.
     */
    public void markCancelRequested(String by, Instant now) {
        this.cancelRequested = true;
        this.cancelRequestedBy = by;
        this.cancelRequestedAt = now;
    }

    // ------------------------------------------------------------------- getters

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getGoal() {
        return goal;
    }

    public AgentJobStatus getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    /** The raw comma-joined column; {@link #getRequestedByRoleSet} is what callers usually want. */
    public String getRequestedByRoles() {
        return requestedByRoles;
    }

    /**
     * The frozen requester authority, ready to hand to {@code
     * TenantContext.of(...)} on a worker thread. Never {@code null} - a job with
     * no recorded roles rebuilds as a context with no roles, which is strictly
     * less privileged, never more.
     */
    public Set<String> getRequestedByRoleSet() {
        if (requestedByRoles == null || requestedByRoles.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(requestedByRoles.split(ROLE_SEPARATOR))
            .map(String::trim)
            .filter(role -> !role.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public String getSubjectKey() {
        return subjectKey;
    }

    public AgentJobOrigin getOrigin() {
        return origin;
    }

    public String getOriginRef() {
        return originRef;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProgressNote() {
        return progressNote;
    }

    public Instant getProgressUpdatedAt() {
        return progressUpdatedAt;
    }

    public String getResultText() {
        return resultText;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getResultSchemaJson() {
        return resultSchemaJson;
    }

    public int getSchemaAttempts() {
        return schemaAttempts;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public int getTurnsUsed() {
        return turnsUsed;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String getCancelRequestedBy() {
        return cancelRequestedBy;
    }

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public String getOwnerInstance() {
        return ownerInstance;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public Instant getScheduleOccurrenceAt() {
        return scheduleOccurrenceAt;
    }

    /**
     * Written by the SCHEDULES PR at enqueue and never again - the pair is what
     * V208's two unique indexes (at most one live job per schedule; exactly one
     * job per occurrence) are enforced on, so it must be set in the same INSERT
     * that creates the row, not patched afterwards.
     */
    public void bindToSchedule(UUID scheduleId, Instant occurrenceAt) {
        this.scheduleId = scheduleId;
        this.scheduleOccurrenceAt = occurrenceAt;
    }

    /**
     * Written by the EVENT-TRIGGERS PR at enqueue and never again - the mirror of
     * {@link #bindToSchedule} for the other non-HTTP origin. Must be applied in
     * the SAME INSERT that creates the row: an event-triggered job that records
     * its trigger only afterwards would be unattributable for the window in
     * between, and "which trigger produced this job?" is the first question asked
     * when a trigger misbehaves.
     *
     * <p>Until this existed, {@code trigger_id}/{@code trigger_depth} (shipped in
     * V207) were getter-only columns nothing could ever write.
     *
     * @param triggerDepth reserved. There is no reliable lineage signal today -
     *     the causing job's identity does not survive to the thread that
     *     publishes the follow-on event - so this is recorded as {@code 0} and
     *     must not be presented as working loop detection. The loop guard is the
     *     per-trigger rolling-window firing cap, not this number.
     */
    public void bindToTrigger(UUID triggerId, int triggerDepth) {
        this.triggerId = triggerId;
        this.triggerDepth = triggerDepth;
    }

    /**
     * RUNNING -&gt; QUEUED. The in-memory mirror of {@link
     * AgentJobRepository#requeueIfStillRunning}, so a loaded copy agrees with the
     * row the guarded UPDATE just wrote - the same relationship {@link
     * #markRunning} has with {@code claim}.
     *
     * <p>Clears every field that identifies a specific execution attempt:
     * a re-queued job has no owner, no start, no deadline and no heartbeat until
     * it is claimed again. Leaving {@code heartbeatAt} behind would be the
     * subtler bug of the two - {@code heartbeat_at < cutoff} would keep matching
     * the orphan sweep's query while the row sat harmlessly QUEUED.
     * {@code attemptCount} is deliberately NOT reset: it is what bounds how many
     * times a job may be recovered.
     *
     * @param now part of the agreed cross-PR signature and deliberately unused -
     *     a re-queue stamps no timestamp of its own, exactly as the repository
     *     statement it mirrors writes none. Accepting it keeps every mutator in
     *     this class callable the same way and leaves room for a future
     *     {@code requeued_at} without a signature change.
     */
    public void requeue(Instant now) {
        transitionTo(AgentJobStatus.QUEUED);
        this.ownerInstance = null;
        this.startedAt = null;
        this.deadlineAt = null;
        this.heartbeatAt = null;
    }

    public UUID getTriggerId() {
        return triggerId;
    }

    public int getTriggerDepth() {
        return triggerDepth;
    }

    public Instant getParkedAt() {
        return parkedAt;
    }

    public Instant getApprovalDeadlineAt() {
        return approvalDeadlineAt;
    }

    public int getParkCount() {
        return parkCount;
    }

    public String getResumeReason() {
        return resumeReason;
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

    private static String packRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        String packed = String.join(ROLE_SEPARATOR, roles);
        if (packed.length() <= 512) {
            return packed;
        }
        // Truncation rather than rejection: a caller with an unusually long role
        // list must still be able to submit a job. Cut back to the last WHOLE
        // role, never mid-token - a mid-token cut could in principle turn some
        // future "ROLE_tenant-admin-readonly" into "ROLE_tenant-admin", i.e.
        // truncation that GRANTS rather than removes. Dropping whole roles can
        // only ever leave the job less privileged than the human who asked.
        int lastSeparator = packed.lastIndexOf(ROLE_SEPARATOR, 512);
        return lastSeparator > 0 ? packed.substring(0, lastSeparator) : null;
    }
}
