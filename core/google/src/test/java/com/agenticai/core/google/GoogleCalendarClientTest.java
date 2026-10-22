package com.agenticai.core.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GoogleCalendarClient} request-mapping and error-translation tests on the injected stub
 * transport (see {@link HttpStubs}). Calendar-specific mapping risks: the create body nests times
 * as {@code start.dateTime}/ {@code end.dateTime} objects (flat "start" strings are silently
 * invalid), attendees must be omitted entirely when absent (an empty attendees array still triggers
 * invite semantics), and all-day events come back with {@code start.date} instead of {@code
 * start.dateTime} - the list mapper must fall back or agents see null times for every all-day
 * event.
 */
class GoogleCalendarClientTest {

    private static final String TOKEN = "at-123";

    private GoogleCalendarClient client;
    private HttpStubs.RecordingHttpClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        GoogleOAuthService oauthService = mock(GoogleOAuthService.class);
        when(oauthService.currentAccessToken()).thenReturn(TOKEN);
        client = new GoogleCalendarClient(objectMapper, oauthService);
        http = HttpStubs.install(client);
    }

    @Test
    void createEventPostsNestedDateTimesAndAttendeesToThePrimaryCalendar() throws Exception {
        http.enqueue(
                200,
                "{\"id\":\"ev-1\",\"summary\":\"Sync\",\"htmlLink\":\"https://cal.google.com/ev-1\"}");

        GoogleCalendarClient.CalendarEvent event =
                client.createEvent(
                        "Sync",
                        "2026-08-10T15:00:00+05:30",
                        "2026-08-10T15:30:00+05:30",
                        List.of("a@example.com", "b@example.com"));

        HttpRequest request = http.onlyRequest();
        assertEquals(
                "https://www.googleapis.com/calendar/v3/calendars/primary/events",
                request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("Bearer " + TOKEN, request.headers().firstValue("Authorization").orElse(null));
        JsonNode body = objectMapper.readTree(HttpStubs.bodyOf(request));
        assertEquals("Sync", body.path("summary").asText());
        assertEquals("2026-08-10T15:00:00+05:30", body.path("start").path("dateTime").asText());
        assertEquals("2026-08-10T15:30:00+05:30", body.path("end").path("dateTime").asText());
        assertEquals("a@example.com", body.path("attendees").get(0).path("email").asText());
        assertEquals(2, body.path("attendees").size());

        // The returned event blends Google's response (id, htmlLink) with the
        // caller's requested times.
        assertEquals(
                new GoogleCalendarClient.CalendarEvent(
                        "ev-1",
                        "Sync",
                        "2026-08-10T15:00:00+05:30",
                        "2026-08-10T15:30:00+05:30",
                        "https://cal.google.com/ev-1"),
                event);
    }

    @Test
    void createEventWithoutAttendeesOmitsTheFieldEntirely() throws Exception {
        http.enqueue(200, "{\"id\":\"ev-2\"}");

        client.createEvent("Solo", "2026-08-10T15:00:00Z", "2026-08-10T16:00:00Z", null);

        JsonNode body = objectMapper.readTree(HttpStubs.bodyOf(http.onlyRequest()));
        assertTrue(
                body.path("attendees").isMissingNode(),
                "null/empty attendees must not serialize as attendees:[]");
    }

    @Test
    void createEventErrorTranslatesToGoogleApiExceptionWithStatusAndBody() {
        http.enqueue(403, "{\"error\":{\"message\":\"Insufficient Permission\"}}");

        GoogleApiException ex =
                assertThrows(
                        GoogleApiException.class,
                        () ->
                                client.createEvent(
                                        "Sync",
                                        "2026-08-10T15:00:00Z",
                                        "2026-08-10T16:00:00Z",
                                        null));

        assertTrue(ex.getMessage().contains("Could not create calendar event (403)"));
        assertTrue(ex.getMessage().contains("Insufficient Permission"));
    }

    @Test
    void listUpcomingClampsMaxResultsAndFallsBackToDateForAllDayEvents() {
        http.enqueue(
                200,
                "{\"items\":["
                        + "{\"id\":\"ev-1\",\"summary\":\"Standup\","
                        + "\"start\":{\"dateTime\":\"2026-08-08T09:00:00Z\"},\"end\":{\"dateTime\":\"2026-08-08T09:15:00Z\"},"
                        + "\"htmlLink\":\"https://cal/ev-1\"},"
                        + "{\"id\":\"ev-2\",\"summary\":\"Holiday\","
                        + "\"start\":{\"date\":\"2026-08-15\"},\"end\":{\"date\":\"2026-08-16\"}}]}");

        List<GoogleCalendarClient.CalendarEvent> events = client.listUpcoming(50);

        String uri = http.onlyRequest().uri().toString();
        assertTrue(
                uri.startsWith(
                        "https://www.googleapis.com/calendar/v3/calendars/primary/events?timeMin="));
        // 50 clamps to the API max of 20; recurring events must arrive
        // expanded and time-ordered.
        assertTrue(uri.contains("maxResults=20"));
        assertTrue(uri.contains("singleEvents=true&orderBy=startTime"));

        assertEquals(2, events.size());
        assertEquals("2026-08-08T09:00:00Z", events.get(0).start());
        // All-day events have no dateTime - date is the honest fallback.
        assertEquals("2026-08-15", events.get(1).start());
        assertEquals("2026-08-16", events.get(1).end());
    }

    @Test
    void listErrorTranslatesToGoogleApiExceptionWithStatus() {
        http.enqueue(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");

        GoogleApiException ex =
                assertThrows(GoogleApiException.class, () -> client.listUpcoming(5));

        assertTrue(ex.getMessage().contains("Google Calendar API call failed (429)"));
    }

    // ---- booking: busy time, move, cancel --------------------------------

    private static final Instant FROM = Instant.parse("2026-10-15T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-16T00:00:00Z");

    @Test
    void listBusySkipsFreeTimeAndReadsAllDayEventsInTheCalendarsZone() {
        // Shapes follow the documented events.list response
        // (https://developers.google.com/calendar/api/v3/reference/events/list).
        http.enqueue(
                200,
                "{\"timeZone\":\"Asia/Kolkata\",\"items\":["
                        + "{\"id\":\"busy\",\"start\":{\"dateTime\":\"2026-10-15T10:00:00+05:30\"},"
                        + "\"end\":{\"dateTime\":\"2026-10-15T11:00:00+05:30\"}},"
                        + "{\"id\":\"available\",\"transparency\":\"transparent\","
                        + "\"start\":{\"dateTime\":\"2026-10-15T12:00:00+05:30\"},\"end\":{\"dateTime\":\"2026-10-15T13:00:00+05:30\"}},"
                        + "{\"id\":\"cancelled\",\"status\":\"cancelled\","
                        + "\"start\":{\"dateTime\":\"2026-10-15T14:00:00+05:30\"},\"end\":{\"dateTime\":\"2026-10-15T15:00:00+05:30\"}},"
                        + "{\"id\":\"declined\",\"attendees\":[{\"email\":\"owner@example.com\",\"self\":true,\"responseStatus\":\"declined\"}],"
                        + "\"start\":{\"dateTime\":\"2026-10-15T16:00:00+05:30\"},\"end\":{\"dateTime\":\"2026-10-15T17:00:00+05:30\"}},"
                        + "{\"id\":\"holiday\",\"start\":{\"date\":\"2026-10-16\"},\"end\":{\"date\":\"2026-10-17\"}}"
                        + "]}");

        GoogleCalendarClient.BusyTime busy = client.listBusy(FROM, TO);

        String uri = http.onlyRequest().uri().toString();
        assertTrue(uri.contains("timeMin=2026-10-15T00:00:00Z&timeMax=2026-10-16T00:00:00Z"), uri);
        assertTrue(uri.contains("singleEvents=true"), uri);
        assertEquals(ZoneId.of("Asia/Kolkata"), busy.calendarZone());
        assertFalse(busy.truncated());
        assertEquals(
                List.of(
                        new GoogleCalendarClient.Interval(
                                Instant.parse("2026-10-15T04:30:00Z"),
                                Instant.parse("2026-10-15T05:30:00Z")),
                        // All-day: midnight to midnight in Asia/Kolkata, not in UTC.
                        new GoogleCalendarClient.Interval(
                                Instant.parse("2026-10-15T18:30:00Z"),
                                Instant.parse("2026-10-16T18:30:00Z"))),
                busy.busy());
    }

    @Test
    void listBusyFollowsPagesAndReportsTruncationPastTheCap() {
        for (int i = 0; i < GoogleCalendarClient.MAX_BUSY_PAGES; i++) {
            http.enqueue(200, "{\"items\":[],\"nextPageToken\":\"p" + (i + 1) + "\"}");
        }

        GoogleCalendarClient.BusyTime busy = client.listBusy(FROM, TO);

        assertEquals(GoogleCalendarClient.MAX_BUSY_PAGES, http.requests.size());
        assertTrue(http.requests.get(1).uri().toString().endsWith("&pageToken=p1"));
        assertTrue(
                busy.truncated(), "a window with more pages than the cap must not look complete");
    }

    @Test
    void moveEventPatchesOnlyTheTimesAndChoosesWhetherGoogleEmailsAttendees() throws Exception {
        http.enqueue(
                200,
                "{\"id\":\"ev 1\",\"summary\":\"Consultation\",\"htmlLink\":\"https://cal/ev\"}");

        GoogleCalendarClient.CalendarEvent moved =
                client.moveEvent(
                        "ev 1", "2026-10-15T15:00:00+05:30", "2026-10-15T15:30:00+05:30", true);

        HttpRequest request = http.onlyRequest();
        assertEquals("PATCH", request.method());
        assertEquals(
                "https://www.googleapis.com/calendar/v3/calendars/primary/events/ev%201?sendUpdates=all",
                request.uri().toString());
        JsonNode body = objectMapper.readTree(HttpStubs.bodyOf(request));
        assertEquals(
                2, body.size(), "PATCH must carry only start and end, keeping every other field");
        assertEquals("2026-10-15T15:00:00+05:30", body.path("start").path("dateTime").asText());
        assertEquals("Consultation", moved.summary());
    }

    @Test
    void cancelEventDeletesSilentlyWhenAskedAndTreatsGoneAsAlreadyCancelled() {
        http.enqueue(204, "");
        http.enqueue(410, "{\"error\":{\"message\":\"Resource has been deleted\"}}");

        assertEquals("cancelled", client.cancelEvent("ev-1", false));
        assertEquals("already_cancelled", client.cancelEvent("ev-1", false));

        HttpRequest first = http.requests.get(0);
        assertEquals("DELETE", first.method());
        assertTrue(first.uri().toString().endsWith("/events/ev-1?sendUpdates=none"));
    }

    @Test
    void cancelEventOfAnUnknownIdIsAnError() {
        http.enqueue(404, "{\"error\":{\"message\":\"Not Found\"}}");

        GoogleApiException ex =
                assertThrows(GoogleApiException.class, () -> client.cancelEvent("nope", true));

        assertTrue(ex.getMessage().contains("Could not cancel calendar event (404)"));
    }
}
