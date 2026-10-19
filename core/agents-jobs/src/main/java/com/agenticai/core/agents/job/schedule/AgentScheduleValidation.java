package com.agenticai.core.agents.job.schedule;

import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Configuration-time checks for {@link AgentScheduleService}: cadence, minimum interval, target
 * consistency, plan entitlement, tenant capacity and the configuring identity. Each failure is
 * the 400 (or 404 / plan error) the caller sees. Split out of the service to keep it readable;
 * not a bean - one instance is owned by the service.
 */
final class AgentScheduleValidation {

    private final AgentScheduleRepository repository;
    private final AgentJobProperties properties;
    private final ObjectProvider<ScheduledWorkflowRunner> workflowRunner;
    private final TenantLimits tenantLimits;

    AgentScheduleValidation(
        AgentScheduleRepository repository,
        AgentJobProperties properties,
        ObjectProvider<ScheduledWorkflowRunner> workflowRunner,
        TenantLimits tenantLimits
    ) {
        this.repository = repository;
        this.properties = properties;
        this.workflowRunner = workflowRunner;
        this.tenantLimits = tenantLimits;
    }

    /** Parses the cadence and returns its first firing, or 400s with the parser's own message. */
    Instant requireFireable(AgentScheduleCadence cadence, String timezone, Instant now) {
        Instant next;
        try {
            next = cadence.nextAfter(timezone, now);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid schedule: " + e.getMessage()
                + (cadence.isInterval() || AgentCronSchedules.hasSpringFieldCount(cadence.cron())
                    ? ""
                    : " Use a 5-field cron such as '0 9 * * *' (09:00 daily)."));
        }
        if (next == null) {
            throw new IllegalArgumentException(
                "Invalid schedule: '" + cadence.describe() + "' has no future occurrence, so it would never fire.");
        }
        return next;
    }

    /**
     * Rejects CRON cadences faster than {@code
     * app.agent-jobs.schedule.min-interval-minutes}.
     *
     * <p>Not arbitrary throttling: with only four worker threads per replica, a
     * per-minute schedule against a job that takes any real time produces a
     * schedule that spends its entire life skipping, which looks exactly like a
     * broken feature. Measured between the schedule's OWN next two firings rather
     * than parsed out of the cron string, so an irregular expression
     * ({@code 0 0,1,2 * * *}) is judged by what it actually does.
     *
     * <p>Interval schedules are allowed down to {@link
     * AgentScheduleCadence#MIN_INTERVAL_SECONDS} (one minute) - the product
     * requirement - and are bounded by the overlap guard like any other.
     */
    void requireMinimumInterval(AgentScheduleCadence cadence, String timezone, Instant firstRun) {
        if (cadence.isInterval()) {
            return;
        }
        Duration gap = cadence.gapAfter(timezone, firstRun);
        if (gap == null) {
            return;
        }
        int minMinutes = properties.getSchedule().getMinIntervalMinutes();
        if (gap.toMinutes() < minMinutes) {
            throw new IllegalArgumentException(
                "Invalid schedule: '" + cadence.cron() + "' fires more often than once every " + minMinutes
                    + " minutes. An agent run takes minutes, so a faster cadence would spend its life being skipped "
                    + "by the overlap guard. (Use intervalSeconds for a fixed cadence of a minute or more.)");
        }
    }

    /** AGENT: workflowId must be absent. WORKFLOW: it must be present and the workflow must exist. */
    UUID requireTargetConsistency(AgentScheduleTarget target, UUID workflowId) {
        if (target == AgentScheduleTarget.AGENT) {
            if (workflowId != null) {
                throw new IllegalArgumentException(
                    "Invalid schedule: workflowId is only allowed when targetType is WORKFLOW.");
            }
            return null;
        }
        if (workflowId == null) {
            throw new IllegalArgumentException("Invalid schedule: targetType WORKFLOW requires workflowId.");
        }
        ScheduledWorkflowRunner runner = workflowRunner.getIfAvailable();
        if (runner == null || !runner.exists(workflowId)) {
            throw new NoSuchElementException("Workflow " + workflowId + " was not found");
        }
        return workflowId;
    }

    void requireAutomationAllowed() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.automationAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                "Your plan does not include unattended automation - upgrade your plan to schedule agents."
            );
        }
    }

    void requireTenantCapacity() {
        int max = properties.getSchedule().getMaxPerTenant();
        if (repository.countByEnabledTrue() >= max) {
            throw new IllegalArgumentException(
                "This tenant already has the maximum of " + max + " enabled agent schedules. "
                    + "Disable one before adding another.");
        }
    }

    String currentSubject() {
        return TenantContext.current()
            .map(TenantContext::getSubject)
            .orElseThrow(() -> new IllegalStateException(
                "A schedule cannot be configured without an authenticated tenant context - every firing runs with "
                    + "the configuring human's identity, so there has to be one"));
    }
}
