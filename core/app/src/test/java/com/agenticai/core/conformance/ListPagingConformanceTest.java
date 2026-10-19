package com.agenticai.core.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Conformance by construction (ADR-0019): every list-returning GET operation in the committed
 * {@code docs/api/openapi.json} must take a paging parameter (limit, offset, page, size or
 * cursor), unless it is listed in {@code conformance/list-paging-allowlist.txt}.
 *
 * <p>The allowlist is a ratchet: it can only shrink. A new unpaged list endpoint fails the build,
 * and so does an allowlisted entry that now has paging (delete the line). To print the current
 * violations as allowlist lines run with {@code -Dconformance.print=true}.
 */
class ListPagingConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PAGING = Set.of("limit", "offset", "page", "size", "cursor");
    private static final String ALLOWLIST = "/conformance/list-paging-allowlist.txt";

    @Test
    void listEndpointsArePagedOrAllowlisted() throws IOException {
        Set<String> unpaged = unpagedListOperations();
        Set<String> allowed = allowlist();

        if (Boolean.getBoolean("conformance.print")) {
            unpaged.forEach(System.out::println);
        }

        Set<String> newViolations = new TreeSet<>(unpaged);
        newViolations.removeAll(allowed);
        assertThat(newViolations)
                .as(
                        "list-returning GET operations without limit/offset/page/size/cursor. Add"
                                + " paging; do not add to the allowlist (it can only shrink)")
                .isEmpty();

        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(unpaged);
        assertThat(stale)
                .as(
                        "allowlisted operations that are now paged or gone: delete these lines from"
                                + " list-paging-allowlist.txt")
                .isEmpty();
    }

    private static Set<String> allowlist() throws IOException {
        try (var in = ListPagingConformanceTest.class.getResourceAsStream(ALLOWLIST)) {
            assertThat(in).as("allowlist resource " + ALLOWLIST).isNotNull();
            Set<String> out = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    out.add(t);
                }
            }
            return out;
        }
    }

    static Set<String> unpagedListOperations() throws IOException {
        Path root = findRepoRoot();
        Assumptions.assumeTrue(root != null, "repo root not found");
        Path spec = root.resolve("docs").resolve("api").resolve("openapi.json");
        Assumptions.assumeTrue(Files.isRegularFile(spec), "docs/api/openapi.json missing");
        JsonNode doc = MAPPER.readTree(Files.readString(spec, StandardCharsets.UTF_8));
        JsonNode schemas = doc.path("components").path("schemas");
        Set<String> out = new TreeSet<>();
        Iterator<Map.Entry<String, JsonNode>> paths = doc.path("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> p = paths.next();
            JsonNode get = p.getValue().path("get");
            if (get.isMissingNode() || !returnsList(get, schemas) || hasPaging(get)) {
                continue;
            }
            out.add("GET " + p.getKey());
        }
        return out;
    }

    private static boolean hasPaging(JsonNode op) {
        for (JsonNode param : op.path("parameters")) {
            if ("query".equals(param.path("in").asText())
                    && PAGING.contains(param.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean returnsList(JsonNode op, JsonNode schemas) {
        JsonNode responses = op.path("responses");
        for (String code : List.of("200", "201", "202")) {
            JsonNode schema =
                    responses.path(code).path("content").path("application/json").path("schema");
            if (schema.isMissingNode()) {
                continue;
            }
            if (isArray(schema, schemas)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArray(JsonNode schema, JsonNode schemas) {
        for (int hops = 0; hops < 5; hops++) {
            if ("array".equals(schema.path("type").asText())) {
                return true;
            }
            String ref = schema.path("$ref").asText("");
            if (!ref.startsWith("#/components/schemas/")) {
                return false;
            }
            schema = schemas.path(ref.substring("#/components/schemas/".length()));
        }
        return false;
    }

    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("docs"))
                    && Files.isRegularFile(dir.resolve("core").resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
