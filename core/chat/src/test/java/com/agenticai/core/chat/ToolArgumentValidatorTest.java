package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ToolArgumentValidatorTest {

    /** Shape of generate_document's real schema: type and filename required. */
    static final String GENERATE_DOCUMENT_SCHEMA =
            """
        {
          "type": "object",
          "properties": {
            "type": {"type": "string", "enum": ["PDF", "DOCX", "XLSX"]},
            "filename": {"type": "string"},
            "title": {"type": "string"},
            "sections": {"type": "array", "items": {"type": "object"}}
          },
          "required": ["type", "filename"]
        }
        """;

    private final ObjectMapper objectMapper = com.agenticai.core.json.JsonMappers.plain();

    @Test
    void theIncidentArgumentsAreRejectedWithBothMissingPropertiesAndTheStrayKeys() {
        String args =
                "{\"f\": \"mandi_prices_weather_report\", \"path\": \"templates/report.md\", \"skill\": \"x\"}";

        ToolArgumentValidator.Result result =
                ToolArgumentValidator.validate(args, GENERATE_DOCUMENT_SCHEMA, objectMapper);

        assertFalse(result.valid());
        assertEquals(
                List.of("missing required property 'type'", "missing required property 'filename'"),
                result.problems());
        assertEquals(List.of("f", "path", "skill"), result.unknownKeys());
    }

    @Test
    void aCallWithEveryRequiredPropertyIsValidEvenWithExtraKeys() {
        String args = "{\"type\":\"PDF\",\"filename\":\"r.pdf\",\"title\":\"R\",\"extra\":1}";

        ToolArgumentValidator.Result result =
                ToolArgumentValidator.validate(args, GENERATE_DOCUMENT_SCHEMA, objectMapper);

        assertTrue(result.valid(), "unknown keys are a note, never a rejection");
        assertEquals(List.of("extra"), result.unknownKeys());
    }

    @Test
    void propertyTypesAreDeliberatelyNotChecked() {
        String args = "{\"type\":5,\"filename\":[\"not\",\"a\",\"string\"]}";

        assertTrue(
                ToolArgumentValidator.validate(args, GENERATE_DOCUMENT_SCHEMA, objectMapper)
                        .valid());
    }

    @Test
    void nullCountsAsMissing() {
        String args = "{\"type\":\"PDF\",\"filename\":null}";

        assertEquals(
                List.of("missing required property 'filename'"),
                ToolArgumentValidator.validate(args, GENERATE_DOCUMENT_SCHEMA, objectMapper)
                        .problems());
    }

    @Test
    void nonObjectAndNonJsonArgumentsAreRejected() {
        assertEquals(
                List.of("arguments must be a JSON object, got array"),
                ToolArgumentValidator.validate("[1,2]", GENERATE_DOCUMENT_SCHEMA, objectMapper)
                        .problems());
        assertEquals(
                List.of("arguments are not valid JSON"),
                ToolArgumentValidator.validate("{not json", GENERATE_DOCUMENT_SCHEMA, objectMapper)
                        .problems());
        assertEquals(
                List.of("missing required property 'type'", "missing required property 'filename'"),
                ToolArgumentValidator.validate("", GENERATE_DOCUMENT_SCHEMA, objectMapper)
                        .problems(),
                "empty input is treated as {} - the sanitizer's own repair value");
    }

    @Test
    void aSchemaWithoutRequiredOrPropertiesIsNotWorthWrapping() {
        assertFalse(
                ToolArgumentValidator.hasCheckableSchema("{\"type\":\"object\"}", objectMapper));
        assertFalse(ToolArgumentValidator.hasCheckableSchema(null, objectMapper));
        assertFalse(ToolArgumentValidator.hasCheckableSchema("not json", objectMapper));
        assertTrue(
                ToolArgumentValidator.hasCheckableSchema(GENERATE_DOCUMENT_SCHEMA, objectMapper));
        assertTrue(
                ToolArgumentValidator.hasCheckableSchema(
                        "{\"type\":\"object\",\"properties\":{}}", objectMapper));
    }

    @Test
    void anUnparsableSchemaNeverRejects() {
        assertTrue(ToolArgumentValidator.validate("{}", "not json", objectMapper).valid());
        assertTrue(ToolArgumentValidator.validate("{}", null, objectMapper).valid());
    }

    @Test
    void feedbackIsMachineReadableAndCarriesTheSchema() throws Exception {
        ToolArgumentValidator.Result result =
                ToolArgumentValidator.validate("{\"f\":1}", GENERATE_DOCUMENT_SCHEMA, objectMapper);

        String feedback =
                ToolArgumentValidator.feedback(
                        "generate_document", result, GENERATE_DOCUMENT_SCHEMA, objectMapper);

        JsonNode json = objectMapper.readTree(feedback);
        assertEquals("invalid_arguments", json.get("error").asString());
        assertEquals("generate_document", json.get("tool").asString());
        assertEquals(2, json.get("problems").size());
        assertEquals("f", json.get("unknown_keys").get(0).asString());
        assertTrue(json.get("hint").asString().contains("NOT executed"));
        assertTrue(json.get("hint").asString().contains("f"));
        assertTrue(json.get("schema").asString().contains("\"required\""));
    }

    @Test
    void aHugeSchemaIsTruncatedInTheFeedback() throws Exception {
        String huge =
                "{\"type\":\"object\",\"required\":[\"a\"],\"description\":\""
                        + "x".repeat(10_000)
                        + "\"}";
        ToolArgumentValidator.Result result =
                ToolArgumentValidator.validate("{}", huge, objectMapper);

        JsonNode json =
                objectMapper.readTree(
                        ToolArgumentValidator.feedback("t", result, huge, objectMapper));

        assertTrue(
                json.get("schema").asString().length()
                        <= ToolArgumentValidator.MAX_SCHEMA_ECHO_CHARS + 3);
        assertTrue(json.get("schema").asString().endsWith("..."));
    }
}
