package com.agenticai.core.common.tutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import com.agenticai.core.tutor.StudyPlanService;
import com.agenticai.core.tutor.StudyPlanService.ReviewReminder;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The sweep is a scheduling shell: flags, one notification per claimed reminder, and failure
 * isolation. Who is due and the once-per-gap guard are {@code StudyPlanService#claimDueReminders}'s
 * (covered by {@code StudyPlanServiceTest}).
 */
class StudyReviewReminderSweepTest {

    private TenantSchemaIterator schemaIterator;
    private PgAdvisoryJobLock jobLock;
    private StudyPlanService studyPlans;
    private NotificationService notifications;

    @BeforeEach
    void setUp() {
        schemaIterator = mock(TenantSchemaIterator.class);
        jobLock = mock(PgAdvisoryJobLock.class);
        studyPlans = mock(StudyPlanService.class);
        notifications = mock(NotificationService.class);

        doAnswer(invocation -> {
            Runnable job = invocation.getArgument(1);
            job.run();
            return true;
        }).when(jobLock).tryRunExclusive(any(), any());
    }

    private void tenants(String... schemas) {
        doAnswer(invocation -> {
            Consumer<String> action = invocation.getArgument(0);
            for (String schema : schemas) {
                action.accept(schema);
            }
            return null;
        }).when(schemaIterator).forEachActiveTenantSchema(any());
    }

    private StudyReviewReminderSweep sweep(boolean tutorEnabled, boolean remindersEnabled) {
        return new StudyReviewReminderSweep(
            schemaIterator, jobLock, studyPlans, notifications, tutorEnabled, remindersEnabled, 20);
    }

    @Test
    void doesNothingUnlessBothFlagsAreOn() {
        sweep(false, true).sweep();
        sweep(true, false).sweep();
        sweep(false, false).sweep();

        verifyNoInteractions(jobLock, schemaIterator, studyPlans, notifications);
    }

    @Test
    void sendsOneNotificationPerClaimedReminder() {
        tenants("tenant_a");
        when(studyPlans.claimDueReminders(Duration.ofHours(20))).thenReturn(List.of(
            new ReviewReminder("alice", 3, "Photosynthesis", 1),
            new ReviewReminder("bob", 5, "Algebra", 2)));

        sweep(true, true).sweep();

        verify(notifications).create(
            eq("alice"), eq(NotificationCategory.SYSTEM), eq("3 reviews due in \"Photosynthesis\""), any(),
            eq(NotificationSeverity.INFO), eq("/learn/tutor?review=1"), isNull());
        verify(notifications).create(
            eq("bob"), eq(NotificationCategory.SYSTEM), eq("5 reviews due across 2 courses"), any(),
            eq(NotificationSeverity.INFO), eq("/learn/tutor?review=1"), isNull());
    }

    @Test
    void oneMembersFailedNotificationDoesNotStopTheOthers() {
        tenants("tenant_a");
        when(studyPlans.claimDueReminders(any())).thenReturn(List.of(
            new ReviewReminder("alice", 1, "Photosynthesis", 1),
            new ReviewReminder("bob", 2, "Algebra", 1)));
        when(notifications.create(eq("alice"), any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("db hiccup"));

        sweep(true, true).sweep();

        verify(notifications).create(eq("bob"), any(), eq("2 reviews due in \"Algebra\""), any(), any(), any(), any());
    }

    @Test
    void oneTenantsFailureDoesNotStopTheNextTenant() {
        tenants("tenant_a", "tenant_b");
        when(studyPlans.claimDueReminders(any()))
            .thenThrow(new IllegalStateException("tenant_a is broken"))
            .thenReturn(List.of(new ReviewReminder("carol", 1, "Chess", 1)));

        sweep(true, true).sweep();

        verify(studyPlans, times(2)).claimDueReminders(any());
        verify(notifications).create(eq("carol"), any(), eq("1 review due in \"Chess\""), any(), any(), any(), any());
    }

    @Test
    void titlesStayWithinTheNotificationTitleColumn() {
        String title = StudyReviewReminderSweep.title(new ReviewReminder("alice", 12, "x".repeat(300), 1));

        assertTrue(title.length() <= 255, title);
        assertTrue(title.startsWith("12 reviews due in \""), title);
        assertEquals("4 reviews due", StudyReviewReminderSweep.title(new ReviewReminder("alice", 4, null, 1)));
    }
}
