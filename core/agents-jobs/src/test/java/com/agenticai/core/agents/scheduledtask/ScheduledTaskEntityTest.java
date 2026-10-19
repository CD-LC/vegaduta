package com.agenticai.core.agents.scheduledtask;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.scheduledtask.ScheduledTaskSchedule.Kind;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskSchedule.Spec;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The counters and state changes on {@link ScheduledTask}: failure streak, auto-pause, run cap, expiry, edits. */
class ScheduledTaskEntityTest {

    private static final Instant NOW = Instant.parse("2026-10-04T08:00:00Z");

    private static ScheduledTask task(Integer maxRuns, Instant expiresAt) {
        ScheduledTaskSchedule.Compiled schedule = ScheduledTaskSchedule.compile(
            new Spec(Kind.DAILY, "08:00", null, null), "UTC", 15, NOW);
        return ScheduledTask.create(
            UUID.randomUUID(), UUID.randomUUID(), "Digest", "Summarise my mail", schedule,
            ScheduledTaskDelivery.IN_APP, null, maxRuns, expiresAt, "user-1", NOW.plusSeconds(3600), NOW);
    }

    @Test
    void aNewTaskIsEnabledWithItsFirstSlot() {
        ScheduledTask t = task(null, null);
        assertThat(t.isEnabled()).isTrue();
        assertThat(t.getNextRunAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(t.getCron()).isEqualTo("0 0 8 * * *");
        assertThat(t.getOwnerSubject()).isEqualTo("user-1");
        assertThat(t.getRunCount()).isZero();
        assertThat(t.getConsecutiveFailures()).isZero();
    }

    @Test
    void theTaskPausesItselfAtTheFailureThresholdAndSaysWhy() {
        ScheduledTask t = task(null, null);
        assertThat(t.recordFinalFailure(3, NOW)).isFalse();
        assertThat(t.recordFinalFailure(3, NOW)).isFalse();
        assertThat(t.isEnabled()).isTrue();
        assertThat(t.recordFinalFailure(3, NOW)).isTrue();
        assertThat(t.isEnabled()).isFalse();
        assertThat(t.getNextRunAt()).isNull();
        assertThat(t.getConsecutiveFailures()).isEqualTo(3);
        assertThat(t.getDisabledReason()).contains("3 failed runs in a row");
        assertThat(t.getLastStatus()).isEqualTo(ScheduledTask.STATUS_FAILED);
    }

    @Test
    void aSuccessResetsTheStreakSoOnlyConsecutiveFailuresCount() {
        ScheduledTask t = task(null, null);
        t.recordFinalFailure(3, NOW);
        t.recordFinalFailure(3, NOW);
        t.recordSuccess(NOW);
        assertThat(t.getConsecutiveFailures()).isZero();
        assertThat(t.recordFinalFailure(3, NOW)).isFalse();
        assertThat(t.isEnabled()).isTrue();
    }

    @Test
    void aCancelledOrSkippedRunDoesNotTouchTheStreak() {
        ScheduledTask t = task(null, null);
        t.recordFinalFailure(3, NOW);
        t.recordCancelled(NOW);
        assertThat(t.getConsecutiveFailures()).isEqualTo(1);
        assertThat(t.getLastStatus()).isEqualTo(ScheduledTask.STATUS_CANCELLED);
        t.recordSkipped(NOW);
        assertThat(t.getConsecutiveFailures()).isEqualTo(1);
        t.recordFailureWithoutStreak(NOW);
        assertThat(t.getConsecutiveFailures()).isEqualTo(1);
        assertThat(t.getLastStatus()).isEqualTo(ScheduledTask.STATUS_FAILED);
    }

    @Test
    void theRunCapPausesTheTaskOnTheLastAllowedRunButARetryDoesNotSpendIt() {
        ScheduledTask t = task(2, null);
        assertThat(t.recordStarted(UUID.randomUUID(), NOW, true)).isFalse();
        assertThat(t.getRunCount()).isEqualTo(1);
        assertThat(t.recordStarted(UUID.randomUUID(), NOW, false)).isFalse();
        assertThat(t.getRunCount()).isEqualTo(1);
        assertThat(t.recordStarted(UUID.randomUUID(), NOW, true)).isTrue();
        assertThat(t.getRunCount()).isEqualTo(2);
        assertThat(t.runCapReached()).isTrue();
        assertThat(t.isEnabled()).isFalse();
        assertThat(t.getDisabledReason()).contains("limit of 2 runs");
    }

    @Test
    void noRunCapMeansNeverCapped() {
        ScheduledTask t = task(null, null);
        for (int i = 0; i < 50; i++) {
            t.recordStarted(UUID.randomUUID(), NOW, true);
        }
        assertThat(t.runCapReached()).isFalse();
        assertThat(t.isEnabled()).isTrue();
    }

    @Test
    void expiryIsInclusiveOfTheInstantItself() {
        ScheduledTask t = task(null, NOW.plusSeconds(60));
        assertThat(t.expired(NOW)).isFalse();
        assertThat(t.expired(NOW.plusSeconds(59))).isFalse();
        assertThat(t.expired(NOW.plusSeconds(60))).isTrue();
        assertThat(task(null, null).expired(NOW.plusSeconds(999_999))).isFalse();
    }

    @Test
    void pauseKeepsTheConfigurationAndResumeClearsTheStreakAndReason() {
        ScheduledTask t = task(null, null);
        t.recordFinalFailure(1, NOW);
        assertThat(t.isEnabled()).isFalse();
        t.resume(NOW.plusSeconds(7200), NOW);
        assertThat(t.isEnabled()).isTrue();
        assertThat(t.getConsecutiveFailures()).isZero();
        assertThat(t.getDisabledReason()).isNull();
        assertThat(t.getNextRunAt()).isEqualTo(NOW.plusSeconds(7200));
        t.pause(NOW);
        assertThat(t.isEnabled()).isFalse();
        assertThat(t.getNextRunAt()).isNull();
        assertThat(t.getCron()).isEqualTo("0 0 8 * * *");
        assertThat(t.getPrompt()).isEqualTo("Summarise my mail");
    }

    @Test
    void editingAPausedTaskDoesNotResumeItButClearsTheStreak() {
        ScheduledTask t = task(null, null);
        t.recordFinalFailure(1, NOW);
        ScheduledTaskSchedule.Compiled weekly = ScheduledTaskSchedule.compile(
            new Spec(Kind.WEEKLY, "09:00", "MON", null), "UTC", 15, NOW);
        t.reconfigure("Renamed", "New prompt", weekly, ScheduledTaskDelivery.IN_APP, null, null, null, NOW.plusSeconds(1), NOW);
        assertThat(t.isEnabled()).isFalse();
        assertThat(t.getNextRunAt()).isNull();
        assertThat(t.getConsecutiveFailures()).isZero();
        assertThat(t.getName()).isEqualTo("Renamed");
        assertThat(t.getCron()).isEqualTo("0 0 9 * * MON");
        assertThat(t.getOwnerSubject()).isEqualTo("user-1");
    }

    @Test
    void aDeliveryAddressIsOnlyKeptForExternalDeliveries() {
        ScheduledTaskSchedule.Compiled schedule = ScheduledTaskSchedule.compile(
            new Spec(Kind.DAILY, "08:00", null, null), "UTC", 15, NOW);
        ScheduledTask inApp = ScheduledTask.create(
            UUID.randomUUID(), UUID.randomUUID(), "A", "p", schedule, ScheduledTaskDelivery.IN_APP, "ignored@example.com",
            null, null, "u", NOW, NOW);
        ScheduledTask email = ScheduledTask.create(
            UUID.randomUUID(), UUID.randomUUID(), "B", "p", schedule, ScheduledTaskDelivery.EMAIL, "me@example.com",
            null, null, "u", NOW, NOW);
        assertThat(inApp.getDeliveryTo()).isNull();
        assertThat(email.getDeliveryTo()).isEqualTo("me@example.com");
    }
}
