package com.agenticai.core.agents.job.sentinel;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.schedule.AgentSchedule;
import com.agenticai.core.agents.job.schedule.AgentScheduleRepository;
import com.agenticai.core.agents.job.schedule.AgentScheduleRun;
import com.agenticai.core.agents.job.schedule.AgentScheduleRunRepository;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns finished sentinel watch runs into outcomes - the "notice and escalate" half of ambient
 * mode, sitting next to {@code AgentScheduleTicker} (which produces the runs).
 *
 * <p>Every minute it takes the same posture {@code AgentScheduleTicker} takes ({@link
 * PgAdvisoryJobLock#tryRunExclusive} as dedupe only - correctness lives in the {@code
 * sentinel_outcome IS NULL} predicate, so two concurrent sweeps each see the other's committed
 * verdicts and never escalate the same run twice), iterates tenant schemas via {@link
 * TenantSchemaIterator}, and reconciles sentinel runs in firing order.
 *
 * <p>For each unevaluated run the outcome is one of:
 *
 * <ul>
 *   <li>{@code JOB_FAILED} / {@code JOB_GONE} - the run's job died or its row vanished; the
 *       watcher's own health is the signal. The owner is notified only when the PREVIOUS evaluated
 *       sentinel run already ended in failure - a first failure is noise any job can hit, a second
 *       in a row is a broken watcher.
 *   <li>{@code NO_VERDICT} - the job finished but produced no parseable {@code ```sentinel} block;
 *       recorded and otherwise ignored.
 *   <li>{@code QUIET} - the sentinel's expected verdict; recorded, nothing else. A watcher that
 *       talks every tick is an alarm nobody reads.
 *   <li>{@code NOTIFIED} - a {@code notify} verdict (or a {@code propose} downgraded for lacking a
 *       usable proposal) becomes a notification to the schedule's owner, deep-linked at the agent.
 *   <li>{@code PROPOSED} - a {@code propose} verdict parks an {@code agent.proposal} {@link
 *       ApprovalRequest} via {@link HumanApprovalGateService#evaluate} - the same parked-approval
 *       substrate every other gated action uses, so the ratify path is the existing {@code
 *       /api/approvals} UI and {@code SentinelProposalExecutor} - and notifies the owner that a
 *       proposal is waiting.
 * </ul>
 *
 * <p>Every side effect here is best-effort: an escalate/notify failure logs and leaves the outcome
 * unwritten so the next tick retries, while a verdict record itself never blocks the rest of the
 * batch.
 */
@Component
public class AgentSentinelSweep {

    private static final Logger log = LoggerFactory.getLogger(AgentSentinelSweep.class);

    public static final String OUTCOME_JOB_FAILED = "JOB_FAILED";
    public static final String OUTCOME_JOB_GONE = "JOB_GONE";
    public static final String OUTCOME_NO_VERDICT = "NO_VERDICT";
    public static final String OUTCOME_QUIET = "QUIET";
    public static final String OUTCOME_NOTIFIED = "NOTIFIED";
    public static final String OUTCOME_PROPOSED = "PROPOSED";

    private final AgentScheduleRepository scheduleRepository;
    private final AgentScheduleRunRepository scheduleRunRepository;
    private final AgentJobRepository jobRepository;
    private final NotificationService notificationService;
    // Deferred: HumanApprovalGateService -> ApprovalActionExecutorRegistry ->
    // the SentinelProposalExecutor bean -> AgentJobService -> ... sits close
    // to a bean cycle; resolving the gate on first use keeps wiring order out
    // of correctness (same posture as AgentJobApprovalSweep).
    private final ObjectProvider<HumanApprovalGateService> gateService;
    private final PgAdvisoryJobLock jobLock;
    private final TenantSchemaIterator tenantIterator;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public AgentSentinelSweep(
            AgentScheduleRepository scheduleRepository,
            AgentScheduleRunRepository scheduleRunRepository,
            AgentJobRepository jobRepository,
            NotificationService notificationService,
            ObjectProvider<HumanApprovalGateService> gateService,
            PgAdvisoryJobLock jobLock,
            TenantSchemaIterator tenantIterator,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleRunRepository = scheduleRunRepository;
        this.jobRepository = jobRepository;
        this.notificationService = notificationService;
        this.gateService = gateService;
        this.jobLock = jobLock;
        this.tenantIterator = tenantIterator;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 75_000)
    public void sweep() {
        jobLock.tryRunExclusive(
                "agent-sentinel-sweep",
                () -> tenantIterator.forEachActiveTenantSchema(this::sweepTenant));
    }

    /**
     * One schema's sentinel schedules, each one's unevaluated firings oldest first so a burst of
     * verdicts lands in firing order. Iterating sentinel SCHEDULES rather than all outcome-NULL
     * runs is what keeps the query away from the bulk of {@code agent_schedule_run} (every ordinary
     * firing has a NULL outcome too - it was never meant to be judged). A schedule whose sentinel
     * flag was toggled off mid-flight simply stops being visited; its stragglers age out with the
     * run-history prune. Tenant routing is bound by the iterator's schema switch - every repository
     * call here targets {@code tenant}'s tables.
     */
    private void sweepTenant(String tenant) {
        List<AgentSchedule> schedules;
        try {
            schedules = scheduleRepository.findBySentinelEnabledTrue();
        } catch (RuntimeException e) {
            log.warn(
                    "Sentinel sweep could not read schedules in schema {}: {}",
                    tenant,
                    e.getMessage());
            return;
        }
        if (schedules.isEmpty()) {
            return;
        }
        Map<String, Long> outcomes = new HashMap<>();
        for (AgentSchedule schedule : schedules) {
            List<AgentScheduleRun> runs;
            try {
                runs =
                        scheduleRunRepository
                                .findByScheduleIdAndJobIdIsNotNullAndSentinelOutcomeIsNullOrderByStartedAtAsc(
                                        schedule.getId());
            } catch (RuntimeException e) {
                log.warn(
                        "Sentinel sweep could not read runs for schedule {} (schema {}): {}",
                        schedule.getId(),
                        tenant,
                        e.getMessage());
                continue;
            }
            for (AgentScheduleRun run : runs) {
                try {
                    reconcile(run, schedule, outcomes);
                } catch (RuntimeException e) {
                    log.warn(
                            "Sentinel sweep failed on schedule run {} (schema {}): {}",
                            run.getId(),
                            tenant,
                            e.getMessage());
                }
            }
        }
        int reconciled = outcomes.values().stream().mapToInt(Long::intValue).sum();
        if (reconciled > 0) {
            outcomes.forEach(
                    (outcome, n) ->
                            meterRegistry
                                    .counter("agent.sentinel.sweep.outcomes", "outcome", outcome)
                                    .increment(n));
            log.info("Sentinel sweep in {}: reconciled {} run(s) {}", tenant, reconciled, outcomes);
        }
    }

    private void reconcile(
            AgentScheduleRun run, AgentSchedule schedule, Map<String, Long> outcomes) {
        Optional<AgentJob> job =
                run.getJobId() == null ? Optional.empty() : jobRepository.findById(run.getJobId());

        if (job.isEmpty()) {
            mark(run, OUTCOME_JOB_GONE, "job row " + run.getJobId() + " not found", outcomes);
            notifyOwner(
                    schedule,
                    "Sentinel watch run lost its job",
                    "A sentinel firing of \""
                            + schedule.getAgentId()
                            + "\" recorded a job that no longer exists - the watcher may not be running.",
                    NotificationSeverity.WARNING,
                    null);
            return;
        }
        if (!job.get().getStatus().isTerminal()) {
            // Still running - leave the outcome NULL so a later tick retries.
            return;
        }
        if (job.get().getStatus() != AgentJobStatus.SUCCEEDED) {
            String note =
                    "job "
                            + job.get().getId()
                            + " ended "
                            + job.get().getStatus()
                            + Optional.ofNullable(job.get().getErrorMessage())
                                    .map(e -> ": " + abbrev(e, 300))
                                    .orElse("");
            // Suppression: only surface a watcher failure when the previous
            // evaluated sentinel run also failed - one dead tick is noise, two
            // in a row is a broken watcher.
            boolean streak =
                    scheduleRunRepository
                            .findFirstByScheduleIdAndSentinelOutcomeIsNotNullOrderByStartedAtDesc(
                                    schedule.getId())
                            .map(
                                    prev ->
                                            OUTCOME_JOB_FAILED.equals(prev.getSentinelOutcome())
                                                    || OUTCOME_JOB_GONE.equals(
                                                            prev.getSentinelOutcome()))
                            .orElse(false);
            mark(run, OUTCOME_JOB_FAILED, note, outcomes);
            if (streak) {
                notifyOwner(
                        schedule,
                        "Sentinel watch is failing",
                        "Two consecutive sentinel firings of this schedule failed. Last error: "
                                + abbrev(job.get().getErrorMessage(), 400),
                        NotificationSeverity.ERROR,
                        null);
            }
            return;
        }

        Optional<SentinelVerdict> verdict = SentinelVerdict.extract(job.get().getResultText());
        if (verdict.isEmpty()) {
            mark(
                    run,
                    OUTCOME_NO_VERDICT,
                    "no parseable ```sentinel block in job " + job.get().getId() + "'s result",
                    outcomes);
            return;
        }
        SentinelVerdict v = verdict.get();
        switch (v.verdict()) {
            case QUIET -> mark(run, OUTCOME_QUIET, v.title(), outcomes);
            case NOTIFY -> {
                notifyOwner(
                        schedule,
                        orDefault(v.title(), "Sentinel finding"),
                        v.body(),
                        mapSeverity(v.severity()),
                        null);
                mark(run, OUTCOME_NOTIFIED, v.title(), outcomes);
            }
            case PROPOSE -> {
                SentinelVerdict.Proposal p = v.proposal();
                String requestId = parkProposal(schedule, run, job.get(), v, p);
                notifyOwner(
                        schedule,
                        orDefault(v.title(), "Sentinel proposes an action"),
                        join(
                                v.body(),
                                "Proposed action: "
                                        + orDefault(p.summary(), "(see approval request)")
                                        + " - approve or reject it from Approvals."),
                        mapSeverity(v.severity()),
                        "/admin/approvals");
                mark(run, OUTCOME_PROPOSED, "approval request " + requestId, outcomes);
            }
        }
    }

    /**
     * Parks the proposal. {@code evaluate} is the gate's single chokepoint: {@code agent.proposal}
     * is default-sensitive (ApprovalPolicyService), so this returns PENDING and nothing executes
     * until a human decides; if a tenant loosened the pattern, evaluate auto-approves and the
     * executor runs immediately - either way the request row is the source of truth.
     */
    private String parkProposal(
            AgentSchedule schedule,
            AgentScheduleRun run,
            AgentJob job,
            SentinelVerdict v,
            SentinelVerdict.Proposal p) {
        String payloadJson;
        try {
            payloadJson =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "agentId", schedule.getAgentId().toString(),
                                    "goal", p.goal(),
                                    "configuredBy",
                                            schedule.getCreatedBy() == null
                                                    ? ""
                                                    : schedule.getCreatedBy(),
                                    "sourceScheduleId", schedule.getId().toString(),
                                    "sourceRunId", run.getId().toString(),
                                    "sourceJobId", job.getId().toString()));
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise proposal payload", e);
        }
        String summary =
                orDefault(
                        p.summary(),
                        orDefault(v.title(), "Sentinel proposal from job " + job.getId()));
        var evaluation =
                gateService
                        .getObject()
                        .evaluate(
                                SentinelProposalExecutor.ACTION_TYPE,
                                summary,
                                payloadJson,
                                "agent:" + schedule.getAgentId() + ":sentinel:" + schedule.getId());
        // evaluate() returns an ApprovalEvaluation(approved, pendingRequestId):
        // PENDING -> the parked request's id; auto-approve -> already ran.
        return evaluation.pendingRequestId() == null
                ? "(auto-approved)"
                : evaluation.pendingRequestId().toString();
    }

    private void mark(
            AgentScheduleRun run, String outcome, String note, Map<String, Long> outcomes) {
        run.recordSentinelOutcome(outcome, abbrev(note, 500));
        scheduleRunRepository.save(run);
        outcomes.merge(outcome, 1L, Long::sum);
    }

    /**
     * Best-effort by convention: notifications must never flip a sentinel outcome - a watcher whose
     * bell is broken still recorded its verdict.
     */
    private void notifyOwner(
            AgentSchedule schedule,
            String title,
            String body,
            NotificationSeverity severity,
            String linkUrl) {
        try {
            notificationService.create(
                    schedule.getCreatedBy(),
                    NotificationCategory.AGENT,
                    title == null || title.isBlank() ? "Sentinel" : abbrev(title, 200),
                    body == null || body.isBlank() ? "(no detail)" : abbrev(body, 2000),
                    severity,
                    linkUrl == null ? "/agents/" + schedule.getAgentId() : linkUrl,
                    null);
        } catch (RuntimeException e) {
            log.warn(
                    "Sentinel notification for schedule {} failed (best-effort): {}",
                    schedule.getId(),
                    e.getMessage());
        }
    }

    private static NotificationSeverity mapSeverity(SentinelVerdict.Severity severity) {
        return switch (severity) {
            case ERROR -> NotificationSeverity.ERROR;
            case WARNING -> NotificationSeverity.WARNING;
            case INFO -> NotificationSeverity.INFO;
        };
    }

    private static String orDefault(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String join(String a, String b) {
        return a == null || a.isBlank() ? b : a + "\n\n" + b;
    }

    private static String abbrev(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
