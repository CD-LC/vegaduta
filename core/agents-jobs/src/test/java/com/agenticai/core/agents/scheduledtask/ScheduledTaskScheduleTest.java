package com.agenticai.core.agents.scheduledtask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.scheduledtask.ScheduledTaskSchedule.Compiled;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskSchedule.Kind;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskSchedule.Spec;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure schedule maths: friendly schedule to cron, validation, minimum interval, next-run computation across
 * timezones / DST / month ends, the missed-run window and the plain-language description.
 */
class ScheduledTaskScheduleTest {

    private static final int MIN = 15;
    /** Sunday 2026-10-04. */
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    private static Compiled compile(Kind kind, String time, String day, String cron, String tz) {
        return ScheduledTaskSchedule.compile(new Spec(kind, time, day, cron), tz, MIN, NOW);
    }

    // ------------------------------------------------------------------ compile

    @Test
    void theFriendlyKindsCompileToNormalizedSixFieldCrons() {
        assertThat(compile(Kind.DAILY, "8:05", null, null, "UTC").cron()).isEqualTo("0 5 8 * * *");
        assertThat(compile(Kind.WEEKDAYS, "08:00", null, null, "UTC").cron()).isEqualTo("0 0 8 * * MON-FRI");
        assertThat(compile(Kind.WEEKLY, "17:30", "friday", null, "UTC").cron()).isEqualTo("0 30 17 * * FRI");
        assertThat(compile(Kind.MONTHLY, "09:00", "15", null, "UTC").cron()).isEqualTo("0 0 9 15 * *");
        assertThat(compile(Kind.MONTHLY, "09:00", "last", null, "UTC").cron()).isEqualTo("0 0 9 L * *");
    }

    @Test
    void theCompiledScheduleKeepsTheNormalizedPartsSoTheEditFormCanRoundTrip() {
        Compiled c = compile(Kind.WEEKLY, "7:00", "Mon", null, "Asia/Kolkata");
        assertThat(c.kind()).isEqualTo(Kind.WEEKLY);
        assertThat(c.time()).isEqualTo("07:00");
        assertThat(c.day()).isEqualTo("MON");
        assertThat(c.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(compile(Kind.MONTHLY, "10:00", "L", null, null).day()).isEqualTo("LAST");
        assertThat(compile(Kind.DAILY, "10:00", null, null, null).timezone()).isEqualTo("UTC");
    }

    @Test
    void aCustomFiveFieldCronIsNormalizedAndASixFieldOneIsKept() {
        assertThat(compile(Kind.CUSTOM, null, null, "30 6 * * 1-5", "UTC").cron()).isEqualTo("0 30 6 * * 1-5");
        assertThat(compile(Kind.CUSTOM, null, null, "0 0 6 * * MON", "UTC").cron()).isEqualTo("0 0 6 * * MON");
    }

    @Test
    void invalidSchedulesAreRejectedWithAUserFacingMessage() {
        assertThatThrownBy(() -> compile(Kind.DAILY, null, null, null, "UTC")).hasMessageContaining("time of day");
        assertThatThrownBy(() -> compile(Kind.DAILY, "25:00", null, null, "UTC")).hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> compile(Kind.DAILY, "08:60", null, null, "UTC")).hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> compile(Kind.WEEKLY, "08:00", null, null, "UTC")).hasMessageContaining("weekday");
        assertThatThrownBy(() -> compile(Kind.WEEKLY, "08:00", "Funday", null, "UTC")).hasMessageContaining("weekday");
        assertThatThrownBy(() -> compile(Kind.MONTHLY, "08:00", null, null, "UTC")).hasMessageContaining("day of the month");
        assertThatThrownBy(() -> compile(Kind.CUSTOM, null, null, null, "UTC")).hasMessageContaining("cron");
        assertThatThrownBy(() -> compile(Kind.CUSTOM, null, null, "not a cron", "UTC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compile(Kind.DAILY, "08:00", null, null, "Mars/Olympus")).hasMessageContaining("Unknown timezone");
        assertThatThrownBy(() -> ScheduledTaskSchedule.compile(new Spec(null, "08:00", null, null), "UTC", MIN, NOW))
            .hasMessageContaining("frequency");
        assertThatThrownBy(() -> ScheduledTaskSchedule.compile(null, "UTC", MIN, NOW)).hasMessageContaining("schedule is required");
        assertThatThrownBy(() -> Kind.parse("hourly")).hasMessageContaining("daily, weekdays, weekly, monthly or custom");
    }

    @Test
    void monthDays29To31AreRefusedBecauseShorterMonthsWouldBeSkipped() {
        for (String day : List.of("29", "30", "31", "0", "-1")) {
            assertThatThrownBy(() -> compile(Kind.MONTHLY, "08:00", day, null, "UTC"))
                .hasMessageContaining("use 'last'");
        }
    }

    @Test
    void secondLevelCronIsRefused() {
        assertThatThrownBy(() -> compile(Kind.CUSTOM, null, null, "30 0 8 * * *", "UTC")).hasMessageContaining("seconds field must be 0");
    }

    // ------------------------------------------------------- minimum interval

    @Test
    void aCronFasterThanTheMinimumIntervalIsRejected() {
        assertThatThrownBy(() -> compile(Kind.CUSTOM, null, null, "*/5 * * * *", "UTC"))
            .hasMessageContaining("more often than once every 15 minutes");
        assertThatThrownBy(() -> compile(Kind.CUSTOM, null, null, "* * * * *", "UTC"))
            .hasMessageContaining("more often than once every 15 minutes");
    }

    @Test
    void aCronExactlyAtTheMinimumIntervalIsAccepted() {
        Compiled c = compile(Kind.CUSTOM, null, null, "*/15 * * * *", "UTC");
        assertThat(c.cron()).isEqualTo("0 */15 * * * *");
    }

    @Test
    void anIrregularCronIsJudgedByItsClosestPairOfRuns() {
        // 08:00, 08:10 and 20:00: the 10-minute gap is what counts, not the 12-hour one.
        assertThatThrownBy(() -> compile(Kind.CUSTOM, null, null, "0,10 8 * * *", "UTC"))
            .hasMessageContaining("more often than once every 15 minutes");
    }

    @Test
    void theMinimumIntervalIsConfigurable() {
        assertThat(ScheduledTaskSchedule.compile(new Spec(Kind.CUSTOM, null, null, "*/5 * * * *"), "UTC", 5, NOW).cron())
            .isEqualTo("0 */5 * * * *");
        assertThatThrownBy(() -> ScheduledTaskSchedule.compile(new Spec(Kind.CUSTOM, null, null, "*/20 * * * *"), "UTC", 30, NOW))
            .hasMessageContaining("30 minutes");
    }

    @Test
    void aCronThatNeverFiresAgainIsRejected() {
        // Feb 30th does not exist.
        assertThatThrownBy(() -> compile(Kind.CUSTOM, null, null, "0 8 30 2 *", "UTC"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------- next runs

    @Test
    void dailyInKolkataFiresAtTheLocalTime() {
        // 08:00 IST (UTC+5:30, no DST) is 02:30 UTC.
        List<Instant> runs = ScheduledTaskSchedule.nextRuns("0 0 8 * * *", "Asia/Kolkata", NOW, 3);
        assertThat(runs).containsExactly(
            Instant.parse("2026-10-04T02:30:00Z"),
            Instant.parse("2026-10-05T02:30:00Z"),
            Instant.parse("2026-10-06T02:30:00Z"));
    }

    @Test
    void theRunsAreStrictlyAfterTheReferenceInstant() {
        Instant exactly = Instant.parse("2026-10-04T08:00:00Z");
        assertThat(ScheduledTaskSchedule.nextAfter("0 0 8 * * *", "UTC", exactly)).isEqualTo(Instant.parse("2026-10-05T08:00:00Z"));
    }

    @Test
    void weekdaysSkipTheWeekend() {
        assertThat(java.time.LocalDate.of(2026, 10, 2).getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        List<Instant> runs = ScheduledTaskSchedule.nextRuns(
            "0 0 8 * * MON-FRI", "UTC", Instant.parse("2026-10-02T12:00:00Z"), 3);
        assertThat(runs).containsExactly(
            Instant.parse("2026-10-05T08:00:00Z"),
            Instant.parse("2026-10-06T08:00:00Z"),
            Instant.parse("2026-10-07T08:00:00Z"));
    }

    @Test
    void weeklyFiresOnTheChosenDayOnly() {
        List<Instant> runs = ScheduledTaskSchedule.nextRuns("0 30 9 * * MON", "UTC", NOW, 2);
        assertThat(runs).containsExactly(Instant.parse("2026-10-05T09:30:00Z"), Instant.parse("2026-10-12T09:30:00Z"));
    }

    @Test
    void monthlyLastDayHandlesShortMonthsAndLeapFebruary() {
        List<Instant> runs = ScheduledTaskSchedule.nextRuns(
            "0 0 8 L * *", "UTC", Instant.parse("2026-01-31T09:00:00Z"), 3);
        assertThat(runs).containsExactly(
            Instant.parse("2026-02-28T08:00:00Z"),
            Instant.parse("2026-03-31T08:00:00Z"),
            Instant.parse("2026-04-30T08:00:00Z"));
        assertThat(ScheduledTaskSchedule.nextAfter("0 0 8 L * *", "UTC", Instant.parse("2028-02-01T00:00:00Z")))
            .isEqualTo(Instant.parse("2028-02-29T08:00:00Z"));
    }

    @Test
    void monthlyOnTheFifteenthFiresOncePerMonth() {
        List<Instant> runs = ScheduledTaskSchedule.nextRuns(
            "0 0 9 15 * *", "UTC", Instant.parse("2026-12-20T00:00:00Z"), 2);
        assertThat(runs).containsExactly(Instant.parse("2027-01-15T09:00:00Z"), Instant.parse("2027-02-15T09:00:00Z"));
    }

    @Test
    void springForwardMovesTheUtcInstantNotTheLocalTime() {
        // New York switches to DST on Sunday 2026-03-08. 08:00 local is 13:00Z before and 12:00Z after.
        List<Instant> runs = ScheduledTaskSchedule.nextRuns(
            "0 0 8 * * *", "America/New_York", Instant.parse("2026-03-07T12:00:00Z"), 3);
        assertThat(runs).containsExactly(
            Instant.parse("2026-03-07T13:00:00Z"),
            Instant.parse("2026-03-08T12:00:00Z"),
            Instant.parse("2026-03-09T12:00:00Z"));
        assertThat(Duration.between(runs.get(0), runs.get(1))).isEqualTo(Duration.ofHours(23));
    }

    @Test
    void fallBackMovesTheUtcInstantTheOtherWay() {
        // DST ends Sunday 2026-11-01: 08:00 local is 12:00Z (EDT) on Saturday and 13:00Z (EST) afterwards.
        List<Instant> runs = ScheduledTaskSchedule.nextRuns(
            "0 0 8 * * *", "America/New_York", Instant.parse("2026-10-31T13:00:00Z"), 2);
        assertThat(runs).containsExactly(Instant.parse("2026-11-01T13:00:00Z"), Instant.parse("2026-11-02T13:00:00Z"));
        assertThat(Duration.between(Instant.parse("2026-10-31T12:00:00Z"), runs.get(0))).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void aWallClockTimeInTheSpringForwardGapStillFiresOnceAndInOrder() {
        // 02:30 does not exist in New York on 2026-03-08. The cron library shifts it to the next real instant; what
        // matters here is that it fires, in order, and does not stall or double-fire.
        Instant from = Instant.parse("2026-03-07T12:00:00Z");
        List<Instant> runs = ScheduledTaskSchedule.nextRuns("0 30 2 * * *", "America/New_York", from, 4);
        assertThat(runs).hasSize(4).isSorted().doesNotHaveDuplicates();
        assertThat(runs.get(0)).isAfter(from).isBefore(from.plus(Duration.ofHours(48)));
        for (int i = 1; i < runs.size(); i++) {
            Duration gap = Duration.between(runs.get(i - 1), runs.get(i));
            assertThat(gap).isBetween(Duration.ofHours(20), Duration.ofHours(28));
        }
    }

    @Test
    void timezonesDecideWhichInstantIsTheNextEightAm() {
        Instant from = Instant.parse("2026-10-04T05:00:00Z");
        // 05:00Z is already 10:30 in Kolkata, so the next 08:00 there is tomorrow; in New York it is 01:00 so it is today.
        assertThat(ScheduledTaskSchedule.nextAfter("0 0 8 * * *", "Asia/Kolkata", from)).isEqualTo(Instant.parse("2026-10-05T02:30:00Z"));
        assertThat(ScheduledTaskSchedule.nextAfter("0 0 8 * * *", "America/New_York", from)).isEqualTo(Instant.parse("2026-10-04T12:00:00Z"));
        assertThat(ZoneId.of("America/New_York").getRules().isDaylightSavings(Instant.parse("2026-10-04T12:00:00Z"))).isTrue();
    }

    // ------------------------------------------------------- missed-run policy

    @Test
    void aDueRunIsStillFiredWithinTheCatchUpWindow() {
        Instant due = Instant.parse("2026-10-04T08:00:00Z");
        assertThat(ScheduledTaskSchedule.withinCatchUpWindow(due, due.plusSeconds(60), 720 * 60L)).isTrue();
        assertThat(ScheduledTaskSchedule.withinCatchUpWindow(due, due.plus(Duration.ofHours(6)), 720 * 60L)).isTrue();
        assertThat(ScheduledTaskSchedule.withinCatchUpWindow(due, due.plus(Duration.ofHours(12)), 720 * 60L)).isTrue();
    }

    @Test
    void aRunOlderThanTheWindowIsMissedNotReplayed() {
        Instant due = Instant.parse("2026-10-04T08:00:00Z");
        assertThat(ScheduledTaskSchedule.withinCatchUpWindow(due, due.plus(Duration.ofHours(12)).plusSeconds(1), 720 * 60L)).isFalse();
        assertThat(ScheduledTaskSchedule.withinCatchUpWindow(due, due.plus(Duration.ofDays(3)), 720 * 60L)).isFalse();
        assertThat(ScheduledTaskSchedule.withinCatchUpWindow(null, due, 0)).isTrue();
    }

    @Test
    void theNextSlotAfterDowntimeIsComputedFromNowSoThereIsNoBacklog() {
        // Three days of downtime on a daily task: one next slot, tomorrow - not three catch-up runs.
        Instant now = Instant.parse("2026-10-07T09:00:00Z");
        Instant next = ScheduledTaskSchedule.nextAfter("0 0 8 * * *", "UTC", now);
        assertThat(next).isEqualTo(Instant.parse("2026-10-08T08:00:00Z"));
    }

    // -------------------------------------------------------------- describe

    @Test
    void describeSpeaksPlainLanguageForEveryGeneratedShape() {
        assertThat(ScheduledTaskSchedule.describe("0 0 8 * * *", "UTC")).isEqualTo("Every day at 08:00 (UTC)");
        assertThat(ScheduledTaskSchedule.describe("0 30 7 * * MON-FRI", "Asia/Kolkata"))
            .isEqualTo("Every weekday (Monday to Friday) at 07:30 (Asia/Kolkata)");
        assertThat(ScheduledTaskSchedule.describe("0 0 17 * * FRI", "UTC")).isEqualTo("Every Friday at 17:00 (UTC)");
        assertThat(ScheduledTaskSchedule.describe("0 0 9 15 * *", "UTC")).isEqualTo("On day 15 of every month at 09:00 (UTC)");
        assertThat(ScheduledTaskSchedule.describe("0 0 9 L * *", "UTC")).isEqualTo("On the last day of every month at 09:00 (UTC)");
    }

    @Test
    void describeRecognisesNumericWeekdaysAndFallsBackToTheCronForUnknownShapes() {
        assertThat(ScheduledTaskSchedule.describe("0 0 8 * * 1-5", "UTC")).contains("Every weekday");
        assertThat(ScheduledTaskSchedule.describe("0 0 8 * * 0", "UTC")).contains("Every Sunday");
        assertThat(ScheduledTaskSchedule.describe("0 0 8 * * 7", "UTC")).contains("Every Sunday");
        assertThat(ScheduledTaskSchedule.describe("0 */15 * * * *", "UTC")).startsWith("Custom schedule (cron 0 */15 * * * *)");
        assertThat(ScheduledTaskSchedule.describe("0 0 8 1 6 *", "UTC")).startsWith("Custom schedule");
        assertThat(ScheduledTaskSchedule.describe(null, null)).startsWith("Custom schedule");
    }
}
