package com.agenticai.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure helpers shared by the Dify-style AI nodes ({@link WorkflowAiNodeHandler}): the untrusted-data fence, tolerant
 * config readers and the "is this value empty" rule the aggregator uses. No Spring, no I/O.
 *
 * <p>The fence follows the repository's existing convention for delimiting untrusted text inside a prompt (the
 * {@code <<<NAME ... NAME>>>} block with the delimiters neutralised inside the data, as in the demo-studio moderation
 * and the study-plan generator): the system prompt tells the model that everything between the markers is DATA, and
 * {@link #fence} guarantees the data cannot contain a marker that would close the block early.
 */
final class WorkflowAiNodeSupport {

    static final String FENCE_OPEN = "<<<TEXT";
    static final String FENCE_CLOSE = "TEXT>>>";

    private WorkflowAiNodeSupport() {
    }

    /**
     * {@code text} (clipped to {@code maxChars}) inside the fence block. Every {@code <<<} and {@code >>>} in the data is
     * broken up first, so no spelling of a marker survives inside the block.
     */
    static String fence(String text, int maxChars) {
        String clipped = clip(text == null ? "" : text, maxChars);
        return FENCE_OPEN + "\n" + neutralise(clipped) + "\n" + FENCE_CLOSE;
    }

    /** Breaks every {@code <<<} / {@code >>>} run so untrusted text can never close (or reopen) a fence. */
    static String neutralise(String text) {
        return text.replace("<<<", "< < <").replace(">>>", "> > >");
    }

    static String clip(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, Math.max(0, maxChars));
    }

    /** Collapses whitespace/newlines to single spaces and clips: author text placed on one prompt line. */
    static String oneLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").strip();
        return clip(collapsed, maxChars);
    }

    static String str(Object raw) {
        return raw == null ? "" : raw.toString().strip();
    }

    static int intConfig(Map<String, Object> config, String key, int fallback, int min, int max) {
        Object raw = config.get(key);
        int value = fallback;
        if (raw instanceof Number n) {
            value = n.intValue();
        } else if (raw != null && !raw.toString().isBlank()) {
            try {
                value = Integer.parseInt(raw.toString().strip());
            } catch (NumberFormatException e) {
                value = fallback;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    /** A number in [0, 1]; anything unparseable or out of range yields {@code fallback}. */
    static double unitConfig(Map<String, Object> config, String key, double fallback) {
        Object raw = config.get(key);
        if (raw == null || raw.toString().isBlank()) {
            return fallback;
        }
        try {
            double value = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString().strip());
            return Double.isNaN(value) || value < 0.0 || value > 1.0 ? fallback : value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A list-of-objects config value, tolerant of arriving as an already-parsed List or as raw JSON text. */
    static List<Map<?, ?>> listOfMaps(Object raw, ObjectMapper mapper) {
        if (raw == null) {
            return List.of();
        }
        Object value = raw;
        if (raw instanceof String s) {
            if (s.isBlank()) {
                return List.of();
            }
            try {
                value = mapper.readValue(s, Object.class);
            } catch (Exception e) {
                return List.of();
            }
        }
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                out.add(map);
            }
        }
        return out;
    }

    /** A list-of-strings config value, tolerant of a List or a raw JSON array string. Non-string scalars are stringified. */
    static List<String> listOfStrings(Object raw, ObjectMapper mapper) {
        if (raw == null) {
            return List.of();
        }
        Object value = raw;
        if (raw instanceof String s) {
            if (s.isBlank()) {
                return List.of();
            }
            if (!s.stripLeading().startsWith("[")) {
                return List.of(s);
            }
            try {
                value = mapper.readValue(s, Object.class);
            } catch (Exception e) {
                return List.of(s);
            }
        }
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object entry : list) {
            out.add(entry == null ? "" : entry.toString());
        }
        return out;
    }

    /**
     * The aggregator's notion of "nothing here": blank text, the literal {@code null}/{@code undefined}, an empty JSON
     * array/object/string, or an expression that was never resolved (still contains {@code {{ }}}).
     */
    static boolean isEmptyValue(String value) {
        if (value == null) {
            return true;
        }
        String v = value.strip();
        if (v.isEmpty()) {
            return true;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.equals("null") || lower.equals("undefined") || v.equals("[]") || v.equals("{}") || v.equals("\"\"")) {
            return true;
        }
        return v.contains("{{") && v.contains("}}");
    }

    /** True when {@code node} is a non-null, non-JSON-null value. */
    static boolean present(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull();
    }
}
