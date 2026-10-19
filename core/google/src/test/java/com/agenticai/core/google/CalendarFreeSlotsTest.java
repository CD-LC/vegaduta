package com.agenticai.core.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.google.GoogleCalendarClient.Interval;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CalendarFreeSlots} rules, one per test. Thursday 2026-10-15 in Asia/Kolkata (UTC+05:30)
 * unless a test says otherwise; working hours 09:00-12:00 keep the expected lists short.
 */
class CalendarFreeSlotsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant THU = Instant.parse("2026-10-14T18:30:00Z"); // Thu 00:00 IST
    private static final Instant FRI = Instant.parse("2026-10-15T18:30:00Z"); // Fri 00:00 IST

    private static CalendarFreeSlots.Request request(Instant from, Instant to, Instant notBefore, int minutes, boolean weekends) {
        return new CalendarFreeSlots.Request(
            from, to, notBefore, Duration.ofMinutes(minutes), LocalTime.of(9, 0), LocalTime.of(12, 0), weekends, IST, 50);
    }

    private static Instant ist(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
    }

    @Test
    void anEmptyDayIsSplitIntoBackToBackSlotsInsideWorkingHours() {
        List<Interval> slots = CalendarFreeSlots.find(request(THU, FRI, THU, 60, false), List.of());

        assertEquals(List.of(
            new Interval(ist("2026-10-15T09:00"), ist("2026-10-15T10:00")),
            new Interval(ist("2026-10-15T10:00"), ist("2026-10-15T11:00")),
            new Interval(ist("2026-10-15T11:00"), ist("2026-10-15T12:00"))), slots);
    }

    @Test
    void busyTimeIsSkippedAndTheSearchResumesOnTheHalfHourGrid() {
        // Busy 09:15-10:10: the 60-minute slot can start at 10:30 at the earliest (grid of 30), not 10:10.
        List<Interval> busy = List.of(new Interval(ist("2026-10-15T09:15"), ist("2026-10-15T10:10")));

        List<Interval> slots = CalendarFreeSlots.find(request(THU, FRI, THU, 60, false), busy);

        assertEquals(List.of(new Interval(ist("2026-10-15T10:30"), ist("2026-10-15T11:30"))), slots);
    }

    @Test
    void busyIntervalsAreHalfOpenSoAMeetingEndingAtTheSlotStartDoesNotBlockIt() {
        List<Interval> busy = List.of(new Interval(ist("2026-10-15T08:00"), ist("2026-10-15T09:00")));

        List<Interval> slots = CalendarFreeSlots.find(request(THU, FRI, THU, 180, false), busy);

        assertEquals(List.of(new Interval(ist("2026-10-15T09:00"), ist("2026-10-15T12:00"))), slots);
    }

    @Test
    void nothingIsOfferedBeforeNotBefore() {
        List<Interval> slots = CalendarFreeSlots.find(request(THU, FRI, ist("2026-10-15T10:05"), 60, false), List.of());

        assertEquals(List.of(new Interval(ist("2026-10-15T10:30"), ist("2026-10-15T11:30"))), slots);
    }

    @Test
    void weekendsAreSkippedUnlessAskedFor() {
        Instant sat = ist("2026-10-17T00:00");
        Instant sun = ist("2026-10-18T00:00");

        assertTrue(CalendarFreeSlots.find(request(sat, sun, sat, 60, false), List.of()).isEmpty());
        assertEquals(3, CalendarFreeSlots.find(request(sat, sun, sat, 60, true), List.of()).size());
    }

    @Test
    void aSlotMustEndInsideTheWindowNotJustStartInIt() {
        List<Interval> slots = CalendarFreeSlots.find(request(THU, ist("2026-10-15T10:30"), THU, 60, false), List.of());

        assertEquals(List.of(new Interval(ist("2026-10-15T09:00"), ist("2026-10-15T10:00"))), slots);
    }

    @Test
    void maxSlotsCapsTheAnswerAcrossDays() {
        CalendarFreeSlots.Request capped = new CalendarFreeSlots.Request(
            THU, ist("2026-10-22T00:00"), THU, Duration.ofMinutes(30), LocalTime.of(9, 0), LocalTime.of(12, 0), false, IST, 4);

        assertEquals(4, CalendarFreeSlots.find(capped, List.of()).size());
    }

    @Test
    void invalidRequestsAreRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> CalendarFreeSlots.find(request(FRI, THU, THU, 30, false), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> CalendarFreeSlots.find(request(THU, THU.plus(Duration.ofDays(32)), THU, 30, false), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> CalendarFreeSlots.find(request(THU, FRI, THU, 0, false), List.of()));
    }
}
