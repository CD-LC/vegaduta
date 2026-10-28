package com.agenticai.core.guardrails;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.Iterator;

/**
 * Recognises the platform's own control results ({@link ApprovalParked}, {@link ToolBudgetExhausted}) by their
 * whole shape, not by a substring. Both are written as exactly {@code {"error":"<marker>","result":"<text>"}}.
 * Matching the marker anywhere in a tool's output let any page, document or third-party MCP reply that merely
 * contained the text end a turn as "waiting for approval" or hide a real call from the effect journal.
 *
 * <p>This narrows the forgery surface, it does not remove it: a tool that returns exactly this two-field object is
 * still read as the platform's answer by the consumers that have no registry to ask (the tool loop in {@code
 * core/chat}). Consumers that can ask {@link ToolResultMarker} should prefer {@link ToolResultMarker#kindOf}.
 */
final class PlatformMarkerDocument {

    /** The platform's control results are a sentence or two; anything larger is a tool's output. */
    static final int MAX_CHARS = 16_384;

    /** Rejects anything after the first JSON value: {@code {...} trailing text} is a tool's output, not the marker. */
    private static final ObjectReader STRICT =
        JsonMappers.plain().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private PlatformMarkerDocument() {}

    static boolean is(String text, String marker) {
        if (text == null || text.length() > MAX_CHARS || !text.contains(marker)) {
            return false;
        }
        try {
            JsonNode root = STRICT.readTree(text);
            if (root == null || !root.isObject() || root.size() > 2) {
                return false;
            }
            JsonNode error = root.get("error");
            if (error == null || !error.isTextual() || !marker.equals(error.asText())) {
                return false;
            }
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!"error".equals(name) && !("result".equals(name) && root.get(name).isTextual())) {
                    return false;
                }
            }
            return true;
        } catch (Exception notJson) {
            return false;
        }
    }
}
