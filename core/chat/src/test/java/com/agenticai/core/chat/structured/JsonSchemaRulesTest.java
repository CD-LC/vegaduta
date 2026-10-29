package com.agenticai.core.chat.structured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * The schema-aware checks behind {@link StructuredOutput}'s native structured-output switches:
 * OpenAI strict-mode compatibility, the Gemini keyword strip, Anthropic's local-$ref rule and the
 * provider-safe tool name. The load-bearing detail every test pins: a property NAMED like a
 * keyword ("properties", "additionalProperties") is a name, never a keyword.
 */
class JsonSchemaRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode tree(String json) throws Exception {
        return mapper.readTree(json);
    }

    // --- parseSchema ---------------------------------------------------------

    @Test
    void parseSchemaAcceptsOnlyAnObjectDocument() throws Exception {
        assertNull(JsonSchemaRules.parseSchema(null));
        assertNull(JsonSchemaRules.parseSchema("  "));
        assertNull(JsonSchemaRules.parseSchema("not json"));
        assertNull(JsonSchemaRules.parseSchema("[1,2]"), "an array is valid JSON but not a schema");
        assertNull(JsonSchemaRules.parseSchema("{\"a\":1} trailing junk"));
        assertTrue(JsonSchemaRules.parseSchema("{\"type\":\"object\"}").isObject());
    }

    // --- sanitizeSchemaName ---------------------------------------------------

    @Test
    void schemaNamesAreMadeProviderSafe() {
        assertEquals("structured_output", JsonSchemaRules.sanitizeSchemaName(null));
        assertEquals("structured_output", JsonSchemaRules.sanitizeSchemaName(""));
        // Anything else is rewritten to the [A-Za-z0-9_-] alphabet, blanks included.
        assertEquals("___", JsonSchemaRules.sanitizeSchemaName("   "));
        assertEquals("a_b_c", JsonSchemaRules.sanitizeSchemaName("a b!c"));
        assertEquals("x", JsonSchemaRules.sanitizeSchemaName("x"));
        assertEquals(64, JsonSchemaRules.sanitizeSchemaName("n".repeat(200)).length());
        // Underscores, digits and dashes survive untouched.
        assertEquals("get-weather_2", JsonSchemaRules.sanitizeSchemaName("get-weather_2"));
    }

    // --- isStrictCompatible ---------------------------------------------------

    @Test
    void openAiStrictModeRejectsOpenObjectsAndMissingRequired() throws Exception {
        // additionalProperties missing or true = open = rejected, properties or not.
        assertFalse(JsonSchemaRules.isStrictCompatible(tree("{\"type\":\"object\"}")));
        assertFalse(JsonSchemaRules.isStrictCompatible(tree(
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"additionalProperties\":true}")));
        // A property absent from "required" is rejected.
        assertFalse(JsonSchemaRules.isStrictCompatible(tree(
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"required\":[],\"additionalProperties\":false}")));
        // Fully closed passes.
        assertTrue(JsonSchemaRules.isStrictCompatible(tree(
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"required\":[\"a\"],\"additionalProperties\":false}")));
    }

    @Test
    void strictCompatibilityWalksNestedSchemasNotPropertyNames() throws Exception {
        // A property literally NAMED "properties"/"additionalProperties" is a name, not a keyword -
        // its VALUE is itself a schema that must also be strict.
        JsonNode tricky = tree(
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"properties\"],"
                + "\"properties\":{\"properties\":{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"required\":[],\"properties\":{}}}}");
        assertTrue(JsonSchemaRules.isStrictCompatible(tricky));

        JsonNode nestedOpen = tree(
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"inner\"],"
                + "\"properties\":{\"inner\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},"
                + "\"required\":[\"x\"]}}}");
        assertFalse(JsonSchemaRules.isStrictCompatible(nestedOpen),
            "the nested object is missing its own additionalProperties:false");
    }

    @Test
    void strictCompatibilityWalksApplicatorKeywords() throws Exception {
        // An open object under anyOf/items/$defs fails the whole schema.
        assertFalse(JsonSchemaRules.isStrictCompatible(tree(
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],\"properties\":{},"
                + "\"anyOf\":[{\"type\":\"object\"}]}")));
        assertFalse(JsonSchemaRules.isStrictCompatible(tree(
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],\"properties\":{},"
                + "\"items\":{\"type\":\"object\"}}")));
        // items as an ARRAY of schemas is walked element-wise too.
        assertTrue(JsonSchemaRules.isStrictCompatible(tree(
            "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],\"properties\":{},"
                + "\"items\":[{\"type\":\"string\"},{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"required\":[],\"properties\":{}}]}")));
        // A leaf (non-object) node is always compatible.
        assertTrue(JsonSchemaRules.isStrictCompatible(tree("{\"type\":\"string\"}")));
        assertTrue(JsonSchemaRules.isStrictCompatible(null));
    }

    @Test
    void aTypeArrayDeclaringObjectIsStillAnObject() throws Exception {
        assertFalse(JsonSchemaRules.isStrictCompatible(tree(
            "{\"type\":[\"object\",\"null\"],\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"required\":[\"a\"]}")),
            "type:[\"object\",\"null\"] is still an open object without additionalProperties:false");
        assertTrue(JsonSchemaRules.isStrictCompatible(tree(
            "{\"type\":[\"object\",\"null\"],\"properties\":{\"a\":{\"type\":\"string\"}},"
                + "\"required\":[\"a\"],\"additionalProperties\":false}")));
    }

    // --- subSchemas / containsKey ----------------------------------------------

    @Test
    void subSchemasFindsItemsApplicatorsAndDefs() throws Exception {
        JsonNode node = tree(
            "{\"items\":{},\"anyOf\":[{}],\"allOf\":[],\"oneOf\":[{}],"
                + "\"$defs\":{\"a\":{}},\"definitions\":{\"b\":{}},\"properties\":{\"p\":{}}}");
        // items(1) + anyOf(1) + oneOf(1) + $defs(1) + definitions(1); properties is NOT a subschema.
        assertEquals(5, JsonSchemaRules.subSchemas(node).size());
    }

    @Test
    void containsKeyFindsAKeyAtAnyDepth() throws Exception {
        JsonNode node = tree("{\"a\":{\"b\":{\"target\":1}},\"list\":[{\"target\":2}]}");
        assertTrue(JsonSchemaRules.containsKey(node, "target"));
        assertFalse(JsonSchemaRules.containsKey(node, "absent"));
    }

    // --- stripKeys (the Gemini rewrite) ------------------------------------------

    @Test
    void stripKeysRemovesSchemaOnlyKeywordsButNeverPropertyNames() throws Exception {
        ObjectNode node = (ObjectNode) tree(
            "{\"$schema\":\"http://x\",\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"additionalProperties\":{\"type\":\"object\",\"additionalProperties\":true,"
                + "\"properties\":{\"nested\":{\"type\":\"string\"},\"$schema\":{\"type\":\"string\"}}}},"
                + "\"items\":{\"$schema\":\"y\",\"type\":\"object\"}}");
        JsonSchemaRules.stripKeys(node);

        assertFalse(node.has("$schema"));
        assertFalse(node.has("additionalProperties"));
        // The property NAMED "additionalProperties" keeps its name; only ITS keywords are stripped.
        JsonNode prop = node.get("properties").get("additionalProperties");
        assertTrue(prop.isObject());
        assertFalse(prop.has("additionalProperties"));
        // A property NAMED "$schema" is a name too - the schema-walk strips it only as a keyword,
        // and under "properties" it is a key, not a keyword, so it stays.
        assertTrue(prop.get("properties").has("$schema"));
        // items is a real subschema: its $schema keyword goes.
        assertFalse(node.get("items").has("$schema"));
    }

    // --- hasNonLocalRef ----------------------------------------------------------

    @Test
    void onlyRefsPointingOutsideTheDocumentAreNonLocal() throws Exception {
        assertFalse(JsonSchemaRules.hasNonLocalRef(tree("{\"$ref\":\"#/definitions/a\"}")));
        assertTrue(JsonSchemaRules.hasNonLocalRef(tree("{\"$ref\":\"https://schemas.example/x.json\"}")));
        // Found at any depth.
        assertTrue(JsonSchemaRules.hasNonLocalRef(tree(
            "{\"a\":{\"b\":[{\"$ref\":\"other.json#/x\"}]}}")));
        assertFalse(JsonSchemaRules.hasNonLocalRef(tree("{\"a\":[1,2,{\"ref\":\"not-a-dollar-ref\"}]}")));
    }
}
