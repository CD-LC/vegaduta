package com.agenticai.core.google.agenttools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.google.GoogleCalendarClient;
import com.agenticai.core.google.GoogleCalendarClient.BusyTime;
import com.agenticai.core.google.GoogleCalendarClient.Interval;
import com.agenticai.core.google.GoogleSheetsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The booking tools (free slots, move, cancel) and Sheets append, at the tool boundary: argument
 * validation, what reaches the client, and the risk each tool name is declared with.
 */
class GoogleBookingToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);

    private final GoogleCalendarClient calendar = mock(GoogleCalendarClient.class);

    @Test
    void freeSlotsResolveADateOnlyWindowInTheCalendarsOwnZone() throws Exception {
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        when(calendar.listBusy(from.capture(), to.capture())).thenReturn(new BusyTime(
            List.of(new Interval(Instant.parse("2026-10-15T03:30:00Z"), Instant.parse("2026-10-15T05:30:00Z"))),
            ZoneId.of("Asia/Kolkata"), false));

        JsonNode out = MAPPER.readTree(new GoogleCalendarFreeSlotsToolCallback(calendar, NOW).call(
            "{\"from\":\"2026-10-15\",\"to\":\"2026-10-15\",\"duration_minutes\":60,"
                + "\"working_hours_start\":\"09:00\",\"working_hours_end\":\"13:00\"}"));

        // Read padded a day each side, so the zone (unknown until Google answers) cannot cut the window.
        assertEquals(Instant.parse("2026-10-14T00:00:00Z"), from.getValue());
        assertEquals(Instant.parse("2026-10-17T00:00:00Z"), to.getValue());
        assertEquals("Asia/Kolkata", out.path("time_zone").asText());
        // Busy 09:00-11:00 IST: the first free hour is 11:00, then 12:00.
        assertEquals(2, out.path("slots").size(), out.toString());
        assertEquals("2026-10-15T11:00:00+05:30", out.path("slots").get(0).path("start").asText());
        assertEquals("2026-10-15T13:00:00+05:30", out.path("slots").get(1).path("end").asText());
    }

    @Test
    void freeSlotsRefuseATruncatedCalendarRatherThanOfferBusyTimeAsFree() throws Exception {
        when(calendar.listBusy(any(), any())).thenReturn(new BusyTime(List.of(), ZoneId.of("UTC"), true));

        JsonNode out = MAPPER.readTree(new GoogleCalendarFreeSlotsToolCallback(calendar, NOW).call(
            "{\"from\":\"2026-10-15\",\"to\":\"2026-10-16\"}"));

        assertTrue(out.path("error").asText().contains("narrow the window"), out.toString());
    }

    @Test
    void freeSlotsValidateInputBeforeCallingGoogle() throws Exception {
        GoogleCalendarFreeSlotsToolCallback tool = new GoogleCalendarFreeSlotsToolCallback(calendar, NOW);

        assertTrue(MAPPER.readTree(tool.call("{\"from\":\"2026-10-15\"}")).has("error"));
        assertTrue(MAPPER.readTree(tool.call(
            "{\"from\":\"2026-10-15\",\"to\":\"2026-10-16\",\"duration_minutes\":1}")).has("error"));
        assertTrue(MAPPER.readTree(tool.call("{\"from\":\"2026-10-01\",\"to\":\"2026-12-31\"}")).has("error"));
        assertTrue(MAPPER.readTree(tool.call(
            "{\"from\":\"2026-10-15\",\"to\":\"2026-10-16\",\"time_zone\":\"Mars/Base\"}")).has("error"));
        verify(calendar, never()).listBusy(any(), any());
    }

    @Test
    void moveRequiresAnExplicitNotifyChoiceAndAnEndAfterStart() throws Exception {
        GoogleCalendarChangeEventToolCallback move = GoogleCalendarChangeEventToolCallback.move(calendar);

        assertTrue(MAPPER.readTree(move.call(
            "{\"event_id\":\"e1\",\"start\":\"2026-10-15T10:00:00+05:30\",\"end\":\"2026-10-15T11:00:00+05:30\"}"))
            .path("error").asText().contains("notify_attendees"));
        assertTrue(MAPPER.readTree(move.call(
            "{\"event_id\":\"e1\",\"start\":\"2026-10-15T11:00:00+05:30\",\"end\":\"2026-10-15T10:00:00+05:30\","
                + "\"notify_attendees\":true}")).path("error").asText().contains("after start"));
        verify(calendar, never()).moveEvent(anyString(), anyString(), anyString(), anyBoolean());

        when(calendar.moveEvent("e1", "2026-10-15T10:00:00+05:30", "2026-10-15T11:00:00+05:30", true))
            .thenReturn(new GoogleCalendarClient.CalendarEvent("e1", "Consultation", "s", "e", "link"));
        JsonNode out = MAPPER.readTree(move.call(
            "{\"event_id\":\"e1\",\"start\":\"2026-10-15T10:00:00+05:30\",\"end\":\"2026-10-15T11:00:00+05:30\","
                + "\"notify_attendees\":true}"));
        assertEquals("moved", out.path("status").asText());
        assertTrue(out.path("attendees_notified").asBoolean());
    }

    @Test
    void cancelPassesTheNotifyChoiceThrough() throws Exception {
        when(calendar.cancelEvent("e1", false)).thenReturn("cancelled");

        JsonNode out = MAPPER.readTree(GoogleCalendarChangeEventToolCallback.cancel(calendar).call(
            "{\"event_id\":\"e1\",\"notify_attendees\":false}"));

        assertEquals("cancelled", out.path("status").asText());
        assertEquals(false, out.path("attendees_notified").asBoolean());
        verify(calendar).cancelEvent("e1", false);
    }

    @Test
    void movesAndCancelsNeedAHumanWhileFreeSlotSearchDoesNot() {
        assertEquals(GoogleCalendarChangeEventToolCallback.MOVE,
            GoogleCalendarChangeEventToolCallback.move(calendar).getToolDefinition().name());
        assertEquals(GoogleCalendarChangeEventToolCallback.CANCEL,
            GoogleCalendarChangeEventToolCallback.cancel(calendar).getToolDefinition().name());
        for (String writer : List.of(GoogleCalendarChangeEventToolCallback.MOVE, GoogleCalendarChangeEventToolCallback.CANCEL)) {
            ToolClassification c = BuiltInToolClassifications.all().get(writer);
            assertTrue(c != null && c.requiresHumanConfirmation(), writer + " must require human confirmation");
        }
        ToolClassification read = BuiltInToolClassifications.all().get(GoogleCalendarFreeSlotsToolCallback.NAME);
        assertTrue(read != null && !read.requiresHumanConfirmation() && !read.irreversible());
    }

    @Test
    void sheetsAppendDelegatesAndCapsTheBatch() throws Exception {
        GoogleSheetsClient sheets = mock(GoogleSheetsClient.class);
        when(sheets.appendRows("s1", "Leads", List.of(List.of("Asha", "")))).thenReturn(
            new GoogleSheetsClient.AppendResult("Leads!A2:B2", 1));
        GoogleSheetsToolCallback tool = new GoogleSheetsToolCallback(sheets);

        JsonNode out = MAPPER.readTree(tool.call(
            "{\"action\":\"append_rows\",\"spreadsheet_id\":\"s1\",\"range\":\"Leads\",\"rows\":[[\"Asha\",null]]}"));
        assertEquals("Leads!A2:B2", out.path("updatedRange").asText());

        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i <= GoogleSheetsToolCallback.MAX_APPEND_ROWS; i++) {
            many.append(i == 0 ? "" : ",").append("[\"x\"]");
        }
        JsonNode tooMany = MAPPER.readTree(tool.call(
            "{\"action\":\"append_rows\",\"spreadsheet_id\":\"s1\",\"range\":\"Leads\",\"rows\":" + many + "]}"));
        assertTrue(tooMany.path("error").asText().contains("at most"), tooMany.toString());
    }

    @Test
    void theApprovalCardSaysWhatWillHappenAndWhetherTheCustomerIsEmailed() {
        String move = GoogleCalendarChangeEventToolCallback.move(calendar).describeForConfirmation(
            "{\"event_id\":\"e1\",\"start\":\"2026-10-15T15:00:00+05:30\",\"end\":\"2026-10-15T15:30:00+05:30\","
                + "\"notify_attendees\":true}");
        assertTrue(move.contains("Reschedule Google Calendar event e1"), move);
        assertTrue(move.contains("2026-10-15T15:00:00+05:30 to 2026-10-15T15:30:00+05:30"), move);
        assertTrue(move.contains("email every attendee"), move);

        String cancel = GoogleCalendarChangeEventToolCallback.cancel(calendar).describeForConfirmation(
            "{\"event_id\":\"e1\",\"notify_attendees\":false}");
        assertTrue(cancel.startsWith("Cancel Google Calendar event e1."), cancel);
        assertTrue(cancel.contains("will not be notified"), cancel);

        assertEquals(null, GoogleCalendarChangeEventToolCallback.cancel(calendar).describeForConfirmation("not json"));
    }
}
