package com.agenticai.core.agents.agui;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * One AG-UI (Agent-User Interaction Protocol) event, as the wire JSON object a client's {@code
 * HttpAgent} parses out of each {@code data:} line.
 *
 * <h2>Shape</h2>
 *
 * AG-UI defines a flat union: every event has {@code type} (an upper-snake string) and an optional
 * {@code timestamp} (epoch millis), and each type adds its own handful of camelCase fields. Rather
 * than sixteen record classes and a polymorphic serializer, this is ONE record carrying the
 * superset of fields, serialized with {@link JsonInclude.Include#NON_NULL} so each event only ever
 * shows the fields its type defines - a {@code TEXT_MESSAGE_CONTENT} is exactly {@code {type,
 * timestamp, messageId, delta}} on the wire, nothing else. Field names are the protocol's own; the
 * record components are camelCase so Jackson needs no renaming.
 *
 * <p>The static factories are the only intended constructors: they are what pins each type to its
 * required fields, and what {@link AgUiEventMapper}'s unit test compares against.
 *
 * <h2>Which of the sixteen core events this server produces</h2>
 *
 * Lifecycle: {@code RUN_STARTED}, {@code RUN_FINISHED}, {@code RUN_ERROR}. Text: {@code
 * TEXT_MESSAGE_START/CONTENT/END}. Tools: {@code TOOL_CALL_START/ARGS/END/RESULT}. State: {@code
 * STATE_SNAPSHOT} (once, right after {@code RUN_STARTED}, so a client can bind state before any
 * {@code STATE_DELTA} could ever arrive - none is sent today). Plus {@code CUSTOM} for the
 * platform's own run-level vocabulary ({@code plan}, {@code guardrail}, {@code handoff}) that has
 * no standard AG-UI equivalent. {@code STEP_STARTED/FINISHED}, {@code STATE_DELTA}, {@code
 * MESSAGES_SNAPSHOT} and {@code RAW} are not emitted; a compliant client treats every one of them
 * as optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgUiEvent(
        String type,
        Long timestamp,
        // RUN_STARTED / RUN_FINISHED
        String threadId,
        String runId,
        // TEXT_MESSAGE_* and TOOL_CALL_RESULT
        String messageId,
        String role,
        // TEXT_MESSAGE_CONTENT and TOOL_CALL_ARGS
        String delta,
        // TOOL_CALL_*
        String toolCallId,
        String toolCallName,
        String parentMessageId,
        // TOOL_CALL_RESULT
        String content,
        // RUN_ERROR
        String message,
        String code,
        // STATE_SNAPSHOT
        Map<String, Object> snapshot,
        // CUSTOM
        String name,
        Map<String, Object> value) {

    /**
     * Single-line only - the SSE framing note in {@code AgentStepEventEmitter} applies here
     * verbatim.
     */
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    public static AgUiEvent runStarted(String threadId, String runId, long timestamp) {
        return new AgUiEvent(
                "RUN_STARTED",
                timestamp,
                threadId,
                runId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgUiEvent runFinished(String threadId, String runId, long timestamp) {
        return new AgUiEvent(
                "RUN_FINISHED",
                timestamp,
                threadId,
                runId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgUiEvent runError(String message, String code, long timestamp) {
        return new AgUiEvent(
                "RUN_ERROR",
                timestamp,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                message,
                code,
                null,
                null,
                null);
    }

    public static AgUiEvent stateSnapshot(Map<String, Object> snapshot, long timestamp) {
        return new AgUiEvent(
                "STATE_SNAPSHOT",
                timestamp,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                snapshot,
                null,
                null);
    }

    public static AgUiEvent textMessageStart(String messageId, long timestamp) {
        return new AgUiEvent(
                "TEXT_MESSAGE_START",
                timestamp,
                null,
                null,
                messageId,
                "assistant",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgUiEvent textMessageContent(String messageId, String delta, long timestamp) {
        return new AgUiEvent(
                "TEXT_MESSAGE_CONTENT",
                timestamp,
                null,
                null,
                messageId,
                null,
                delta,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgUiEvent textMessageEnd(String messageId, long timestamp) {
        return new AgUiEvent(
                "TEXT_MESSAGE_END",
                timestamp,
                null,
                null,
                messageId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgUiEvent toolCallStart(
            String toolCallId, String toolCallName, String parentMessageId, long timestamp) {
        return new AgUiEvent(
                "TOOL_CALL_START",
                timestamp,
                null,
                null,
                null,
                null,
                null,
                toolCallId,
                toolCallName,
                parentMessageId,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgUiEvent toolCallArgs(String toolCallId, String delta, long timestamp) {
        return new AgUiEvent(
                "TOOL_CALL_ARGS",
                timestamp,
                null,
                null,
                null,
                null,
                delta,
                toolCallId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgUiEvent toolCallEnd(String toolCallId, long timestamp) {
        return new AgUiEvent(
                "TOOL_CALL_END",
                timestamp,
                null,
                null,
                null,
                null,
                null,
                toolCallId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgUiEvent toolCallResult(
            String messageId, String toolCallId, String content, long timestamp) {
        return new AgUiEvent(
                "TOOL_CALL_RESULT",
                timestamp,
                null,
                null,
                messageId,
                "tool",
                null,
                toolCallId,
                null,
                null,
                content,
                null,
                null,
                null,
                null,
                null);
    }

    public static AgUiEvent custom(String name, Map<String, Object> value, long timestamp) {
        return new AgUiEvent(
                "CUSTOM", timestamp, null, null, null, null, null, null, null, null, null, null,
                null, null, name, value);
    }

    /**
     * The exact bytes that go after {@code data:}. Serialization of a flat record of strings and
     * maps cannot realistically fail, but if it ever does this degrades to a {@code
     * RUN_ERROR}-shaped literal rather than throwing out of an SSE writer mid-stream, mirroring the
     * fail-open posture of every other emit path in this codebase.
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"RUN_ERROR\",\"message\":\"Could not serialize an event.\",\"code\":\"SERIALIZATION\"}";
        }
    }
}
