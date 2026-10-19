package com.agenticai.core.agents.scheduledtask;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobLaunch;
import com.agenticai.core.agents.job.AgentJobLauncher;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.AgentJobView;
import com.agenticai.core.agents.job.schedule.AgentCronSchedules;
import com.agenticai.core.audit.NotificationSeverity;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The firing and settling halves of scheduled tasks, run per tenant schema by {@link ScheduledTaskTicker}.
 *
 * <p><b>A scheduled task is a job PRODUCER, not a second execution path.</b> Every firing goes through {@link
 * AgentJobLauncher#enqueue} and lands in {@code agent_job}, so a run inherits - with no new enforcement code - the
 * per-user dollar budget (the job carries the OWNER as {@code subjectKey}), the tenant message/token caps, the
 * kill switch, the per-turn tool ceiling, the wall clock and the cancel path of any other job. The task's id is
 * written to {@code agent_job.schedule_id}, which is what makes V208's two unique indexes (at most one live job
 * per schedule; exactly one job per occurrence) guard tasks too.
 *
 * <p><b>Unattended means fail closed.</b> The job runs with NO roles and no human present. A tool call that hits a
 * confirmation / approval gate is recorded in the approvals inbox and the job PARKS ({@code AWAITING_APPROVAL});
 * if nobody decides, the existing approval sweep fails the job - which this class then treats as a failed run. A
 * parked task blocks its own next firing (the overlap guard counts AWAITING_APPROVAL as live), so a task waiting
 * on a human can never pile up runs. Nothing here approves anything.
 *
 * <p><b>Missed runs.</b> The tick claims a due occurrence with a compare-and-set that advances {@code next_run_at}
 * to the next slot computed from NOW, then fires it once: any amount of downtime yields one firing (or none, when
 * the occurrence is older than the catch-up window and is recorded as skipped), never a catch-up storm.
 *
 * <p><b>Failures.</b> A failed run is retried at most once (a {@link ScheduledTaskRun#TRIGGER_RETRY} run);
 * a failure that is not retried counts toward {@code consecutive_failures}, and the task pauses itself (with a
 * notification) when the streak reaches {@code app.scheduled-tasks.max-consecutive-failures}.
 */
@Component
public class ScheduledTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskRunner.class);

    /** Run-history rows kept per task; older settled ones are pruned on each insert. */
    static final int RUN_HISTORY_LIMIT = 50;

    private final ScheduledTaskRepository tasks;
    private final ScheduledTaskRunRepository runs;
    private final AgentRepository agentRepository;
    private final AgentJobLauncher launcher;
    private final AgentJobRepository jobRepository;
    private final ScheduledTaskSettings settings;
    private final ScheduledTaskResultDelivery delivery;
    private final MeterRegistry meterRegistry;
    // Setter-injected so the runner's test-constructed signature is unchanged; null (slim contexts) skips the check.
    private ScheduledTaskOwnerAccess ownerAccess;

    public ScheduledTaskRunner(
        ScheduledTaskRepository tasks,
        ScheduledTaskRunRepository runs,
        AgentRepository agentRepository,
        AgentJobLauncher launcher,
        AgentJobRepository jobRepository,
        ScheduledTaskSettings settings,
        ScheduledTaskResultDelivery delivery,
        MeterRegistry meterRegistry
    ) {
        this.tasks = tasks;
        this.runs = runs;
        this.agentRepository = agentRepository;
        this.launcher = launcher;
        this.jobRepository = jobRepository;
        this.settings = settings;
        this.delivery = delivery;
        this.meterRegistry = meterRegistry;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOwnerAccess(ScheduledTaskOwnerAccess ownerAccess) {
        this.ownerAccess = ownerAccess;
    }

    // ------------------------------------------------------------------ the tick

    /** Due tasks of the CURRENT tenant schema (the caller iterates schemas). One bad row never stops the rest. */
    public void fireDueTasks(Instant now) {
        List<ScheduledTask> due = tasks.findTop200ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now);
        for (ScheduledTask task : due) {
            try {
                fireOrSkip(task, now);
            } catch (RuntimeException e) {
                log.error("Unexpected error firing scheduled task {} (agent {})", task.getId(), task.getAgentId(), e);
            }
        }
    }

    /**
     * One due task: stop conditions first (expiry, run cap), then claim the occurrence, then fire it once.
     * Package-private so the unit suite drives it directly.
     */
    void fireOrSkip(ScheduledTask task, Instant now) {
        if (task.expired(now)) {
            retire(task.getId(), "Finished: it reached its end date.", now);
            return;
        }
        if (task.runCapReached()) {
            retire(task.getId(), "Finished: it reached its limit of " + task.getMaxRuns() + " runs.", now);
            return;
        }
        Instant observed = task.getNextRunAt();
        Instant next;
        try {
            next = ScheduledTaskSchedule.nextAfter(task.getCron(), task.getTimezone(), now);
        } catch (IllegalArgumentException unparseable) {
            // Valid when saved (the service parses it), so this is a hand-edited row: pause rather than log the
            // same failure every minute forever.
            log.error("Scheduled task {} has an unparseable stored schedule '{}' in zone '{}' - pausing it",
                task.getId(), task.getCron(), task.getTimezone(), unparseable);
            retire(task.getId(), "Paused: its stored schedule could not be read.", now);
            return;
        }
        if (next == null) {
            retire(task.getId(), "Finished: its schedule has no further runs.", now);
            return;
        }
        // The claim is what makes this exactly-once across replicas (the advisory lock around the tick is only a
        // de-duplication convenience): rowcount 0 = another replica won, or the task was paused in between.
        if (tasks.claimOccurrence(task.getId(), next, now, observed) == 0) {
            return;
        }
        if (!ScheduledTaskSchedule.withinCatchUpWindow(observed, now, settings.getCatchUpWindowSeconds())) {
            recordWithoutJob(task, ScheduledTaskRun.TRIGGER_SCHEDULE, 1, ScheduledTaskRun.SKIPPED,
                "Missed: the service was unavailable when this run was due (" + observed
                    + "), and it was too long ago to run now.", now);
            mutate(task.getId(), t -> t.recordSkipped(now));
            meterRegistry.counter("scheduled_task.skipped", "reason", "missed").increment();
            return;
        }
        fire(task, observed, now, ScheduledTaskRun.TRIGGER_SCHEDULE, 1);
    }

    /**
     * Decides and executes one firing for an already-claimed occurrence (tick, run-now and retry all come through
     * here, so they share identical guards). Only ever ENQUEUES.
     *
     * @param occurrenceAt the slot being fired - half of V208's per-occurrence unique key, so it is the slot, not
     *     {@code now}, for a scheduled firing; run-now and retry pass {@code now}
     */
    public ScheduledTaskRun fire(ScheduledTask task, Instant occurrenceAt, Instant now, String trigger, int attempt) {
        Optional<Agent> agent = agentRepository.findById(task.getAgentId());
        String unusable = unusableReason(agent);
        if (unusable != null) {
            ScheduledTaskRun run = recordWithoutJob(task, trigger, attempt, ScheduledTaskRun.FAILED, unusable, now);
            finalFailure(task.getId(), unusable, trigger, now);
            return run;
        }
        String accessProblem = ownerAccess == null ? null : ownerAccess.problem(task, agent.get());
        if (accessProblem != null) {
            // Not a transient failure: retrying or counting a streak would only repeat it. Record it, stop the task and
            // tell the owner why.
            ScheduledTaskRun run = recordWithoutJob(task, trigger, attempt, ScheduledTaskRun.FAILED, accessProblem, now);
            retire(task.getId(), "Paused: " + accessProblem, now);
            meterRegistry.counter("scheduled_task.failed", "paused", "owner_access").increment();
            return run;
        }
        Optional<AgentJobView> live = launcher.findLiveForSchedule(task.getId());
        if (live.isPresent()) {
            ScheduledTaskRun run = recordWithoutJob(task, trigger, attempt, ScheduledTaskRun.SKIPPED,
                "The previous run is still in progress (" + live.get().status() + "), so this one was skipped.", now);
            mutate(task.getId(), t -> t.recordSkipped(now));
            meterRegistry.counter("scheduled_task.skipped", "reason", "previous_run_live").increment();
            return run;
        }
        UUID jobId;
        try {
            jobId = launcher.enqueue(new AgentJobLaunch(
                task.getAgentId(),
                renderGoal(task, occurrenceAt),
                AgentJobOrigin.SCHEDULE,
                "scheduled-task:" + task.getId(),
                task.getId(),
                occurrenceAt,
                task.getOwnerSubject(),
                // No roles snapshot: a task runs with NONE - fewer roles can only make the job less capable than
                // its owner, never more (same rule and reason as agent_schedule).
                Set.of(),
                task.getOwnerSubject(),
                null
            ));
        } catch (DataIntegrityViolationException lostRace) {
            // V208's unique index rejected the insert: another replica already created this occurrence, or a live
            // job exists that the overlap read missed by microseconds. The guard of record working, not an error.
            ScheduledTaskRun run = recordWithoutJob(task, trigger, attempt, ScheduledTaskRun.SKIPPED,
                "Another instance already started this run.", now);
            meterRegistry.counter("scheduled_task.skipped", "reason", "concurrent_firing_lost").increment();
            return run;
        } catch (RuntimeException e) {
            String why = "Could not start the run: " + e.getMessage();
            ScheduledTaskRun run = recordWithoutJob(task, trigger, attempt, ScheduledTaskRun.FAILED, why, now);
            finalFailure(task.getId(), why, trigger, now);
            return run;
        }
        ScheduledTaskRun run = runs.save(ScheduledTaskRun.enqueued(UUID.randomUUID(), task.getId(), trigger, attempt, jobId, now));
        boolean countsAsRun = !ScheduledTaskRun.TRIGGER_RETRY.equals(trigger);
        mutate(task.getId(), t -> {
            if (t.recordStarted(jobId, now, countsAsRun)) {
                delivery.notifyOwner(t, "Scheduled task finished: " + t.getName(),
                    "This task has used its limit of " + t.getMaxRuns() + " runs and is now paused.",
                    NotificationSeverity.INFO);
            }
        });
        prune(task.getId());
        meterRegistry.counter("scheduled_task.fired", "trigger", trigger).increment();
        log.info("Scheduled task {} fired job {} (agent {}, trigger {}, attempt {})",
            task.getId(), jobId, task.getAgentId(), trigger, attempt);
        return run;
    }

    // --------------------------------------------------------------- the settle sweep

    /** Walks the current tenant's unsettled runs and settles those whose job has ended. */
    public void settleFinishedRuns(Instant now) {
        for (ScheduledTaskRun run : runs.findTop100BySettledFalseOrderByStartedAtAsc()) {
            try {
                settleOne(run, now);
            } catch (RuntimeException e) {
                log.warn("Could not settle scheduled task run {}: {}", run.getId(), e.getMessage());
            }
        }
    }

    void settleOne(ScheduledTaskRun run, Instant now) {
        Optional<AgentJob> job = run.getJobId() == null ? Optional.empty() : jobRepository.findById(run.getJobId());
        if (job.isPresent() && !job.get().getStatus().isTerminal()) {
            return; // still queued / running / parked on an approval: a later tick settles it
        }
        if (runs.claimForSettlement(run.getId()) == 0) {
            return; // another replica settled it
        }
        Optional<ScheduledTaskRun> fresh = runs.findById(run.getId());
        Optional<ScheduledTask> task = tasks.findById(run.getTaskId());
        if (fresh.isEmpty() || task.isEmpty()) {
            return;
        }
        ScheduledTaskRun row = fresh.get();
        ScheduledTask t = task.get();

        if (job.isPresent() && job.get().getStatus() == AgentJobStatus.SUCCEEDED
            && isNoProviderReply(job.get().getResultText())) {
            // B8: the agent has no usable LLM provider, so the chat service answers with a plain notice and the job
            // "succeeds". Delivering that as the task's result and counting a success meant the consecutive-failure
            // auto-pause could never trigger for this class. It is a failure, and not retried: the same
            // configuration gap would just repeat.
            row.settle(ScheduledTaskRun.FAILED, NO_PROVIDER_ERROR, job.get().getCostUsd(), null, now);
            runs.save(row);
            meterRegistry.counter("scheduled_task.completed", "status", "failed").increment();
            finalFailure(t.getId(), NO_PROVIDER_ERROR, row.getTriggerKind(), now);
            return;
        }
        if (job.isPresent() && job.get().getStatus() == AgentJobStatus.SUCCEEDED) {
            String deliveryStatus = delivery.deliver(t, job.get().getResultText());
            row.settle(ScheduledTaskRun.SUCCEEDED, null, job.get().getCostUsd(), deliveryStatus, now);
            runs.save(row);
            mutate(t.getId(), x -> x.recordSuccess(now));
            meterRegistry.counter("scheduled_task.completed", "status", "succeeded").increment();
            return;
        }
        if (job.isPresent() && job.get().getStatus() == AgentJobStatus.CANCELLED) {
            row.settle(ScheduledTaskRun.CANCELLED, "The run was cancelled.", job.get().getCostUsd(), null, now);
            runs.save(row);
            mutate(t.getId(), x -> x.recordCancelled(now));
            meterRegistry.counter("scheduled_task.completed", "status", "cancelled").increment();
            return;
        }
        // FAILED, or the job row vanished.
        String code = job.isPresent() ? job.get().getErrorCode() : "JOB_GONE";
        String error = job.map(j -> describeFailure(j.getErrorCode(), j.getErrorMessage()))
            .orElse("The run's job record could not be found.");
        row.settle(ScheduledTaskRun.FAILED, error, job.map(AgentJob::getCostUsd).orElse(null), null, now);
        runs.save(row);
        meterRegistry.counter("scheduled_task.completed", "status", "failed").increment();
        if (shouldRetry(settings.isRetryFailedOnce(), row.getTriggerKind(), row.getAttempt(), t.isEnabled(), code)) {
            // At most once: the retry run is attempt 2, so its own failure is final.
            ScheduledTaskRun retry;
            try {
                retry = fire(t, now, now, ScheduledTaskRun.TRIGGER_RETRY, 2);
            } catch (RuntimeException e) {
                log.warn("Retry of scheduled task {} could not be started: {}", t.getId(), e.getMessage());
                retry = null;
            }
            if (retry == null || ScheduledTaskRun.SKIPPED.equals(retry.getStatus())) {
                // The retry never started (a live job, or another instance won the slot), so nothing else will record
                // the original failure: count it now or the streak never moves and the owner is never told.
                finalFailure(t.getId(), error, row.getTriggerKind(), now);
            }
            return;
        }
        finalFailure(t.getId(), error, row.getTriggerKind(), now);
    }

    static final String NO_PROVIDER_ERROR =
        "The agent has no usable LLM provider, so the run produced no result. Connect a provider (or switch the agent "
            + "to your own key) in Providers settings.";

    /** True when a "successful" job's text is the chat service's no-provider notice rather than real output. */
    static boolean isNoProviderReply(String resultText) {
        return resultText != null
            && resultText.strip().startsWith(com.agenticai.core.agents.turn.AgentChatService.NO_PLATFORM_LLM_NOTICE_PREFIX);
    }

    /**
     * The single-retry rule as a pure function. Retried: a failed SCHEDULE run, first attempt, on a task that is
     * still enabled, whose failure was not an approval timeout (retrying would only park on the same unanswered
     * approval). Never retried: run-now (a human is watching), the retry itself, a paused task.
     */
    static boolean shouldRetry(boolean retryEnabled, String triggerKind, int attempt, boolean taskEnabled, String errorCode) {
        if (!retryEnabled || !taskEnabled || attempt >= 2 || !ScheduledTaskRun.TRIGGER_SCHEDULE.equals(triggerKind)) {
            return false;
        }
        return errorCode == null || !errorCode.startsWith("APPROVAL");
    }

    // ------------------------------------------------------------------ internals

    /** A final failure: bump the streak (scheduled/retry runs only), auto-pause at the threshold, tell the owner. */
    private void finalFailure(UUID taskId, String error, String trigger, Instant now) {
        Optional<ScheduledTask> loaded = tasks.findById(taskId);
        if (loaded.isEmpty()) {
            return;
        }
        ScheduledTask t = loaded.get();
        if (ScheduledTaskRun.TRIGGER_MANUAL.equals(trigger)) {
            t.recordFailureWithoutStreak(now);
            tasks.save(t);
            return;
        }
        boolean paused = t.recordFinalFailure(settings.getMaxConsecutiveFailures(), now);
        tasks.save(t);
        if (paused) {
            delivery.notifyOwner(t, "Scheduled task paused: " + t.getName(),
                "It failed " + t.getConsecutiveFailures() + " times in a row and was paused so it stops using your "
                    + "budget. Last error: " + error + " Fix the cause, then resume it from Scheduled tasks.",
                NotificationSeverity.ERROR);
        } else {
            delivery.notifyOwner(t, "Scheduled task failed: " + t.getName(),
                "The run failed (" + error + "). It will try again at the next scheduled time.",
                NotificationSeverity.WARNING);
        }
        meterRegistry.counter("scheduled_task.failed", "paused", String.valueOf(paused)).increment();
    }

    private void retire(UUID taskId, String reason, Instant now) {
        mutate(taskId, t -> {
            t.autoDisable(reason, now);
            delivery.notifyOwner(t, "Scheduled task " + (reason.startsWith("Finished") ? "finished" : "paused")
                + ": " + t.getName(), reason, NotificationSeverity.INFO);
        });
    }

    private ScheduledTaskRun recordWithoutJob(
        ScheduledTask task, String trigger, int attempt, String status, String error, Instant now
    ) {
        ScheduledTaskRun run = runs.save(ScheduledTaskRun.ended(
            UUID.randomUUID(), task.getId(), trigger, attempt, status, error, now));
        prune(task.getId());
        return run;
    }

    /**
     * Load-mutate-save on a FRESH copy. The tick's own copy has a stale {@code next_run_at} (the claim advanced it
     * with a bulk UPDATE), so saving that copy would silently undo the claim.
     */
    private void mutate(UUID taskId, Consumer<ScheduledTask> change) {
        tasks.findById(taskId).ifPresent(t -> {
            change.accept(t);
            tasks.save(t);
        });
    }

    /** Best-effort history housekeeping - must never fail a firing. Only settled rows are pruned. */
    private void prune(UUID taskId) {
        try {
            List<ScheduledTaskRun> excess = runs.findByTaskIdOrderByStartedAtDesc(
                taskId, PageRequest.of(1, RUN_HISTORY_LIMIT));
            List<ScheduledTaskRun> settled = excess.stream().filter(ScheduledTaskRun::isSettled).toList();
            if (!settled.isEmpty()) {
                runs.deleteAll(settled);
            }
        } catch (RuntimeException e) {
            log.warn("Could not prune run history for scheduled task {}", taskId, e);
        }
    }

    private static String unusableReason(Optional<Agent> agent) {
        if (agent.isEmpty()) {
            return "The agent this task runs no longer exists.";
        }
        String tierProblem = ScheduledTaskService.unattendedTierProblem(agent.get().getAutonomyTier());
        return tierProblem == null ? null : "This task cannot run: " + tierProblem;
    }

    private static String describeFailure(String code, String message) {
        String c = code == null || code.isBlank() ? "FAILED" : code;
        return message == null || message.isBlank() ? c : c + ": " + message;
    }

    /**
     * The goal handed to the agent: the user's prompt plus one short line saying this is an unattended scheduled
     * run (nobody can answer a question) and, for an external destination, that the reply is sent as a message.
     * Added to THESE jobs only - it is not part of any chat prompt.
     */
    static String renderGoal(ScheduledTask task, Instant occurrenceAt) {
        ZoneId zone = AgentCronSchedules.zoneOf(task.getTimezone());
        String at = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(occurrenceAt.atZone(zone));
        StringBuilder note = new StringBuilder("\n\n[Scheduled task \"").append(task.getName())
            .append("\", run at ").append(at)
            .append(". This is an unattended run - nobody can answer questions, so do the work and reply with the "
                + "final result only");
        if (task.getDelivery().isExternal()) {
            note.append("; it will be sent as a ").append(task.getDelivery().key()).append(" message, so keep it concise");
        }
        return task.getPrompt() + note.append(".]");
    }
}
