package com.agenticai.core.chat.structured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonSchemaCheckTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<String> problems(String schema, String value) throws Exception {
        return JsonSchemaCheck.problems(mapper.readTree(schema), mapper.readTree(value));
    }

    @Test
    void aMatchingObjectHasNoProblems() throws Exception {
        String schema = "{\"type\":\"object\",\"required\":[\"a\"],\"properties\":{\"a\":{\"type\":\"string\"},"
                + "\"n\":{\"type\":\"integer\"},\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}";
        assertTrue(problems(schema, "{\"a\":\"x\",\"n\":2,\"tags\":[\"p\",\"q\"]}").isEmpty());
    }

    @Test
    void missingRequiredAndWrongTypesAreNamedWithTheirPath() throws Exception {
        String schema = "{\"type\":\"object\",\"required\":[\"a\",\"b\"],\"properties\":{\"a\":{\"type\":\"string\"},"
                + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}";
        List<String> found = problems(schema, "{\"a\":5,\"tags\":[\"ok\",3]}");
        assertTrue(found.contains("$.b is required"), found.toString());
        assertTrue(found.stream().anyMatch(p -> p.startsWith("$.a must be string")), found.toString());
        assertTrue(found.stream().anyMatch(p -> p.startsWith("$.tags[1] must be string")), found.toString());
    }

    @Test
    void enumConstAndAdditionalPropertiesAreChecked() throws Exception {
        String schema = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":"
                + "{\"k\":{\"enum\":[\"a\",\"b\"]},\"v\":{\"const\":1}}}";
        assertTrue(problems(schema, "{\"k\":\"a\",\"v\":1}").isEmpty());
        assertEquals(3, problems(schema, "{\"k\":\"z\",\"v\":2,\"extra\":true}").size());
    }

    @Test
    void anyOfAndTypeListsAcceptAnyAlternative() throws Exception {
        String schema = "{\"anyOf\":[{\"type\":\"string\"},{\"type\":[\"integer\",\"null\"]}]}";
        assertTrue(problems(schema, "\"s\"").isEmpty());
        assertTrue(problems(schema, "4").isEmpty());
        assertTrue(problems(schema, "null").isEmpty());
        assertEquals(1, problems(schema, "[1]").size());
    }

    @Test
    void unknownKeywordsAndRefsNeverRejectAReply() throws Exception {
        String schema = "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/x\",\"format\":\"email\","
                + "\"minLength\":50}}}";
        assertTrue(problems(schema, "{\"a\":\"short\"}").isEmpty());
    }

    @Test
    void anIntegralNumberFitsIntegerButAFractionDoesNot() throws Exception {
        JsonNode schema = mapper.readTree("{\"type\":\"integer\"}");
        assertTrue(JsonSchemaCheck.problems(schema, mapper.readTree("3.0")).isEmpty());
        assertEquals(1, JsonSchemaCheck.problems(schema, mapper.readTree("3.5")).size());
    }

    @Test
    void aNullOrNonObjectSchemaChecksNothing() throws Exception {
        assertTrue(JsonSchemaCheck.problems(null, mapper.readTree("1")).isEmpty());
        assertTrue(JsonSchemaCheck.problems(mapper.readTree("true"), mapper.readTree("1")).isEmpty());
    }
}
