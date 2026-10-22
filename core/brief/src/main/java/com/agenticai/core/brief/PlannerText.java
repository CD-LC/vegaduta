package com.agenticai.core.brief;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Null-tolerant readers for the planner's LLM JSON replies, and small string helpers. */
final class PlannerText {

    private PlannerText() {}

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode n : array) {
                if (n.isTextual() && !n.asText().isBlank()) {
                    out.add(n.asText().strip());
                }
            }
        }
        return out;
    }

    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText().strip() : null;
    }

    static String orElse(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** UPPER_SNAKE vault name from free text, or null when nothing valid can be made of it. */
    static String sanitizeSecretName(String raw) {
        if (raw == null) {
            return null;
        }
        String name =
                raw.strip()
                        .toUpperCase(Locale.ROOT)
                        .replaceAll("[^A-Z0-9]+", "_")
                        .replaceAll("^_+|_+$", "");
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
        return BriefRefs.SECRET_NAME.matcher(name).matches() ? name : null;
    }
}
