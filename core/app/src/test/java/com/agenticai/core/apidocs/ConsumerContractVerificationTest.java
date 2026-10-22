package com.agenticai.core.apidocs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Provider side of the consumer-driven contracts (ADR-0025): every interaction in {@code
 * contracts/consumers/<consumer>/*.contract.json} must be served by the OpenAPI document generated
 * from the controllers on the classpath (the same generator, and so the same document, as {@code
 * docs/api/openapi.json}).
 *
 * <p>Per interaction: the operation (method + path template, parameter names ignored) exists, the
 * status is declared, each {@code request.requiredFields} entry is in the request body schema, and
 * each {@code response.requiredFields} entry is in the schema of that status. Fields are resolved
 * by {@link ContractSchemaFields}. A failure names the consumer, the file, the interaction id and
 * the missing operation, status or field: either the provider broke a consumer (fix the
 * controller), or the consumer's contract is wrong (fix and regenerate it on the consumer side).
 */
class ConsumerContractVerificationTest {

    private static final Set<String> VERBS =
            Set.of("GET", "PUT", "POST", "DELETE", "PATCH", "OPTIONS", "HEAD", "TRACE");

    @Test
    void everyConsumerInteractionIsServedByTheProvider() throws IOException {
        Path root = ApiContractRepoSupport.findRepoRoot();
        assertThat(root).as("repo root (docs/ and core/pom.xml) above the working dir").isNotNull();
        Path consumers = root.resolve("contracts").resolve("consumers");
        assertThat(consumers).as("contracts/consumers").isDirectory();

        JsonNode spec = ApiContractRepoSupport.generatedSpec(root);
        Result result = verify(spec, consumers);
        System.out.println(
                "[consumer-contracts] verified "
                        + result.interactions
                        + " interactions from "
                        + result.perConsumer
                        + "; "
                        + result.openFields
                        + " fields resolved only against free-form schemas");
        assertThat(result.interactions)
                .as("interactions found under contracts/consumers")
                .isPositive();
        if (!result.failures.isEmpty()) {
            throw new AssertionError(
                    result.failures.size()
                            + " consumer contract violation(s):\n  "
                            + String.join("\n  ", result.failures));
        }
    }

    /** Outcome of a verification run. */
    static final class Result {
        final List<String> failures = new ArrayList<>();
        final Map<String, Integer> perConsumer = new LinkedHashMap<>();
        int interactions;
        int openFields;
    }

    /** Verifies every contract file below {@code consumersDir} against {@code spec}. */
    static Result verify(JsonNode spec, Path consumersDir) throws IOException {
        Result result = new Result();
        Map<String, List<String>> index = indexPaths(spec);
        ContractSchemaFields fields = new ContractSchemaFields(spec);
        List<Path> consumerDirs;
        try (Stream<Path> s = Files.list(consumersDir)) {
            consumerDirs = s.filter(Files::isDirectory).sorted().toList();
        }
        for (Path dir : consumerDirs) {
            String consumer = dir.getFileName().toString();
            List<Path> files;
            try (Stream<Path> s = Files.walk(dir)) {
                files =
                        s.filter(p -> p.getFileName().toString().endsWith(".contract.json"))
                                .sorted()
                                .toList();
            }
            Set<String> ids = new HashSet<>();
            for (Path file : files) {
                String where = consumer + "/" + dir.relativize(file).toString().replace('\\', '/');
                JsonNode doc;
                try {
                    doc = ApiContractRepoSupport.MAPPER.readTree(file.toFile());
                } catch (JacksonException e) {
                    result.failures.add(where + ": not valid JSON (" + e.getMessage() + ")");
                    continue;
                }
                if (!consumer.equals(doc.path("consumer").asString())) {
                    result.failures.add(
                            where + ": \"consumer\" must be \"" + consumer + "\" (the directory)");
                }
                if (!"core".equals(doc.path("provider").asString())) {
                    result.failures.add(where + ": \"provider\" must be \"core\"");
                }
                for (JsonNode it : doc.path("interactions")) {
                    result.interactions++;
                    result.perConsumer.merge(consumer, 1, Integer::sum);
                    String id = it.path("id").asString("");
                    if (id.isBlank()) {
                        result.failures.add(where + ": an interaction has no id");
                    } else if (!ids.add(id)) {
                        result.failures.add(where + " [" + id + "]: duplicate interaction id");
                    }
                    checkInteraction(spec, index, fields, where, id, it, result);
                }
            }
        }
        return result;
    }

    private static void checkInteraction(
            JsonNode spec,
            Map<String, List<String>> index,
            ContractSchemaFields fields,
            String where,
            String id,
            JsonNode it,
            Result result) {
        String method = it.path("method").asString("").toUpperCase(Locale.ROOT);
        String path = it.path("path").asString("");
        String label = where + " [" + id + "] " + method + " " + path;
        if (!VERBS.contains(method) || !path.startsWith("/")) {
            result.failures.add(label + ": needs an HTTP method and a path starting with /");
            return;
        }
        JsonNode op = null;
        for (String candidate : index.getOrDefault(normalize(path), List.of())) {
            JsonNode o = spec.path("paths").path(candidate).path(method.toLowerCase(Locale.ROOT));
            if (!o.isMissingNode()) {
                op = o;
                break;
            }
        }
        if (op == null) {
            result.failures.add(label + ": no such operation in the provider's OpenAPI document");
            return;
        }
        String status = it.path("status").asString("");
        JsonNode response = op.path("responses").path(status);
        if (status.isEmpty() || response.isMissingNode()) {
            result.failures.add(label + ": status " + status + " is not declared by the provider");
            return;
        }
        JsonNode requestFields = it.path("request").path("requiredFields");
        if (requestFields.size() > 0) {
            JsonNode body = fields.bodySchema(op.path("requestBody"));
            checkFields(fields, body, requestFields, label, "request", result);
        }
        JsonNode responseFields = it.path("response").path("requiredFields");
        if (responseFields.size() > 0) {
            JsonNode body = fields.bodySchema(response);
            checkFields(fields, body, responseFields, label, "response", result);
        }
    }

    private static void checkFields(
            ContractSchemaFields fields,
            JsonNode body,
            JsonNode required,
            String label,
            String side,
            Result result) {
        for (JsonNode f : required) {
            String field = f.asString();
            if (body == null) {
                result.failures.add(
                        label + ": " + side + " field " + field + " but no " + side + " body");
                continue;
            }
            try {
                if (!fields.has(body, field)) {
                    result.failures.add(label + ": " + side + " field " + field + " is missing");
                } else if (fields.isOpen(body, field)) {
                    result.openFields++;
                }
            } catch (IllegalArgumentException e) {
                result.failures.add(label + ": " + e.getMessage());
            }
        }
    }

    /** Normalised template ({@code {anything}} to {@code {}}) to the document's path keys. */
    static Map<String, List<String>> indexPaths(JsonNode spec) {
        Map<String, List<String>> index = new HashMap<>();
        for (Iterator<String> it = spec.path("paths").propertyNames().iterator(); it.hasNext(); ) {
            String p = it.next();
            index.computeIfAbsent(normalize(p), k -> new ArrayList<>()).add(p);
        }
        return index;
    }

    static String normalize(String template) {
        return template.replaceAll("\\{[^/{}]*}", "{}");
    }

    // ================================================================== checker self-tests

    private static final String MINI_SPEC =
            """
            {"paths": {
               "/api/things/{id}": {
                 "get": {"responses": {"200": {"content": {"application/json":
                          {"schema": {"$ref": "#/components/schemas/Thing"}}}}}},
                 "put": {"requestBody": {"content": {"application/json":
                          {"schema": {"$ref": "#/components/schemas/ThingInput"}}}},
                         "responses": {"204": {"description": "ok"}}}},
               "/api/things": {
                 "get": {"responses": {"200": {"content": {"application/json":
                          {"schema": {"type": "array",
                                      "items": {"$ref": "#/components/schemas/Thing"}}}}}}}}},
             "components": {"schemas": {
               "Thing": {"allOf": [{"$ref": "#/components/schemas/Base"},
                                   {"type": "object", "properties": {
                                     "name": {"type": "string"},
                                     "tags": {"type": "array", "items": {"type": "object",
                                              "properties": {"label": {"type": "string"}}}},
                                     "extra": {"type": "object",
                                               "additionalProperties": {"type": "string"}},
                                     "self": {"$ref": "#/components/schemas/Thing"}}}]},
               "Base": {"type": "object", "properties": {"id": {"type": "string"}}},
               "ThingInput": {"type": "object", "properties": {"name": {"type": "string"}}}}}}
            """;

    @Test
    void fieldResolutionFollowsRefsAllOfArraysAndFreeFormObjects() throws IOException {
        JsonNode spec = ApiContractRepoSupport.MAPPER.readTree(MINI_SPEC);
        ContractSchemaFields f = new ContractSchemaFields(spec);
        JsonNode thing = spec.path("components").path("schemas").path("Thing");
        assertThat(f.has(thing, "$.id")).isTrue();
        assertThat(f.has(thing, "$.name")).isTrue();
        assertThat(f.has(thing, "$.tags[*].label")).isTrue();
        assertThat(f.has(thing, "$.tags[*].nope")).isFalse();
        assertThat(f.has(thing, "$.self.self.name")).isTrue();
        assertThat(f.has(thing, "$.missing")).isFalse();
        assertThat(f.has(thing, "$.extra.anything")).isTrue();
        assertThat(f.isOpen(thing, "$.extra.anything")).isTrue();
        assertThat(f.isOpen(thing, "$.name")).isFalse();
        assertThat(f.has(thing, "$.name[*]")).isFalse();
        assertThat(ContractSchemaFields.tokens("$[*].a.b")).containsExactly("[*]", "a", "b");
        assertThatThrownBy(() -> ContractSchemaFields.tokens("a.b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractSchemaFields.tokens("$.a..b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyNamesConsumerInteractionAndMissingPiece() throws IOException {
        JsonNode spec = ApiContractRepoSupport.MAPPER.readTree(MINI_SPEC);
        Path dir = Files.createTempDirectory("contracts");
        try {
            Path consumer = Files.createDirectories(dir.resolve("demo"));
            Files.writeString(
                    consumer.resolve("things.contract.json"),
                    """
                    {"consumer": "demo", "provider": "core", "interactions": [
                      {"id": "ok-list", "method": "GET", "path": "/api/things", "status": 200,
                       "response": {"requiredFields": ["$[*].id", "$[*].tags[*].label"]}},
                      {"id": "ok-put", "method": "PUT", "path": "/api/things/{thingId}",
                       "status": 204, "request": {"requiredFields": ["$.name"]}},
                      {"id": "bad-field", "method": "GET", "path": "/api/things/{id}",
                       "status": 200, "response": {"requiredFields": ["$.colour"]}},
                      {"id": "bad-status", "method": "GET", "path": "/api/things/{id}",
                       "status": 201},
                      {"id": "bad-op", "method": "DELETE", "path": "/api/things/{id}",
                       "status": 204},
                      {"id": "bad-body", "method": "PUT", "path": "/api/things/{id}",
                       "status": 204, "response": {"requiredFields": ["$.id"]}},
                      {"id": "ok-list", "method": "GET", "path": "/api/things", "status": 200}
                    ]}
                    """);
            Result r = verify(spec, dir);
            assertThat(r.interactions).isEqualTo(7);
            assertThat(String.join("\n", r.failures))
                    .contains("demo/things.contract.json [bad-field] GET /api/things/{id}")
                    .contains("response field $.colour is missing")
                    .contains("[bad-status] GET /api/things/{id}: status 201 is not declared")
                    .contains("[bad-op] DELETE /api/things/{id}: no such operation")
                    .contains("[bad-body] PUT /api/things/{id}: response field $.id but no")
                    .contains("[ok-list]: duplicate interaction id")
                    .doesNotContain("[ok-put]");
            assertThat(r.failures).hasSize(5);
        } finally {
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
