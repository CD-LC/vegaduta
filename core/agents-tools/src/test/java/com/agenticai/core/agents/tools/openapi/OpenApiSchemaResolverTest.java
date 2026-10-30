package com.agenticai.core.agents.tools.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;

/** {@code $ref} inlining, {@code allOf} folding and OpenAPI-vocabulary stripping. */
class OpenApiSchemaResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode tree(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private static ObjectNode resolve(JsonNode root, String schemaJson) throws Exception {
        return OpenApiSchemaResolver.resolveSchema(
                root, tree(schemaJson), 0, new LinkedHashSet<>());
    }

    @Test
    void missingAndNonObjectSchemasResolveToNull() throws Exception {
        assertNull(OpenApiSchemaResolver.resolveSchema(tree("{}"), null, 0, new LinkedHashSet<>()));
        assertNull(resolve(tree("{}"), "\"text\""));
        assertNull(resolve(tree("{}"), "5"));
        // A boolean schema degrades to a permissive empty object.
        assertEquals(0, resolve(tree("{}"), "true").size());
    }

    @Test
    void internalRefsAreInlined() throws Exception {
        JsonNode root =
                tree(
                        "{\"components\":{\"schemas\":{\"Pet\":{\"type\":\"object\","
                                + "\"properties\":{\"name\":{\"type\":\"string\"}}}}}}");
        ObjectNode resolved = resolve(root, "{\"$ref\":\"#/components/schemas/Pet\"}");

        assertEquals("object", resolved.path("type").asText());
        assertTrue(resolved.path("properties").has("name"));
    }

    @Test
    void externalRefsAndMissingTargetsDegradeToGenericObject() throws Exception {
        JsonNode root = tree("{}");
        ObjectNode external = resolve(root, "{\"$ref\":\"https://x.example.com/s.json#/A\"}");
        assertEquals("object", external.path("type").asText());
        assertEquals(1, external.size());

        ObjectNode missing = resolve(root, "{\"$ref\":\"#/nope\"}");
        assertEquals("object", missing.path("type").asText());
    }

    @Test
    void cyclicRefsStopAtTheGuardInsteadOfRecurring() throws Exception {
        JsonNode root =
                tree(
                        "{\"components\":{\"schemas\":{\"Node\":{\"type\":\"object\",\"properties\":{"
                                + "\"next\":{\"$ref\":\"#/components/schemas/Node\"}}}}}}");
        ObjectNode resolved = resolve(root, "{\"$ref\":\"#/components/schemas/Node\"}");

        JsonNode next = resolved.path("properties").path("next");
        assertEquals("object", next.path("type").asText());
        assertFalse(next.has("properties"), "the cycle must collapse to a bare object");
    }

    @Test
    void nestedPropertiesItemsAndCompositionsAreResolved() throws Exception {
        JsonNode root =
                tree(
                        "{\"components\":{\"schemas\":{\"Id\":{\"type\":\"integer\",\"format\":\"int64\"}}}}");
        ObjectNode resolved =
                resolve(
                        root,
                        "{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":\"array\","
                                + "\"items\":{\"$ref\":\"#/components/schemas/Id\"}}},"
                                + "\"oneOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}");

        assertEquals(
                "integer",
                resolved.path("properties").path("ids").path("items").path("type").asText());
        assertEquals(2, resolved.path("oneOf").size());
    }

    @Test
    void allOfMembersAreFoldedIntoTheEnclosingSchema() throws Exception {
        JsonNode root = tree("{}");
        ObjectNode resolved =
                resolve(
                        root,
                        "{\"allOf\":["
                                + "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},"
                                + "\"required\":[\"a\"]},"
                                + "{\"properties\":{\"b\":{\"type\":\"number\"}}}],"
                                + "\"x-vendor\":\"gone\",\"xml\":{\"name\":\"x\"},"
                                + "\"discriminator\":{}}");

        assertFalse(resolved.has("allOf"));
        assertEquals("object", resolved.path("type").asText());
        assertTrue(resolved.path("properties").has("a"));
        assertTrue(resolved.path("properties").has("b"));
        assertEquals("a", resolved.path("required").get(0).asText());
        assertFalse(resolved.has("x-vendor"));
        assertFalse(resolved.has("xml"));
        assertFalse(resolved.has("discriminator"));
    }

    @Test
    void mergeAllOfMergesIntoAnExistingPropertiesBlock() throws Exception {
        ObjectNode out =
                (ObjectNode)
                        tree(
                                "{\"properties\":{\"own\":{\"type\":\"string\"}},"
                                        + "\"required\":[\"own\"],"
                                        + "\"allOf\":[{\"properties\":{\"add\":{\"type\":\"number\"}},"
                                        + "\"required\":[\"add\"],\"type\":\"object\"}]}");
        OpenApiSchemaResolver.mergeAllOf(out);

        assertFalse(out.has("allOf"));
        assertTrue(out.path("properties").has("own"));
        assertTrue(out.path("properties").has("add"));
        assertEquals(2, out.path("required").size());
    }

    @Test
    void resolveRefFollowsNonSchemaRefsAndStopsAtExternals() throws Exception {
        JsonNode root = tree("{\"defs\":{\"p\":{\"in\":\"query\",\"name\":\"q\"}}}");
        JsonNode resolved = OpenApiSchemaResolver.resolveRef(root, tree("{\"$ref\":\"#/defs/p\"}"));
        assertEquals("q", resolved.path("name").asText());

        JsonNode external =
                OpenApiSchemaResolver.resolveRef(root, tree("{\"$ref\":\"http://x/y.json\"}"));
        assertTrue(external.isObject());
        assertEquals(0, external.size());

        // A node with no $ref is returned as-is.
        JsonNode plain = tree("{\"a\":1}");
        assertSame(plain, OpenApiSchemaResolver.resolveRef(root, plain));
    }

    @Test
    void withDescriptionAddsOnlyWhenAbsent() throws Exception {
        JsonNode bare = tree("{\"type\":\"string\"}");
        JsonNode described = OpenApiSchemaResolver.withDescription(bare, "the id");
        assertEquals("the id", described.path("description").asText());
        assertFalse(bare.has("description"), "the original node is not mutated");

        JsonNode already = tree("{\"type\":\"string\",\"description\":\"kept\"}");
        assertSame(already, OpenApiSchemaResolver.withDescription(already, "other"));
        assertSame(bare, OpenApiSchemaResolver.withDescription(bare, "  "));
        assertSame(bare, OpenApiSchemaResolver.withDescription(bare, null));
    }
}
