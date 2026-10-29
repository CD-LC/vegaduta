package com.agenticai.core.agents.tools.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.openapi.OpenApiSpecParser.ParsedOperation;
import com.agenticai.core.agents.tools.openapi.OpenApiSpecParser.ParsedSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The document-to-tools parse: naming, parameter merge, body modes, and the refusal shapes. */
class OpenApiSpecParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OpenApiSpecParser parser = new OpenApiSpecParser();

    private static String oas3(String paths) {
        return "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Pet API\",\"version\":\"1.2\"},"
                + "\"servers\":[{\"url\":\"https://api.example.com/v1\"}],"
                + "\"paths\":"
                + paths
                + "}";
    }

    @Test
    void parsesAJsonOpenApi3Document() throws Exception {
        ParsedSpec spec =
                parser.parse(
                        oas3(
                                "{\"/pets\":{\"get\":{\"operationId\":\"listPets\",\"summary\":\"List pets\","
                                        + "\"parameters\":[{\"name\":\"limit\",\"in\":\"query\",\"required\":false,"
                                        + "\"schema\":{\"type\":\"integer\"}}]}}}"),
                        "petstore");

        assertEquals("Pet API", spec.title());
        assertEquals("1.2", spec.version());
        assertEquals("https://api.example.com/v1", spec.serverUrl());
        assertEquals(1, spec.operations().size());
        ParsedOperation op = spec.operations().get(0);
        assertEquals("GET /pets", op.operationKey());
        assertEquals("listPets", op.operationId());
        assertEquals("GET", op.method());
        assertEquals("/pets", op.path());
        assertEquals("List pets", op.summary());
        assertEquals("petstore_list_pets", op.suggestedToolName());
        assertEquals(OpenApiBodyMode.NONE, op.bodyMode());
        assertEquals(1, op.parameters().size());
        assertEquals("limit", op.parameters().get(0).name());
        assertEquals("query", op.parameters().get(0).in());
        assertFalse(op.parameters().get(0).required());

        JsonNode inputSchema = MAPPER.readTree(op.inputSchemaJson());
        assertEquals("object", inputSchema.path("type").asText());
        assertTrue(inputSchema.path("properties").has("limit"));
    }

    @Test
    void parsesYamlAndFallsBackToMethodPathNaming() {
        ParsedSpec spec =
                parser.parse(
                        "openapi: '3.0.0'\n"
                                + "info:\n"
                                + "  title: T\n"
                                + "  version: '1'\n"
                                + "paths:\n"
                                + "  /users/{id}:\n"
                                + "    delete: {}\n",
                        "api");

        assertEquals(1, spec.operations().size());
        ParsedOperation op = spec.operations().get(0);
        assertEquals("DELETE /users/{id}", op.operationKey());
        assertEquals("api_delete_users_id", op.suggestedToolName());
        assertNull(spec.serverUrl());
    }

    @Test
    void swagger20DocumentsGetHostSchemesAndFormData() {
        String swagger =
                "{\"swagger\":\"2.0\",\"host\":\"api.example.com\",\"basePath\":\"/v2\","
                        + "\"schemes\":[\"http\"],\"consumes\":[\"application/json\"],"
                        + "\"paths\":{\"/thing\":{\"post\":{\"operationId\":\"makeThing\","
                        + "\"parameters\":["
                        + "{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"type\":\"string\"},"
                        + "{\"name\":\"note\",\"in\":\"formData\",\"required\":true,\"type\":\"string\"},"
                        + "{\"name\":\"opt\",\"in\":\"formData\",\"type\":\"integer\"}]}}}}";
        ParsedSpec spec = parser.parse(swagger, "s2");

        assertEquals("http://api.example.com/v2", spec.serverUrl());
        ParsedOperation op = spec.operations().get(0);
        assertEquals("application/x-www-form-urlencoded", op.bodyContentType());
        assertTrue(op.bodyRequired());
        assertEquals(OpenApiBodyMode.FLATTENED, op.bodyMode());
        assertEquals(1, op.parameters().size());
        assertEquals("id", op.parameters().get(0).name());
    }

    @Test
    void swagger2BodyParameterUsesConsumes() {
        String swagger =
                "{\"swagger\":\"2.0\",\"host\":\"h.example.com\","
                        + "\"paths\":{\"/x\":{\"post\":{\"operationId\":\"doX\","
                        + "\"parameters\":[{\"name\":\"payload\",\"in\":\"body\",\"required\":true,"
                        + "\"schema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}}]}}}}";
        ParsedOperation op = parser.parse(swagger, "s2").operations().get(0);

        assertEquals("application/json", op.bodyContentType());
        assertTrue(op.bodyRequired());
        assertEquals(OpenApiBodyMode.FLATTENED, op.bodyMode());
    }

    @Test
    void requestBodyObjectIsFlattenedAndRequiredIsPropagated() throws Exception {
        ParsedOperation op =
                parser.parse(
                                oas3(
                                        "{\"/pets\":{\"post\":{\"operationId\":\"createPet\","
                                                + "\"requestBody\":{\"required\":true,\"content\":{"
                                                + "\"application/json\":{\"schema\":{\"type\":\"object\","
                                                + "\"properties\":{\"name\":{\"type\":\"string\"},"
                                                + "\"tag\":{\"type\":\"string\"}},"
                                                + "\"required\":[\"name\"]}}}}}}}"),
                                "p")
                        .operations()
                        .get(0);

        assertEquals(OpenApiBodyMode.FLATTENED, op.bodyMode());
        assertEquals("application/json", op.bodyContentType());
        JsonNode schema = MAPPER.readTree(op.inputSchemaJson());
        assertTrue(schema.path("properties").has("name"));
        assertTrue(schema.path("properties").has("tag"));
        assertEquals("name", schema.path("required").get(0).asText());
    }

    @Test
    void aBodyPropertyCollidingWithAParameterWrapsTheBody() throws Exception {
        ParsedOperation op =
                parser.parse(
                                oas3(
                                        "{\"/pets/{name}\":{\"put\":{\"operationId\":\"renamePet\","
                                                + "\"parameters\":[{\"name\":\"name\",\"in\":\"path\","
                                                + "\"schema\":{\"type\":\"string\"}}],"
                                                + "\"requestBody\":{\"content\":{\"application/json\":{"
                                                + "\"schema\":{\"type\":\"object\",\"properties\":{"
                                                + "\"name\":{\"type\":\"string\"}}}}}}}}}"),
                                "p")
                        .operations()
                        .get(0);

        assertEquals(OpenApiBodyMode.WRAPPED, op.bodyMode());
        JsonNode schema = MAPPER.readTree(op.inputSchemaJson());
        assertTrue(schema.path("properties").has("body"));
    }

    @Test
    void aNonObjectBodyIsWrapped() {
        ParsedOperation op =
                parser.parse(
                                oas3(
                                        "{\"/pets\":{\"post\":{\"operationId\":\"bulk\","
                                                + "\"requestBody\":{\"content\":{\"application/json\":{"
                                                + "\"schema\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}}}}}"),
                                "p")
                        .operations()
                        .get(0);

        assertEquals(OpenApiBodyMode.WRAPPED, op.bodyMode());
    }

    @Test
    void pathParametersAreRequiredAndReservedHeadersAreDropped() {
        ParsedOperation op =
                parser.parse(
                                oas3(
                                        "{\"/pets/{id}\":{\"get\":{\"operationId\":\"getPet\","
                                                + "\"parameters\":["
                                                + "{\"name\":\"id\",\"in\":\"path\",\"schema\":{\"type\":\"string\"}},"
                                                + "{\"name\":\"Authorization\",\"in\":\"header\",\"schema\":{\"type\":\"string\"}},"
                                                + "{\"name\":\"X-Trace\",\"in\":\"header\",\"schema\":{\"type\":\"string\"}},"
                                                + "{\"name\":\"session\",\"in\":\"cookie\",\"schema\":{\"type\":\"string\"}}]}}}"),
                                "p")
                        .operations()
                        .get(0);

        assertEquals(2, op.parameters().size());
        assertEquals("id", op.parameters().get(0).name());
        assertTrue(op.parameters().get(0).required());
        assertEquals("X-Trace", op.parameters().get(1).name());
        assertEquals("header", op.parameters().get(1).in());
    }

    @Test
    void operationParametersOverridePathLevelOnes() {
        ParsedOperation op =
                parser.parse(
                                oas3(
                                        "{\"/pets\":{\"parameters\":["
                                                + "{\"name\":\"v\",\"in\":\"query\",\"schema\":{\"type\":\"integer\"}}],"
                                                + "\"get\":{\"operationId\":\"g\",\"parameters\":["
                                                + "{\"name\":\"v\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}]}}}"),
                                "p")
                        .operations()
                        .get(0);

        assertEquals(1, op.parameters().size());
        assertEquals("string", op.parameters().get(0).schema().path("type").asText());
    }

    @Test
    void refParametersAndPathItemsAreResolved() {
        ParsedOperation op =
                parser.parse(
                                "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
                                        + "\"components\":{\"parameters\":{\"Q\":{\"name\":\"q\",\"in\":\"query\","
                                        + "\"schema\":{\"type\":\"string\"}}},"
                                        + "\"pathItems\":{\"p\":{\"get\":{\"operationId\":\"go\","
                                        + "\"parameters\":[{\"$ref\":\"#/components/parameters/Q\"}]}}}},"
                                        + "\"paths\":{\"/a\":{\"$ref\":\"#/components/pathItems/p\"}}}",
                                "p")
                        .operations()
                        .get(0);

        assertEquals(1, op.parameters().size());
        assertEquals("q", op.parameters().get(0).name());
    }

    @Test
    void duplicateToolNamesGetANumericSuffix() {
        String spec =
                oas3(
                        "{\"/a\":{\"get\":{\"operationId\":\"same\"}},"
                                + "\"/b\":{\"get\":{\"operationId\":\"same\"}}}");
        ParsedSpec parsed = parser.parse(spec, "p");

        assertEquals("p_same", parsed.operations().get(0).suggestedToolName());
        assertEquals("p_same_2", parsed.operations().get(1).suggestedToolName());
    }

    @Test
    void xExtensionPathsAndNonOperationKeysAreIgnored() {
        ParsedSpec spec =
                parser.parse(
                        oas3(
                                "{\"x-internal\":{\"get\":{\"operationId\":\"no\"}},"
                                        + "\"/ok\":{\"description\":\"not an op\",\"get\":{\"operationId\":\"ok\"}}}"),
                        "p");

        assertEquals(1, spec.operations().size());
        assertEquals("GET /ok", spec.operations().get(0).operationKey());
    }

    @Test
    void refusedDocumentsThrowOpenApiToolException() {
        assertThrows(OpenApiToolException.class, () -> parser.parse(null, "p"));
        assertThrows(OpenApiToolException.class, () -> parser.parse("   ", "p"));
        assertThrows(OpenApiToolException.class, () -> parser.parse("[1,2,3]", "p"));
        assertThrows(OpenApiToolException.class, () -> parser.parse("{\"foo\":1}", "p"));
        assertThrows(
                OpenApiToolException.class,
                () -> parser.parse("{\"openapi\":\"3.0.0\",\"paths\":{}}", "p"));
        assertThrows(
                OpenApiToolException.class,
                () -> parser.parse("{\"openapi\":\"3.0.0\",\"paths\":{\"/a\":{}}}", "p"));
        assertThrows(
                OpenApiToolException.class,
                () -> parser.parse("{not json at all", "p"));
    }

    @Test
    void serverVariablesAreSubstitutedWithDefaults() {
        ParsedSpec spec =
                parser.parse(
                        "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
                                + "\"servers\":[{\"url\":\"https://{region}.api.example.com/{v}\","
                                + "\"variables\":{\"region\":{\"default\":\"eu\"},\"v\":{\"default\":\"v3\"}}}],"
                                + "\"paths\":{\"/a\":{\"get\":{}}}}",
                        "p");

        assertEquals("https://eu.api.example.com/v3", spec.serverUrl());
    }

    @Test
    void slugifySplitsCamelCaseAndCleansUp() {
        assertEquals("list_users", OpenApiSpecParser.slugify("listUsers"));
        assertEquals("get_thing", OpenApiSpecParser.slugify("get-thing"));
        assertEquals("a_b", OpenApiSpecParser.slugify("__A//B__"));
        assertEquals("", OpenApiSpecParser.slugify(null));
        assertEquals("", OpenApiSpecParser.slugify("!!!"));
    }

    @Test
    void uniqueToolNameFallsBackAndDeduplicates() {
        Set<String> used = new HashSet<>();
        String first = OpenApiSpecParser.uniqueToolName("pfx", null, "GET", "/x", used);
        assertEquals("pfx_get_x", first);
        String second = OpenApiSpecParser.uniqueToolName("pfx", null, "GET", "/x", used);
        assertEquals("pfx_get_x_2", second);
        // An all-punctuation slug degrades to "op" rather than an empty name.
        assertEquals("p_op", OpenApiSpecParser.uniqueToolName("p", "!!!", "GET", "/", new HashSet<>()));
    }

    @Test
    void descriptionsComeFromSummaryThenDescription() {
        ParsedOperation op =
                parser.parse(
                                oas3(
                                        "{\"/a\":{\"get\":{\"description\":\"Long text\","
                                                + "\"deprecated\":true}}}"),
                                "p")
                        .operations()
                        .get(0);

        assertEquals("Long text", op.description());
        assertNull(op.summary());
        assertTrue(op.deprecated());
        assertNotNull(op.parametersJson());
    }
}
