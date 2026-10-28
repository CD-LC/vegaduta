package com.agenticai.core.guardrails;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The structured tool result every layer hands the model once this turn is parked on a human
 * approval: {@code {"error":"approval_parked","result":"<what the model should do>"}}. A JSON object
 * (Gemini replays every tool result as one) whose {@code error} value is the marker the tool loop in
 * {@code core/chat} recognises ({@code ArgumentSanitizingToolCallingManager.APPROVAL_PARKED}: a turn
 * that parked must end, so the loop answers the model's next round with the budget-exhausted result
 * instead of queueing more calls behind a decision that may never come). The two modules cannot
 * share a class, so the string is repeated there and a test pins that the two agree.
 */
public final class ApprovalParked {

    /** The {@code error} value that marks a result as "this turn waits on a human". */
    public static final String MARKER = "approval_parked";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private ApprovalParked() {}

    /** The parked/queued answer carrying {@code instruction} (what the model should do now). */
    public static String result(String instruction) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", MARKER);
        body.put("result", instruction);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return fallbackResult(instruction);
        }
    }

    /**
     * The same JSON written by hand, for the case the serializer fails (unreachable for a map of strings). It carries
     * no English of its own: the instruction is the caller's text.
     */
    static String fallbackResult(String instruction) {
        return "{\"error\":\"" + MARKER + "\",\"result\":\""
                + new String(JsonStringEncoder.getInstance().quoteAsString(String.valueOf(instruction))) + "\"}";
    }

    /** True when {@code toolResult} is a parked-turn result (from this class or the tool loop). */
    public static boolean isParked(String toolResult) {
        return PlatformMarkerDocument.is(toolResult, MARKER);
    }
}
