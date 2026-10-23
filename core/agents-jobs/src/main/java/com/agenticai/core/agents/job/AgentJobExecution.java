package com.agenticai.core.agents.job;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.TenantUnavailableException;
import com.agenticai.core.agents.job.output.StructuredOutputViolationException;
import com.agenticai.core.agents.job.ownership.JobOwnership;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What actually runs on a worker thread: claim the job, run the agent turn, write a terminal row.
 * One instance, called concurrently from {@link AgentJobExecutor}'s pool - it holds no per-job
 * state in fields.
 *
 * <h2>Tenant context is the highest-probability regression in this whole workstream. Read this
 * before changing anything below.</h2>
 *
 * {@link TenantContext} is a plain {@code ThreadLocal}. It does NOT survive the hop from the
 * request thread to a pool thread (this is the same root cause {@code
 * TenantContextPropagatingToolCallback}'s javadoc documents for the hop onto Reactor's schedulers).
 * Three traps, each already paid for once in this repo's history:
 *
 * <ol>
 *   <li><b>Set it BEFORE the first repository read.</b> This is verbatim the 2026-08-10
 *       document-generation incident: 100% of jobs sat in their initial state for three days
 *       because {@code findById} ran on a fresh async thread with no context, {@code
 *       TenantSchemaResolver} fell back to the empty {@code public} schema, and the job
 *       "successfully" found nothing to do.
 *   <li><b>The {@code finally} restore is mandatory here in a way it is not on a fresh virtual
 *       thread.</b> These threads are POOLED and REUSED, so a missed restore is not a leak that
 *       dies with the thread - it is one tenant's context serving the very next job, which may be a
 *       different tenant's.
 *   <li><b>{@code Thread.interrupted()} in the {@code finally}.</b> A cancelled job leaves its
 *       worker thread interrupted. Returning that thread to the pool still flagged makes the NEXT,
 *       unrelated job die instantly with what will look exactly like a flaky upstream error.
 * </ol>
 *
 * Production canary: running jobs must produce ZERO increments of {@code
 * tenant_context_public_fallback_total} attributed to this class.
 *
 * <p>The job id deliberately does NOT go into {@code TenantContext.externalTriggerId}: that field
 * means "an external credential authenticated this request" and {@code LoggingToolCallback}
 * persists it as {@code mcp_grant_id}. Job attribution rides on {@code sessionId} (see {@link
 * AgentJobSession}), which costs nothing and lies about nothing.
 */
@Component
public class AgentJobExecution {

    private static final Logger log = LoggerFactory.getLogger(AgentJobExecution.class);

    /**
     * The identity used only for the two reads that happen BEFORE the row's own frozen requester
     * authority is known (load, and claim). It carries no roles - it exists purely to route the
     * schema, and is replaced by the real requester context before a single line of agent work
     * runs.
     */
    private static final String BOOTSTRAP_ACTOR = "system:agent-job-bootstrap";

    private final AgentJobRepository repository;
    private final MeterRegistry meterRegistry;

    /**
     * The two seams that keep this file closed to the feature packages: {@link
     * AgentJobOutputContract} (structured output) and {@link AgentJobApprovalPark}
     * (human-in-the-loop parking). Resolved ONCE at construction from an {@code ObjectProvider},
     * defaulting to the no-op constants - so with no implementation registered, every line below
     * behaves exactly as it did before the seams existed.
     */
    private final AgentJobOutputContract outputContract;

    private final AgentJobApprovalPark approvalPark;

    /** The turn loop and the single agent turn - see {@link AgentJobTurns}. */
    private final AgentJobTurns turns;

    /** Terminal writes, cancel reads and cost - see {@link AgentJobTerminalWrites}. */
    private final AgentJobTerminalWrites terminal;

    /**
     * L1 step journal. Optional, set by setter so no constructor arity changes: null (unit tests,
     * slim contexts) or disabled => {@link AgentJobTurns#runTurns} is exactly the pre-journal loop.
     */
    private volatile StepJournal stepJournal;

    @Autowired(required = false)
    public void setStepJournal(StepJournal stepJournal) {
        this.stepJournal = stepJournal;
        turns.setStepJournal(stepJournal);
    }

    /**
     * Builds the first turn of a job resumed after an approval park from the decisions it was
     * parked on. Optional setter, like the journal: null (unit tests, slim contexts) => a resumed
     * job re-sends its goal, as before.
     */
    @Autowired(required = false)
    public void setApprovalResume(AgentJobApprovalResumption approvalResume) {
        turns.setApprovalResume(approvalResume);
    }

    /**
     * Cross-replica ownership (app.agent-jobs.ownership.*). Optional setter, like the journal: null
     * or disabled => owner id, terminal writes and interrupt handling are exactly the pre-ownership
     * behaviour.
     */
    @Autowired(required = false)
    public void setOwnership(JobOwnership ownership) {
        terminal.setOwnership(ownership);
    }

    /**
     * Tells feature listeners (research run meter) a job went terminal. Optional: null in unit
     * tests.
     */
    @Autowired(required = false)
    public void setOutcomes(AgentJobOutcomes outcomes) {
        terminal.setOutcomes(outcomes);
    }

    /**
     * Where a finished job's cost comes from: the tenant's durable {@code llm_call_log}. Optional
     * setter, like the journal: null (unit tests, slim contexts) => the job simply records no cost,
     * as before.
     */
    @Autowired(required = false)
    public void setLlmCallLogService(LlmCallLogService llmCallLogService) {
        terminal.setLlmCallLogService(llmCallLogService);
    }

    /** See {@link AgentJobTerminalWrites#observedCostUsd}. */
    BigDecimal observedCostUsd(AgentJob job) {
        return terminal.observedCostUsd(job);
    }

    /**
     * {@code ObjectProvider} rather than {@code @ConditionalOnMissingBean}: conditional ordering
     * against component-scanned beans is unreliable, while {@code getIfAvailable(default)} is a
     * plain runtime lookup with no ordering question at all.
     */
    @Autowired
    public AgentJobExecution(
            AgentJobRepository repository,
            AgentChatService agentChatService,
            ToolCallLogRepository toolCallLogRepository,
            AgentJobProperties properties,
            MeterRegistry meterRegistry,
            AgentJobProgressService progressService,
            ObjectProvider<AgentJobOutputContract> outputContractProvider,
            ObjectProvider<AgentJobApprovalPark> approvalParkProvider) {
        this(
                repository,
                agentChatService,
                toolCallLogRepository,
                properties,
                meterRegistry,
                progressService,
                outputContractProvider.getIfAvailable(() -> AgentJobOutputContract.PASS_THROUGH),
                approvalParkProvider.getIfAvailable(() -> AgentJobApprovalPark.NEVER_PARKS));
    }

    /**
     * Seam-free constructor, for the unit suite (which builds this class directly with plain
     * Mockito mocks and no Spring context). Kept so that introducing the two seams did not require
     * editing seven existing test classes owned by other workstreams - a compile break there would
     * have no relationship to what those tests assert.
     */
    public AgentJobExecution(
            AgentJobRepository repository,
            AgentChatService agentChatService,
            ToolCallLogRepository toolCallLogRepository,
            AgentJobProperties properties,
            MeterRegistry meterRegistry,
            AgentJobProgressService progressService) {
        this(
                repository,
                agentChatService,
                toolCallLogRepository,
                properties,
                meterRegistry,
                progressService,
                AgentJobOutputContract.PASS_THROUGH,
                AgentJobApprovalPark.NEVER_PARKS);
    }

    private AgentJobExecution(
            AgentJobRepository repository,
            AgentChatService agentChatService,
            ToolCallLogRepository toolCallLogRepository,
            AgentJobProperties properties,
            MeterRegistry meterRegistry,
            AgentJobProgressService progressService,
            AgentJobOutputContract outputContract,
            AgentJobApprovalPark approvalPark) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.outputContract = outputContract;
        this.approvalPark = approvalPark;
        this.turns =
                new AgentJobTurns(
                        repository,
                        agentChatService,
                        toolCallLogRepository,
                        properties,
                        meterRegistry,
                        progressService,
                        outputContract);
        this.terminal = new AgentJobTerminalWrites(repository, meterRegistry);
    }

    /**
     * Entry point for a worker thread. Never throws: a job's failure is a ROW, not an exception
     * escaping into a thread pool where the only place it could surface is a swallowed {@code
     * Future}.
     *
     * @param tenantId the schema to run in, taken FROM THE ROW at dispatch time. Passed in rather
     *     than captured from a {@code TenantContext} object because the fast path (in-memory
     *     dispatch from the POST) and the slow path (the poller, or a boot resume, where no
     *     captured object exists at all) must be ONE code path - otherwise only one of the two ever
     *     gets exercised in practice, and it will not be the one that runs at 3am.
     */
    public void run(UUID jobId, String tenantId) {
        Optional<TenantContext> previous = TenantContext.current();
        TenantContext.set(TenantContext.of(tenantId, BOOTSTRAP_ACTOR, Set.of()));
        MDC.put("agentJobId", jobId.toString());
        MDC.put("tenantId", tenantId);
        try {
            execute(jobId, tenantId);
        } catch (RuntimeException | Error unexpected) {
            log.error("Agent job {} died outside its own error handling", jobId, unexpected);
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
            MDC.remove("agentJobId");
            MDC.remove("tenantId");
            // See trap (3) in the class javadoc.
            Thread.interrupted();
        }
    }

    private void execute(UUID jobId, String tenantId) {
        AgentJob job = repository.findById(jobId).orElse(null);
        if (job == null) {
            // Not an error worth alarming on: the row can legitimately be gone
            // if the tenant's schema was erased between dispatch and execution.
            log.warn("Agent job {} vanished before it could be claimed", jobId);
            return;
        }

        Instant startedAt = Instant.now();
        Instant deadlineAt = startedAt.plusSeconds(job.getTimeoutSeconds());
        // Read BEFORE the claim: the loaded entity is the pre-claim row (the claim is a guarded
        // UPDATE, and a
        // test mock's markRunning bumps attemptCount), so only here do QUEUED + attemptCount>=1
        // mean "an
        // orphan the recovery policy re-queued" - as opposed to AWAITING_APPROVAL (a human-approved
        // park
        // resume, whose parked turn must NOT be replayed) or a never-run QUEUED job (attemptCount
        // 0).
        boolean orphanRequeue =
                job.getStatus() == AgentJobStatus.QUEUED && job.getAttemptCount() >= 1;
        // A park resume: the job waited on a human and this run follows the decision. Read before the
        // claim for the same reason as orphanRequeue (the claim moves the row to RUNNING).
        boolean resumedFromPark = job.getStatus() == AgentJobStatus.AWAITING_APPROVAL;
        boolean freshRun =
                job.getStatus() == AgentJobStatus.QUEUED
                        && job.getAttemptCount() == 0
                        && job.getTurnsUsed() == 0;
        if (!claimForExecution(job, startedAt, deadlineAt)) {
            // Another worker (or replica) won, or the row is in a state this
            // worker has no business touching. Not an error - this is the claim
            // race resolving exactly as designed.
            return;
        }

        // From here on the job runs with the REQUESTER'S authority, frozen at
        // submit time - never the bootstrap identity, and never elevated system
        // authority. This is what makes it structurally impossible for a job to
        // do more than the human who asked for it.
        TenantContext.set(
                TenantContext.of(tenantId, job.getRequestedBy(), job.getRequestedByRoleSet()));
        meterRegistry.counter("agent.job.started").increment();

        try {
            if (terminal.observedCancel(jobId)) {
                if (terminal.countable(terminal.finish(jobId, j -> j.markCancelled(Instant.now())))) {
                    meterRegistry.counter("agent.job.cancelled").increment();
                }
                return;
            }

            StepJournal freshJournal = stepJournal;
            if (freshRun && freshJournal != null && freshJournal.isEnabled()) {
                // A new run must never see another run's journal rows under a reused job id.
                freshJournal.purgeJobRun(job.getId());
            }
            // Blocker 3: a journal-resumed attempt must still see an approval gate its PREVIOUS
            // attempt tripped
            // (the request was created before this attempt's startedAt, so the detector would
            // ignore it and the
            // job would finish SUCCEEDED with prose about an action that never ran). Widen the
            // detector's
            // "caused by this turn" boundary back to when the resumed turn first STARTED, per the
            // journal.
            Instant parkBoundary = turns.resumedTurnStart(job, orphanRequeue, startedAt);
            String rawResult = turns.runTurns(job, deadlineAt, orphanRequeue, resumedFromPark);

            if (terminal.observedCancel(jobId)) {
                if (terminal.countable(terminal.finish(jobId, j -> j.markCancelled(Instant.now())))) {
                    meterRegistry.counter("agent.job.cancelled").increment();
                }
                return;
            }

            // The park check sits BEFORE finalization on purpose: a job that
            // stopped at a human approval gate has no final answer, so
            // validating one would fail it for a schema violation it never had
            // the chance to satisfy.
            if (parkIfGated(job, parkBoundary)) {
                return;
            }

            // Structured output plugs in exactly here, between the final text
            // and the terminal write. With no implementation registered this is
            // pass-through: the text is persisted unchanged, result_json stays
            // null and schema_attempts stays 0, exactly as before.
            //
            // REPAIR TURNS ARE TEXT-ONLY (chatTextOnly): the agent's tool set is
            // withheld and no extraTools are passed, so a repair turn cannot
            // re-execute a side-effecting tool the goal turn already ran, cannot
            // trip a NEW approval gate of its own, and cannot call
            // generate_document and feed the PR-3 event-trigger loop with a
            // document.generation.completed it minted itself. A repair's entire
            // job is reshaping an answer that already exists into conforming
            // JSON - it never needed tools, it merely had them.
            AgentJobOutputContract.FinalResult finalResult;
            try {
                finalResult =
                        outputContract.finalizeResult(
                                job,
                                rawResult,
                                prompt -> {
                                    // The ceiling is re-checked here so a repair turn can never
                                    // dodge the per-job tool budget the goal turns obey. (With
                                    // tool-less repair turns this can only trip on calls the
                                    // GOAL turns already made - kept because it is one cheap
                                    // COUNT and removing it would silently couple this seam's
                                    // safety to the text-only property staying true.)
                                    turns.guardBeforeTurn(job);
                                    return turns.runOneTurn(job, deadlineAt, prompt, true);
                                });
            } catch (StructuredOutputViolationException violation) {
                // Defensive, not load-bearing since repair turns became
                // text-only: a repair turn can no longer trip a gate of its own.
                // Kept because the check is one indexed query on a path that only
                // runs when a schema job is already failing, and because it also
                // catches a gate the GOAL turn tripped that was recorded a
                // heartbeat after the first parkIfGated read - parking beats
                // failing whenever a gated action is genuinely waiting.
                if (parkIfGated(job, parkBoundary)) {
                    return;
                }
                throw violation;
            }

            // Second park checkpoint, same defensive posture as the catch above
            // now that repair turns are tool-less. Cheap: the detector's query
            // only runs when a schema contract is registered AND repair turns
            // actually ran, because otherwise the first check already covered
            // the same window.
            if (finalResult.schemaAttempts() > 1 && parkIfGated(job, parkBoundary)) {
                return;
            }

            BigDecimal costUsd = terminal.observedCostUsd(job);
            terminal.finish(
                    jobId,
                    j ->
                            j.succeed(
                                    finalResult.resultText(),
                                    finalResult.resultJson(),
                                    costUsd,
                                    finalResult.schemaAttempts(),
                                    Instant.now()));
            meterRegistry.counter("agent.job.completed", "outcome", "SUCCEEDED").increment();
            recordDuration(startedAt);

        } catch (CancellationException | InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            if (terminal.interruptedByShutdown()) {
                log.info(
                        "Agent job {} interrupted by shutdown of this replica; leaving it RUNNING"
                                + " for adoption",
                        jobId);
                return;
            }
            if (terminal.countable(terminal.finish(jobId, j -> j.markCancelled(Instant.now())))) {
                meterRegistry.counter("agent.job.cancelled").increment();
            }

        } catch (com.agenticai.core.agents.spendcap.SpendCapReachedException spendCap) {
            // Monthly hosted spend cap: deterministic until the 1st, so fail once - never retry.
            terminal.failWith(jobId, "SPEND_CAP_REACHED", spendCap.getMessage());

        } catch (PerUserBudgetExhaustedException budget) {
            // The identical ceiling and the identical message a chat turn would
            // show. A job must never be a way around a spend cap, and must never
            // disguise one as a mysterious internal failure either.
            terminal.failWith(jobId, "BUDGET_EXHAUSTED", budget.getMessage());

        } catch (AgentNotFoundException agentGone) {
            // The documented consequence of agent_id deliberately not being a
            // foreign key: the job fails visibly instead of the delete failing.
            terminal.failWith(jobId, "AGENT_NOT_FOUND", agentGone.getMessage());

        } catch (TenantUnavailableException stopped) {
            terminal.failWith(jobId, "TENANT_UNAVAILABLE", stopped.getMessage());

        } catch (AgentJobTimeoutException timedOut) {
            terminal.failWith(jobId, "TIMEOUT", timedOut.getMessage());
            meterRegistry.counter("agent.job.timed_out").increment();

        } catch (AgentJobToolLimitException toolLimit) {
            terminal.failWith(jobId, "TOOL_LIMIT_EXCEEDED", toolLimit.getMessage());

        } catch (StructuredOutputViolationException violation) {
            // MUST stay above the generic RuntimeException clause below, which
            // would otherwise relabel it EXECUTION_FAILED. A job that declared a
            // schema and could not satisfy it fails EXPLICITLY - it must never
            // succeed with prose that the caller then has to parse.
            terminal.failWith(jobId, "SCHEMA_VIOLATION", violation.getMessage());

        } catch (RuntimeException e) {
            if (ModelUrlRefusal.failIfRefused(e, jobId, terminal::failWith)) return;
            // Interrupt-driven failures surface from Reactor as an assortment of
            // wrapper types, so the interrupt flag is the reliable signal that
            // this was a cancel rather than a real fault.
            if (Thread.currentThread().isInterrupted() && terminal.interruptedByShutdown()) {
                log.info(
                        "Agent job {} interrupted by shutdown of this replica; leaving it RUNNING"
                                + " for adoption",
                        jobId);
                return;
            }
            if (Thread.currentThread().isInterrupted() || terminal.observedCancel(jobId)) {
                if (terminal.countable(terminal.finish(jobId, j -> j.markCancelled(Instant.now())))) {
                    meterRegistry.counter("agent.job.cancelled").increment();
                }
                return;
            }
            log.error("Agent job {} failed", jobId, e);
            terminal.failWith(jobId, "EXECUTION_FAILED", messageOf(e));
        }
    }

    /**
     * Asks the approval seam whether this job stopped at a human gate and, if it did, performs the
     * durable park.
     *
     * @return {@code true} when the caller must return WITHOUT writing a terminal state. Returning
     *     without {@code finish()} is what "releases the worker without terminating the job" means
     *     mechanically: the pool wrapper's {@code finally} frees the slot and drops this id from
     *     {@code active}, the heartbeat stops touching the row, and {@code isActiveHere()} goes
     *     false.
     */
    private boolean parkIfGated(AgentJob job, Instant turnStartedAt) {
        Optional<AgentJobApprovalPark.ParkDecision> park =
                approvalPark.parkIfGated(job, turnStartedAt);
        if (park.isEmpty()) {
            return false;
        }
        AgentJobApprovalPark.ParkDecision decision = park.get();
        // The caller performs the durable park; the seam only answers the
        // question. One status writer per transition.
        if (repository.parkForApproval(
                        job.getId(),
                        Instant.now(),
                        decision.approvalDeadlineAt(),
                        decision.reason())
                > 0) {
            meterRegistry.counter("agent.job.parked").increment();
            log.info(
                    "Agent job {} parked on a human approval gate until {} (request {}): {}",
                    job.getId(),
                    decision.approvalDeadlineAt(),
                    decision.approvalRequestId(),
                    decision.reason());
        }
        return true;
    }

    /**
     * Claims the row for THIS worker, in the one guarded UPDATE appropriate to the state it is
     * actually in.
     *
     * <p>A resume is not a re-claim of the same execution: it takes a FRESHLY computed deadline
     * rather than reusing the one from before the park (which is long past for a job that waited
     * hours for a human, so the deadline sweep would kill it within 15 seconds), and it re-stamps
     * {@code heartbeat_at}, which the park deliberately NULLed.
     *
     * <p>Rowcount 0 from either statement means somebody else won. Every other status is simply not
     * this worker's business - a RUNNING row belongs to whoever claimed it, and a terminal row is
     * finished.
     */
    private boolean claimForExecution(AgentJob job, Instant now, Instant deadlineAt) {
        return switch (job.getStatus()) {
            case QUEUED -> repository.claim(job.getId(), terminal.ownerId(), now, deadlineAt) > 0;
            case AWAITING_APPROVAL -> {
                boolean claimed =
                        repository.resumeClaim(job.getId(), terminal.ownerId(), now, deadlineAt) > 0;
                if (claimed) {
                    log.info(
                            "Agent job {} resumed from approval on {} (park #{}, new deadline {})",
                            job.getId(),
                            terminal.ownerId(),
                            job.getParkCount(),
                            deadlineAt);
                }
                yield claimed;
            }
            case RUNNING, SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    private void recordDuration(Instant startedAt) {
        meterRegistry
                .timer("agent.job.duration")
                .record(Duration.between(startedAt, Instant.now()));
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : e.getClass().getSimpleName();
    }

    /**
     * The in-band half of the wall clock (see {@link AgentJobRunner#enforceDeadlines} for why an
     * out-of-band sweep is ALSO required and is not redundant with this).
     */
    static final class AgentJobTimeoutException extends RuntimeException {
        AgentJobTimeoutException(int timeoutSeconds) {
            super("Agent job exceeded its " + timeoutSeconds + "s wall-clock budget");
        }
    }

    /** The per-job tool ceiling, checked at turn boundaries - see {@link AgentJobTurns#runTurns}. */
    static final class AgentJobToolLimitException extends RuntimeException {
        AgentJobToolLimitException(int maxToolCalls) {
            super("Agent job reached its ceiling of " + maxToolCalls + " tool calls");
        }
    }
}
