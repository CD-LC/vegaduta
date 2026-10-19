package com.agenticai.core.google.agenttools;

import com.agenticai.core.google.GoogleCalendarClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Exposes {@link GoogleCalendarClient} as a native tool - see {@code GoogleDriveToolCallback}'s
 * javadoc for the shared action-parameter reasoning. Always the connected account's primary
 * calendar.
 */
public class GoogleCalendarToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final GoogleCalendarClient client;

    public GoogleCalendarToolCallback(GoogleCalendarClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("google_calendar")
                .description(
                        "Reads and creates events on the connected Google account's primary calendar. Actions: "
                                + "'list_upcoming' (upcoming events from now), 'create_event' (requires summary, start, end as "
                                + "RFC3339 date-times e.g. 2026-07-10T15:00:00-07:00, optional attendees as a comma-separated "
                                + "list of email addresses).")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "action": {"type": "string", "enum": ["list_upcoming", "create_event"]},
                        "summary": {"type": "string", "description": "Event title, required for 'create_event'"},
                        "start": {"type": "string", "description": "RFC3339 start date-time, required for 'create_event'"},
                        "end": {"type": "string", "description": "RFC3339 end date-time, required for 'create_event'"},
                        "attendees": {"type": "string", "description": "Optional comma-separated attendee emails for 'create_event'"}
                    },
                    "required": ["action"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String action = String.valueOf(args.get("action"));
            return switch (action) {
                case "list_upcoming" -> MAPPER.writeValueAsString(client.listUpcoming(10));
                case "create_event" -> {
                    String summary = asString(args.get("summary"));
                    String start = asString(args.get("start"));
                    String end = asString(args.get("end"));
                    if (summary == null || start == null || end == null) {
                        yield "{\"error\": \"summary, start, and end are all required for action=create_event\"}";
                    }
                    String attendeesRaw = asString(args.get("attendees"));
                    List<String> attendees =
                            attendeesRaw == null || attendeesRaw.isBlank()
                                    ? List.of()
                                    : List.of(attendeesRaw.split(","));
                    yield MAPPER.writeValueAsString(
                            client.createEvent(summary, start, end, attendees));
                }
                default -> "{\"error\": \"Unknown action - use list_upcoming or create_event\"}";
            };
        } catch (Exception e) {
            log.warn("google_calendar tool call failed", e);
            return errorJson("Google Calendar call failed: " + e.getMessage());
        }
    }

    // Same bug class fixed in KnowledgeSearchToolCallback - hand-built
    // "{\"error\": \"...\"}" strings break if the exception message
    // contains a raw '"'.
    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
