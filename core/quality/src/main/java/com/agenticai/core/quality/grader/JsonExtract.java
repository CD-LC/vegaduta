package com.agenticai.core.quality.grader;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Finds the JSON value an agent answered with. Models routinely wrap JSON in
 * prose or a markdown fence, and a grader that demanded a bare document would
 * grade formatting habits instead of content. Order: the whole trimmed text;
 * then the first balanced {@code {...}} or {@code [...]} span that parses.
 */
final class JsonExtract {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** Cap on candidate spans tried per response (model output is untrusted). */
    private static final int MAX_ATTEMPTS = 50;

    private JsonExtract() {
    }

    /** @return the parsed value, or null when the text holds no parseable JSON object/array/scalar */
    static JsonNode firstJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        JsonNode whole = tryParse(trimmed);
        if (whole != null) {
            return whole;
        }
        int attempts = 0;
        for (int start = 0; start < trimmed.length(); start++) {
            char c = trimmed.charAt(start);
            if (c != '{' && c != '[') {
                continue;
            }
            if (++attempts > MAX_ATTEMPTS) {
                return null;
            }
            int end = balancedEnd(trimmed, start);
            if (end < 0) {
                // Unbalanced from here: any later start sits inside the same unclosed span,
                // so scanning on would be quadratic for no extra result.
                return null;
            }
            JsonNode parsed = tryParse(trimmed.substring(start, end + 1));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /** Index of the bracket closing the one at {@code start}, string/escape aware; -1 when unbalanced. */
    private static int balancedEnd(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static JsonNode tryParse(String candidate) {
        try {
            JsonNode node = MAPPER.readTree(candidate);
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }
}
