package com.agenticai.core.google;

import com.agenticai.core.google.GoogleCalendarClient.Interval;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Free appointment slots from a calendar's busy time: a pure function, no clock and no network, so
 * every rule below is unit-tested directly.
 *
 * <p>Rules: a slot lies wholly inside the day's working hours (in {@code zone}), wholly inside the
 * requested window, starts at or after {@code notBefore}, and overlaps no busy interval. Candidate
 * starts sit on a grid of {@code min(duration, 30 min)} from the start of working hours, and slots
 * returned on one day never overlap each other. Weekends (Saturday, Sunday) are skipped unless asked
 * for. A working day that ends at or before it starts is skipped.
 */
public final class CalendarFreeSlots {

    /** The longest window one search may cover; a wider one is refused rather than scanned slowly. */
    public static final Duration MAX_WINDOW = Duration.ofDays(31);

    private static final Duration MAX_STEP = Duration.ofMinutes(30);

    private CalendarFreeSlots() {
    }

    /** One search. All times are absolute; {@code zone} only decides where working hours fall. */
    public record Request(
        Instant windowStart,
        Instant windowEnd,
        Instant notBefore,
        Duration duration,
        LocalTime workStart,
        LocalTime workEnd,
        boolean includeWeekends,
        ZoneId zone,
        int maxSlots
    ) {
    }

    public static List<Interval> find(Request request, List<Interval> busy) {
        if (request.duration().isZero() || request.duration().isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw new IllegalArgumentException("the window end must be after its start");
        }
        if (Duration.between(request.windowStart(), request.windowEnd()).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("the window may cover at most " + MAX_WINDOW.toDays() + " days");
        }
        Duration step = request.duration().compareTo(MAX_STEP) < 0 ? request.duration() : MAX_STEP;
        Instant earliest = request.notBefore().isAfter(request.windowStart()) ? request.notBefore() : request.windowStart();
        List<Interval> slots = new ArrayList<>();
        LocalDate day = request.windowStart().atZone(request.zone()).toLocalDate();
        LocalDate lastDay = request.windowEnd().atZone(request.zone()).toLocalDate();
        while (!day.isAfter(lastDay) && slots.size() < request.maxSlots()) {
            if (request.includeWeekends() || !isWeekend(day)) {
                collectDay(request, busy, step, earliest, day, slots);
            }
            day = day.plusDays(1);
        }
        return slots;
    }

    private static void collectDay(
        Request request, List<Interval> busy, Duration step, Instant earliest, LocalDate day, List<Interval> slots) {
        ZonedDateTime dayStart = day.atTime(request.workStart()).atZone(request.zone());
        Instant dayEnd = day.atTime(request.workEnd()).atZone(request.zone()).toInstant();
        Instant start = dayStart.toInstant();
        while (slots.size() < request.maxSlots()) {
            Instant end = start.plus(request.duration());
            if (end.isAfter(dayEnd) || end.isAfter(request.windowEnd())) {
                return;
            }
            if (!start.isBefore(earliest) && isFree(start, end, busy)) {
                slots.add(new Interval(start, end));
                start = end;
            } else {
                start = start.plus(step);
            }
        }
    }

    private static boolean isFree(Instant start, Instant end, List<Interval> busy) {
        for (Interval b : busy) {
            if (b.start().isBefore(end) && b.end().isAfter(start)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWeekend(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
