package com.agenticai.core.agents.job;

import com.agenticai.core.agents.job.AgentJobExecution.AgentJobTimeoutException;
import com.agenticai.core.agents.job.AgentJobExecution.AgentJobToolLimitException;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.job.progress.ReportProgressToolCallback;
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.tools.TenantContextPropagatingToolCallback;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/**
 * The turn machinery of {@link AgentJobExecution}: the turn loop, the step-journal resume, the
 * per-job tool ceiling and the single blocking agent turn. Split out of that class to keep it
 * readable; not a bean - one instance is owned by each {@link AgentJobExecution} and runs on that
 * class's worker threads, so the tenant-context rules in its javadoc apply here unchanged. Logs
 * under {@link AgentJobExecution}'s logger so log routing is unchanged by the split.
 */
final class AgentJobTurns {

    private static final Logger log = LoggerFactory.getLogger(AgentJobExecution.class);

    private final AgentJobRepository repository;
    private final AgentChatService agentChatService;
    private final ToolCallLogRepository toolCallLogRepository;
    private final AgentJobProperties properties;
    private final MeterRegistry meterRegistry;
    private final AgentJobProgressService progressService;
    private final AgentJobOutputContract outputContract;

    /** See {@link AgentJobExecution#setStepJournal}: null or disabled => the pre-journal loop. */
    private volatile StepJournal stepJournal;

    AgentJobTurns(
            AgentJobRepository repository,
            AgentChatService agentChatService,
            ToolCallLogRepository toolCallLogRepository,
            AgentJobProperties properties,
            MeterRegistry meterRegistry,
            AgentJobProgressService progressService,
            AgentJobOutputContract outputContract) {
        this.repository = repository;
        this.agentChatService = agentChatService;
        this.toolCallLogRepository = toolCallLogRepository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.progressService = progressService;
        this.outputContract = outputContract;
    }

    void setStepJournal(StepJournal stepJournal) {
        this.stepJournal = stepJournal;
    }

    /**
     * The park detector's lower bound for "an approval request this attempt's turn caused".
     * Normally this attempt's own start. For a journal-resumed orphan (only then) it is the
     * journaled start of the turn that is being resumed, if that is earlier, so a PENDING approval
     * the dead attempt created is found and the job is parked again instead of being finalised
     * SUCCEEDED. Never later than {@code attemptStart}.
     */
    Instant resumedTurnStart(AgentJob job, boolean orphanRequeue, Instant attemptStart) {
        StepJournal journal = stepJournal;
        if (!orphanRequeue
                || journal == null
                || !journal.isEnabled()
                || job.getTurnsUsed() <= 0
                || !properties.getDurability().isResumeFromJournal()) {
            return attemptStart;
        }
        try {
            Instant turnStart =
                    journal.find(
                                    StepJournal.runIdForJob(job.getId()),
                                    StepJournal.turnStepId(job.getTurnsUsed() - 1))
                            .map(StepJournal.Step::createdAt)
                            .orElse(null);
            return turnStart != null && turnStart.isBefore(attemptStart) ? turnStart : attemptStart;
        } catch (RuntimeException e) {
            return attemptStart;
        }
    }

    /**
     * The turn loop. This PR ships {@code max_turns = 1}, so it executes exactly once - it is
     * written as a loop anyway because {@code turns_used} is a durable column and the per-job tool
     * ceiling is checked at TURN BOUNDARIES, and retrofitting both into a straight-line call later
     * would mean rewriting the error handling around it.
     *
     * <p><b>The per-job tool-call ceiling is enforced at turn granularity, and that limitation is
     * deliberate rather than an oversight.</b> Exact per-job counting would require an overload of
     * {@code AgentToolProvider#resolveToolsFor}, and that file is owned by a parallel session this
     * workstream must not touch. Within a single turn, the existing {@code
     * app.guardrails.max-tool-calls-per-turn} ceiling (enforced by {@code GuardedToolCallback},
     * unchanged) still applies, so there is no unbounded window - only a coarser one. With {@code
     * max_turns = 1} the two ceilings coincide exactly.
     */
    String runTurns(AgentJob job, Instant deadlineAt, boolean orphanRequeue)
            throws InterruptedException {
        String lastResult = null;
        // Each park buys exactly ONE more turn. Without this, a resumed job
        // (turns_used already 1, max_turns 1) would run ZERO turns and land
        // succeed(null, ...) - a silent empty success right after a human took
        // the trouble to approve something. park_count is durable on the row and
        // is itself capped by app.agent-jobs.approval.max-parks-per-job, so this
        // allowance cannot run away.
        int maxTurns = Math.max(1, job.getMaxTurns()) + job.getParkCount();

        // L1 step journal (flag-gated, default OFF => none of this runs). An ORPHAN-REQUEUED
        // attempt
        // (orphanRequeue, computed before the claim; NEVER an approval-park resume, whose parked
        // turn is
        // STARTED-only and must not be replayed after a human approved it) that already started
        // turns
        // consults the journal for the last started turn:
        //  - COMPLETED: reuse its result; never call the model again for it. If no further turn is
        // owed the
        //    loop below is skipped and this result is returned (without this a re-queued job at
        //    turns_used == max_turns would run ZERO turns and succeed with an empty result).
        //  - STARTED only: re-run that turn iff resume-rerun-incomplete-turn is on, WITHOUT
        // incrementing
        //    turns_used again (it already counts this turn).
        StepJournal journal = stepJournal;
        boolean journaling = journal != null && journal.isEnabled();
        String runId = StepJournal.runIdForJob(job.getId());
        int firstTurn = job.getTurnsUsed();
        boolean rerunFirstTurn = false;
        AgentJobDurabilityProperties durability = properties.getDurability();
        if (journaling && durability.isResumeFromJournal() && firstTurn > 0 && orphanRequeue) {
            Optional<StepJournal.Step> prev =
                    journal.find(runId, StepJournal.turnStepId(firstTurn - 1));
            if (prev.isPresent() && prev.get().completedWithResult()) {
                lastResult = prev.get().result();
                meterRegistry
                        .counter("agent.job.resumed_from_journal", "mode", "completed-turn")
                        .increment();
                log.info(
                        "Agent job {} resumes from the step journal: turn {} already completed",
                        job.getId(),
                        firstTurn - 1);
            } else if (prev.isPresent() && durability.isResumeRerunIncompleteTurn()) {
                // Blocker 1 (defence in depth: the recovery policy already refuses to re-queue
                // this): never
                // re-run a turn that has a DURABLE tool call STARTED without COMPLETED.
                if (!journal.isDurableCallTrackingActive()
                        || journal.hasIncompleteDurableCall(job.getId())) {
                    throw new IllegalStateException(
                            "Not re-running turn "
                                    + (firstTurn - 1)
                                    + ": a write/send tool call of this job started but never"
                                    + " recorded completion, so it may already have taken effect."
                                    + " Review the external system and re-submit if the work is"
                                    + " still wanted");
                }
                firstTurn = firstTurn - 1;
                rerunFirstTurn = true;
                meterRegistry
                        .counter("agent.job.resumed_from_journal", "mode", "rerun-turn")
                        .increment();
                log.warn(
                        "Agent job {} re-runs incomplete turn {} from the step journal",
                        job.getId(),
                        firstTurn);
            }
        }

        if (firstTurn >= maxTurns
                && orphanRequeue
                && journaling
                && durability.isResumeFromJournal()
                && (lastResult == null || lastResult.isBlank())) {
            // Blocker 2: no turn is owed and the journal gave no usable completed result. Finishing
            // here would
            // write SUCCEEDED with a null/empty result; fail loudly instead.
            throw new IllegalStateException(
                    "Cannot resume agent job "
                            + job.getId()
                            + " from the step journal: its last turn has no recorded result and no"
                            + " further turn is owed. Re-submit the job if the work is still"
                            + " wanted");
        }

        for (int turn = firstTurn; turn < maxTurns; turn++) {
            guardBeforeTurn(job);

            // THE DURABLE "A TURN HAS STARTED" MARKER, AND IT IS WRITTEN BEFORE
            // THE MODEL CALL ON PURPOSE. turns_used counts turns STARTED, not
            // turns completed.
            //
            // This ordering is the whole basis of PR-4's no-double-charge
            // argument, and the reverse ordering silently broke it. Money is
            // taken BEFORE a provider is ever attempted: buildChainForFreeTenant
            // takes a hosted-pool token reservation and a
            // PerUserBudgetService reservation ahead of the call (see
            // FreeTierUsageLimiterService#checkAndReserveTokenBudget's
            // reserve-before-call contract, and AgentChatService's release path,
            // which only runs on a graceful failure). A worker SIGKILLed during the
            // model call - the longest and by far the likeliest interruption
            // window - runs no refund path at all. With the increment after the
            // call, such a job was left at turns_used = 0 with no tool_call_log
            // rows, ZeroWorkOrphanRecoveryPolicy read that as "provably zero
            // work", re-queued it, and the requester paid twice for one request.
            // Incremented first, the row itself records that the spend window
            // was entered, and the recovery policy fails it loudly instead.
            //
            // In-memory for this loop's own bookkeeping, and a targeted UPDATE
            // for the durable counter. Deliberately NOT repository.save(job):
            // this entity was loaded before a turn that may have run for minutes,
            // and writing it back whole would reset any cancel_requested set
            // during that turn - see AgentJobRepository#incrementTurnsUsed.
            if (rerunFirstTurn && turn == firstTurn) {
                // turns_used already counts this turn (see journal block above).
                rerunFirstTurn = false;
            } else {
                job.incrementTurn();
                repository.incrementTurnsUsed(job.getId());
            }

            String stepId = StepJournal.turnStepId(turn);
            if (journaling) {
                journal.begin(runId, stepId, StepJournal.KIND_TURN);
            }
            lastResult = runOneTurn(job, deadlineAt, outputContract.effectiveGoal(job), false);
            if (journaling) {
                journal.complete(runId, stepId, StepJournal.KIND_TURN, lastResult);
            }
        }
        return lastResult;
    }

    /**
     * The per-job tool-call ceiling, checked before EVERY model call - both goal turns and
     * structured-output repair turns.
     *
     * <p>Extracted from the turn loop precisely so the repair path cannot dodge it: a repair turn
     * is a real model call with real tools attached, and a ceiling only the loop enforced would be
     * a ceiling a schema-repairing job walked straight past.
     */
    void guardBeforeTurn(AgentJob job) {
        long toolCallsSoFar =
                toolCallLogRepository.countByAgentIdAndSessionId(
                        job.getAgentId(), job.getSessionId());
        if (toolCallsSoFar >= job.getMaxToolCalls()) {
            throw new AgentJobToolLimitException(job.getMaxToolCalls());
        }
    }

    /**
     * One agent turn, blocking until the stream completes or the wall clock runs out.
     *
     * <p>{@code subjectKey} is the job's own {@code requested_by}, and that is NOT cosmetic. {@code
     * PerUserBudgetService} keys its spend window on (tenant, subject, modality); a synthetic
     * per-job subject would mint a fresh daily dollar ceiling for every single submission, which
     * would make a job literally BE the budget escape hatch this design forbids.
     *
     * <p>{@code extraTools} is empty in this PR. It is the seam - and the ONLY seam needed -
     * through which the OBSERVABILITY PR adds its {@code report_progress} callback, which is
     * precisely why neither {@code AgentChatService} nor {@code AgentToolProvider} needs editing
     * anywhere in this workstream. Anything added here must be wrapped in {@code
     * TenantContextPropagatingToolCallback}: {@code extraTools} are added RAW, without the {@code
     * guardAndLog} wrapping the resolved tool list gets, so an unwrapped callback would write to
     * the empty {@code public} schema.
     *
     * @param messageText what the model is actually asked. Parameterised rather than always {@code
     *     job.getGoal()} so that the SAME turn machinery - the deadline check, the progress tool,
     *     the timeout relabelling - backs both a goal turn and a structured-output repair turn. A
     *     repair path that called {@code agentChatService.chat} itself would silently have none of
     *     them.
     * @param textOnly {@code true} for a structured-output REPAIR turn: the turn goes through
     *     {@link AgentChatService#chatTextOnly}, which withholds the agent's whole tool set and
     *     takes no extraTools (not even the progress callback - "no tools" means none), so a repair
     *     turn cannot re-fire a side-effecting tool, trip a fresh approval gate, or publish an
     *     event that feeds a trigger loop. Goal turns pass {@code false} and are byte-identical to
     *     before this parameter existed.
     */
    String runOneTurn(AgentJob job, Instant deadlineAt, String messageText, boolean textOnly)
            throws InterruptedException {
        Duration remaining = Duration.between(Instant.now(), deadlineAt);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new AgentJobTimeoutException(job.getTimeoutSeconds());
        }
        if (textOnly) {
            try {
                // The tool-less turn chatTextOnly builds, with the turn context made here
                // (ADR-0022 stage 4) from the tenant this job runs under.
                ChatTurnRequest textOnlyTurn =
                        ChatTurnRequest.builder(
                                        job.getAgentId(),
                                        job.getSessionId(),
                                        job.getSubjectKey(),
                                        messageText)
                                .suppressAgentTools(true)
                                .build();
                return agentChatService
                        .chat(
                                TurnContext.forEntry(
                                        TenantContext.current().orElse(null), textOnlyTurn),
                                textOnlyTurn)
                        .collect(Collectors.joining())
                        .block(remaining);
            } catch (IllegalStateException blockFailure) {
                // Same clock-confirmed relabelling as the tool-enabled path below.
                if (!Instant.now().isBefore(deadlineAt)) {
                    throw new AgentJobTimeoutException(job.getTimeoutSeconds());
                }
                throw blockFailure;
            }
        }
        // WIRED (integration fix): the foundation built this seam and the
        // observability PR built ReportProgressToolCallback, but nothing
        // connected them - so agent_job_progress was guaranteed empty and a
        // ten-minute job stayed indistinguishable from a hung one, which is the
        // single acceptance criterion progress reporting exists for.
        //
        // The wrap is mandatory, not stylistic: extraTools are passed RAW into
        // the chat client without the guardAndLog wrapping that resolved tools
        // get, and the tool executes later on a reactive thread that does NOT
        // inherit this plain-ThreadLocal TenantContext. Unwrapped, the progress
        // write would land in the empty `public` schema instead of the tenant's.
        List<ToolCallback> extraTools =
                List.of(
                        new TenantContextPropagatingToolCallback(
                                new ReportProgressToolCallback(progressService, job.getId()),
                                TenantContext.current().orElse(null)));

        try {
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(
                                    job.getAgentId(),
                                    job.getSessionId(),
                                    job.getSubjectKey(),
                                    messageText)
                            .extraTools(extraTools)
                            .build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
            return agentChatService
                    .chat(turnContext, chatTurn)
                    .collect(Collectors.joining())
                    .block(remaining);
        } catch (IllegalStateException blockFailure) {
            // Mono#block(Duration) reports its own expiry as an IllegalStateException.
            // Only treat it as a timeout if the clock agrees - otherwise it is a
            // genuine illegal state and must not be relabelled.
            if (!Instant.now().isBefore(deadlineAt)) {
                throw new AgentJobTimeoutException(job.getTimeoutSeconds());
            }
            throw blockFailure;
        }
    }
}
