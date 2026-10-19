package com.agenticai.core.agents.job.approval;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobExecutor;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestExecutionRecordedEvent;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * What actually makes an approval decision reach a parked job, and what makes an
 * IGNORED decision stop being a job parked forever.
 *
 * <h2>This sweep is the guarantee, not an optimisation</h2>
 * The obvious design is to resume the job the instant a human clicks approve, by
 * registering an {@code ApprovalActionExecutor} that {@code
 * HumanApprovalGateService#approve} would invoke. <b>That seam is not available
 * to agent jobs, and the reason is worth stating precisely rather than
 * discovering later.</b> The gate a job actually trips records its request as
 * {@code "mcp_tool.<name>"}, and {@code ApprovalActionExecutorRegistry#find}
 * returns exactly ONE executor per action type - the {@code mcp_tool.} family is
 * already owned by {@code AgentToolCallApprovalExecutor}, which is the bean that
 * REPLAYS the approved tool call. Registering a second executor over the same
 * prefix would resolve by bean iteration order (that registry's own javadoc says
 * so), and the two possible outcomes are both bad: if ours lost it would never
 * run, and if ours won the approved tool would never actually execute - which
 * would also destroy the reason a fresh turn is safe at all (see below).
 *
 * <p>So resume is driven from here, on a tick - <b>and that remains true</b>:
 * the tick is what survives a JVM restart between the decision and the resume,
 * and a worker pool that was saturated at the moment of the decision. Nothing
 * below removes it or is allowed to become required for correctness.
 *
 * <h2>{@link #onApprovalExecutionRecorded} is a latency optimisation on top,
 * not a second resume path</h2>
 * The 60s bound above was never free - work that already spent hours waiting
 * for a human then waits up to another minute for the tick, on top of the 90s
 * {@link #REPLAY_GRACE} window below. {@code AgentToolCallApprovalExecutor}
 * (and every other {@code ApprovalActionExecutor}) already know, precisely,
 * the instant their replay's outcome becomes durable - they just have nowhere
 * safe to say so, for the exact registry reason above. {@link
 * com.agenticai.core.approval.ApprovalRequestExecutionRecordedEvent} is that
 * "somewhere safe": a plain Spring {@code ApplicationEvent}, published by
 * {@code core/approval} (which has no idea agent jobs exist) whenever an
 * approval request's execution outcome settles, listened for here. The
 * listener does nothing {@link #settleOne} could not already do on the next
 * tick - it just calls {@link #settleOne} early, for the one row the event
 * names, and every decision inside it (PENDING/REJECTED short-circuit, the
 * {@link #REPLAY_GRACE} check, the guarded {@code resumeClaim}) still applies
 * unchanged. A missed, duplicated, or late event changes only how many
 * milliseconds a job waits - never whether it resumes correctly - because the
 * tick still runs regardless and {@code resumeClaim} is a guarded conditional
 * UPDATE: whichever of the event listener and the next tick gets there first
 * wins, and the other observes rowcount 0 against an already-RUNNING job, same
 * as any two ticks racing each other already could.
 *
 * <h2>Resume dispatches a FRESH turn; it does not replay the parked one</h2>
 * All this sweep does on approval is call {@link AgentJobExecutor#tryDispatch} -
 * the ordinary dispatch path. {@code AgentJobExecution} then re-claims the row
 * with the guarded AWAITING_APPROVAL &rarr; RUNNING {@code resumeClaim} (fresh
 * deadline, re-stamped heartbeat) and runs one more turn, the allowance
 * {@code max_turns + park_count} buys it. <b>Exactly one code path transitions a
 * job to RUNNING</b>, and it is not this one.
 *
 * <p>A fresh turn continues rather than repeats because {@code
 * AgentToolCallApprovalExecutor} has already re-run the approved tool under the
 * recorded tenant/subject and appended {@code [approved action '<tool>'
 * completed: ...]} to {@code SessionMemoryService} under the originating session
 * id - and a job's session id is {@code job:<jobId>}. The outcome is therefore
 * already in the parked job's conversation history before this sweep dispatches
 * anything. Re-running the parked turn instead would be actively unsafe: nothing
 * dedupes tool calls, so image generation, document generation, knowledge writes
 * and channel sends would all re-fire.
 *
 * <h2>Two passes, and why they are not one</h2>
 * <ul>
 *   <li>{@link #expireOverdueParks} reads by {@code approval_deadline_at}
 *       (unbounded, served by V211's partial index). It TERMINATES: a park past
 *       its deadline, and a park over the per-job ceiling.</li>
 *   <li>{@link #settleDecidedParks} reads the parked rows themselves (capped at
 *       50, the same cap and the same reasoning as {@code
 *       AgentJobRunner#dispatchQueued}). It RESUMES or terminates on a
 *       rejection.</li>
 * </ul>
 * Splitting them means the cap on the second pass can only ever delay a resume,
 * never strand a job: expiry is unbounded, so a tenant with more than fifty
 * simultaneously parked jobs still has every one of them time out on schedule.
 * That cap is a real limitation and is written down in
 * {@code docs/AUTONOMOUS-AGENTS-PR5-APPROVAL-PARKING.md} rather than hidden.
 *
 * <p>Shape is the one every scheduled job in this codebase uses: {@link
 * PgAdvisoryJobLock#tryRunExclusive} so two replicas do not both sweep, then
 * {@link TenantSchemaIterator#forEachActiveTenantSchema} because a {@code
 * @Scheduled} method has NO {@code TenantContext} and an un-iterated repository
 * call would silently only ever see the empty {@code public} schema (the
 * 2026-08-10 document-generation incident), then a per-row try/catch so one bad
 * row cannot stop the batch. Every write below is a guarded conditional UPDATE,
 * so a loser observes rowcount 0 - the advisory lock is de-duplication, never
 * correctness.
 */
@Component
public class AgentJobApprovalSweep {

    private static final Logger log = LoggerFactory.getLogger(AgentJobApprovalSweep.class);

    /** Distinct from every other {@code PgAdvisoryJobLock} name in this fan-out. */
    static final String APPROVAL_SWEEP_LOCK = "agent-job-approval-sweep";

    /** No human decided in time. */
    static final String ERROR_APPROVAL_TIMEOUT = "APPROVAL_TIMEOUT";
    /** A human said no. */
    static final String ERROR_APPROVAL_REJECTED = "APPROVAL_REJECTED";
    /** The job asked for sign-off more times than its ceiling allows. */
    static final String ERROR_APPROVAL_PARK_LIMIT = "APPROVAL_PARK_LIMIT";

    static final String METRIC_TIMED_OUT = "agent.job.approval.timed_out";
    static final String METRIC_REJECTED = "agent.job.approval.rejected";
    static final String METRIC_PARK_LIMIT = "agent.job.approval.park_limit";
    static final String METRIC_RESUMED = "agent.job.approval.resumed";
    static final String METRIC_CANCELLED = "agent.job.approval.cancelled";

    /** Recorded as the canceller when a parked job carries a cancel flag with no recorded requester. */
    private static final String SWEEP_ACTOR = "system:agent-job-approval-sweep";

    /**
     * How long a decided-but-not-yet-executed approval is left alone before this
     * sweep resumes the job anyway.
     *
     * <p>{@code HumanApprovalGateService#approve} commits the decision and only
     * then runs the executor, outside any transaction, on the admin's HTTP
     * thread - so there is a window in which the request is APPROVED but the
     * gated tool has not been replayed and its result is not yet in session
     * memory. Dispatching inside that window costs a billed turn that re-asks
     * for the same gate, or races the replay into a duplicate side effect.
     * Waiting forever would be worse (a replay that dies with the JVM would
     * strand the job), so it is a bounded wait: one extra sweep tick at most in
     * the normal case.
     */
    private static final Duration REPLAY_GRACE = Duration.ofSeconds(90);

    private final AgentJobRepository repository;
    private final AgentJobApprovalLookup approvalLookup;
    private final AgentJobProperties properties;
    private final TenantSchemaIterator schemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final MeterRegistry meterRegistry;

    /**
     * {@code ObjectProvider} is load-bearing here, not a slim-test convenience.
     * A direct field would close a real bean cycle: this bean &rarr;
     * {@code AgentJobExecutor} &rarr; {@code AgentJobExecution} &rarr; {@code
     * ObjectProvider<AgentJobApprovalPark>} &rarr; {@code
     * AgentJobApprovalParkDetector}. Both existing approval-family executors
     * document the same idiom for the same reason.
     */
    private final ObjectProvider<AgentJobExecutor> executor;

    /**
     * The ONLY writer this class uses against {@code approval_request}, and it
     * goes through the owning service rather than the table.
     *
     * <p>It exists to close a hole the first cut left open: when this sweep
     * terminalises a parked job (timeout, park ceiling, cancel) it wrote only to
     * {@code agent_job} and left the blocking request PENDING forever. A
     * tenant-admin approving that orphaned request later would make {@code
     * AgentToolCallApprovalExecutor} REPLAY a real, non-idempotent tool call -
     * a channel send, a document generation, any {@code mcp_tool.*} - on behalf
     * of a job that is already FAILED or CANCELLED. Rejecting the request as
     * part of terminalising the job is what makes that impossible: {@code
     * approve} then throws on a non-PENDING row.
     *
     * <p>{@code ObjectProvider} for the same bean-cycle reason as the executor
     * above - the gate service reaches {@code ApprovalActionExecutorRegistry},
     * which injects every executor, including ones that reach back into the job
     * machinery.
     */
    private final ObjectProvider<HumanApprovalGateService> approvalGate;

    @Autowired
    public AgentJobApprovalSweep(
        AgentJobRepository repository,
        AgentJobApprovalLookup approvalLookup,
        AgentJobProperties properties,
        TenantSchemaIterator schemaIterator,
        PgAdvisoryJobLock jobLock,
        MeterRegistry meterRegistry,
        ObjectProvider<AgentJobExecutor> executor,
        ObjectProvider<HumanApprovalGateService> approvalGate
    ) {
        this.repository = repository;
        this.approvalLookup = approvalLookup;
        this.properties = properties;
        this.schemaIterator = schemaIterator;
        this.jobLock = jobLock;
        this.meterRegistry = meterRegistry;
        this.executor = executor;
        this.approvalGate = approvalGate;
    }

    /**
     * Every tick, on every replica that wins the advisory lock.
     *
     * <p>{@code initialDelay} equal to the interval, matching {@code
     * AgentJobRunner}'s ticks: there is nothing useful to sweep in the first
     * seconds after boot, and a tick that fires during context refresh competes
     * with startup for the same connection pool.
     */
    @Scheduled(
        fixedDelayString = "${app.agent-jobs.approval.sweep-interval:60s}",
        initialDelayString = "${app.agent-jobs.approval.sweep-interval:60s}"
    )
    public void sweepParkedJobs() {
        jobLock.tryRunExclusive(APPROVAL_SWEEP_LOCK, this::sweepExclusively);
    }

    private void sweepExclusively() {
        Instant now = Instant.now();
        schemaIterator.forEachActiveTenantSchema(schema -> {
            expireOverdueParks(now, schema);
            settleDecidedParks(now, schema);
        });
    }

    /**
     * The event-driven fast path - see the class javadoc's {@code
     * onApprovalExecutionRecorded} section for why this is an optimisation on
     * top of {@link #sweepParkedJobs}, never a replacement for it.
     *
     * <p>{@code fallbackExecution = true} is load-bearing, not a default left
     * unconsidered: {@code @TransactionalEventListener} without it SILENTLY
     * DROPS an event published outside an active transaction, and two of the
     * three publishers of {@link ApprovalRequestExecutionRecordedEvent} -
     * {@code AgentToolCallApprovalExecutor} and {@code
     * RunCodeWorkspaceApprovalExecutor}'s {@code persistExecutionResult}
     * methods - publish from exactly there, by design: {@code
     * HumanApprovalGateService} deliberately invokes {@code
     * ApprovalActionExecutor#execute} outside any transaction (see that
     * class's own javadoc on why - a slow outbound network call must never
     * hold a pooled DB connection open). With the flag set, an event
     * published with no transaction active runs synchronously, in place, same
     * as a plain {@code @EventListener} would - the only publisher that ever
     * takes the transactional branch is {@code ApprovalRequestRecorder}'s own
     * {@code @Transactional} methods, where AFTER_COMMIT is exactly the
     * correct semantics (never resume into a row whose write has not landed).
     *
     * <p>Runs on whichever thread published the event, inheriting whatever
     * {@code TenantContext} that thread already had set (every current
     * publisher sets it correctly before this fires - see each publisher's
     * own javadoc). This method sets none of its own: a wrong or missing
     * context makes {@link #repository}'s lookup below resolve against the
     * wrong schema (or none), which reads back as "job not found" and this
     * method quietly does nothing - the row simply waits for the next {@link
     * #sweepParkedJobs} tick, which iterates every schema correctly via
     * {@link #schemaIterator} regardless. That silent no-op is the intended
     * degradation, not a swallowed error: this method must never let a
     * context problem surface as a failed approval decision.
     *
     * <p>Cheap to call for the (large) majority of {@link
     * ApprovalRequestExecutionRecordedEvent}s that are not job-related at
     * all (channel sends, workflow-node approvals, console tool-call gates,
     * code-intel runs invoked outside a job) - {@link #jobIdFromRequestedBy}
     * is pure string parsing, no query, and returns empty for every one of
     * those before this method ever touches the database.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onApprovalExecutionRecorded(ApprovalRequestExecutionRecordedEvent event) {
        Optional<UUID> jobId = jobIdFromRequestedBy(event.getRequestedBy());
        if (jobId.isEmpty()) {
            return;
        }
        AgentJob job;
        try {
            job = repository.findById(jobId.get()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not look up agent job {} after its approval request {} settled - the next sweep tick "
                + "will still pick it up", jobId.get(), event.getRequestId(), e);
            return;
        }
        if (job == null || job.getStatus() != AgentJobStatus.AWAITING_APPROVAL) {
            // Nothing parked under this id (never was, or the poll / another
            // event already resumed - or terminalised - it first).
            return;
        }
        try {
            settleOne(job, Instant.now());
        } catch (RuntimeException e) {
            log.error("Failed to resume agent job {} immediately after its approval request {} settled - the next "
                + "sweep tick will retry", job.getId(), event.getRequestId(), e);
        }
    }

    /**
     * Inverse of {@link AgentJobSession#requestedByLabel}, without depending on
     * it - that method builds the string this one parses, and neither can call
     * the other without an import cycle risk this stays clear of by just
     * re-deriving the one substring it needs. Mirrors {@code
     * AgentToolCallApprovalExecutor#fromLegacyPayload}'s identical parse of the
     * same label shape.
     *
     * <p>Total and null-safe: any {@code requestedBy} that is not a
     * well-formed {@code "agent:<uuid>:session:job:<jobId>"} label (a human
     * subject string, a legacy row with no agent prefix, {@code null}) yields
     * {@link Optional#empty()} rather than throwing, because this event fires
     * for every settled approval request in the application, the overwhelming
     * majority of which never belonged to a job at all.
     */
    private static Optional<UUID> jobIdFromRequestedBy(String requestedBy) {
        if (requestedBy == null || !requestedBy.startsWith("agent:")) {
            return Optional.empty();
        }
        int sessionMarker = requestedBy.indexOf(":session:");
        if (sessionMarker < 0) {
            return Optional.empty();
        }
        String sessionId = requestedBy.substring(sessionMarker + ":session:".length());
        return AgentJobSession.jobIdOf(sessionId);
    }

    /**
     * The half of the acceptance criterion that says "an ignored approval times
     * out with a clear status".
     *
     * <p>{@code approval_deadline_at} has been written since V207 and read by
     * absolutely nothing until this method. The difference matters more than it
     * looks: a job parked forever is not merely stuck, it holds V208's
     * live-job-per-schedule unique index shut, so a single un-decided approval
     * would mute that agent's whole schedule indefinitely while every layer
     * reported itself healthy.
     */
    private void expireOverdueParks(Instant now, String schema) {
        for (AgentJob job : repository.findByStatusAndApprovalDeadlineAtBefore(AgentJobStatus.AWAITING_APPROVAL, now)) {
            try {
                // Over-cap is checked FIRST because the park detector expresses
                // "I refuse to park this again" as a park with an already-dead
                // deadline. Both conditions are therefore true for such a row,
                // and reporting it as a timeout would send an operator looking
                // for a human who never existed instead of at an agent stuck in
                // an approve-loop.
                boolean overParkCap = job.getParkCount() > parkCap();
                String errorCode = overParkCap ? ERROR_APPROVAL_PARK_LIMIT : ERROR_APPROVAL_TIMEOUT;
                String message = overParkCap
                    ? "This job stopped at a human approval gate " + job.getParkCount() + " times, past its ceiling of "
                        + parkCap() + " (app.agent-jobs.approval.max-parks-per-job). It was failed rather than "
                        + "parked again, because each park buys another billed turn."
                    : "No human decided this job's approval request before its deadline of "
                        + job.getApprovalDeadlineAt() + " (app.agent-jobs.approval.park-timeout = "
                        + properties.getApproval().getParkTimeout() + ").";

                if (repository.failIfAwaitingApproval(job.getId(), errorCode, message, now) > 0) {
                    meterRegistry.counter(overParkCap ? METRIC_PARK_LIMIT : METRIC_TIMED_OUT).increment();
                    log.warn(
                        "Agent job {} (agent {}) was failed with {} after parking {} time(s); its approval deadline "
                            + "was {}",
                        job.getId(), job.getAgentId(), errorCode, job.getParkCount(), job.getApprovalDeadlineAt()
                    );
                    // The other half of terminalising a parked job. Without it
                    // the blocking request stays PENDING forever, and a
                    // tenant-admin approving it weeks later replays a real,
                    // non-idempotent tool call for a job that is already FAILED.
                    rejectOrphanedPendingRequests(job, "failed with " + errorCode);
                }
            } catch (RuntimeException e) {
                log.error("Failed to expire parked agent job {} in schema '{}'", job.getId(), schema, e);
            }
        }
    }

    /**
     * The half that says "approving it completes the work", plus rejection and
     * the cancel hole.
     */
    private void settleDecidedParks(Instant now, String schema) {
        for (AgentJob job : repository.findTop50ByStatusOrderByCreatedAtAsc(AgentJobStatus.AWAITING_APPROVAL)) {
            try {
                settleOne(job, now);
            } catch (RuntimeException e) {
                log.error("Failed to settle parked agent job {} in schema '{}'", job.getId(), schema, e);
            }
        }
    }

    /**
     * One parked job, in a deliberate order.
     *
     * <ol>
     *   <li><b>Cancel first.</b> A parked job holds no worker, so {@code
     *       AgentJobExecutor}'s heartbeat tick - which is what normally notices
     *       {@code cancel_requested} - never looks at it. {@code
     *       AgentJobService#cancel} handles the common case synchronously, but
     *       there is a real race: a cancel that lands between {@code
     *       AgentJobExecution}'s last cancel checkpoint and the park itself
     *       writes only the flag, and would otherwise leave the row parked with
     *       {@code cancel_requested = true} until its 24h deadline.</li>
     *   <li><b>Still PENDING &rarr; leave it alone.</b> This is the normal state
     *       of a parked job and must stay cheap.</li>
     *   <li><b>REJECTED &rarr; fail.</b> Checked only after PENDING, so a job
     *       that had one gate rejected in an earlier turn and is now parked on a
     *       DIFFERENT, still-pending gate is not killed for the old decision.</li>
     *   <li><b>Still settling &rarr; wait.</b> Two decided-looking shapes are
     *       actually in flight and resuming into them can run the gated tool
     *       twice: {@code EXECUTION_FAILED} with {@code nextRetryAt} set (a
     *       retry is scheduled), and {@code APPROVED} with no {@code
     *       executionResult} inside {@link #REPLAY_GRACE} of the decision (the
     *       replay is running right now, outside any transaction). See the
     *       inline comment for the full argument and the honest residual.</li>
     *   <li><b>Anything else &rarr; dispatch.</b> APPROVED-with-result is the
     *       intended case. {@code EXECUTION_FAILED} with no retry scheduled and
     *       {@code DEAD_LETTERED} also resume, and that is correct rather than
     *       sloppy: the human said yes, the replay failed terminally, and {@code
     *       AgentToolCallApprovalExecutor} has already written {@code [approved
     *       action '<tool>' failed: ...]} into the job's session memory - so the
     *       resumed turn learns what happened instead of the job dying with a
     *       status that blames the approval. A vanished request (no rows at all)
     *       resumes too: the safest reading of "there is nothing left to wait
     *       for".</li>
     * </ol>
     */
    private void settleOne(AgentJob job, Instant now) {
        if (job.isCancelRequested()) {
            String by = job.getCancelRequestedBy() != null && !job.getCancelRequestedBy().isBlank()
                ? job.getCancelRequestedBy()
                : SWEEP_ACTOR;
            if (repository.cancelIfAwaitingApproval(job.getId(), by, now) > 0) {
                meterRegistry.counter(METRIC_CANCELLED).increment();
                log.info("Parked agent job {} carried a cancel request from '{}' and was terminated", job.getId(), by);
                // Same reasoning as the expiry pass: a cancelled job's blocking
                // request must not stay approvable.
                rejectOrphanedPendingRequests(job, "cancelled");
            }
            return;
        }

        String label = AgentJobSession.requestedByLabel(job.getAgentId(), job.getId());
        if (!approvalLookup.findByRequestedByAndStatusOrderByCreatedAtAsc(label, ApprovalStatus.PENDING).isEmpty()) {
            return;
        }

        if (!approvalLookup.findByRequestedByAndStatusOrderByCreatedAtAsc(label, ApprovalStatus.REJECTED).isEmpty()) {
            String message = "A human rejected the action this job stopped on, so the job cannot continue. "
                + "See the approval request recorded against '" + label + "' for the reason.";
            if (repository.failIfAwaitingApproval(job.getId(), ERROR_APPROVAL_REJECTED, message, now) > 0) {
                meterRegistry.counter(METRIC_REJECTED).increment();
                log.info("Agent job {} (agent {}) was failed: its approval request was rejected",
                    job.getId(), job.getAgentId());
            }
            return;
        }

        // Nothing pending and nothing rejected: a decision exists (or the
        // request is gone). But two shapes of "decided" are still IN FLIGHT and
        // must not be resumed yet:
        //
        //   1. EXECUTION_FAILED with nextRetryAt set means "failed, RETRY
        //      SCHEDULED" - RetryFailedActionsJob will call the executor again.
        //      Resuming now runs a fresh billed turn whose model, reading
        //      "[approved action '...' failed: ...]" in session memory, may
        //      re-invoke the same gated tool CONCURRENTLY with the pending
        //      retry. Nothing dedupes tool calls, so wait the retry out; it
        //      always terminates (success -> APPROVED + executionResult,
        //      exhaustion -> DEAD_LETTERED, non-retryable -> nextRetryAt null).
        //      A retry job that is broken outright cannot strand the job
        //      forever - the expiry pass still times the park out.
        //
        //   2. APPROVED with no executionResult inside REPLAY_GRACE of the
        //      decision: approve() commits the decision FIRST and replays the
        //      tool afterwards, outside any transaction, so this window is the
        //      replay actually running. Dispatching inside it costs a billed
        //      turn that re-asks for the same gate, or races the replay into a
        //      duplicate side effect. Past the grace the sweep resumes anyway -
        //      the replay may have died with its JVM, and the session-memory
        //      note (written by the executor, not the gate) may legitimately
        //      never appear. That residual is bounded and chosen over parking
        //      forever.
        ApprovalRequest inFlight = replayInFlight(label, now);
        if (inFlight != null) {
            log.debug(
                "Parked agent job {} has approval request {} still settling ({}) - not resuming yet",
                job.getId(), inFlight.getId(), inFlight.getStatus());
            return;
        }

        // Hand the row back to the ordinary dispatch path and let
        // AgentJobExecution own the AWAITING_APPROVAL -> RUNNING claim.
        // A `false` here is not a failure - the pool was busy, the row stays
        // parked, and the next tick tries again. Nothing is lost.
        if (dispatch(job)) {
            meterRegistry.counter(METRIC_RESUMED).increment();
            log.info(
                "Agent job {} (agent {}) was handed back to a worker after its approval was decided (park #{})",
                job.getId(), job.getAgentId(), job.getParkCount()
            );
        }
    }

    /**
     * The newest request that is still SETTLING for this job's label, or null
     * when every recent request is genuinely terminal-and-settled.
     *
     * <p>Bounded at 20 rows, newest first ({@link
     * AgentJobApprovalLookup#findTop20ByRequestedByOrderByCreatedAtDesc}) - a
     * job that tripped more gates than that has other problems, and this runs
     * once per parked row per tick only after the cheap PENDING/REJECTED checks
     * both came back empty.
     */
    private ApprovalRequest replayInFlight(String label, Instant now) {
        for (ApprovalRequest request : approvalLookup.findTop20ByRequestedByOrderByCreatedAtDesc(label)) {
            if (request.getStatus() == ApprovalStatus.EXECUTION_FAILED && request.getNextRetryAt() != null) {
                return request;
            }
            if (request.getStatus() == ApprovalStatus.APPROVED
                && request.getExecutionResult() == null
                && request.getDecidedAt() != null
                && request.getDecidedAt().isAfter(now.minus(REPLAY_GRACE))) {
                return request;
            }
        }
        return null;
    }

    /**
     * Closes the half of terminalisation the first cut left open: the parked
     * job's blocking {@code approval_request} rows were left PENDING forever,
     * and a tenant-admin approving one later made {@code
     * AgentToolCallApprovalExecutor} REPLAY the real, non-idempotent tool call
     * for a job already FAILED or CANCELLED.
     *
     * <p>Goes through {@link HumanApprovalGateService#reject} - the OWNING
     * service, never a direct table write - so the decision is durable, audited,
     * and {@code approve} then throws on the now-non-PENDING row. Best-effort
     * per row and strictly AFTER the job's own terminal write committed: a
     * rejection failure leaves at worst the old exposure (a stale PENDING row),
     * which {@code AgentToolCallApprovalExecutor}'s terminal-job refusal now
     * backstops anyway. Jobs cancelled synchronously by {@code
     * AgentJobService#cancel} (never seen by this sweep) rely on that same
     * backstop.
     */
    private void rejectOrphanedPendingRequests(AgentJob job, String jobOutcome) {
        String label = AgentJobSession.requestedByLabel(job.getAgentId(), job.getId());
        List<ApprovalRequest> pending;
        try {
            pending = approvalLookup.findByRequestedByAndStatusOrderByCreatedAtAsc(label, ApprovalStatus.PENDING);
        } catch (RuntimeException e) {
            log.warn("Could not look up pending approval requests for terminalised agent job {} - a stale PENDING "
                + "request may remain approvable until the executor's terminal-job check refuses it", job.getId(), e);
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        HumanApprovalGateService gate = approvalGate.getIfAvailable();
        if (gate == null) {
            log.warn("No approval gate service is available - {} pending approval request(s) for terminalised agent "
                + "job {} stay PENDING", pending.size(), job.getId());
            return;
        }
        for (ApprovalRequest request : pending) {
            try {
                gate.reject(
                    request.getId(), SWEEP_ACTOR,
                    "The agent job that asked for this approval was " + jobOutcome + " before anyone decided, so "
                        + "nothing is waiting on this request any more. Approving it now would execute the gated "
                        + "action for a job that is already terminal. Re-run the job if the work is still wanted.");
                log.info("Rejected orphaned approval request {} for terminalised agent job {}",
                    request.getId(), job.getId());
            } catch (RuntimeException e) {
                // A decided-in-the-same-instant race lands here
                // (ApprovalRequestAlreadyDecidedException) and is fine: decided
                // is decided. Anything else is logged and left to the
                // executor-side terminal-job refusal.
                log.warn("Could not reject orphaned approval request {} for terminalised agent job {}",
                    request.getId(), job.getId(), e);
            }
        }
    }

    private boolean dispatch(AgentJob job) {
        if (properties.getRunNowMode() == AgentJobProperties.RunNowMode.ENQUEUE) {
            // This JVM does not run jobs (run-now-mode=enqueue). The event-driven
            // fast path can still fire here; the row stays parked and the jobs
            // shape's sweep resumes it on its next tick.
            return false;
        }
        AgentJobExecutor pool = executor.getIfAvailable();
        if (pool == null) {
            // Only reachable in a slim context with no executor bean; in the
            // real application this is always present.
            log.warn("No agent job executor is available - parked job {} stays parked", job.getId());
            return false;
        }
        return pool.tryDispatch(job.getId(), job.getTenantId());
    }

    /** Floored at 1 for the same reason the detector floors it: {@code 0} must not mean "never park". */
    private int parkCap() {
        return Math.max(1, properties.getApproval().getMaxParksPerJob());
    }
}
