package com.agenticai.core.workflow;

import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.workflow.dto.WorkflowScheduleRequest;
import com.agenticai.core.workflow.dto.WorkflowScheduleResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cron schedule trigger (enable/disable/get, due-scan, scheduled run + advance), split out of
 * {@link WorkflowService}. Webhook, signature, poll and channel trigger configuration already live in
 * {@link WorkflowTriggerConfigService}, which the facade keeps delegating to (it depends back on the
 * facade for its gates, hence the lazy setter there).
 */
@Service
class WorkflowTriggerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerService.class);

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowAccessGuard guard;
    private final WorkflowRunService runService;

    /**
     * Smallest gap between two consecutive fires a workflow schedule may have (SE-13). Default 60
     * seconds, the finest the canvas's 5-field picker can express.
     */
    @Value("${app.workflow.schedule.min-interval-seconds:60}")
    private long scheduleMinIntervalSeconds = 60;

    WorkflowTriggerService(
            WorkflowDefinitionRepository definitionRepository,
            WorkflowAccessGuard guard,
            WorkflowRunService runService) {
        this.definitionRepository = definitionRepository;
        this.guard = guard;
        this.runService = runService;
    }

    /**
     * Schedule trigger (#166). Same governance requirement as {@link #enableWebhook}: the workflow
     * must already be {@link WorkflowStatus#ACTIVE}. {@code cron} is validated by attempting to
     * parse it with {@link CronExpression} - an invalid expression fails loudly here rather than
     * silently never firing.
     *
     * <p>Bug fix: {@code WorkflowCanvas.js}'s frequency picker (see {@code cronFromPreset} in that
     * file) generates a plain 5-field unix cron string (minute hour day-of-month month day-of-week
     * - nobody configuring a workflow ever types cron syntax themselves), but {@link
     * CronExpression} is Spring's own 6-field format (seconds first) and rejects a 5-field string
     * outright ("must consist of 6 fields, found 5"). {@link #normalizeCron} bridges the two by
     * prepending a "0" seconds field to any 5-field input before it ever reaches {@link
     * CronExpression#parse} - applied here AND persisted as the normalized 6-field string, so every
     * later re-parse of the stored value ({@link #advanceSchedule}, {@code WorkflowScheduler}) sees
     * the same already-valid format instead of needing this same fix repeated there.
     */
    @Transactional
    public WorkflowScheduleResponse enableSchedule(UUID id, WorkflowScheduleRequest request) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.EDITOR);
        guard.assertEditable(definition);
        guard.requireUnattendedAutomation();
        if (definition.getStatus() != WorkflowStatus.ACTIVE) {
            throw new WorkflowValidationException(
                    List.of("Activate this workflow before enabling its schedule trigger."));
        }
        String cron = WorkflowCronSupport.normalize(request.cron());
        CronExpression parsed;
        try {
            parsed = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new WorkflowValidationException(List.of("Invalid schedule: " + e.getMessage()));
        }
        assertScheduleInterval(parsed);
        Instant next = WorkflowCronSupport.nextRun(parsed, Instant.now());
        definition.enableSchedule(cron, next);
        return new WorkflowScheduleResponse(
                definition.getScheduleCron(), true, definition.getNextRunAt());
    }

    @Transactional
    public WorkflowScheduleResponse disableSchedule(UUID id) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.EDITOR);
        guard.assertEditable(definition);
        definition.disableSchedule();
        return new WorkflowScheduleResponse(definition.getScheduleCron(), false, null);
    }

    @Transactional(readOnly = true)
    public WorkflowScheduleResponse getSchedule(UUID id) {
        WorkflowDefinition definition = guard.requireAccess(id, PermissionLevel.VIEWER);
        return new WorkflowScheduleResponse(
                definition.getScheduleCron(),
                definition.isScheduleEnabled(),
                definition.getNextRunAt());
    }

    /**
     * {@code WorkflowScheduler}'s per-tick query - already tenant-scoped by the caller (see that
     * class's javadoc).
     */
    @Transactional(readOnly = true)
    public List<UUID> findDueScheduledWorkflowIds(Instant now) {
        return definitionRepository
                .findByScheduleEnabledTrueAndStatusAndNextRunAtLessThanEqual(
                        WorkflowStatus.ACTIVE, now)
                .stream()
                .map(WorkflowDefinition::getId)
                .toList();
    }

    /**
     * {@code WorkflowScheduler} calls this once per due workflow id. Not {@code @Transactional}
     * itself for the same reason {@link #run} isn't - the actual execution can make an outbound LLM
     * call. Advances past this occurrence in a separate small transaction regardless of whether the
     * run succeeded, so a schedule attached to a broken graph fails once per scheduled occurrence,
     * not once per scheduler tick forever.
     */
    public void runScheduledWorkflow(UUID id) {
        try {
            runService.run(id, "Triggered by schedule.", "system:workflow-schedule");
        } catch (RuntimeException e) {
            log.warn("Scheduled workflow {} failed to run: {}", id, e.getMessage());
        }
        advanceSchedule(id);
    }

    /**
     * Deliberately NOT {@code @Transactional} on this method itself - calling it from {@link
     * #runScheduledWorkflow} above is same-class self-invocation, which bypasses Spring's
     * transactional proxy entirely (a well-known Spring AOP limitation) and would make an
     * {@code @Transactional} here a silent no-op, leaving the mutation below un-flushed. Explicitly
     * calling {@link WorkflowDefinitionRepository#save} instead sidesteps the problem completely:
     * that call goes through the repository's own separate, always-real proxy, which commits
     * regardless of how this method was invoked.
     */
    private void advanceSchedule(UUID id) {
        WorkflowDefinition definition = guard.get(id);
        if (!definition.isScheduleEnabled() || definition.getScheduleCron() == null) {
            return;
        }
        try {
            Instant next =
                    WorkflowCronSupport.nextRun(
                            CronExpression.parse(definition.getScheduleCron()), Instant.now());
            definition.advanceSchedule(next);
        } catch (IllegalArgumentException e) {
            // Cron was valid when saved (enableSchedule validates it) - this
            // would only happen if a row was hand-edited in the database.
            // Disabling rather than looping forever on the same bad row.
            log.error(
                    "Workflow {} has an unparseable stored cron '{}' - disabling its schedule",
                    id,
                    definition.getScheduleCron());
            definition.disableSchedule();
        }
        definitionRepository.save(definition);
    }

    /**
     * Rejects a schedule whose consecutive fire times are closer than {@code
     * app.workflow.schedule.min-interval-seconds}. Looks at several consecutive fires, not just
     * two, so an irregular expression such as {@code 0 0,1 * * * *} cannot slip a short gap past
     * the check.
     */
    private void assertScheduleInterval(CronExpression parsed) {
        long min = scheduleMinIntervalSeconds;
        if (min <= 0) {
            return;
        }
        LocalDateTime previous = parsed.next(LocalDateTime.now(ZoneOffset.UTC));
        for (int i = 0; i < 12 && previous != null; i++) {
            LocalDateTime following = parsed.next(previous);
            if (following == null) {
                return;
            }
            long gap = java.time.Duration.between(previous, following).getSeconds();
            if (gap < min) {
                throw new WorkflowValidationException(
                        List.of(
                                "Schedule fires every "
                                        + gap
                                        + "s; the minimum interval between runs is "
                                        + min
                                        + "s."));
            }
            previous = following;
        }
    }
}
