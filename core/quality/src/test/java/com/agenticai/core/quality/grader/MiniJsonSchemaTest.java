package com.agenticai.core.quality.grader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The miniature schema validator is the grader's contract with dataset authors: an unsupported
 * keyword must be refused at save time (never silently ignored), and the supported subset must
 * accept and reject data exactly as the keywords say. Pinned here because a grader that validates
 * loosely passes responses it should fail - the worst failure mode a grader has.
 */
class MiniJsonSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> errors(String schema, String data) {
        List<String> errors = new ArrayList<>();
        MiniJsonSchema.validate(json(schema), json(data), "$", errors);
        return errors;
    }

    // ---------------------------------------------------------------- assertSupported

    @Test
    void supportedKeywordsAndAnnotationsAreAccepted() {
        assertDoesNotThrow(
                () ->
                        MiniJsonSchema.assertSupported(
                                json(
                                        "{\"$schema\": \"http://x\", \"title\": \"t\", \"description\": \"d\","
                                                + " \"$comment\": \"c\", \"default\": 1, \"examples\": [],"
                                                + " \"type\": \"object\", \"required\": [\"a\"],"
                                                + " \"properties\": {\"a\": {\"type\": \"string\", \"minLength\": 1, \"maxLength\": 9,"
                                                + " \"pattern\": \"^[a-z]+$\", \"enum\": [\"x\"]}},"
                                                + " \"additionalProperties\": false}")));
    }

    @Test
    void anUnknownKeywordIsRefusedSoStricterSchemasCannotWeaken() {
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                MiniJsonSchema.assertSupported(
                                        json("{\"oneOf\": [{\"type\": \"string\"}]}")));

        assertTrue(thrown.getMessage().contains("oneOf"));
    }

    @Test
    void nonObjectSchemasAreRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonSchema.assertSupported(json("[\"type\"]")));
    }

    @Test
    void malformedSupportedKeywordsAreRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonSchema.assertSupported(json("{\"properties\": []}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonSchema.assertSupported(json("{\"anyOf\": []}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonSchema.assertSupported(json("{\"required\": \"a\"}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonSchema.assertSupported(json("{\"enum\": []}")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MiniJsonSchema.assertSupported(
                                json("{\"additionalProperties\": {\"type\": \"string\"}}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonSchema.assertSupported(json("{\"minLength\": \"3\"}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonSchema.assertSupported(json("{\"minimum\": true}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonSchema.assertSupported(json("{\"type\": 4}")));
    }

    @Test
    void anInvalidOrOverlongPatternIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonSchema.assertSupported(json("{\"pattern\": \"([unclosed\"}")));

        String longPattern = "a".repeat(CodeGrader.MAX_PATTERN_LENGTH + 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MiniJsonSchema.assertSupported(
                                json("{\"pattern\": \"" + longPattern + "\"}")));
    }

    @Test
    void nestingBeyondTheDepthCapIsRefused() {
        // 21 nested "properties" levels exceeds MAX_DEPTH (20).
        StringBuilder schema = new StringBuilder("{\"type\":\"object\"");
        for (int i = 0; i < 21; i++) {
            schema.append(",\"properties\":{\"x\":{\"type\":\"object\"");
        }
        for (int i = 0; i < 21; i++) {
            schema.append("}}");
        }
        schema.append("}");

        assertThrows(
                IllegalArgumentException.class,
                () -> MiniJsonSchema.assertSupported(json(schema.toString())));
    }

    @Test
    void itemsAndAnyOfSubschemasAreCheckedRecursively() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MiniJsonSchema.assertSupported(
                                json("{\"items\": {\"const\": 1, \"$ref\": \"#/x\"}}")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MiniJsonSchema.assertSupported(
                                json("{\"anyOf\": [{\"type\": \"string\"}, {\"bogus\": 1}]}")));
    }

    // ---------------------------------------------------------------- validate

    @Test
    void aValidDocumentProducesNoErrors() {
        List<String> errors =
                errors(
                        "{\"type\": \"object\", \"required\": [\"name\"], \"additionalProperties\": false,"
                                + " \"properties\": {\"name\": {\"type\": \"string\", \"minLength\": 2},"
                                + " \"tags\": {\"type\": \"array\", \"items\": {\"type\": \"integer\"}, \"minItems\": 1}}}",
                        "{\"name\": \"ok\", \"tags\": [1, 2]}");

        assertTrue(errors.isEmpty(), "unexpected errors: " + errors);
    }

    @Test
    void typeChecksCoverEveryPrimitiveAndTheIntegerSpecialCase() {
        assertTrue(errors("{\"type\": \"string\"}", "1").get(0).contains("expected type"));
        assertTrue(errors("{\"type\": \"number\"}", "\"x\"").size() == 1);
        assertTrue(errors("{\"type\": \"boolean\"}", "0").size() == 1);
        assertTrue(errors("{\"type\": \"object\"}", "[]").size() == 1);
        assertTrue(errors("{\"type\": \"array\"}", "{}").size() == 1);
        assertTrue(errors("{\"type\": \"null\"}", "0").size() == 1);
        assertTrue(errors("{\"type\": \"integer\"}", "1.5").size() == 1);
        assertTrue(
                errors("{\"type\": \"integer\"}", "2.0").isEmpty(),
                "an integral double is an integer");
        assertTrue(errors("{\"type\": \"integer\"}", "3").isEmpty());
        assertTrue(errors("{\"type\": [\"string\", \"null\"]}", "null").isEmpty());
        assertTrue(errors("{\"type\": [\"string\", \"null\"]}", "\"s\"").isEmpty());
        assertTrue(errors("{\"type\": [\"string\", \"null\"]}", "4").size() == 1);
        assertTrue(
                errors("{\"type\": \"mystery\"}", "\"anything\"").size() == 1,
                "an unknown type name matches nothing");
    }

    @Test
    void constEnumAndAnyOfAreEnforced() {
        assertTrue(errors("{\"const\": 7}", "7").isEmpty());
        assertTrue(errors("{\"const\": 7}", "8").get(0).contains("constant"));

        assertTrue(errors("{\"enum\": [\"a\", \"b\"]}", "\"b\"").isEmpty());
        assertTrue(errors("{\"enum\": [\"a\", \"b\"]}", "\"c\"").get(0).contains("enum"));

        assertTrue(
                errors("{\"anyOf\": [{\"type\": \"string\"}, {\"type\": \"integer\"}]}", "3")
                        .isEmpty());
        assertTrue(
                errors("{\"anyOf\": [{\"type\": \"string\"}, {\"type\": \"integer\"}]}", "\"s\"")
                        .isEmpty());
        assertTrue(
                errors("{\"anyOf\": [{\"type\": \"string\"}, {\"type\": \"integer\"}]}", "true")
                        .get(0)
                        .contains("anyOf"));
    }

    @Test
    void stringBoundsAndPatternAreChecked() {
        assertTrue(errors("{\"minLength\": 3}", "\"ab\"").get(0).contains("minLength"));
        assertTrue(errors("{\"maxLength\": 2}", "\"abc\"").get(0).contains("maxLength"));
        assertTrue(errors("{\"pattern\": \"^[a-z]+$\"}", "\"ABC\"").get(0).contains("pattern"));
        assertTrue(errors("{\"minLength\": 3, \"maxLength\": 5}", "\"abcd\"").isEmpty());
        // Non-string values ignore string keywords entirely.
        assertTrue(errors("{\"minLength\": 3, \"pattern\": \"^x$\"}", "42").isEmpty());
    }

    @Test
    void numericBoundsAreChecked() {
        assertTrue(errors("{\"minimum\": 5}", "4").get(0).contains("minimum"));
        assertTrue(errors("{\"maximum\": 5}", "6").get(0).contains("maximum"));
        assertTrue(errors("{\"minimum\": 5, \"maximum\": 5}", "5").isEmpty());
        assertTrue(errors("{\"minimum\": 5}", "\"not a number\"").isEmpty());
    }

    @Test
    void objectRulesCheckRequiredUnknownAndNestedProperties() {
        String schema =
                "{\"type\": \"object\", \"required\": [\"id\"], \"additionalProperties\": false,"
                        + " \"properties\": {\"id\": {\"type\": \"integer\"}, \"nested\": {\"type\": \"object\","
                        + " \"properties\": {\"flag\": {\"type\": \"boolean\"}}}}}";

        assertTrue(errors(schema, "{\"id\": 1, \"nested\": {\"flag\": true}}").isEmpty());
        assertTrue(
                errors(schema, "{\"nested\": {}}")
                        .get(0)
                        .contains("missing required property 'id'"));
        assertTrue(
                errors(schema, "{\"id\": 1, \"extra\": 0}")
                        .get(0)
                        .contains("unexpected property 'extra'"));
        assertTrue(
                errors(schema, "{\"id\": 1, \"nested\": {\"flag\": \"yes\"}}")
                        .get(0)
                        .contains("$.nested.flag"));

        // additionalProperties absent (or true) lets extras through.
        assertTrue(
                errors(
                                "{\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"integer\"}}}",
                                "{\"id\": 1, \"extra\": 0}")
                        .isEmpty());
    }

    @Test
    void arrayRulesCheckSizeAndPerItemSchemas() {
        String schema =
                "{\"type\": \"array\", \"minItems\": 2, \"maxItems\": 3,"
                        + " \"items\": {\"type\": \"integer\"}}";

        assertTrue(errors(schema, "[1, 2]").isEmpty());
        assertTrue(errors(schema, "[1]").get(0).contains("minItems"));
        assertTrue(errors(schema, "[1, 2, 3, 4]").get(0).contains("maxItems"));
        assertTrue(errors(schema, "[1, \"x\"]").get(0).contains("$[1]"));
    }

    @Test
    void errorsAreCappedAtFifty() {
        StringBuilder data = new StringBuilder("[");
        for (int i = 0; i < 60; i++) {
            data.append(i == 0 ? "" : ",").append("\"not-an-int\"");
        }
        data.append("]");

        List<String> errors =
                errors(
                        "{\"type\": \"array\", \"items\": {\"type\": \"integer\"}}",
                        data.toString());

        assertTrue(errors.size() <= 50, "the cap keeps a hostile payload from flooding the report");
    }
}
