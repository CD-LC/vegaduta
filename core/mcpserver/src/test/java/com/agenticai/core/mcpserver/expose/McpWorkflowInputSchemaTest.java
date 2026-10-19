package com.agenticai.core.mcpserver.expose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpWorkflowInputSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    /** key, value, key, value ... - explicit Map&lt;String, Object&gt; so nested literals never trip generic inference. */
    private static Map<String, Object> m(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<>(List.of(items));
    }

    @Test
    void noDeclaredInputsIsAnOpenObject() throws Exception {
        for (String json : new String[] {
            McpWorkflowInputSchema.fromTriggerConfig(null),
            McpWorkflowInputSchema.fromTriggerConfig(m()),
            McpWorkflowInputSchema.fromTriggerConfig(m("unrelated", "x")),
            McpWorkflowInputSchema.openSchemaJson()}) {
            JsonNode schema = parse(json);
            assertEquals("object", schema.get("type").asText());
            assertTrue(schema.get("additionalProperties").asBoolean());
            assertTrue(schema.get("properties").has("input"));
        }
    }

    @Test
    void aDeclaredInputSchemaIsUsedWithItsRequiredList() throws Exception {
        Map<String, Object> config = m("inputSchema", m(
            "properties", m("customer", m("type", "string"), "amount", m("type", "number")),
            "required", list("customer")));
        JsonNode schema = parse(McpWorkflowInputSchema.fromTriggerConfig(config));
        assertEquals("object", schema.get("type").asText());
        assertEquals("string", schema.get("properties").get("customer").get("type").asText());
        assertEquals("number", schema.get("properties").get("amount").get("type").asText());
        assertEquals("customer", schema.get("required").get(0).asText());
    }

    @Test
    void aFieldsListIsMappedToTypedProperties() throws Exception {
        Map<String, Object> config = m("fields", list(
            m("key", "email", "type", "string", "label", "Customer email", "required", Boolean.TRUE),
            m("name", "count", "type", "integer"),
            m("key", "flag", "type", "bool"),
            m("type", "string")));
        JsonNode schema = parse(McpWorkflowInputSchema.fromTriggerConfig(config));
        JsonNode properties = schema.get("properties");
        assertEquals(3, properties.size());
        assertEquals("string", properties.get("email").get("type").asText());
        assertEquals("Customer email", properties.get("email").get("description").asText());
        assertEquals("number", properties.get("count").get("type").asText());
        assertEquals("boolean", properties.get("flag").get("type").asText());
        assertEquals(1, schema.get("required").size());
        assertEquals("email", schema.get("required").get(0).asText());
    }

    @Test
    void aMalformedDeclarationFallsBackToOpenInsteadOfThrowing() throws Exception {
        JsonNode schema = parse(McpWorkflowInputSchema.fromTriggerConfig(m("fields", list("not-a-map", 42))));
        assertTrue(schema.get("additionalProperties").asBoolean());
        JsonNode other = parse(McpWorkflowInputSchema.fromTriggerConfig(m("inputSchema", "nope")));
        assertTrue(other.get("additionalProperties").asBoolean());
    }

    @Test
    void aLoneInputStringIsPassedThroughVerbatim() throws Exception {
        assertEquals("hello world", McpWorkflowInputSchema.toRunInput(parse("{\"input\": \"hello world\"}")));
    }

    @Test
    void otherArgumentsArePassedAsJsonText() throws Exception {
        assertEquals("{\"a\":1,\"b\":\"x\"}", McpWorkflowInputSchema.toRunInput(parse("{\"a\":1,\"b\":\"x\"}")));
        assertEquals("{\"input\":{\"k\":1}}", McpWorkflowInputSchema.toRunInput(parse("{\"input\": {\"k\": 1}}")));
    }

    @Test
    void noArgumentsIsAnEmptyInput() throws Exception {
        assertEquals("", McpWorkflowInputSchema.toRunInput(null));
        assertEquals("", McpWorkflowInputSchema.toRunInput(parse("{}")));
        assertEquals("", McpWorkflowInputSchema.toRunInput(parse("null")));
    }
}
