package com.agenticai.core.agents.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/** Reads a remote agent's replies in either A2A dialect, for {@link A2aClientService}. */
final class A2aRemoteParsing {

    private A2aRemoteParsing() {}

    /**
     * 1.0 wraps in {@code {"task"|"message": ...}}; 0.3 sends the object bare. Tolerates either
     * from either.
     */
    static JsonNode unwrap(JsonNode result) {
        if (result.has("task") && result.get("task").isObject()) {
            return result.get("task");
        }
        if (result.has("message") && result.get("message").isObject() && !result.has("parts")) {
            return result.get("message");
        }
        return result;
    }

    static boolean isTask(JsonNode node) {
        return "task".equals(node.path("kind").asText(null))
                || node.has("status") && !node.has("parts");
    }

    /**
     * Normalises {@code TASK_STATE_INPUT_REQUIRED} / {@code input-required} to {@code
     * input-required}.
     */
    static String state(JsonNode task) {
        String raw = task.path("status").path("state").asText("");
        String s = raw.toLowerCase(Locale.ROOT).replace("task_state_", "").replace('_', '-');
        return s.equals("cancelled") ? "canceled" : s;
    }

    static boolean isActive(String state) {
        return state.isEmpty()
                || state.equals("submitted")
                || state.equals("working")
                || state.equals("unspecified")
                || state.equals("unknown");
    }

    static String taskText(JsonNode task) {
        StringBuilder text = new StringBuilder();
        JsonNode artifacts = task.path("artifacts");
        if (artifacts.isArray()) {
            for (JsonNode artifact : artifacts) {
                text.append(partsText(artifact.path("parts")));
            }
        }
        if (text.toString().isBlank()) {
            text.setLength(0);
            text.append(partsText(task.path("status").path("message").path("parts")));
        }
        return text.toString();
    }

    static String partsText(JsonNode parts) {
        StringBuilder text = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if (part.hasNonNull("text")) {
                    text.append(part.get("text").asText());
                }
                // file/data parts are deliberately dropped: only text reaches the model.
            }
        }
        return text.toString();
    }
}
