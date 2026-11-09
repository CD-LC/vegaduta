package com.agenticai.core.guardrails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Recognises a tool result that is plain structured data - numbers, booleans, ids, short status words -
 * and so cannot carry an instruction to the model. Such a result does not need the LLM injection judge:
 * the judge is one extra model call per tool result (about 150 ms on a laptop model, plus the cost of
 * evicting that model's prompt cache), and a value of one or two short words has no room to hijack an
 * agent. The regex layer still runs on it first, unchanged.
 *
 * <p>Deliberately narrow: only a JSON object, array or scalar, every string (keys included) at most
 * {@value #MAX_STRING_CHARS} characters and {@value #MAX_WORDS} words, bounded size and depth. Anything
 * else - prose, a long string, a malformed document - goes to the judge as before.
 */
final class InertDataScreen {

    static final int MAX_STRING_CHARS = 24;
    static final int MAX_WORDS = 2;
    static final int MAX_CHARS = 2_000;
    static final int MAX_DEPTH = 6;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InertDataScreen() {}

    static boolean isInertData(String output) {
        if (output == null) {
            return false;
        }
        String text = output.strip();
        if (text.isEmpty() || text.length() > MAX_CHARS) {
            return false;
        }
        char first = text.charAt(0);
        if (first != '{' && first != '[' && first != '-' && !Character.isDigit(first)) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            return node != null && inert(node, 0);
        } catch (Exception notJson) {
            return false;
        }
    }

    private static boolean inert(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        if (node.isContainerNode()) {
            if (node.isObject()) {
                var names = node.fieldNames();
                while (names.hasNext()) {
                    if (!shortText(names.next())) {
                        return false;
                    }
                }
            }
            for (JsonNode child : node) {
                if (!inert(child, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        return !node.isTextual() || shortText(node.textValue());
    }

    private static boolean shortText(String s) {
        if (s.length() > MAX_STRING_CHARS) {
            return false;
        }
        String t = s.strip();
        return t.isEmpty() || t.split("\\s+").length <= MAX_WORDS;
    }
}
