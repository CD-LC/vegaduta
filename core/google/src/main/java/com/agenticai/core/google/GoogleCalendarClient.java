package com.agenticai.core.google;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Calendar v3, scoped to {@code calendar.events} and always against the connected account's
 * primary calendar - no multi-calendar selection in this MVP.
 */
@Component
public class GoogleCalendarClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String BASE =
            "https://www.googleapis.com/calendar/v3/calendars/primary/events";

    private final HttpClient httpClient = GuardedJdkHttpClient.defaults(TIMEOUT, TIMEOUT, false);
    private final ObjectMapper objectMapper;
    private final GoogleOAuthService oauthService;

    public GoogleCalendarClient(ObjectMapper objectMapper, GoogleOAuthService oauthService) {
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    public List<CalendarEvent> listUpcoming(int maxResults) {
        String uri =
                BASE
                        + "?timeMin="
                        + Instant.now()
                        + "&maxResults="
                        + Math.max(1, Math.min(maxResults, 20))
                        + "&singleEvents=true&orderBy=startTime";
        JsonNode body = get(uri);
        List<CalendarEvent> events = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            events.add(
                    new CalendarEvent(
                            item.path("id").asString(null),
                            item.path("summary").asString(null),
                            firstNonBlank(
                                    item.path("start").path("dateTime").asString(null),
                                    item.path("start").path("date").asString(null)),
                            firstNonBlank(
                                    item.path("end").path("dateTime").asString(null),
                                    item.path("end").path("date").asString(null)),
                            item.path("htmlLink").asString(null)));
        }
        return events;
    }

    /**
     * @param startIso/endIso RFC3339 date-times, e.g. "2026-07-10T15:00:00-07:00".
     */
    public CalendarEvent createEvent(
            String summary, String startIso, String endIso, List<String> attendeeEmails) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", summary);
        body.put("start", Map.of("dateTime", startIso));
        body.put("end", Map.of("dateTime", endIso));
        if (attendeeEmails != null && !attendeeEmails.isEmpty()) {
            body.put(
                    "attendees",
                    attendeeEmails.stream().map(email -> Map.of("email", email)).toList());
        }
        try {
            HttpRequest request =
                    authedRequest(BASE)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(body)))
                            .build();
            HttpResponse<String> response = send(request);
            if (response.statusCode() >= 300) {
                throw new GoogleApiException(
                        "Could not create calendar event ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            JsonNode created = objectMapper.readTree(response.body());
            return new CalendarEvent(
                    created.path("id").asString(null),
                    created.path("summary").asString(summary),
                    startIso,
                    endIso,
                    created.path("htmlLink").asString(null));
        } catch (GoogleApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleApiException("Could not create calendar event: " + e.getMessage(), e);
        }
    }

    /**
     * Pages of events read when collecting busy time; a window needing more is reported truncated
     * rather than read without bound.
     */
    static final int MAX_BUSY_PAGES = 5;

    /**
     * The busy time on the primary calendar between {@code from} and {@code to}, for free-slot
     * search. Built from {@code events.list} (not {@code freeBusy.query}) because the base consent
     * grants {@code calendar.events}, which {@code freeBusy.query} does not accept; reading events
     * needs no new scope and so no re-consent.
     *
     * <p>Skipped as not busy: cancelled events, events marked "show as available" ({@code
     * transparency=transparent}) and events the account itself declined. All-day events block their
     * whole day(s) in the calendar's own time zone.
     */
    public BusyTime listBusy(Instant from, Instant to) {
        List<Interval> busy = new ArrayList<>();
        ZoneId calendarZone = ZoneId.of("UTC");
        String pageToken = null;
        int pages = 0;
        do {
            String uri =
                    BASE
                            + "?timeMin="
                            + from
                            + "&timeMax="
                            + to
                            + "&singleEvents=true&orderBy=startTime&maxResults=250"
                            + (pageToken == null ? "" : "&pageToken=" + urlEncode(pageToken));
            JsonNode body = get(uri);
            calendarZone = zoneOr(body.path("timeZone").asString(null), calendarZone);
            for (JsonNode item : body.path("items")) {
                if (!blocksTime(item)) {
                    continue;
                }
                Instant start = instantOf(item.path("start"), calendarZone);
                Instant end = instantOf(item.path("end"), calendarZone);
                if (start != null && end != null && end.isAfter(start)) {
                    busy.add(new Interval(start, end));
                }
            }
            pageToken = body.path("nextPageToken").asString(null);
            pages++;
        } while (pageToken != null && !pageToken.isBlank() && pages < MAX_BUSY_PAGES);
        boolean truncated = pageToken != null && !pageToken.isBlank();
        return new BusyTime(busy, calendarZone, truncated);
    }

    /**
     * Moves an existing event to a new start and end ({@code PATCH}, so every other field is kept).
     *
     * @param notifyAttendees true sends Google's update email to every attendee ({@code
     *     sendUpdates=all}); false changes the calendar silently ({@code sendUpdates=none})
     */
    public CalendarEvent moveEvent(
            String eventId, String startIso, String endIso, boolean notifyAttendees) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("start", Map.of("dateTime", startIso));
        body.put("end", Map.of("dateTime", endIso));
        try {
            HttpRequest request =
                    authedRequest(eventUri(eventId, notifyAttendees))
                            .header("Content-Type", "application/json")
                            .method(
                                    "PATCH",
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(body)))
                            .build();
            HttpResponse<String> response = send(request);
            if (response.statusCode() >= 300) {
                throw new GoogleApiException(
                        "Could not move calendar event ("
                                + response.statusCode()
                                + "): "
                                + response.body());
            }
            JsonNode moved = objectMapper.readTree(response.body());
            return new CalendarEvent(
                    moved.path("id").asString(eventId),
                    moved.path("summary").asString(null),
                    startIso,
                    endIso,
                    moved.path("htmlLink").asString(null));
        } catch (GoogleApiException e) {
            throw e;
        } catch (Exception e) {
            throw new GoogleApiException("Could not move calendar event: " + e.getMessage(), e);
        }
    }

    /**
     * Cancels (deletes) an event. An event that is already gone ({@code 410}) counts as cancelled,
     * so a retried call is harmless.
     *
     * @return {@code "cancelled"} or {@code "already_cancelled"}
     */
    public String cancelEvent(String eventId, boolean notifyAttendees) {
        HttpRequest request = authedRequest(eventUri(eventId, notifyAttendees)).DELETE().build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 410) {
            return "already_cancelled";
        }
        if (response.statusCode() >= 300) {
            throw new GoogleApiException(
                    "Could not cancel calendar event ("
                            + response.statusCode()
                            + "): "
                            + response.body());
        }
        return "cancelled";
    }

    private static String eventUri(String eventId, boolean notifyAttendees) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("event id is required");
        }
        return BASE
                + "/"
                + urlEncode(eventId)
                + "?sendUpdates="
                + (notifyAttendees ? "all" : "none");
    }

    private static boolean blocksTime(JsonNode item) {
        if ("cancelled".equals(item.path("status").asString())) {
            return false;
        }
        if ("transparent".equals(item.path("transparency").asString())) {
            return false;
        }
        for (JsonNode attendee : item.path("attendees")) {
            if (attendee.path("self").asBoolean(false)
                    && "declined".equals(attendee.path("responseStatus").asString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * A {@code start}/{@code end} object: {@code dateTime} when timed, {@code date} (midnight in
     * the calendar's zone) when all-day.
     */
    private static Instant instantOf(JsonNode time, ZoneId calendarZone) {
        try {
            String dateTime = time.path("dateTime").asString(null);
            if (dateTime != null && !dateTime.isBlank()) {
                return OffsetDateTime.parse(dateTime).toInstant();
            }
            String date = time.path("date").asString(null);
            if (date != null && !date.isBlank()) {
                return LocalDate.parse(date).atStartOfDay(calendarZone).toInstant();
            }
        } catch (DateTimeException e) {
            return null;
        }
        return null;
    }

    private static ZoneId zoneOr(String zone, ZoneId fallback) {
        if (zone == null || zone.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            return fallback;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode get(String uri) {
        HttpRequest request = authedRequest(uri).GET().build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= 300) {
            throw new GoogleApiException(
                    "Google Calendar API call failed ("
                            + response.statusCode()
                            + "): "
                            + response.body());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not parse Google Calendar API response: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder authedRequest(String uri) {
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + oauthService.currentAccessToken());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new GoogleApiException(
                    "Could not reach the Google Calendar API: " + e.getMessage(), e);
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    public record CalendarEvent(
            String id, String summary, String start, String end, String htmlLink) {}

    /** A half-open busy interval {@code [start, end)}. */
    public record Interval(Instant start, Instant end) {}

    /**
     * @param calendarZone the calendar's own time zone as Google reports it (UTC when absent)
     * @param truncated true when the window held more events than {@link #MAX_BUSY_PAGES} pages, so
     *     later busy time is missing and the caller must not offer slots from it as free
     */
    public record BusyTime(List<Interval> busy, ZoneId calendarZone, boolean truncated) {}
}
