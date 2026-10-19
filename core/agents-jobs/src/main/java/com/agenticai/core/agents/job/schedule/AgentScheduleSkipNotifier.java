package com.agenticai.core.agents.job.schedule;

import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobView;
import com.agenticai.core.agents.job.schedule.AgentScheduleService.DueSchedule;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Skip bookkeeping for {@link AgentScheduleService}: the persisted reason and counters, the
 * threshold notification and the WARN line. Split out of that class to keep it readable; not a
 * bean - one instance is owned by the service. Logs under the service's logger.
 */
final class AgentScheduleSkipNotifier {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduleService.class);

    /**
     * How often the "this schedule keeps skipping" notification repeats once the
     * threshold has been crossed. A threshold plus a stride, never per-skip: a
     * five-minute schedule against a twenty-minute job skips roughly three times
     * per run FOREVER by design, and a bell on each one teaches operators to
     * ignore the bell - which is the only thing that would ever tell them about a
     * genuinely stuck schedule.
     */
    private static final int SKIP_NOTIFICATION_STRIDE = 10;

    private final AgentScheduleRepository repository;
    private final AgentJobProperties properties;
    private final NotificationService notificationService;
    private final MeterRegistry meterRegistry;

    AgentScheduleSkipNotifier(
        AgentScheduleRepository repository,
        AgentJobProperties properties,
        NotificationService notificationService,
        MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.properties = properties;
        this.notificationService = notificationService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records a skip and decides whether it is worth telling a human about.
     *
     * <p>Four layers of surfacing, none of which touch {@code core/audit}'s
     * outcome enum (adding {@code AuditOutcome.SKIPPED} was considered and
     * rejected - it buys nothing the schedule row does not already carry):
     * the persisted reason + counters, a threshold notification, an escalation
     * to ERROR when the blocking job has been stuck far too long, and a WARN log
     * that names the blocking job AND ITS AGE - the age being the number that
     * turns a support ticket into a diagnosis.
     */
    void recordSkip(
        DueSchedule due, AgentScheduleSkipReason reason, Optional<AgentJobView> live, Instant now
    ) {
        meterRegistry.counter("agent.schedule.skipped", "reason", reason.name()).increment();

        Optional<AgentSchedule> stored = repository.findById(due.scheduleId());
        stored.ifPresent(schedule -> {
            schedule.skipped(reason, now);
            repository.save(schedule);
        });
        int consecutive = stored.map(AgentSchedule::getConsecutiveSkipCount).orElse(1);

        Duration blockedFor = live
            .map(job -> Duration.between(job.createdAt() == null ? now : job.createdAt(), now))
            .orElse(Duration.ZERO);
        boolean severelyBlocked = live.isPresent()
            && blockedFor.compareTo(properties.getSchedule().getBlockedAlertAfter()) > 0;

        log.warn(
            "Agent schedule {} (agent {}) skipped occurrence {}: {} (blocking job {} status {} age {}); "
                + "consecutive skips {}",
            due.scheduleId(), due.agentId(), due.observedNextRunAt(), reason,
            live.map(AgentJobView::id).orElse(null),
            live.map(job -> job.status().name()).orElse("n/a"),
            blockedFor,
            consecutive
        );

        if (shouldNotify(consecutive)) {
            notify(due, reason, live, consecutive, blockedFor, severelyBlocked);
        }
    }

    /** At the threshold, then every {@link #SKIP_NOTIFICATION_STRIDE} skips after it. */
    private boolean shouldNotify(int consecutive) {
        int threshold = properties.getSchedule().getSkipNotificationThreshold();
        if (consecutive < threshold) {
            return false;
        }
        return (consecutive - threshold) % SKIP_NOTIFICATION_STRIDE == 0;
    }

    /**
     * Broadcast to tenant-admins ({@code null} recipient), best-effort by
     * contract: a notification-write failure must never become a second failure
     * on top of a schedule that has merely skipped - same posture every other
     * caller of {@link NotificationService} in this codebase takes.
     *
     * <p>Never auto-disables the schedule and never auto-cancels the blocking
     * job. Both would be this code deciding, unattended, to throw away work a
     * human asked for; cancellation stays an explicit operator action, which is
     * what the deep link in the notification is for.
     */
    private void notify(
        DueSchedule due, AgentScheduleSkipReason reason, Optional<AgentJobView> live,
        int consecutive, Duration blockedFor, boolean severelyBlocked
    ) {
        try {
            String body = "The schedule for this agent has skipped " + consecutive
                + " firings in a row (" + reason + ")."
                + live.map(job -> " The previous run " + job.id() + " has been " + job.status()
                    + " for " + humanize(blockedFor) + ".").orElse("")
                + (severelyBlocked
                    ? " That is far longer than a run should take - cancel it if it is stuck."
                    : " This is normal if each run legitimately takes longer than the interval between firings.");
            notificationService.create(
                null,
                NotificationCategory.SYSTEM,
                "Scheduled agent run keeps being skipped",
                body,
                severelyBlocked ? NotificationSeverity.ERROR : NotificationSeverity.WARNING,
                "/agents/" + due.agentId(),
                null
            );
        } catch (RuntimeException e) {
            log.warn("Could not record a skip notification for agent schedule {}", due.scheduleId(), e);
        }
    }

    private static String humanize(Duration duration) {
        long minutes = duration.toMinutes();
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h" + (minutes % 60) + "m";
    }
}
