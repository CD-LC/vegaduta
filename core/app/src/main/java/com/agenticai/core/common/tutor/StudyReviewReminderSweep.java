package com.agenticai.core.common.tutor;

import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.agenticai.core.tutor.StudyPlanService;
import com.agenticai.core.tutor.StudyPlanService.ReviewReminder;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly review-due reminder for the Learn Anything tutor: one in-app notification per member whose
 * spaced-repetition cards have come due, at most once per {@code app.tutor.reminders.min-gap-hours}
 * (default 20). The bell is what pulls a learner back - nothing else in the tutor reaches out.
 *
 * <p>Pure database + notification work: no model call, so no token cost. The "who is due and has not
 * been reminded lately" decision and its bookkeeping ({@code lastRemindedAt} inside the plan's review
 * JSON - no extra column) live in {@link StudyPlanService#claimDueReminders}; this class only
 * schedules it per tenant and writes the notification.
 *
 * <p>Same operational shape as {@code AgentSupervisorSweep}: {@link TenantSchemaIterator} for
 * per-tenant {@code TenantContext}, {@link PgAdvisoryJobLock} so one replica sweeps per tick, and
 * per-tenant and per-member failure isolation. Off unless BOTH {@code app.tutor.enabled} and
 * {@code app.tutor.reminders.enabled} are true (both default false).
 */
@Component
public class StudyReviewReminderSweep {

    private static final Logger log = LoggerFactory.getLogger(StudyReviewReminderSweep.class);

    private static final String JOB_LOCK_NAME = "tutor-review-reminder-sweep";
    static final String REVIEW_LINK = "/learn/tutor?review=1";
    private static final int MAX_TOPIC_CHARS_IN_TITLE = 120;

    private final TenantSchemaIterator tenantSchemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final StudyPlanService studyPlans;
    private final NotificationService notifications;
    private final boolean enabled;
    private final Duration minGap;

    public StudyReviewReminderSweep(
        TenantSchemaIterator tenantSchemaIterator,
        PgAdvisoryJobLock jobLock,
        StudyPlanService studyPlans,
        NotificationService notifications,
        @Value("${app.tutor.enabled:false}") boolean tutorEnabled,
        @Value("${app.tutor.reminders.enabled:false}") boolean remindersEnabled,
        @Value("${app.tutor.reminders.min-gap-hours:20}") int minGapHours
    ) {
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.jobLock = jobLock;
        this.studyPlans = studyPlans;
        this.notifications = notifications;
        this.enabled = tutorEnabled && remindersEnabled;
        this.minGap = Duration.ofHours(Math.max(1, minGapHours));
    }

    @Scheduled(cron = "0 41 * * * *", zone = "UTC")
    public void sweep() {
        if (!enabled) {
            return;
        }
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::sweepExclusively);
    }

    private void sweepExclusively() {
        tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
            try {
                int sent = sweepTenant();
                if (sent > 0) {
                    log.info("Tutor review reminders: notified {} member(s) in tenant schema '{}'", sent, schema);
                }
            } catch (RuntimeException e) {
                log.error("Tutor review reminder sweep failed in tenant schema '{}'", schema, e);
            }
        });
    }

    int sweepTenant() {
        List<ReviewReminder> reminders = studyPlans.claimDueReminders(minGap);
        int sent = 0;
        for (ReviewReminder reminder : reminders) {
            try {
                notifications.create(
                    reminder.ownerSubject(), NotificationCategory.SYSTEM, title(reminder),
                    "A few minutes of review now keeps what you learned. Open your review queue to go through them.",
                    NotificationSeverity.INFO, REVIEW_LINK, null);
                sent++;
            } catch (RuntimeException e) {
                // One member's failed write must not stop the rest of the tenant's reminders.
                log.warn("Tutor review reminder could not be written for one member - skipping", e);
            }
        }
        return sent;
    }

    /** "3 reviews due in "Photosynthesis"", or "5 reviews due across 2 courses". Always within the 255-char title column. */
    static String title(ReviewReminder reminder) {
        String count = reminder.dueCount() == 1 ? "1 review due" : reminder.dueCount() + " reviews due";
        if (reminder.planCount() > 1 || reminder.topic() == null || reminder.topic().isBlank()) {
            return reminder.planCount() > 1 ? count + " across " + reminder.planCount() + " courses" : count;
        }
        String topic = reminder.topic().strip();
        if (topic.length() > MAX_TOPIC_CHARS_IN_TITLE) {
            topic = topic.substring(0, MAX_TOPIC_CHARS_IN_TITLE - 3) + "...";
        }
        return count + " in \"" + topic + "\"";
    }
}
