package com.agenticai.core.platform.northstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.platform.northstar.NorthStarAggregator.FirstRun;
import com.agenticai.core.platform.northstar.NorthStarAggregator.PassKRow;
import com.agenticai.core.platform.northstar.NorthStarAggregator.RunBucket;
import com.agenticai.core.platform.northstar.NorthStarAggregator.RunKind;
import com.agenticai.core.platform.northstar.NorthStarDtos.ChannelPoint;
import com.agenticai.core.platform.northstar.NorthStarDtos.CohortPoint;
import com.agenticai.core.platform.northstar.NorthStarDtos.PassK;
import com.agenticai.core.platform.northstar.NorthStarDtos.WeekPoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pure aggregation and suppression rules: the 5-user cell floor, the
 * user-initiated-only weekly-active definition, complementary suppression for
 * pass^k and the honest-absence behaviour of empty windows.
 */
class NorthStarAggregatorTest {

    private static final LocalDate W1 = LocalDate.of(2026, 9, 14);
    private static final LocalDate W2 = LocalDate.of(2026, 9, 21);
    private static final LocalDate W3 = LocalDate.of(2026, 9, 28);
    private static final List<LocalDate> WEEKS = List.of(W1, W2, W3);

    private static RunBucket bucket(LocalDate week, RunKind kind, boolean success, String user, long runs) {
        return new RunBucket(week, kind, kind == RunKind.CHANNEL ? "WHATSAPP" : null, success, user, runs);
    }

    /** {@code users} distinct users, each with 2 successful runs of {@code kind}; keys never collide across kinds. */
    private static List<RunBucket> usersRunning(LocalDate week, RunKind kind, int users) {
        List<RunBucket> out = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            out.add(bucket(week, kind, true, "t:" + kind + i, 2));
        }
        return out;
    }

    @Test
    void weekStarts_endsWithTheMondayOfTheCurrentWeek() {
        // 2026-10-03 is a Saturday; its ISO week starts Monday 2026-09-28.
        assertEquals(W3, NorthStarAggregator.weekStartOf(LocalDate.of(2026, 10, 3)));
        assertEquals(W3, NorthStarAggregator.weekStartOf(W3));
        assertEquals(WEEKS, NorthStarAggregator.weekStarts(LocalDate.of(2026, 10, 3), 3));
    }

    @Test
    void classification_isClosedAndNeverGuesses() {
        assertEquals(RunKind.INTERACTIVE, NorthStarAggregator.kindForJobOrigin("API"));
        assertEquals(RunKind.SCHEDULED, NorthStarAggregator.kindForJobOrigin("SCHEDULE"));
        assertEquals(RunKind.TRIGGERED, NorthStarAggregator.kindForJobOrigin("EVENT"));
        assertEquals(RunKind.TRIGGERED, NorthStarAggregator.kindForJobOrigin("APPROVAL"));
        assertNull(NorthStarAggregator.kindForJobOrigin("SOMETHING_NEW"));
        assertNull(NorthStarAggregator.kindForJobOrigin(null));
        assertEquals(RunKind.INTERACTIVE, NorthStarAggregator.kindForQuickTaskChannel("LOG"));
        assertEquals(RunKind.CHANNEL, NorthStarAggregator.kindForQuickTaskChannel("WHATSAPP"));
        assertEquals(RunKind.CHANNEL, NorthStarAggregator.kindForQuickTaskChannel("SMS"));
        assertEquals(RunKind.CHANNEL, NorthStarAggregator.kindForQuickTaskChannel("CALL"));
        assertNull(NorthStarAggregator.kindForQuickTaskChannel("TELEGRAM"));
    }

    @Test
    void suppression_hidesOnlyOneToFourContributors() {
        assertFalse(NorthStarAggregator.isSuppressed(0));
        assertTrue(NorthStarAggregator.isSuppressed(1));
        assertTrue(NorthStarAggregator.isSuppressed(4));
        assertFalse(NorthStarAggregator.isSuppressed(5));
    }

    @Test
    void weekly_fiveUsersArePublishedFourAreHidden() {
        List<RunBucket> buckets = new ArrayList<>();
        buckets.addAll(usersRunning(W1, RunKind.INTERACTIVE, 5)); // published
        buckets.addAll(usersRunning(W2, RunKind.INTERACTIVE, 4)); // suppressed
        List<WeekPoint> weeks = NorthStarAggregator.weekly(buckets, WEEKS, W3);

        WeekPoint published = weeks.get(0);
        assertEquals(5L, published.wau());
        assertEquals(10L, published.runs());
        assertEquals(0.0, published.scheduledShare());
        assertEquals(1.0, published.successRate());
        assertFalse(published.suppressed());
        assertFalse(published.partial());

        WeekPoint hidden = weeks.get(1);
        assertTrue(hidden.suppressed());
        assertNull(hidden.wau());
        assertNull(hidden.runs());
        assertNull(hidden.scheduledShare());
        assertNull(hidden.successRate());
        assertNull(hidden.runsByKind());

        WeekPoint empty = weeks.get(2);
        assertTrue(empty.partial());
        assertEquals(0L, empty.wau());
        assertEquals(0L, empty.runs());
        assertNull(empty.successRate());
        assertFalse(empty.suppressed());
    }

    @Test
    void weekly_scheduledRunsCountAsRunsButNeverAsActiveUsers() {
        List<RunBucket> buckets = new ArrayList<>();
        buckets.addAll(usersRunning(W1, RunKind.SCHEDULED, 6)); // 6 schedule owners, 12 runs
        buckets.addAll(usersRunning(W1, RunKind.INTERACTIVE, 2)); // only 2 real active users, 4 runs
        WeekPoint w = NorthStarAggregator.weekly(buckets, WEEKS, W3).get(0);

        assertEquals(16L, w.runs()); // 8 distinct users contributed runs, so runs are published
        assertNull(w.wau()); // but only 2 active users, so WAU itself is suppressed
        assertTrue(w.suppressed());
        assertEquals(12.0 / 16.0, w.scheduledShare());
        assertEquals(12L, w.runsByKind().get("SCHEDULED"));
        assertEquals(4L, w.runsByKind().get("INTERACTIVE"));
    }

    @Test
    void weekly_failedRunsLowerSuccessRateAndDoNotMakeAUserActive() {
        List<RunBucket> buckets = new ArrayList<>();
        buckets.addAll(usersRunning(W1, RunKind.INTERACTIVE, 5)); // 10 successful runs
        buckets.add(bucket(W1, RunKind.INTERACTIVE, false, "t:failer", 10)); // 6th user, only failures
        WeekPoint w = NorthStarAggregator.weekly(buckets, WEEKS, W3).get(0);

        assertEquals(5L, w.wau());
        assertEquals(20L, w.runs());
        assertEquals(0.5, w.successRate());
    }

    @Test
    void weekly_ignoresRowsOutsideTheWindowAndNonPositiveCounts() {
        List<RunBucket> buckets = new ArrayList<>(usersRunning(W1, RunKind.INTERACTIVE, 5));
        buckets.add(bucket(LocalDate.of(2026, 1, 5), RunKind.INTERACTIVE, true, "t:old", 99));
        buckets.add(bucket(W1, RunKind.INTERACTIVE, true, "t:zero", 0));
        WeekPoint w = NorthStarAggregator.weekly(buckets, WEEKS, W3).get(0);
        assertEquals(5L, w.wau());
        assertEquals(10L, w.runs());
    }

    @Test
    void channels_areSplitByDeliveryChannelAndSuppressedPerChannel() {
        List<RunBucket> buckets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            buckets.add(new RunBucket(W1, RunKind.CHANNEL, "WHATSAPP", true, "t:u" + i, 3));
        }
        buckets.add(new RunBucket(W2, RunKind.CHANNEL, "WHATSAPP", false, "t:u0", 5));
        buckets.add(new RunBucket(W1, RunKind.CHANNEL, "CALL", true, "t:c1", 7));
        buckets.add(new RunBucket(W1, RunKind.INTERACTIVE, null, true, "t:x", 100)); // not channel-delivered
        List<ChannelPoint> channels = NorthStarAggregator.channels(buckets, WEEKS);

        assertEquals(2, channels.size());
        ChannelPoint wa = channels.get(0);
        assertEquals("WHATSAPP", wa.channel());
        assertEquals(20L, wa.runs());
        assertEquals(15.0 / 20.0, wa.successRate());
        assertFalse(wa.suppressed());
        ChannelPoint call = channels.get(1);
        assertEquals("CALL", call.channel());
        assertTrue(call.suppressed());
        assertNull(call.runs());
    }

    @Test
    void median_handlesOddEvenAndEmpty() {
        assertEquals(3.0, NorthStarAggregator.median(List.of(5.0, 1.0, 3.0)));
        assertEquals(2.5, NorthStarAggregator.median(List.of(4.0, 1.0, 2.0, 3.0)));
        assertEquals(1.2, NorthStarAggregator.median(List.of(1.24)));
        assertNull(NorthStarAggregator.median(List.of()));
        assertNull(NorthStarAggregator.median(null));
    }

    private static List<FirstRun> firstRuns(LocalDate week, int users, double startMinutes) {
        List<FirstRun> out = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            out.add(new FirstRun(week, "t:" + week + ":u" + i, startMinutes + i));
        }
        return out;
    }

    @Test
    void cohorts_publishMedianOnlyWhenFiveUsersActivated() {
        List<FirstRun> runs = new ArrayList<>();
        runs.addAll(firstRuns(W1, 5, 10)); // 10..14 -> median 12
        runs.addAll(firstRuns(W2, 3, 1)); // 3 activated -> suppressed
        Map<LocalDate, Long> signups = Map.of(W1, 9L, W2, 8L, W3, 0L);
        List<CohortPoint> cohorts = NorthStarAggregator.cohorts(runs, signups, WEEKS);

        CohortPoint c1 = cohorts.get(0);
        assertEquals(12.0, c1.medianMinutes());
        assertEquals(5L, c1.activated());
        assertEquals(9L, c1.signups());
        assertFalse(c1.suppressed());

        CohortPoint c2 = cohorts.get(1);
        assertTrue(c2.suppressed());
        assertNull(c2.medianMinutes());
        assertNull(c2.activated());
        assertNull(c2.signups());

        CohortPoint c3 = cohorts.get(2);
        assertEquals(0L, c3.activated());
        assertEquals(0L, c3.signups());
        assertNull(c3.medianMinutes());
        assertFalse(c3.suppressed());
    }

    @Test
    void cohorts_aSmallSignupCountIsSuppressedEvenWithNoActivations() {
        List<CohortPoint> cohorts = NorthStarAggregator.cohorts(List.of(), Map.of(W1, 3L), WEEKS);
        assertTrue(cohorts.get(0).suppressed());
        assertNull(cohorts.get(0).signups());
    }

    @Test
    void cohorts_withUnreadableSignupsShowNullSignupsNotAFalseZero() {
        List<CohortPoint> cohorts = NorthStarAggregator.cohorts(firstRuns(W1, 5, 1), null, WEEKS);
        assertNull(cohorts.get(0).signups());
        assertEquals(5L, cohorts.get(0).activated());
        assertNull(cohorts.get(2).signups());
    }

    @Test
    void cohorts_dropNegativeAndNonFiniteMinutes() {
        List<FirstRun> runs = new ArrayList<>(firstRuns(W1, 5, 10));
        runs.add(new FirstRun(W1, "t:bad1", -5));
        runs.add(new FirstRun(W1, "t:bad2", Double.NaN));
        CohortPoint c = NorthStarAggregator.cohorts(runs, Map.of(W1, 7L), WEEKS).get(0);
        assertEquals(5L, c.activated());
        assertEquals(12.0, c.medianMinutes());
    }

    @Test
    void overallMedian_needsFiveDistinctUsersInTheWindow() {
        assertNull(NorthStarAggregator.overallMedianMinutes(firstRuns(W1, 4, 10), WEEKS));
        assertNull(NorthStarAggregator.overallMedianMinutes(List.of(), WEEKS));
        List<FirstRun> runs = new ArrayList<>(firstRuns(W1, 3, 10)); // 10, 11, 12
        runs.addAll(firstRuns(W2, 2, 100)); // 100, 101 -> median of five = 12
        assertEquals(12.0, NorthStarAggregator.overallMedianMinutes(runs, WEEKS));
        runs.addAll(firstRuns(LocalDate.of(2025, 1, 6), 10, 0)); // outside the window
        assertEquals(12.0, NorthStarAggregator.overallMedianMinutes(runs, WEEKS));
    }

    private static PassKRow row(String user, int k, long cases, long passed) {
        return new PassKRow(user, k, cases, passed);
    }

    @Test
    void passK_isNullWithoutRepeatedRuns() {
        assertNull(NorthStarAggregator.passK(List.of()));
        assertNull(NorthStarAggregator.passK(List.of(row("t:a", 1, 10, 10))));
    }

    @Test
    void passK_averagesPerKAndOverall() {
        List<PassKRow> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("t:u" + i, 3, 10, 6)); // 50 cases, 30 all-passed
        }
        PassK p = NorthStarAggregator.passK(rows);
        assertNotNull(p);
        assertEquals(1, p.byK().size());
        assertEquals(3, p.byK().get(0).k());
        assertEquals(50L, p.byK().get(0).cases());
        assertEquals(0.6, p.byK().get(0).passPowK());
        assertEquals(0.6, p.average());
        assertEquals(50L, p.cases());
        assertFalse(p.suppressed());
    }

    @Test
    void passK_aSuppressedGroupIsExcludedFromTheAverageSoItCannotBeRecovered() {
        List<PassKRow> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("t:u" + i, 3, 10, 10)); // visible group: pass^3 = 1.0
        }
        rows.add(row("t:lonely", 5, 100, 0)); // 1 user at k=5 -> suppressed, must not drag the average
        PassK p = NorthStarAggregator.passK(rows);
        assertNotNull(p);
        assertTrue(p.suppressed());
        assertEquals(2, p.byK().size());
        assertTrue(p.byK().get(1).suppressed());
        assertNull(p.byK().get(1).passPowK());
        assertEquals(1.0, p.average());
        assertEquals(50L, p.cases());
    }

    @Test
    void passK_averageIsWithheldWhenFewerThanFiveUsersContributed() {
        PassK p = NorthStarAggregator.passK(List.of(row("t:a", 3, 10, 10), row("t:b", 3, 10, 5)));
        assertNotNull(p);
        assertNull(p.average());
        assertNull(p.cases());
        assertTrue(p.suppressed());
    }

    @Test
    void countSuppressed_sumsEveryCellKind() {
        List<WeekPoint> weeks = NorthStarAggregator.weekly(usersRunning(W1, RunKind.INTERACTIVE, 2), WEEKS, W3);
        List<CohortPoint> cohorts = NorthStarAggregator.cohorts(firstRuns(W1, 2, 1), Map.of(W1, 9L), WEEKS);
        List<ChannelPoint> channels = NorthStarAggregator.channels(
            List.of(new RunBucket(W1, RunKind.CHANNEL, "SMS", true, "t:a", 1)), WEEKS);
        PassK passK = NorthStarAggregator.passK(List.of(row("t:a", 2, 4, 4)));
        assertEquals(4, NorthStarAggregator.countSuppressed(weeks, cohorts, channels, passK));
        assertEquals(0, NorthStarAggregator.countSuppressed(List.of(), List.of(), List.of(), null));
    }
}
