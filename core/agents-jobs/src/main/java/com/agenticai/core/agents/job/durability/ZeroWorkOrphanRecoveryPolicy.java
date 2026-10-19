package com.agenticai.core.agents.job.durability;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobOrphanRecoveryPolicy;
import com.agenticai.core.agents.job.AgentJobDurabilityProperties;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRunner;
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PR-4's whole decision: which interrupted job may be tried again, and which must be failed loudly
 * instead.
 *
 * <p>Registered as the {@link AgentJobOrphanRecoveryPolicy} implementation that {@link
 * AgentJobRunner#reapOrphans} consults. With this bean absent the sweep falls back to {@link
 * AgentJobOrphanRecoveryPolicy#FAIL_LOUDLY} and behaves byte-for-byte as it shipped; with it
 * present, one narrow class of orphan is re-queued instead and everything else still fails with
 * {@code ORPHANED}.
 *
 * <h2>The idempotency argument - why exactly this condition and no wider one</h2>
 *
 * This is the load-bearing paragraph of PR-4 and the reason the safe answer is "fail", not "retry".
 *
 * <p><b>There is no job-level cost ledger.</b> {@code agent_job.cost_usd} is a column nothing ever
 * writes: {@code AgentJobExecution} calls {@code AgentJob#succeed} with a {@code null} cost. So a
 * resumed job cannot be reconciled after the fact against what its previous attempt already spent -
 * there is nothing recorded to reconcile against.
 *
 * <p><b>Spend is metered per model call, inside the turn.</b> Every {@code AgentChatService#chat}
 * invocation independently consumes a FREE-tier message unit ({@code FreeTierChatClientService})
 * and takes a {@code PerUserBudgetService} reservation keyed on (tenant, subject, TEXT) - and the
 * subject is the real human's budget key on purpose (see {@code AgentJob#queued}'s {@code
 * subjectKey} javadoc: a synthetic per-job subject would mint a fresh daily ceiling per
 * submission). Re-running a job that had already started a turn therefore charges that human a
 * second time for one request, with no record that the first charge happened.
 *
 * <p><b>Tool side effects are not idempotent and {@code tool_call_log} is a log, not a dedupe
 * key.</b> Nothing in the tool path keys on a request id. A re-run of a job that had already called
 * {@code send_channel_message}, {@code generate_document}, an image generation tool or any {@code
 * mcp_tool.*} would fire it a second time. {@code ToolCircuitBreakerService} counts failures, not
 * replays, so it does not catch this either.
 *
 * <p><b>With {@code max_turns = 1}, "resume from the last completed turn" and "re-run the whole
 * job" are arithmetically the same thing.</b> There is no partial-progress state worth resuming
 * from, which is precisely why this class does not attempt a continuation and re-queues the job
 * from the top instead.
 *
 * <p><b>{@code turns_used} counts turns STARTED, and that is what makes this class safe.</b> {@code
 * AgentJobExecution#runTurns} writes the durable increment BEFORE the model call, not after. The
 * ordering is load-bearing rather than stylistic and was a real defect while it was the other way
 * round: spend is taken ahead of the provider attempt ({@code buildChainForFreeTenant} consumes the
 * FREE-tier message unit and takes the {@code PerUserBudgetService} reservation before any provider
 * is tried), and a SIGKILLed process runs none of the refund paths - so a worker killed DURING the
 * model call, which is the longest and by far the likeliest interruption window, used to leave
 * {@code turns_used = 0} with no tool calls logged and was re-queued as "zero work" after the money
 * had already gone. With the increment first, entering the spend window is itself durable.
 *
 * <p>Put together: a job with {@code turns_used == 0} and zero rows in {@code tool_call_log} for
 * its session has, <em>by construction</em>, made no model call and taken no side effect. Its
 * previous attempt died between {@code claim} and the increment that precedes the first {@code
 * chat} - typically a deploy, a SIGKILL or an OOM in that window. Re-queuing it double-charges
 * nothing because nothing was charged, and repeats no side effect because none occurred. That is
 * the entire safety argument, and every clause of {@link #evaluate} exists to keep it true:
 *
 * <ul>
 *   <li>{@code turns_used > 0} - a turn had STARTED, so a model call was at least entered and may
 *       already have been billed even though no answer came back. FAIL.
 *   <li>any {@code tool_call_log} row for {@code job:&lt;id&gt;} - a side effect already happened.
 *       FAIL.
 *   <li>{@code attempt_count >= max-attempts} - bounds recovery so a job that kills its worker
 *       every time cannot loop forever. FAIL.
 *   <li>older than {@code queue-ttl} - a re-queue would be expired by the very next dispatch tick
 *       with {@code QUEUE_TTL_EXPIRED}, turning one clear verdict into two confusing ones. FAIL,
 *       with the honest reason.
 *   <li>{@code cancel_requested} - somebody asked for this to stop. FAIL.
 *   <li>the tool-call lookup itself failing - an <em>unprovable</em> zero-work claim is not a
 *       zero-work claim. FAIL.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * {@link #decide} is called from inside {@link
 * com.agenticai.core.tenancy.TenantSchemaIterator#forEachActiveTenantSchema}, on the shared
 * scheduler thread, with {@link com.agenticai.core.security.TenantContext} ALREADY set to the
 * schema being swept - that is how the orphan row was read in the first place. So the {@code
 * tool_call_log} count below is correctly routed to the same tenant schema, and this class must NOT
 * set or clear the context itself: doing so would clear the iterator's context mid-iteration and
 * send the rest of the sweep at the empty {@code public} schema - the 2026-08-10
 * document-generation failure mode exactly.
 */
@Component
public class ZeroWorkOrphanRecoveryPolicy implements AgentJobOrphanRecoveryPolicy {

    private static final Logger log = LoggerFactory.getLogger(ZeroWorkOrphanRecoveryPolicy.class);

    /**
     * Counts DECISIONS, not outcomes. A {@code verdict=requeue} here can still end as a failure if
     * the guarded {@code requeueIfStillRunning} UPDATE loses to a worker finishing in the same
     * instant - {@link AgentJobRunner} falls back to the {@code ORPHANED} path in that case. Naming
     * it {@code verdict} rather than {@code requeued} keeps that distinction visible on the
     * dashboard instead of quietly overstating what happened.
     */
    private static final String VERDICT_METRIC = "agent.job.recovery.verdict";

    private final AgentJobProperties properties;
    private final ToolCallLogRepository toolCallLogRepository;
    private final MeterRegistry meterRegistry;

    public ZeroWorkOrphanRecoveryPolicy(
            AgentJobProperties properties,
            ToolCallLogRepository toolCallLogRepository,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.toolCallLogRepository = toolCallLogRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Optional (L1): absent => the journal path is never taken and behaviour is exactly as before.
     */
    private volatile StepJournal stepJournal;

    @Autowired(required = false)
    public void setStepJournal(StepJournal stepJournal) {
        this.stepJournal = stepJournal;
    }

    /**
     * Marker returned by {@link #journalResumePoint}: not resumable, and a human must review it.
     */
    private static final String BLOCKED_DURABLE_CALL = "blocked-durable-call";

    /** Needs-review reasons recorded by {@link #decide}, consumed once by the sweep. Bounded. */
    private final java.util.Map<java.util.UUID, String> reviewReasons =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public String takeNeedsReviewReason(AgentJob orphan) {
        return orphan == null || orphan.getId() == null
                ? null
                : reviewReasons.remove(orphan.getId());
    }

    @Override
    public Verdict decide(AgentJob orphan) {
        if (reviewReasons.size() > 1000) {
            reviewReasons.clear();
        }
        Decision decision = evaluate(orphan);
        meterRegistry
                .counter(
                        VERDICT_METRIC,
                        "verdict",
                        decision.verdict().name().toLowerCase(Locale.ROOT),
                        "reason",
                        decision.reason())
                .increment();

        if (decision.verdict() == Verdict.REQUEUE) {
            log.info(
                    "Agent job {} (agent {}) was interrupted while RUNNING - last owner '{}', last heartbeat {} - "
                            + "and is resumable: {}",
                    orphan.getId(),
                    orphan.getAgentId(),
                    orphan.getOwnerInstance(),
                    orphan.getHeartbeatAt(),
                    decision.detail());
        } else {
            // The actionable line. The row's own error_message says WHAT
            // happened ("the worker stopped reporting"); this says WHY it will
            // not be retried, which is the question an operator actually has
            // when they find a FAILED/ORPHANED job after a deploy.
            log.warn(
                    "Agent job {} (agent {}) was interrupted while RUNNING - last owner '{}', last heartbeat {} - "
                            + "and is NOT resumable: {}. It will be failed with ORPHANED; re-submit it if the work is "
                            + "still wanted.",
                    orphan.getId(),
                    orphan.getAgentId(),
                    orphan.getOwnerInstance(),
                    orphan.getHeartbeatAt(),
                    decision.detail());
        }
        return decision.verdict();
    }

    private Decision evaluate(AgentJob orphan) {
        AgentJobDurabilityProperties durability = properties.getDurability();

        if (!durability.isRequeueZeroWorkOrphans()) {
            return new Decision(
                    Verdict.FAIL,
                    "disabled",
                    "app.agent-jobs.durability.requeue-zero-work-orphans is off, so every orphan is failed");
        }
        if (orphan.isCancelRequested()) {
            return new Decision(
                    Verdict.FAIL,
                    "cancel-requested",
                    "a cancel was requested for it, so re-queuing would re-run work somebody asked to stop");
        }
        String journalResume = null;
        if (orphan.getTurnsUsed() > 0) {
            journalResume = journalResumePoint(orphan);
        }
        if (BLOCKED_DURABLE_CALL.equals(journalResume)) {
            // Blocker 1: never re-run a turn whose journal shows a DURABLE (write/send) tool call
            // that STARTED
            // without COMPLETED - the provider may already have acted (an email sent, an MCP write
            // made).
            reviewReasons.put(
                    orphan.getId(),
                    "a write/send tool call of turn "
                            + (orphan.getTurnsUsed() - 1)
                            + " started but never recorded completion (or in-flight tracking is off: "
                            + "app.tools.effect-journal.enabled)");
            return new Decision(
                    Verdict.FAIL,
                    "durable-call-in-flight",
                    "the step journal shows a DURABLE tool call of job "
                            + orphan.getId()
                            + " that STARTED without "
                            + "COMPLETED, so it may already have sent/written something; re-running the turn could repeat "
                            + "that effect. Failed with ORPHANED_NEEDS_REVIEW for a human to check the external system");
        }
        if (orphan.getTurnsUsed() > 0 && journalResume == null) {
            // The single most important clause in this class.
            return new Decision(
                    Verdict.FAIL,
                    "turn-started",
                    "it had already STARTED a turn (turns_used="
                            + orphan.getTurnsUsed()
                            + ", incremented before the "
                            + "model call, not after), so a model call was entered and may already have been billed to "
                            + orphan.getSubjectKey()
                            + " - a re-run would charge that budget a second time");
        }
        if (orphan.getAttemptCount() >= durability.getMaxAttempts()) {
            return new Decision(
                    Verdict.FAIL,
                    "attempts-exhausted",
                    "attempt_count="
                            + orphan.getAttemptCount()
                            + " has reached app.agent-jobs.durability.max-attempts="
                            + durability.getMaxAttempts()
                            + ", the bound that stops a job which kills its worker every "
                            + "time from looping forever");
        }

        Instant queueCutoff = Instant.now().minus(properties.getQueueTtl());
        if (orphan.getCreatedAt() == null || orphan.getCreatedAt().isBefore(queueCutoff)) {
            // Re-queuing here would be immediately undone: the dispatch tick
            // expires any QUEUED row older than queue-ttl with
            // QUEUE_TTL_EXPIRED. One honest verdict beats two contradictory ones.
            return new Decision(
                    Verdict.FAIL,
                    "queue-ttl-expired",
                    "it was submitted before the app.agent-jobs.queue-ttl ("
                            + properties.getQueueTtl()
                            + ") cutoff, so its requester's frozen authority is stale and a re-queue would be expired "
                            + "by the very next dispatch tick anyway");
        }

        if (journalResume != null) {
            // The journal, not the zero-work proof, is the safety argument here (see
            // journalResumePoint).
            // Logged tool calls are expected and are NOT a reason to fail: a completed turn's
            // effects are
            // already done, and an incomplete turn's DURABLE effects replay from the effect
            // journal.
            return new Decision(
                    Verdict.REQUEUE,
                    journalResume,
                    "the step journal holds a safe resume point for job "
                            + orphan.getId()
                            + " ("
                            + journalResume
                            + ", turns_used="
                            + orphan.getTurnsUsed()
                            + ", attempt "
                            + orphan.getAttemptCount()
                            + " of "
                            + durability.getMaxAttempts()
                            + ")");
        }

        long toolCalls;
        try {
            toolCalls =
                    toolCallLogRepository.countByAgentIdAndSessionId(
                            orphan.getAgentId(), orphan.getSessionId());
        } catch (RuntimeException lookupFailed) {
            log.warn(
                    "Could not count tool calls for agent job {} (session '{}') while deciding its recovery; "
                            + "treating it as unprovable and failing it",
                    orphan.getId(),
                    orphan.getSessionId(),
                    lookupFailed);
            return new Decision(
                    Verdict.FAIL,
                    "lookup-failed",
                    "the tool-call lookup for session "
                            + orphan.getSessionId()
                            + " failed, and an UNPROVABLE "
                            + "zero-work claim is not a zero-work claim");
        }
        if (toolCalls > 0) {
            return new Decision(
                    Verdict.FAIL,
                    "tool-calls-logged",
                    toolCalls
                            + " tool call(s) are already logged for session "
                            + orphan.getSessionId()
                            + ", and tool side effects are not idempotent - nothing in the tool path dedupes on a "
                            + "request id, so a re-run would fire them again");
        }

        return new Decision(
                Verdict.REQUEUE,
                "zero-work",
                "turns_used=0 and no tool calls are logged for session "
                        + orphan.getSessionId()
                        + ", so its previous attempt died before the durable turn marker that precedes the first model "
                        + "call - it charged nothing and took no side effect (attempt "
                        + orphan.getAttemptCount()
                        + " of "
                        + durability.getMaxAttempts()
                        + ")");
    }

    /**
     * L1: the only way past the "turn-started" FAIL. Returns a low-cardinality reason tag when the
     * step journal proves resuming is safe, else {@code null} (=> FAIL, exactly as before). Always
     * {@code null} unless {@code step-journal-enabled} and {@code resume-from-journal} are both on
     * and a journal bean exists.
     *
     * <ul>
     *   <li>{@code journal-turn-completed}: the journal has COMPLETED for the last STARTED turn
     *       ({@code turn:<turns_used-1>}). The worker died after the model call and its tools
     *       finished and before the job was finalised; the resumed attempt reuses the journaled
     *       result - no model call, no tool call, nothing re-charged.
     *   <li>{@code journal-rerun-incomplete-turn}: that turn is STARTED but not COMPLETED and
     *       {@code resume-rerun-incomplete-turn} is on. Re-runs the turn: may re-bill one model
     *       call (no cost ledger), DURABLE tool effects dedupe via the effect journal when it is
     *       on. Opt-in only.
     * </ul>
     *
     * No journal row at all (a pre-journal job, or a lost write) is NOT a resume point.
     */
    private String journalResumePoint(AgentJob orphan) {
        AgentJobDurabilityProperties durability = properties.getDurability();
        StepJournal journal = stepJournal;
        if (journal == null
                || !journal.isEnabled()
                || !durability.isStepJournalEnabled()
                || !durability.isResumeFromJournal()
                || orphan.getId() == null) {
            return null;
        }
        java.util.Optional<StepJournal.Step> last =
                journal.find(
                        StepJournal.runIdForJob(orphan.getId()),
                        StepJournal.turnStepId(orphan.getTurnsUsed() - 1));
        if (last.isEmpty()) {
            return null;
        }
        if (last.get().completedWithResult()) {
            return "journal-turn-completed";
        }
        // Not a usable completed result: either STARTED only, or COMPLETED with a null/blank result
        // (blocker 2:
        // resuming from that would finish the job SUCCEEDED with nothing). Both fall to the
        // rerun-safety rule.
        if (!durability.isResumeRerunIncompleteTurn()) {
            return null;
        }
        if (!journal.isDurableCallTrackingActive()
                || journal.hasIncompleteDurableCall(orphan.getId())) {
            // Tracking off (effect journal disabled) means no in-flight rows exist, so "none in
            // flight" is
            // unprovable: treat as blocked rather than risk repeating a write/send.
            return BLOCKED_DURABLE_CALL;
        }
        return "journal-rerun-incomplete-turn";
    }

    /**
     * @param reason a LOW-CARDINALITY metric tag drawn from a fixed set, so this counter cannot
     *     explode Micrometer's tag space
     * @param detail prose for the operator log; free to mention ids and counts
     */
    private record Decision(Verdict verdict, String reason, String detail) {}
}
