package com.agenticai.core.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generated spec says what the settings routes can answer: 409 on every write (SETTING_LOCKED, SETTING_CONFLICT)
 * and the two bulk history routes exist with their paging parameters.
 */
class OpenApiSettingsRoutesTest {

    private static final List<String> WRITES =
            List.of(
                    "put /api/settings/tenant/{key}",
                    "delete /api/settings/tenant/{key}",
                    "put /api/agents/{agentId}/settings/{key}",
                    "delete /api/agents/{agentId}/settings/{key}");

    private static JsonNode doc;

    private static synchronized JsonNode paths() throws IOException {
        if (doc == null) {
            doc = new ObjectMapper().readTree(new OpenApiSpecGenerator("9.9.9").generateJson());
        }
        return doc.path("paths");
    }

    @Test
    void everySettingsWriteDocuments409AndSaysWhy() throws IOException {
        JsonNode paths = paths();
        for (String route : WRITES) {
            String verb = route.substring(0, route.indexOf(' '));
            String path = route.substring(route.indexOf(' ') + 1);
            JsonNode op = paths.path(path).path(verb);
            assertThat(op.isMissingNode()).as(route).isFalse();
            assertThat(op.path("responses").path("409").path("$ref").asText()).as(route).isEqualTo("#/components/responses/Conflict");
            assertThat(op.path("description").asText()).as(route).contains("SETTING_LOCKED").contains("SETTING_CONFLICT");
        }
    }

    @Test
    void readsAndTheBulkHistoryRoutesDoNotClaim409() throws IOException {
        JsonNode paths = paths();
        assertThat(paths.path("/api/settings/history").path("get").path("responses").has("409")).isFalse();
        assertThat(paths.path("/api/agents/{agentId}/settings/history").path("get").path("responses").has("409")).isFalse();
    }

    @Test
    void theBulkHistoryRoutesAreDocumentedWithTheirPagingParameters() throws IOException {
        JsonNode paths = paths();
        JsonNode workspace = paths.path("/api/settings/history").path("get");
        assertThat(workspace.isMissingNode()).isFalse();
        assertThat(paramNames(workspace)).contains("scope", "agentId", "since", "limit", "offset");
        assertThat(workspace.path("responses").path("200").path("content").path("application/json").path("schema").path("$ref").asText())
                .endsWith("SettingsController_HistoryPageResponse");
        JsonNode agent = paths.path("/api/agents/{agentId}/settings/history").path("get");
        assertThat(agent.isMissingNode()).isFalse();
        assertThat(paramNames(agent)).contains("agentId", "since", "limit", "offset");
    }

    @Test
    void everyExtraErrorKeyMatchesARealOperation() throws IOException {
        JsonNode paths = paths();
        List<String> missing = new ArrayList<>();
        for (String key : OpenApiExtraErrors.keys()) {
            String verb = key.substring(0, key.indexOf(' ')).toLowerCase(java.util.Locale.ROOT);
            String path = key.substring(key.indexOf(' ') + 1);
            if (paths.path(path).path(verb).isMissingNode()) {
                missing.add(key);
            }
        }
        assertThat(missing).isEmpty();
    }

    private static List<String> paramNames(JsonNode op) {
        List<String> names = new ArrayList<>();
        op.path("parameters").forEach(p -> names.add(p.path("name").asText()));
        return names;
    }
}
