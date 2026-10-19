package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Live incident, 2026-08-05: a tool parameter schema shaped like {@code "type": ["integer",
 * "null"]} (the standard JSON Schema way to mark an optional field) crashed {@code
 * GoogleGenAiChatModel#jsonToSchema} and failed the entire chat turn - not just a call to that one
 * tool - for every Gemini-family agent turn that offered such a tool, staging logs showed "limit"
 * and "fiscal_year" parameters both triggering it repeatedly. These tests pin the fix: array-typed
 * "type" fields are collapsed to a single type, everything else is left byte-for-byte alone.
 */
class SchemaTypeArrayNormalizerTest {

    private static ToolCallback fakeTool(String schema) {
        ToolDefinition definition = new DefaultToolDefinition("some_tool", "does a thing", schema);
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return "ok";
            }
        };
    }

    @Test
    void collapsesNullableArrayTypeToTheSingleRemainingType() {
        ToolCallback tool =
                fakeTool(
                        "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":[\"integer\",\"null\"]}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        String fixedSchema = result.get(0).getToolDefinition().inputSchema();
        assertFalse(
                fixedSchema.contains("["), "type array should have been collapsed: " + fixedSchema);
        assertTrue(fixedSchema.contains("\"type\":\"integer\""), fixedSchema);
    }

    @Test
    void collapsesNestedTypeArraysAnywhereInTheSchema() {
        ToolCallback tool =
                fakeTool(
                        "{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\","
                                + "\"items\":{\"type\":[\"string\",\"null\"]}},"
                                + "\"fiscal_year\":{\"type\":[\"integer\",\"null\"]}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        String fixedSchema = result.get(0).getToolDefinition().inputSchema();
        assertFalse(fixedSchema.contains("["), fixedSchema);
    }

    @Test
    void schemaWithNoArrayTypeIsReturnedUnwrapped() {
        // The common case: no normalization needed, so the ORIGINAL callback instance comes back
        // (no wrapping allocation for the vast majority of tools).
        ToolCallback tool =
                fakeTool("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        assertSame(tool, result.get(0));
    }

    @Test
    void nameAndDescriptionAndActualInvocationAreUnaffected() {
        ToolCallback tool =
                fakeTool(
                        "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":[\"integer\",\"null\"]}}}");
        ToolCallback fixed = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool)).get(0);
        assertEquals("some_tool", fixed.getToolDefinition().name());
        assertEquals("does a thing", fixed.getToolDefinition().description());
        assertEquals("ok", fixed.call("{}"));
    }

    @Test
    void malformedSchemaIsLeftAloneRatherThanThrowing() {
        ToolCallback tool = fakeTool("not valid json");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        assertSame(tool, result.get(0));
    }

    @Test
    void nullTypeIsDroppedWhenItIsTheOnlyEntry() {
        ToolCallback tool =
                fakeTool("{\"type\":\"object\",\"properties\":{\"weird\":{\"type\":[\"null\"]}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        String fixedSchema = result.get(0).getToolDefinition().inputSchema();
        assertFalse(fixedSchema.contains("["), fixedSchema);
    }

    /**
     * Live incident, 2026-08-10: a {@code max_tokens}-shaped parameter with a {@code "default"}
     * value crashed Gemini's {@code AutoValue_Schema$Builder} deserialization with a {@code
     * JsonMappingException} - same "one incompatible keyword fails the WHOLE turn" shape as the
     * type-array crash above, different keyword.
     */
    @Test
    void stripsDefaultFieldFromSchema() {
        ToolCallback tool =
                fakeTool(
                        "{\"type\":\"object\",\"properties\":{\"max_tokens\":{\"type\":\"integer\",\"default\":100}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        String fixedSchema = result.get(0).getToolDefinition().inputSchema();
        assertFalse(fixedSchema.contains("default"), fixedSchema);
        assertTrue(fixedSchema.contains("\"type\":\"integer\""), fixedSchema);
    }

    @Test
    void stripsNestedDefaultFieldsAnywhereInTheSchema() {
        ToolCallback tool =
                fakeTool(
                        "{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\","
                                + "\"items\":{\"type\":\"string\",\"default\":\"none\"}},"
                                + "\"limit\":{\"type\":\"integer\",\"default\":10}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        String fixedSchema = result.get(0).getToolDefinition().inputSchema();
        assertFalse(fixedSchema.contains("default"), fixedSchema);
    }

    @Test
    void schemaWithNoDefaultOrArrayTypeIsReturnedUnwrapped() {
        ToolCallback tool =
                fakeTool(
                        "{\"type\":\"object\",\"properties\":{\"max_tokens\":{\"type\":\"integer\"}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        assertSame(tool, result.get(0));
    }

    /**
     * Live incident, 2026-08-12: a third-party MCP server (Firecrawl's {@code get_contents})
     * emitted an {@code ids} parameter with an {@code "items"} sub-schema but no {@code "type"} at
     * all - deserializes fine on this side, then Vertex's server-side validator rejects the live
     * HTTP request with a 400 ("schema type should be ARRAY"), failing the whole turn. Unlike the
     * two fixes above, this defect throws no local exception either fix's detection relies on, so
     * it needed its own proactive structural rule.
     */
    @Test
    void addsMissingArrayTypeWhenItemsIsPresent() {
        ToolCallback tool =
                fakeTool(
                        "{\"type\":\"object\",\"properties\":{\"ids\":{\"items\":{\"type\":\"string\"}}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        String fixedSchema = result.get(0).getToolDefinition().inputSchema();
        assertTrue(fixedSchema.contains("\"type\":\"array\""), fixedSchema);
        assertTrue(fixedSchema.contains("\"items\""), fixedSchema);
    }

    @Test
    void fixesWrongArrayTypeWhenItemsIsPresent() {
        ToolCallback tool =
                fakeTool(
                        "{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":\"string\",\"items\":{\"type\":\"string\"}}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        String fixedSchema = result.get(0).getToolDefinition().inputSchema();
        // The outer "ids" type must become "array" - only "string" left anywhere is the nested
        // items' own (correct, untouched) type.
        assertEquals(1, fixedSchema.split("\"type\":\"string\"", -1).length - 1, fixedSchema);
        assertTrue(fixedSchema.contains("\"ids\":{\"type\":\"array\""), fixedSchema);
    }

    @Test
    void leavesCorrectArrayTypeAlone() {
        ToolCallback tool =
                fakeTool(
                        "{\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        assertSame(tool, result.get(0));
    }

    @Test
    void fixesMissingArrayTypeNestedAnywhereInTheSchema() {
        ToolCallback tool =
                fakeTool(
                        "{\"type\":\"object\",\"properties\":{\"filters\":{\"type\":\"object\","
                                + "\"properties\":{\"ids\":{\"items\":{\"type\":\"integer\"}}}}}}");
        List<ToolCallback> result = SchemaTypeArrayNormalizer.normalizeAll(List.of(tool));
        String fixedSchema = result.get(0).getToolDefinition().inputSchema();
        assertTrue(fixedSchema.contains("\"type\":\"array\""), fixedSchema);
    }
}
