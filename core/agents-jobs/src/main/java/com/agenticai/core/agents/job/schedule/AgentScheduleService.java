package com.agenticai.core.agents.job.schedule;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobLaunch;
import com.agenticai.core.agents.job.AgentJobLauncher;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobView;
import com.agenticai.core.agents.job.schedule.AgentScheduleFiring.FireResult;
import com.agenticai.core.agents.job.schedule.dto.AgentScheduleRequest;
import com.agenticai.core.agents.job.schedule.dto.AgentScheduleResponse;
import com.agenticai.core.agents.job.schedule.dto.AgentScheduleRunResponse;
import com.agenticai.core.agents.job.sentinel.SentinelVerdict;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.PermissionLevel;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Cron-triggered agent jobs: configuration (the HTTP half) and firing (the tick
 * half).
 *
 * <p><b>A scheduled agent is a job PRODUCER, not a second execution path.</b>
 * Every firing goes through {@link AgentJobLauncher#enqueue} and lands in
 * {@code agent_job}, so a scheduled run inherits - by construction, with no new
 * enforcement code - the per-user dollar budget, the tenant message/token caps,
 * the kill switch, the per-turn tool ceiling, the wall clock and the cancel
 * path that a job submitted over HTTP gets. There is exactly one place agent
 * work executes.
 *
 * <p><b>The tick only ever ENQUEUES.</b> It must not call an LLM and must not
 * block: {@code spring.task.scheduling.pool.size} is 8 and roughly twenty-one
 * {@code @Scheduled} methods already share it, with that config's own comment
 * warning to revisit if jobs become long-running by design. Whatever the agent
 * then does happens on the job executor's own bounded pool.
 *
 * <p><b>The overlap guard is the part that matters.</b> A five-minute schedule
 * whose job takes twenty minutes must never spawn four concurrent runs. Three
 * layers, in increasing order of authority:
 * <ol>
 *   <li>{@link #decide} - a PURE function (no Spring, no database) that
 *       compares the schedule against the live job, so the rule itself is
 *       exhaustively unit-testable without a Postgres.</li>
 *   <li>{@link com.agenticai.core.tenancy.PgAdvisoryJobLock} around the tick -
 *       a de-duplication convenience only. It is documented FAIL-OPEN and can
 *       therefore never be the guarantee.</li>
 *   <li>V208's two unique indexes - the GUARD OF RECORD. When the advisory lock
 *       is unavailable and two replicas genuinely race, Postgres is what stops
 *       the second job existing, and {@link DataIntegrityViolationException} is
 *       how this class finds out (recorded as {@link
 *       AgentScheduleSkipReason#CONCURRENT_FIRING_LOST}, not swallowed).</li>
 * </ol>
 */
@Service
public class AgentScheduleService {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduleService.class);

    private final AgentScheduleRepository repository;
    private final AgentService agentService;
    /**
     * Read-only, and for exactly one thing: resolving {@code last_job_id}'s
     * CURRENT status for the GET response. Denormalizing that status onto the
     * schedule row was the alternative and is worse - it would be written once at
     * fire time and then say {@code QUEUED} forever.
     */
    private final AgentJobRepository jobRepository;
    private final AgentScheduleRunRepository runRepository;
    private final AgentScheduleFiring firing;
    private final AgentScheduleSkipNotifier skipNotifier;
    private final AgentScheduleValidation validation;

    /** Run-history rows kept per schedule; older ones are pruned on each insert. */
    static final int RUN_HISTORY_LIMIT = 50;

    public AgentScheduleService(
        AgentScheduleRepository repository,
        AgentRepository agentRepository,
        AgentService agentService,
        AgentJobLauncher launcher,
        AgentJobRepository jobRepository,
        AgentJobProperties properties,
        NotificationService notificationService,
        MeterRegistry meterRegistry,
        AgentScheduleRunRepository runRepository,
        ObjectProvider<ScheduledWorkflowRunner> workflowRunner,
        TenantLimits tenantLimits
    ) {
        this.repository = repository;
        this.agentService = agentService;
        this.jobRepository = jobRepository;
        this.runRepository = runRepository;
        this.firing = new AgentScheduleFiring(
            repository, agentRepository, launcher, properties, meterRegistry, runRepository, workflowRunner);
        this.skipNotifier = new AgentScheduleSkipNotifier(repository, properties, notificationService, meterRegistry);
        this.validation = new AgentScheduleValidation(repository, properties, workflowRunner, tenantLimits);
    }

    // ------------------------------------------------------------ configuration

    /**
     * Creates or re-configures the agent's schedule. One per agent, so {@code
     * PUT} is an upsert and there is no create-vs-update distinction for the
     * caller to get wrong - the same shape as the workflow schedule API.
     *
     * <p>EDITOR here, not RUNNER: configuring a recurring, unattended execution
     * is a change to what the agent IS, not a single invocation of it. The
     * {@code ROLE_tenant-admin} half of the gate lives in {@code SecurityConfig},
     * exactly parallel to {@code PUT /api/workflows/*}{@code /schedule}.
     */
    public AgentScheduleResponse upsert(UUID agentId, AgentScheduleRequest request) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        // Plan gate - a schedule is unattended automation (the agent keeps
        // working with nobody watching), a paid-tier entitlement
        // (plan_limits.automation_allowed). Gated at the config boundary so a
        // downgraded tenant cannot author new unattended work; disable stays
        // ungated because teardown is always safe.
        validation.requireAutomationAllowed();

        AgentScheduleTarget target = parseTarget(request.targetType());
        UUID workflowId = validation.requireTargetConsistency(target, request.workflowId());

        // Sentinel is a watch-brief mode on an AGENT cadence: the sweep parses
        // a ```sentinel verdict out of the finished job's result text. A
        // WORKFLOW target has no result text for a verdict to live in (its run
        // row goes through completeRun with a status string, not a document),
        // so the combination is rejected here rather than silently never
        // escalating.
        boolean sentinel = Boolean.TRUE.equals(request.sentinel());
        if (sentinel && target == AgentScheduleTarget.WORKFLOW) {
            throw new IllegalArgumentException(
                "Invalid schedule: sentinel mode requires targetType AGENT - a workflow run has no free-text "
                    + "result for a ```sentinel verdict to live in.");
        }

        // CONFIG-TIME PRECONDITION, and a genuinely important one. SUPERVISED
        // gates EVERY tool call on a human approval, so a scheduled run would
        // park on its first tool call; the overlap guard would then - correctly,
        // by its own rules - mute this schedule for as long as that job stayed
        // parked, while the schedule row, the agent and the job all reported
        // themselves perfectly healthy. Refusing here is the only place this is
        // cheap to explain. (WORKFLOW targets never enqueue an agent job, so the
        // autonomy tier is irrelevant to them.)
        if (target == AgentScheduleTarget.AGENT && agent.getAutonomyTier() == AgentAutonomyTier.SUPERVISED) {
            throw new IllegalArgumentException(
                "A SUPERVISED agent cannot be scheduled: every tool call waits for a human, so an unattended run "
                    + "would park on its first tool call and block the schedule indefinitely. Move the agent to "
                    + "SEMI_AUTONOMOUS or FULLY_AUTONOMOUS first.");
        }

        AgentScheduleCadence cadence = AgentScheduleCadence.of(request.cron(), request.intervalSeconds());
        String timezone = request.timezone() == null || request.timezone().isBlank()
            ? "UTC"
            : request.timezone().trim();
        // Parses BOTH the cron and the zone, so a bad value fails here with a 400
        // rather than on a background tick sixty seconds later where the only
        // evidence is a log line nobody is watching.
        ZoneId zone = AgentCronSchedules.zoneOf(timezone);
        Instant now = Instant.now();
        Instant nextRun = validation.requireFireable(cadence, timezone, now);
        validation.requireMinimumInterval(cadence, timezone, nextRun);

        Optional<AgentSchedule> existing = repository.findByAgentId(agentId);
        AgentSchedule schedule;
        if (existing.isPresent()) {
            schedule = existing.get();
            schedule.reconfigure(cadence, timezone, request.goalTemplate(), target, workflowId, nextRun, now,
                sentinel);
        } else {
            validation.requireTenantCapacity();
            schedule = AgentSchedule.create(
                UUID.randomUUID(), agentId, cadence, timezone, request.goalTemplate(), target, workflowId,
                validation.currentSubject(), nextRun, now, sentinel);
        }
        repository.save(schedule);
        log.info(
            "Agent {} scheduled ({} -> {}) '{}' ({}); next run {}",
            agentId, target, workflowId, cadence.describe(), zone, nextRun);
        return respond(schedule);
    }

    /**
     * Turns the trigger off. Deliberately KEEPS {@code cron} and {@code
     * goalTemplate} - re-enabling is then one PUT with the same body the operator
     * already had, rather than a re-authoring exercise - and returns the row
     * instead of 204 so the console can render the now-disabled state without a
     * follow-up GET.
     */
    public AgentScheduleResponse disable(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        AgentSchedule schedule = repository.findByAgentId(agentId)
            .orElseThrow(() -> new NoSuchElementException("No schedule is configured for agent " + agentId));
        schedule.disable(Instant.now());
        repository.save(schedule);
        return respond(schedule);
    }

    /** Deletes the schedule and its run history for good (the explicit {@code DELETE ...?remove=true}); {@link #disable} only pauses. */
    public void remove(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        AgentSchedule schedule = repository.findByAgentId(agentId)
            .orElseThrow(() -> new NoSuchElementException("No schedule is configured for agent " + agentId));
        runRepository.deleteAll(runRepository.findByScheduleIdOrderByStartedAtDesc(
            schedule.getId(), org.springframework.data.domain.PageRequest.of(0, 200)));
        repository.delete(schedule);
    }

    /**
     * Re-enables a paused schedule using its stored configuration (no body to
     * re-author), recomputing the next slot from now. 404 when there is no
     * schedule; a no-op re-arm when it is already enabled.
     */
    public AgentScheduleResponse resume(UUID agentId) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        // Plan gate - re-arming is the same automation decision as upserting;
        // without it a downgraded tenant's paused schedules are an entitlement
        // bypass. disable() stays open (turning work OFF is always safe).
        validation.requireAutomationAllowed();
        AgentSchedule schedule = repository.findByAgentId(agentId)
            .orElseThrow(() -> new NoSuchElementException("No schedule is configured for agent " + agentId));
        if (schedule.getTargetType() == AgentScheduleTarget.AGENT
            && agent.getAutonomyTier() == AgentAutonomyTier.SUPERVISED) {
            throw new IllegalArgumentException(
                "A SUPERVISED agent cannot be scheduled. Move the agent to SEMI_AUTONOMOUS or FULLY_AUTONOMOUS first.");
        }
        Instant now = Instant.now();
        Instant next = validation.requireFireable(schedule.cadence(), schedule.getTimezone(), now);
        if (!schedule.isEnabled()) {
            validation.requireTenantCapacity();
        }
        schedule.resume(next, now);
        repository.save(schedule);
        return respond(schedule);
    }

    /**
     * Fires the schedule once, right now, without touching {@code next_run_at}.
     * Goes through exactly the same guards as the tick (overlap, target
     * availability, frozen identity); a refusal is returned as a {@code SKIPPED}
     * run row rather than an error, and does NOT bump the schedule's skip
     * counters - a human clicking the button is not a schedule misfiring. Works on a
     * paused schedule too.
     */
    public AgentScheduleRunResponse runNow(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        AgentSchedule schedule = repository.findByAgentId(agentId)
            .orElseThrow(() -> new NoSuchElementException("No schedule is configured for agent " + agentId));
        Instant now = Instant.now();
        // The occurrence key is `now`, so it can never collide with a cron slot
        // (V208's per-occurrence unique index) except within the same millisecond.
        DueSchedule due = toDue(schedule, now);
        FireResult result = firing.fire(due, now, AgentScheduleRun.TRIGGER_MANUAL);
        if (result.run() == null) {
            String reason = result.skipReason() == null ? "unknown" : result.skipReason().name();
            AgentScheduleRun skipped = firing.saveRun(AgentScheduleRun.create(
                UUID.randomUUID(), due.scheduleId(), due.targetType(), AgentScheduleRun.TRIGGER_MANUAL,
                AgentScheduleRun.SKIPPED, now, null, null, "Run-now refused: " + reason));
            return AgentScheduleRunResponse.from(skipped, null);
        }
        return AgentScheduleRunResponse.from(result.run(), null);
    }

    /** Most recent runs, newest first, capped at {@link #RUN_HISTORY_LIMIT}. */
    public List<AgentScheduleRunResponse> listRuns(UUID agentId, int limit) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        AgentSchedule schedule = repository.findByAgentId(agentId)
            .orElseThrow(() -> new NoSuchElementException("No schedule is configured for agent " + agentId));
        int bounded = Math.max(1, Math.min(limit, RUN_HISTORY_LIMIT));
        List<AgentScheduleRun> runs = runRepository.findByScheduleIdOrderByStartedAtDesc(
            schedule.getId(), PageRequest.of(0, bounded));
        List<UUID> jobIds = runs.stream()
            .filter(r -> AgentScheduleRun.ENQUEUED.equals(r.getStatus()) && r.getJobId() != null)
            .map(AgentScheduleRun::getJobId)
            .toList();
        // Live job status resolved at read time (an ENQUEUED row is never rewritten),
        // for the same reason last_job_id has no denormalized status.
        Map<UUID, String> jobStatus = jobRepository.findAllById(jobIds).stream()
            .collect(Collectors.toMap(AgentJob::getId, j -> j.getStatus().name()));
        return runs.stream()
            .map(r -> AgentScheduleRunResponse.from(r, r.getJobId() == null ? null : jobStatus.get(r.getJobId())))
            .toList();
    }

    public Optional<AgentScheduleResponse> get(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return repository.findByAgentId(agentId).map(this::respond);
    }

    /**
     * Completion callback for WORKFLOW runs, invoked by the runner on its worker
     * thread (tenant context already restored).
     */
    public void completeRun(UUID runRowId, String status, String error) {
        firing.completeRun(runRowId, status, error);
    }

    // -------------------------------------------------------------------- tick

    /**
     * Due schedules for the CURRENT tenant schema. Called from inside {@code
     * TenantSchemaIterator#forEachActiveTenantSchema}, which is what makes this
     * repository read see a real tenant rather than the empty {@code public}
     * schema.
     *
     * <p>Projected into {@link DueSchedule} immediately rather than handing JPA
     * entities to the caller: {@link #decide} is specified as pure, and a lazily
     * initialisable entity crossing into it is how that stops being true.
     */
    public List<DueSchedule> findDue(Instant now) {
        return repository.findTop200ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now)
            .stream()
            .map(s -> toDue(s, s.getNextRunAt()))
            .toList();
    }

    private static DueSchedule toDue(AgentSchedule s, Instant occurrenceAt) {
        return new DueSchedule(
            s.getId(), s.getAgentId(), s.getCron(), s.getIntervalSeconds(), s.getTargetType(), s.getWorkflowId(),
            s.getTimezone(), s.getGoalTemplate(), occurrenceAt, s.getLastFiredAt(), s.getCreatedBy(),
            s.isSentinelEnabled());
    }

    /**
     * One due schedule, in four ordered steps. The ORDER is the design; each step
     * exists because of a specific way the naive version is wrong.
     *
     * <ol>
     *   <li><b>Claim the occurrence.</b> Advance {@code next_run_at} from the
     *       observed value to the next slot with a conditional UPDATE. Rowcount 0
     *       means another replica won or an admin just disabled the schedule -
     *       return SILENTLY, touching no counter, because a lost claim race is not
     *       a skip and counting it as one would make a healthy two-replica
     *       deployment look permanently broken. Doing this BEFORE the overlap
     *       decision is what makes a blocked firing a SKIP rather than a retry
     *       that fires the instant the blocking job ends and then again for every
     *       slot that passed meanwhile.</li>
     *   <li><b>Resolve the agent.</b> Gone, or moved to SUPERVISED since the
     *       schedule was configured -&gt; a recorded {@code AGENT_UNAVAILABLE}
     *       skip, never a silent nothing.</li>
     *   <li><b>Overlap check</b>, queried BY {@code schedule_id}. Never by
     *       following {@code last_job_id}: that pointer goes stale the moment
     *       somebody starts a manual run for the same agent, and a stale pointer
     *       here is a MISSED overlap - precisely the bug the guard exists to
     *       prevent.</li>
     *   <li><b>Enqueue</b>, catching {@link DataIntegrityViolationException} as
     *       {@code CONCURRENT_FIRING_LOST}: that is V208's unique index doing its
     *       job during an advisory-lock outage, not an error.</li>
     * </ol>
     */
    public void fireOrSkip(DueSchedule due, Instant now) {
        Instant next;
        try {
            next = due.cadence().nextAfter(due.timezone(), now);
        } catch (IllegalArgumentException unparseable) {
            // The cron was valid when it was saved (upsert parses it), so this
            // means a hand-edited row. Disable rather than log the same failure
            // every sixty seconds forever - identical posture, and identical
            // reasoning, to WorkflowService#advanceSchedule.
            log.error(
                "Agent schedule {} (agent {}) has an unparseable stored cadence '{}' in zone '{}' - disabling it",
                due.scheduleId(), due.agentId(), due.cadence().describe(), due.timezone(), unparseable);
            repository.disableUnparseable(due.scheduleId(), now);
            return;
        }
        if (next == null) {
            // A cron that can never fire again (a fixed date in the past). Same
            // treatment: a row the tick would otherwise re-examine forever.
            log.warn(
                "Agent schedule {} (agent {}) cadence '{}' has no future occurrence - disabling it",
                due.scheduleId(), due.agentId(), due.cadence().describe());
            repository.disableUnparseable(due.scheduleId(), now);
            return;
        }

        if (repository.claimOccurrence(due.scheduleId(), next, now, due.observedNextRunAt()) == 0) {
            return;
        }

        FireResult result = firing.fire(due, now, AgentScheduleRun.TRIGGER_SCHEDULE);
        if (result.skipReason() != null) {
            skipNotifier.recordSkip(due, result.skipReason(), result.live(), now);
        }
    }

    /**
     * THE OVERLAP RULE, as a pure function: no Spring, no database, no clock of
     * its own.
     *
     * <p>Pure on purpose. This is the single most important behaviour in the
     * feature - "a five-minute schedule on a twenty-minute job must not spawn
     * four concurrent runs" - and a rule that can only be exercised by standing
     * up Postgres and waiting twenty minutes is a rule that will not be tested at
     * all. Everything that needs I/O (claiming the slot, resolving the agent,
     * reading the live job) happens in {@link #fireOrSkip} and arrives here as
     * plain values.
     *
     * <p>All three of {@code QUEUED}, {@code RUNNING} and {@code
     * AWAITING_APPROVAL} block, which is exactly {@code AgentJobStatus#live()}:
     * QUEUED because a saturated pool must not be allowed to accumulate a queue
     * of identical goals that then all run at once, and AWAITING_APPROVAL because
     * it is NOT terminal - firing again would only stack a second copy behind the
     * same unanswered question.
     *
     * @return {@link FiringDecision.NotClaimed} only for a schedule that is no
     *     longer enabled (an admin disabled it between the due query and this
     *     decision). That is not a skip and must not touch the counters.
     */
    public static FiringDecision decide(AgentScheduleView schedule, Optional<AgentJobView> live, Instant now) {
        if (!schedule.enabled()) {
            return new FiringDecision.NotClaimed();
        }
        if (!schedule.agentUsable()) {
            return new FiringDecision.Skip(AgentScheduleSkipReason.AGENT_UNAVAILABLE);
        }
        if (live.isPresent()) {
            AgentScheduleSkipReason blocked = switch (live.get().status()) {
                case QUEUED -> AgentScheduleSkipReason.PREVIOUS_RUN_QUEUED;
                case RUNNING -> AgentScheduleSkipReason.PREVIOUS_RUN_RUNNING;
                case AWAITING_APPROVAL -> AgentScheduleSkipReason.PREVIOUS_RUN_AWAITING_APPROVAL;
                // A terminal previous run does not block anything - and this arm
                // is spelled out rather than left to a `default` so that adding a
                // state to AgentJobStatus fails to COMPILE here, instead of a new
                // non-terminal state silently defaulting to "fire anyway".
                case SUCCEEDED, FAILED, CANCELLED -> null;
            };
            if (blocked != null) {
                return new FiringDecision.Skip(blocked);
            }
        }
        Instant occurrenceAt = schedule.occurrenceAt() == null ? now : schedule.occurrenceAt();
        return new FiringDecision.Fire(occurrenceAt, renderGoal(schedule, occurrenceAt));
    }

    /**
     * Renders the goal template at ENQUEUE, so the goal stored on the job is
     * frozen and auditable even after the template is edited.
     *
     * <p>Exactly three substitutions, and an unknown <code>{{...}}</code> is left
     * LITERAL rather than blanked: a typo then shows up verbatim in the job's
     * goal - visible in the console, visible in the trace - instead of silently
     * deleting part of the instruction the agent was supposed to follow.
     *
     * <p>A sentinel schedule gets {@link SentinelVerdict#GOAL_CONTRACT} appended
     * AFTER substitution: the contract the sweep parses is the contract the
     * model was shown, and appending (rather than templating it in) keeps a
     * stray <code>{{...}}</code> in the contract text from colliding with the
     * substitution pass.
     */
    static String renderGoal(AgentScheduleView schedule, Instant occurrenceAt) {
        ZoneId zone = AgentCronSchedules.zoneOf(schedule.timezone());
        String scheduledAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(occurrenceAt.atZone(zone));
        String lastRunAt = schedule.lastFiredAt() == null
            ? "never"
            : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(schedule.lastFiredAt().atZone(zone));
        String rendered = schedule.goalTemplate()
            .replace("{{scheduledAt}}", scheduledAt)
            .replace("{{agentName}}", schedule.agentName() == null ? "this agent" : schedule.agentName())
            .replace("{{lastRunAt}}", lastRunAt);
        return schedule.sentinel() ? rendered + SentinelVerdict.GOAL_CONTRACT : rendered;
    }

    // --------------------------------------------------------------- internals

    static AgentScheduleTarget parseTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentScheduleTarget.AGENT;
        }
        try {
            return AgentScheduleTarget.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid schedule: targetType must be AGENT or WORKFLOW.");
        }
    }

    private AgentScheduleResponse respond(AgentSchedule schedule) {
        String lastJobStatus = schedule.getLastJobId() == null
            ? null
            : jobRepository.findById(schedule.getLastJobId()).map(AgentJob::getStatus).map(Enum::name).orElse(null);
        return AgentScheduleResponse.from(schedule, lastJobStatus);
    }

    // ------------------------------------------------------------------ values

    /**
     * One row of {@link #findDue}'s projection - everything {@link #fireOrSkip}
     * needs about a due schedule, captured before any decision is made.
     *
     * @param observedNextRunAt the slot this firing represents AND the
     *     compare-and-set target of {@link
     *     AgentScheduleRepository#claimOccurrence}. It is deliberately the same
     *     value for both: the occurrence a replica claims must be exactly the
     *     occurrence it then fires, or V208's per-occurrence unique index is
     *     guarding a different slot than the one that ran.
     */
    public record DueSchedule(
        UUID scheduleId,
        UUID agentId,
        String cron,
        Integer intervalSeconds,
        AgentScheduleTarget targetType,
        UUID workflowId,
        String timezone,
        String goalTemplate,
        Instant observedNextRunAt,
        Instant lastFiredAt,
        String createdBy,
        boolean sentinel
    ) {

        public AgentScheduleCadence cadence() {
            return AgentScheduleCadence.stored(cron, intervalSeconds);
        }
    }

    /**
     * The pure input to {@link #decide}: the schedule's own configuration plus
     * the two facts about its agent that the decision depends on.
     *
     * <p>Carrying {@code agentName}/{@code agentUsable} here rather than passing
     * an {@code Agent} is what keeps {@code decide} free of JPA - a lazily
     * initialisable entity in a pure function is a lazy-load exception waiting
     * for the first tick that runs outside a session.
     */
    public record AgentScheduleView(
        UUID id,
        UUID agentId,
        String timezone,
        String goalTemplate,
        boolean enabled,
        Instant occurrenceAt,
        Instant lastFiredAt,
        String agentName,
        boolean agentUsable,
        boolean sentinel
    ) {
    }

    /**
     * What {@link #decide} concluded. Sealed so that every consumer must handle
     * all three outcomes - in particular so that {@link NotClaimed} ("somebody
     * else owns this tick") can never be quietly lumped in with {@link Skip}
     * ("this firing was lost, tell the operator"), which are opposite things:
     * one is a healthy multi-replica deployment, the other is a signal.
     */
    public sealed interface FiringDecision {

        /**
         * @param occurrenceAt the SLOT, never {@code now} - it is half of V208's
         *     per-occurrence unique key, so two replicas firing the same slot a
         *     millisecond apart must produce the same value here or the index has
         *     nothing to collide on.
         */
        record Fire(Instant occurrenceAt, String goal) implements FiringDecision {
        }

        record Skip(AgentScheduleSkipReason reason) implements FiringDecision {
        }

        /** Not ours to fire, and not a skip: touch no counter, tell nobody. */
        record NotClaimed() implements FiringDecision {
        }
    }
}
