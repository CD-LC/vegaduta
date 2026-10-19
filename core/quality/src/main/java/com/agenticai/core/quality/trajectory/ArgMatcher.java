package com.agenticai.core.quality.trajectory;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What one expected argument of an expected tool call must look like. Written in a trajectory spec as the
 * value under an argument path:
 *
 * <pre>
 *   "limit": 5                              literal: JSON-equal (numbers compare by value, so 5 equals 5.0)
 *   "mode": "fast"                          literal text
 *   "query": {"regex": "(?i)refund"}        the argument's text CONTAINS a match (find); add "ignoreCase": true
 *   "id": {"present": true}                 any non-null value
 *   "debug": {"absent": true}               not provided (or null); a bare null literal means the same
 *   "opts": {"equals": {"a": 1}}            explicit literal, for an object that has a key named like a matcher
 * </pre>
 *
 * A bare JSON object that is none of the matcher forms is also a literal (deep equality, key order ignored).
 *
 * <p>Regexes are author-supplied and applied to model-produced arguments, so matching runs under a charAt step
 * budget ({@link BoundedRegexMatcher}) and values longer than {@link #MAX_REGEX_INPUT_CHARS} never match: a
 * pathological pattern or value fails the check, it cannot hang a run.
 */
final class ArgMatcher {

    static final int MAX_REGEX_LENGTH = 200;
    static final int MAX_REGEX_INPUT_CHARS = 8_192;

    private enum Kind { EQUALS, REGEX, PRESENT, ABSENT }

    private static final Set<String> MATCHER_KEYS = Set.of("equals", "regex", "present", "absent", "ignoreCase");

    private final Kind kind;
    private final JsonNode literal;
    private final Pattern pattern;

    private ArgMatcher(Kind kind, JsonNode literal, Pattern pattern) {
        this.kind = kind;
        this.literal = literal;
        this.pattern = pattern;
    }

    /** @throws IllegalArgumentException naming the argument when the matcher is malformed */
    static ArgMatcher parse(String argPath, JsonNode spec) {
        String where = "Argument '" + argPath + "': ";
        if (spec == null || spec.isNull()) {
            return new ArgMatcher(Kind.ABSENT, null, null);
        }
        if (!spec.isObject() || !isMatcherObject(spec)) {
            return new ArgMatcher(Kind.EQUALS, spec, null);
        }
        for (Iterator<String> it = spec.fieldNames(); it.hasNext();) {
            String key = it.next();
            if (!MATCHER_KEYS.contains(key)) {
                throw new IllegalArgumentException(where + "unknown matcher key '" + key + "'.");
            }
        }
        int forms = (spec.has("equals") ? 1 : 0) + (spec.has("regex") ? 1 : 0) + (spec.has("present") ? 1 : 0)
            + (spec.has("absent") ? 1 : 0);
        if (forms != 1) {
            throw new IllegalArgumentException(where + "use exactly one of equals, regex, present, absent.");
        }
        if (spec.has("ignoreCase") && !spec.has("regex")) {
            throw new IllegalArgumentException(where + "'ignoreCase' only applies to 'regex'.");
        }
        if (spec.has("equals")) {
            return new ArgMatcher(Kind.EQUALS, spec.get("equals"), null);
        }
        if (spec.has("present")) {
            requireTrue(spec.get("present"), where, "present");
            return new ArgMatcher(Kind.PRESENT, null, null);
        }
        if (spec.has("absent")) {
            requireTrue(spec.get("absent"), where, "absent");
            return new ArgMatcher(Kind.ABSENT, null, null);
        }
        JsonNode r = spec.get("regex");
        if (!r.isTextual() || r.asText().isEmpty()) {
            throw new IllegalArgumentException(where + "'regex' must be non-empty text.");
        }
        if (r.asText().length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException(where + "'regex' is limited to " + MAX_REGEX_LENGTH + " characters.");
        }
        boolean ignoreCase = false;
        if (spec.has("ignoreCase")) {
            if (!spec.get("ignoreCase").isBoolean()) {
                throw new IllegalArgumentException(where + "'ignoreCase' must be true or false.");
            }
            ignoreCase = spec.get("ignoreCase").asBoolean();
        }
        try {
            return new ArgMatcher(Kind.REGEX, null, Pattern.compile(r.asText(), ignoreCase ? Pattern.CASE_INSENSITIVE : 0));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(where + "'regex' is not a valid regular expression: " + e.getDescription() + ".");
        }
    }

    private static boolean isMatcherObject(JsonNode obj) {
        return obj.has("equals") || obj.has("regex") || obj.has("present") || obj.has("absent");
    }

    private static void requireTrue(JsonNode v, String where, String key) {
        if (!v.isBoolean() || !v.asBoolean()) {
            throw new IllegalArgumentException(where + "'" + key + "' must be true.");
        }
    }

    /** Whether the argument value (null / missing / JSON null = not provided) satisfies this matcher. */
    boolean matches(JsonNode value) {
        boolean absent = value == null || value.isNull() || value.isMissingNode();
        switch (kind) {
            case ABSENT:
                return absent;
            case PRESENT:
                return !absent;
            case EQUALS:
                return !absent && jsonEquals(literal, value);
            default:
                if (absent) {
                    return false;
                }
                String text = value.isContainerNode() ? value.toString() : value.asText();
                if (text.length() > MAX_REGEX_INPUT_CHARS) {
                    return false;
                }
                return BoundedRegexMatcher.find(pattern, text);
        }
    }

    /** Deep JSON equality with numbers compared by value and object key order ignored. */
    static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!jsonEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Iterator<String> it = a.fieldNames(); it.hasNext();) {
                String name = it.next();
                if (!b.has(name) || !jsonEquals(a.get(name), b.get(name))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }
}
