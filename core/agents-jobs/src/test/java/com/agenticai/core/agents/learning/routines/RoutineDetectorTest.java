package com.agenticai.core.agents.learning.routines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.learning.routines.RoutineDetector.Cadence;
import com.agenticai.core.agents.learning.routines.RoutineDetector.Obs;
import com.agenticai.core.agents.learning.routines.RoutineDetector.Routine;
import com.agenticai.core.agents.learning.routines.RoutineDetector.Settings;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 2026-10-05, 12 and 19 are Mondays; 2026-10-17 and 18 are the weekend. */
class RoutineDetectorTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Settings UTC = new Settings(0.6, 3, 3, 120, ZoneId.of("UTC"));
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    private static Obs obs(String text, String when) {
        return new Obs(AGENT, "chat", RoutineDetector.tokens(text), text, Instant.parse(when));
    }

    // ----------------------------------------------------------- normalisation

    @Test
    void wordingAroundTheSameIntentNormalisesToTheSameWords() {
        var a = RoutineDetector.tokens("Please summarise the unread emails from today");
        var b = RoutineDetector.tokens("summarise my unread email");
        assertEquals(b, a);
        assertEquals(1.0, RoutineDetector.jaccard(a, b));
    }

    @Test
    void differentIntentsAreNotSimilar() {
        var a = RoutineDetector.tokens("summarise my unread emails");
        var b = RoutineDetector.tokens("book a flight to Delhi");
        assertTrue(RoutineDetector.jaccard(a, b) < 0.2);
        assertEquals(0.0, RoutineDetector.jaccard(a, java.util.Set.of()));
    }

    @Test
    void onlySentencesAreWorthRemembering() {
        assertTrue(RoutineDetector.eligible("Summarise my unread emails"));
        assertFalse(RoutineDetector.eligible(null));
        assertFalse(RoutineDetector.eligible("ok thanks"));
        assertFalse(RoutineDetector.eligible("/clear the whole conversation please"));
        assertFalse(RoutineDetector.eligible("hi hi hi hi hi hi hi hi"));
        assertFalse(RoutineDetector.eligible("x".repeat(501)));
    }

    // ----------------------------------------------------------- cadence

    @Test
    void sameThingEveryMondayMorningIsAWeeklyRoutine() {
        List<Obs> all =
                List.of(
                        obs("Summarise my open pull requests", "2026-10-05T09:10:00Z"),
                        obs("Please summarise the open pull requests", "2026-10-12T08:55:00Z"),
                        obs("summarise my open pull requests today", "2026-10-19T09:05:00Z"),
                        obs("Book a flight to Delhi", "2026-10-13T20:00:00Z"));
        List<Routine> found = RoutineDetector.detect(all, UTC, NOW);
        assertEquals(1, found.size());
        Routine r = found.get(0);
        assertEquals(Cadence.WEEKLY, r.cadence());
        assertEquals("MON", r.day());
        assertEquals("09:05", r.time());
        assertEquals(3, r.occurrences());
        assertEquals(3, r.distinctDays());
        assertEquals(AGENT, r.agentId());
        assertEquals("0 5 9 * * MON", r.cron());
        assertEquals("Every Monday at 09:05 (UTC)", r.scheduleInWords());
        assertEquals("weekly", r.cadence().frequency());
    }

    @Test
    void weeklyAllowsAMissedWeek() {
        List<Obs> all =
                List.of(
                        obs("Summarise my open pull requests", "2026-10-05T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-10-12T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-10-26T09:00:00Z"));
        List<Routine> found = RoutineDetector.detect(all, UTC, Instant.parse("2026-10-27T10:00:00Z"));
        assertEquals(Cadence.WEEKLY, found.get(0).cadence());
    }

    @Test
    void consecutiveWeekdaysAreAWeekdayRoutine() {
        List<Obs> all =
                List.of(
                        obs("Check the overnight build failures", "2026-10-19T09:00:00Z"),
                        obs("check overnight build failures", "2026-10-20T09:10:00Z"),
                        obs("Check the overnight build failures please", "2026-10-21T08:50:00Z"));
        List<Routine> found = RoutineDetector.detect(all, UTC, Instant.parse("2026-10-21T12:00:00Z"));
        assertEquals(1, found.size());
        assertEquals(Cadence.WEEKDAYS, found.get(0).cadence());
        assertNull(found.get(0).day());
        assertEquals("0 0 9 * * MON-FRI", found.get(0).cron());
        assertEquals("Every weekday (Monday to Friday) at 09:00 (UTC)", found.get(0).scheduleInWords());
    }

    @Test
    void weekdaysSpanTheWeekendWithoutBreaking() {
        // Thursday, Friday, Monday: Friday to Monday is the next weekday.
        List<Obs> all =
                List.of(
                        obs("Check the overnight build failures", "2026-10-15T09:00:00Z"),
                        obs("Check the overnight build failures", "2026-10-16T09:00:00Z"),
                        obs("Check the overnight build failures", "2026-10-19T09:00:00Z"));
        assertEquals(Cadence.WEEKDAYS, RoutineDetector.detect(all, UTC, NOW).get(0).cadence());
    }

    @Test
    void askingOnTheWeekendToo_isDaily() {
        List<Obs> all =
                List.of(
                        obs("Read me the morning news headlines", "2026-10-16T07:00:00Z"),
                        obs("Read me the morning news headlines", "2026-10-17T07:10:00Z"),
                        obs("Read me the morning news headlines", "2026-10-18T06:55:00Z"));
        List<Routine> found = RoutineDetector.detect(all, UTC, Instant.parse("2026-10-18T12:00:00Z"));
        assertEquals(Cadence.DAILY, found.get(0).cadence());
        assertEquals("0 0 7 * * *", found.get(0).cron());
    }

    // ----------------------------------------------------------- what is NOT a routine

    @Test
    void twoRequestsAreNotEnough() {
        List<Obs> all =
                List.of(
                        obs("Summarise my open pull requests", "2026-10-12T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-10-19T09:00:00Z"));
        assertTrue(RoutineDetector.detect(all, UTC, NOW).isEmpty());
    }

    @Test
    void manyRequestsOnOneDayAreOneDay() {
        List<Obs> all =
                List.of(
                        obs("Summarise my open pull requests", "2026-10-19T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-10-19T09:10:00Z"),
                        obs("Summarise my open pull requests", "2026-10-19T09:20:00Z"));
        assertTrue(RoutineDetector.detect(all, UTC, NOW).isEmpty());
    }

    @Test
    void askedAtAllHoursIsATopicNotARhythm() {
        List<Obs> all =
                List.of(
                        obs("Summarise my open pull requests", "2026-10-05T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-10-12T15:00:00Z"),
                        obs("Summarise my open pull requests", "2026-10-19T21:00:00Z"));
        assertTrue(RoutineDetector.detect(all, UTC, NOW).isEmpty());
    }

    @Test
    void mondayWednesdayFridayIsNotDaily() {
        List<Obs> all =
                List.of(
                        obs("Summarise my open pull requests", "2026-10-19T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-10-21T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-10-23T09:00:00Z"));
        assertTrue(RoutineDetector.detect(all, UTC, Instant.parse("2026-10-23T12:00:00Z")).isEmpty());
    }

    @Test
    void irregularGapsAreNotARhythm() {
        List<Obs> all =
                List.of(
                        obs("Summarise my open pull requests", "2026-10-01T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-10-09T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-10-19T09:00:00Z"));
        assertTrue(RoutineDetector.detect(all, UTC, NOW).isEmpty());
    }

    @Test
    void aLapsedHabitIsNotProposed() {
        List<Obs> all =
                List.of(
                        obs("Summarise my open pull requests", "2026-09-14T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-09-21T09:00:00Z"),
                        obs("Summarise my open pull requests", "2026-09-28T09:00:00Z"));
        assertTrue(RoutineDetector.detect(all, UTC, NOW).isEmpty());
    }

    // ----------------------------------------------------------- time zone and prompt

    @Test
    void daysAndTimesAreReadInTheConfiguredZone() {
        // 03:30Z is 09:00 in Kolkata, still the same local Monday.
        Settings kolkata = new Settings(0.6, 3, 3, 120, ZoneId.of("Asia/Kolkata"));
        List<Obs> all =
                List.of(
                        obs("Summarise my open pull requests", "2026-10-05T03:30:00Z"),
                        obs("Summarise my open pull requests", "2026-10-12T03:30:00Z"),
                        obs("Summarise my open pull requests", "2026-10-19T03:30:00Z"));
        Routine r = RoutineDetector.detect(all, kolkata, NOW).get(0);
        assertEquals("09:00", r.time());
        assertEquals("MON", r.day());
        assertEquals("Asia/Kolkata", r.timezone());
        assertEquals("Every Monday at 09:00 (Asia/Kolkata)", r.scheduleInWords());
    }

    @Test
    void thePromptIsTheMostTypicalWording() {
        List<Obs> cluster = new ArrayList<>();
        cluster.add(obs("summarise unread emails", "2026-10-05T09:00:00Z"));
        cluster.add(obs("summarise unread emails inbox", "2026-10-12T09:00:00Z"));
        cluster.add(obs("summarise the unread emails inbox", "2026-10-19T09:00:00Z"));
        assertEquals("summarise unread emails inbox", RoutineDetector.representative(cluster).text());
    }

    @Test
    void weekdaysBetweenSkipsTheWeekend() {
        assertEquals(1, RoutineDetector.weekdaysBetween(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 19)));
        assertEquals(2, RoutineDetector.weekdaysBetween(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21)));
    }
}
