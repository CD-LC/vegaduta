package com.agenticai.core.chat.structured;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Optional;

/**
 * Finds the JSON in a model reply for {@link StructuredOutput}: the fence-stripped reply when it is
 * one complete document of the wanted shape, otherwise the first balanced {@code {...}} / {@code
 * [...]} span (string- and escape-aware) that parses. Pure; always parses through the caller's
 * {@link ObjectMapper}.
 */
final class JsonLocator {

    /**
     * Upper bound on candidate spans tried by the balanced-span scan, so a pathological reply (tens
     * of KB of code with thousands of braces and no valid JSON) stays linear-ish instead of
     * quadratic. Real replies find their JSON within the first few candidates.
     */
    private static final int MAX_CANDIDATES = 64;

    private JsonLocator() {}

    enum Shape {
        ANY,
        OBJECT,
        ARRAY;

        boolean opens(char c) {
            return switch (this) {
                case ANY -> c == '{' || c == '[';
                case OBJECT -> c == '{';
                case ARRAY -> c == '[';
            };
        }

        boolean accepts(JsonNode node) {
            return switch (this) {
                case ANY -> true;
                case OBJECT -> node.isObject();
                case ARRAY -> node.isArray();
            };
        }
    }

    record Located(String text, JsonNode node) {}

    static Optional<Located> locate(ObjectMapper mapper, String raw, Shape shape) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String stripped = StructuredOutput.stripCodeFence(raw);
        JsonNode whole = parseStrict(mapper, stripped);
        if (whole != null && shape.accepts(whole)) {
            return Optional.of(new Located(stripped, whole));
        }
        Optional<Located> found = scanPreferringObjects(mapper, stripped, shape);
        if (found.isEmpty()) {
            // The fence strip can cut a reply short (an unclosed fence whose JSON contains ```
            // inside a string value) - the untouched reply gets one scan of its own.
            String trimmed = raw.trim();
            if (!trimmed.equals(stripped)) {
                found = scanPreferringObjects(mapper, trimmed, shape);
            }
        }
        return found;
    }

    /**
     * For {@link Shape#ANY}, objects are searched before arrays: in prose, a bracket group is far
     * more often a citation or step marker ("see [1]") than the answer, while nearly every caller
     * that does not say which shape it wants is waiting for an object.
     */
    private static Optional<Located> scanPreferringObjects(
            ObjectMapper mapper, String text, Shape shape) {
        if (shape != Shape.ANY) {
            return scan(mapper, text, shape);
        }
        Optional<Located> object = scan(mapper, text, Shape.OBJECT);
        return object.isPresent() ? object : scan(mapper, text, Shape.ARRAY);
    }

    private static Optional<Located> scan(ObjectMapper mapper, String text, Shape shape) {
        int candidates = 0;
        for (int i = 0; i < text.length() && candidates < MAX_CANDIDATES; i++) {
            if (!shape.opens(text.charAt(i))) {
                continue;
            }
            candidates++;
            int end = matchingClose(text, i);
            if (end < 0) {
                continue;
            }
            String candidate = text.substring(i, end + 1);
            JsonNode node = parseStrict(mapper, candidate);
            if (node != null && shape.accepts(node)) {
                return Optional.of(new Located(candidate, node));
            }
        }
        return Optional.empty();
    }

    /**
     * Index of the bracket closing the one at {@code start}, honouring nesting of both bracket
     * kinds and skipping string literals (with backslash escapes); -1 when it never closes or a
     * closer of the wrong kind shows up first.
     */
    static int matchingClose(String text, int start) {
        char[] stack = new char[16];
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> {
                    if (depth == stack.length) {
                        stack = Arrays.copyOf(stack, depth * 2);
                    }
                    stack[depth++] = c;
                }
                case '}', ']' -> {
                    if (depth == 0) {
                        return -1;
                    }
                    char open = stack[--depth];
                    if ((c == '}') != (open == '{')) {
                        return -1;
                    }
                    if (depth == 0) {
                        return i;
                    }
                }
                default -> {
                    // ordinary character
                }
            }
        }
        return -1;
    }

    /**
     * The single JSON value {@code text} consists of, or null (empty, invalid, or trailing
     * content).
     */
    static JsonNode parseStrict(ObjectMapper mapper, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try (JsonParser parser = mapper.createParser(text)) {
            JsonNode node = mapper.readTree(parser);
            if (node == null || node.isMissingNode()) {
                return null;
            }
            return parser.nextToken() == null ? node : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
