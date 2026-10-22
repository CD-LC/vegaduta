package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.NumberCanon.numberText;

import java.util.Locale;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

/**
 * Value-level helpers every data operation shares: absent/empty tests, the text a value compares
 * as, the stable identity used for grouping and de-duplication. Extracted from {@link
 * WorkflowDataOps}.
 */
final class DataValues {
    static boolean isAbsent(JsonNode v) {
        return v == null || v.isMissingNode() || v.isNull();
    }

    /** A deep copy (JSON null for a missing value), so a result never aliases the input tree. */
    static JsonNode copy(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return NullNode.getInstance();
        }
        JsonNode c = n.deepCopy();
        return c;
    }

    /** Missing, null, blank text, empty list, empty object. */
    static boolean isEmpty(JsonNode v) {
        if (isAbsent(v)) {
            return true;
        }
        if (v.isString()) {
            return v.asString().isBlank();
        }
        if (v.isContainerNode()) {
            return v.size() == 0;
        }
        return false;
    }

    /**
     * The text a value compares / concatenates as: empty for absent, plain text for strings,
     * compact JSON for containers.
     */
    static String text(JsonNode v) {
        if (isAbsent(v)) {
            return "";
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.isNumber()) {
            return numberText(v.decimalValue());
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? "true" : "false";
        }
        return v.toString();
    }

    /**
     * The text a node's output carries for a value: strings unquoted (like every other node), JSON
     * otherwise.
     */
    static String outputText(JsonNode v) {
        return v.isString() ? v.asString() : v.toString();
    }

    /**
     * Stable identity of a value for grouping / deduping. Types are distinct ("1" and 1 are
     * different values).
     */
    static String canonical(JsonNode v, boolean ignoreCase) {
        if (isAbsent(v)) {
            return "z:";
        }
        if (v.isString()) {
            return "s:" + (ignoreCase ? v.asString().toLowerCase(Locale.ROOT) : v.asString());
        }
        if (v.isNumber()) {
            return "n:" + numberText(v.decimalValue());
        }
        if (v.isBoolean()) {
            return "b:" + v.asBoolean();
        }
        return "j:" + (ignoreCase ? v.toString().toLowerCase(Locale.ROOT) : v.toString());
    }

    /** Parses JSON text; null when blank or not valid JSON. */
    static JsonNode parse(ObjectMapper mapper, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            // Strict: trailing text means "not JSON", so 'true story' / '12 apples' stay free text
            // (same stance as expression/Values.java).
            JsonNode n =
                    mapper.readerFor(JsonNode.class)
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(text);
            return n == null || n.isMissingNode() ? null : n;
        } catch (Exception e) {
            return null;
        }
    }

    static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private DataValues() {}
}
