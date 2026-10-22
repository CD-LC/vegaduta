package com.agenticai.core.google.agenttools;

import com.agenticai.core.google.CalendarFreeSlots;
import com.agenticai.core.google.GoogleCalendarClient;
import com.agenticai.core.google.GoogleCalendarClient.BusyTime;
import com.agenticai.core.google.GoogleCalendarClient.Interval;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code google_calendar_find_free_slots}: open appointment slots on the connected account's
 * primary calendar, the read half of booking. A separate tool name from {@code google_calendar} so
 * it can be classified as a plain read (no confirmation) while the writes that email attendees are
 * classified, and gated, on their own.
 *
 * <p>The busy-time read is padded by a day on each side, so a date-only window can be resolved in
 * the calendar's own time zone (only known from Google's answer) without a second request. Slots
 * come back as offset date-times in that zone, ready to pass to {@code google_calendar}'s {@code
 * create_event}.
 */
public class GoogleCalendarFreeSlotsToolCallback implements ToolCallback {

    static final String NAME = "google_calendar_find_free_slots";

    private static final Logger log =
            LoggerFactory.getLogger(GoogleCalendarFreeSlotsToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final int DEFAULT_MAX_SLOTS = 10;
    private static final int MAX_SLOTS = 50;

    private final GoogleCalendarClient client;
    private final Clock clock;

    public GoogleCalendarFreeSlotsToolCallback(GoogleCalendarClient client) {
        this(client, Clock.systemUTC());
    }

    GoogleCalendarFreeSlotsToolCallback(GoogleCalendarClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description(
                        "Finds open appointment slots on the connected Google account's primary calendar between "
                                + "two dates, inside working hours, skipping busy time. Read-only: it books nothing. "
                                + "Book a returned slot with google_calendar action 'create_event'. Slots start no "
                                + "earlier than now.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "from": {"type": "string", "description": "Window start: a date (2026-10-15) or an RFC3339 date-time"},
                        "to": {"type": "string", "description": "Window end: a date (inclusive, the whole day) or an RFC3339 date-time; at most 31 days after from"},
                        "duration_minutes": {"type": "integer", "minimum": 5, "maximum": 480, "description": "Slot length, default 30"},
                        "time_zone": {"type": "string", "description": "IANA zone for working hours and dates, e.g. Asia/Kolkata. Default: the calendar's own zone"},
                        "working_hours_start": {"type": "string", "description": "HH:mm, default 09:00"},
                        "working_hours_end": {"type": "string", "description": "HH:mm, default 18:00"},
                        "include_weekends": {"type": "boolean", "description": "Default false"},
                        "max_slots": {"type": "integer", "minimum": 1, "maximum": 50, "description": "Default 10"}
                    },
                    "required": ["from", "to"],
                    "additionalProperties": false
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String from = asString(args.get("from"));
            String to = asString(args.get("to"));
            if (from == null || to == null) {
                return errorJson("from and to are required");
            }
            int duration = intOr(args.get("duration_minutes"), 30);
            if (duration < 5 || duration > 480) {
                return errorJson("duration_minutes must be between 5 and 480");
            }
            int maxSlots =
                    Math.min(
                            Math.max(intOr(args.get("max_slots"), DEFAULT_MAX_SLOTS), 1),
                            MAX_SLOTS);
            LocalTime workStart =
                    LocalTime.parse(stringOr(args.get("working_hours_start"), "09:00"));
            LocalTime workEnd = LocalTime.parse(stringOr(args.get("working_hours_end"), "18:00"));
            ZoneId requestedZone = zoneOrNull(asString(args.get("time_zone")));

            // Pad by a day so a date-only bound resolves correctly in any zone (offsets are < 1
            // day).
            ZoneId probe = requestedZone == null ? ZoneId.of("UTC") : requestedZone;
            Instant padStart = startOf(from, probe).minus(Duration.ofDays(1));
            Instant padEnd = endOf(to, probe).plus(Duration.ofDays(1));
            if (Duration.between(padStart, padEnd)
                            .compareTo(CalendarFreeSlots.MAX_WINDOW.plusDays(3))
                    > 0) {
                return errorJson(
                        "the window may cover at most "
                                + CalendarFreeSlots.MAX_WINDOW.toDays()
                                + " days");
            }
            BusyTime busy = client.listBusy(padStart, padEnd);
            if (busy.truncated()) {
                return errorJson(
                        "this calendar has too many events in that window to read in one call; narrow the window");
            }
            ZoneId zone = requestedZone == null ? busy.calendarZone() : requestedZone;
            List<Interval> slots =
                    CalendarFreeSlots.find(
                            new CalendarFreeSlots.Request(
                                    startOf(from, zone),
                                    endOf(to, zone),
                                    clock.instant(),
                                    Duration.ofMinutes(duration),
                                    workStart,
                                    workEnd,
                                    Boolean.TRUE.equals(args.get("include_weekends")),
                                    zone,
                                    maxSlots),
                            busy.busy());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("time_zone", zone.getId());
            result.put("duration_minutes", duration);
            result.put(
                    "slots",
                    slots.stream()
                            .map(
                                    s ->
                                            Map.of(
                                                    "start",
                                                    format(s.start(), zone),
                                                    "end",
                                                    format(s.end(), zone)))
                            .toList());
            return MAPPER.writeValueAsString(result);
        } catch (IllegalArgumentException | DateTimeException e) {
            return errorJson("Invalid input: " + e.getMessage());
        } catch (Exception e) {
            log.warn("{} tool call failed: {}", NAME, e.getClass().getSimpleName());
            return errorJson("Google Calendar call failed: " + e.getMessage());
        }
    }

    /** A date means the start of that day in {@code zone}; a date-time is taken as given. */
    static Instant startOf(String value, ZoneId zone) {
        if (value.length() == 10) {
            return LocalDate.parse(value).atStartOfDay(zone).toInstant();
        }
        return OffsetDateTime.parse(value).toInstant();
    }

    /**
     * A date is inclusive (the window runs to the end of that day); a date-time is taken as given.
     */
    static Instant endOf(String value, ZoneId zone) {
        if (value.length() == 10) {
            return LocalDate.parse(value).plusDays(1).atStartOfDay(zone).toInstant();
        }
        return OffsetDateTime.parse(value).toInstant();
    }

    private static String format(Instant instant, ZoneId zone) {
        return instant.atZone(zone)
                .toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static ZoneId zoneOrNull(String zone) {
        if (zone == null || zone.isBlank()) {
            return null;
        }
        return ZoneId.of(zone.trim());
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Integer.parseInt(s.trim());
        }
        return fallback;
    }

    private static String stringOr(Object value, String fallback) {
        String s = asString(value);
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }
}
