package com.agenticai.core.google.agenttools;

import com.agenticai.core.google.GoogleCalendarClient;
import com.agenticai.core.guardrails.ToolConfirmationDescriber;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * The two calendar writes that change an appointment someone else may be relying on: {@code
 * google_calendar_move_event} and {@code google_calendar_cancel_event}. One class, two tool names
 * ({@link #move} / {@link #cancel}), because each must be its own name to be classified and gated
 * on its own: both are declared irreversible EGRESS in {@code BuiltInToolClassifications} (Google
 * emails every attendee when {@code notify_attendees} is true, and a moved or cancelled appointment
 * cannot be un-sent), so the confirmation gate asks a human before either runs. They are
 * deliberately NOT on the confirmation-exempt list that {@code google_calendar} is on.
 *
 * <p>{@code notify_attendees} is required, not defaulted: whether the customer hears about the
 * change is a decision the agent states and the approver sees.
 *
 * <p>The approval card shows {@link #describeForConfirmation}: which event, the new time, and
 * whether the customer will be emailed, built from the arguments alone (no Google call, so it is
 * cheap and cannot fail the gate).
 *
 * <p>Logs never carry the arguments or Google's error body (an event title is often a customer's
 * name); only the tool name and the exception class.
 */
public final class GoogleCalendarChangeEventToolCallback
        implements ToolCallback, ToolConfirmationDescriber {

    static final String MOVE = "google_calendar_move_event";
    static final String CANCEL = "google_calendar_cancel_event";

    private static final Logger log =
            LoggerFactory.getLogger(GoogleCalendarChangeEventToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final GoogleCalendarClient client;
    private final boolean cancelling;

    private GoogleCalendarChangeEventToolCallback(GoogleCalendarClient client, boolean cancelling) {
        this.client = client;
        this.cancelling = cancelling;
    }

    public static GoogleCalendarChangeEventToolCallback move(GoogleCalendarClient client) {
        return new GoogleCalendarChangeEventToolCallback(client, false);
    }

    public static GoogleCalendarChangeEventToolCallback cancel(GoogleCalendarClient client) {
        return new GoogleCalendarChangeEventToolCallback(client, true);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        if (cancelling) {
            return ToolDefinition.builder()
                    .name(CANCEL)
                    .description(
                            "Cancels (deletes) one event on the connected Google account's primary calendar by its "
                                    + "event id (from google_calendar list_upcoming or create_event). Needs human "
                                    + "approval. Set notify_attendees true to have Google email every attendee the "
                                    + "cancellation.")
                    .inputSchema(
                            """
                    {
                        "type": "object",
                        "properties": {
                            "event_id": {"type": "string"},
                            "notify_attendees": {"type": "boolean", "description": "true: Google emails each attendee; false: change silently"}
                        },
                        "required": ["event_id", "notify_attendees"],
                        "additionalProperties": false
                    }
                    """)
                    .build();
        }
        return ToolDefinition.builder()
                .name(MOVE)
                .description(
                        "Moves one event on the connected Google account's primary calendar to a new start and end "
                                + "(reschedule an appointment); title, attendees and description are kept. Needs human "
                                + "approval. Find a free time first with google_calendar_find_free_slots. Set "
                                + "notify_attendees true to have Google email every attendee the new time.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "event_id": {"type": "string"},
                        "start": {"type": "string", "description": "New RFC3339 start, e.g. 2026-10-15T15:00:00+05:30"},
                        "end": {"type": "string", "description": "New RFC3339 end, after start"},
                        "notify_attendees": {"type": "boolean", "description": "true: Google emails each attendee; false: change silently"}
                    },
                    "required": ["event_id", "start", "end", "notify_attendees"],
                    "additionalProperties": false
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String describeForConfirmation(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object eventId = args.get("event_id");
            Object notify = args.get("notify_attendees");
            String who =
                    Boolean.TRUE.equals(notify)
                            ? "Google will email every attendee about this change."
                            : Boolean.FALSE.equals(notify)
                                    ? "Attendees will not be notified."
                                    : "notify_attendees was not given; the call will be refused.";
            if (cancelling) {
                return "Cancel Google Calendar event " + eventId + ".\n" + who;
            }
            return "Reschedule Google Calendar event "
                    + eventId
                    + "\nNew time: "
                    + args.get("start")
                    + " to "
                    + args.get("end")
                    + "\n"
                    + who;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        String name = cancelling ? CANCEL : MOVE;
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String eventId =
                    args.get("event_id") == null ? null : args.get("event_id").toString().trim();
            if (eventId == null || eventId.isEmpty()) {
                return GoogleCalendarFreeSlotsToolCallback.errorJson("event_id is required");
            }
            if (!(args.get("notify_attendees") instanceof Boolean notify)) {
                return GoogleCalendarFreeSlotsToolCallback.errorJson(
                        "notify_attendees (true or false) is required");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            if (cancelling) {
                result.put("event_id", eventId);
                result.put("status", client.cancelEvent(eventId, notify));
            } else {
                String start = args.get("start") == null ? null : args.get("start").toString();
                String end = args.get("end") == null ? null : args.get("end").toString();
                if (start == null || end == null) {
                    return GoogleCalendarFreeSlotsToolCallback.errorJson(
                            "start and end are required");
                }
                if (!OffsetDateTime.parse(end).isAfter(OffsetDateTime.parse(start))) {
                    return GoogleCalendarFreeSlotsToolCallback.errorJson("end must be after start");
                }
                result.put("status", "moved");
                result.put("event", client.moveEvent(eventId, start, end, notify));
            }
            result.put("attendees_notified", notify);
            return MAPPER.writeValueAsString(result);
        } catch (DateTimeException e) {
            return GoogleCalendarFreeSlotsToolCallback.errorJson(
                    "start and end must be RFC3339 date-times with an offset, e.g. 2026-10-15T15:00:00+05:30");
        } catch (Exception e) {
            log.warn("{} tool call failed: {}", name, e.getClass().getSimpleName());
            return GoogleCalendarFreeSlotsToolCallback.errorJson(
                    "Google Calendar call failed: " + e.getMessage());
        }
    }
}
