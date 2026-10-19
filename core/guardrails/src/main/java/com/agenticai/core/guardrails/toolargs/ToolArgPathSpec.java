package com.agenticai.core.guardrails.toolargs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny, safe path into a tool's JSON arguments: {@code a.b[*].c[2]}.
 *
 * <ul>
 *   <li>{@code name} - an object field.</li>
 *   <li>{@code [n]} - array element n (0-based, at most 4 digits).</li>
 *   <li>{@code [*]} - EVERY element of an array (the rule then applies to each one).</li>
 * </ul>
 *
 * <p>There is deliberately no recursive descent, filter or script syntax: a path is a fixed sequence of at
 * most {@value #MAX_STEPS} steps, resolved iteratively, with a hard cap on how many elements a wildcard may
 * fan out to. Pure and immutable.
 */
public final class ToolArgPathSpec {

    public static final int MAX_LENGTH = 128;
    public static final int MAX_STEPS = 12;
    /** Elements a single wildcard rule will look at; more than this and the evaluation reports it cannot verify. */
    public static final int MAX_FAN_OUT = 1000;

    enum Kind { FIELD, INDEX, ALL }

    record Step(Kind kind, String field, int index) {
    }

    /** One resolved location: the concrete path (indexes filled in) and the value found there. */
    public record Hit(String path, JsonNode value) {
    }

    /** Resolution result; {@code truncated} is true when a wildcard hit {@link #MAX_FAN_OUT}. */
    public record Resolution(List<Hit> hits, boolean truncated) {
    }

    private final String text;
    private final List<Step> steps;

    private ToolArgPathSpec(String text, List<Step> steps) {
        this.text = text;
        this.steps = List.copyOf(steps);
    }

    /** The original text, e.g. {@code items[*].id}. */
    public String text() {
        return text;
    }

    /** True when the path contains a {@code [*]} step. */
    public boolean hasWildcard() {
        for (Step s : steps) {
            if (s.kind() == Kind.ALL) {
                return true;
            }
        }
        return false;
    }

    /** The first field name (the top-level argument this path lives under). */
    public String topLevelField() {
        return steps.get(0).field();
    }

    /**
     * @throws IllegalArgumentException with a message naming the problem when the text is not a valid path
     */
    public static ToolArgPathSpec parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("'path' must not be empty.");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("'path' is limited to " + MAX_LENGTH + " characters.");
        }
        List<Step> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            int start = i;
            while (i < n && isNameChar(text.charAt(i))) {
                i++;
            }
            if (start == i) {
                throw bad(text, "expected a field name at position " + (i + 1));
            }
            out.add(new Step(Kind.FIELD, text.substring(start, i), -1));
            while (i < n && text.charAt(i) == '[') {
                int close = text.indexOf(']', i);
                if (close < 0) {
                    throw bad(text, "missing ']'");
                }
                String inner = text.substring(i + 1, close);
                if (inner.equals("*")) {
                    out.add(new Step(Kind.ALL, null, -1));
                } else if (!inner.isEmpty() && inner.length() <= 4 && inner.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    out.add(new Step(Kind.INDEX, null, Integer.parseInt(inner)));
                } else {
                    throw bad(text, "use [*] or [number] inside brackets");
                }
                i = close + 1;
            }
            if (i < n) {
                if (text.charAt(i) != '.') {
                    throw bad(text, "unexpected character '" + text.charAt(i) + "' at position " + (i + 1));
                }
                i++;
                if (i == n) {
                    throw bad(text, "path must not end with '.'");
                }
            }
            if (out.size() > MAX_STEPS) {
                throw bad(text, "more than " + MAX_STEPS + " steps");
            }
        }
        return new ToolArgPathSpec(text, out);
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '$' || c == '-';
    }

    private static IllegalArgumentException bad(String text, String why) {
        return new IllegalArgumentException("Invalid 'path' \"" + abbreviate(text) + "\": " + why + ".");
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }

    /**
     * Resolves this path against the arguments object. A step that does not fit the value in hand (field on a
     * non-object, index past the end, wildcard on a non-array) simply yields no hit for that branch - absence
     * is reported by the caller's {@code required} handling, never as an exception.
     */
    public Resolution resolve(JsonNode root) {
        List<Hit> frontier = List.of(new Hit("", root));
        boolean truncated = false;
        for (Step step : steps) {
            List<Hit> next = new ArrayList<>();
            for (Hit hit : frontier) {
                JsonNode node = hit.value();
                switch (step.kind()) {
                    case FIELD -> {
                        if (node != null && node.isObject() && node.has(step.field())) {
                            String child = hit.path().isEmpty() ? step.field() : hit.path() + "." + step.field();
                            next.add(new Hit(child, node.get(step.field())));
                        }
                    }
                    case INDEX -> {
                        if (node != null && node.isArray() && step.index() < node.size()) {
                            next.add(new Hit(hit.path() + "[" + step.index() + "]", node.get(step.index())));
                        }
                    }
                    case ALL -> {
                        if (node != null && node.isArray()) {
                            for (int k = 0; k < node.size(); k++) {
                                if (next.size() >= MAX_FAN_OUT) {
                                    truncated = true;
                                    break;
                                }
                                next.add(new Hit(hit.path() + "[" + k + "]", node.get(k)));
                            }
                        }
                    }
                }
            }
            frontier = next;
            if (frontier.isEmpty()) {
                break;
            }
        }
        return new Resolution(frontier, truncated);
    }

    @Override
    public String toString() {
        return text;
    }
}
