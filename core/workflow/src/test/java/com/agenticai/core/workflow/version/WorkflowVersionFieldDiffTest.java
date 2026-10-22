package com.agenticai.core.workflow.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 4: field-level node diff for the canvas's version-diff view. */
class WorkflowVersionFieldDiffTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowVersionDiffer service = new WorkflowVersionDiffer(mapper);

    private JsonNode node(String json) throws Exception {
        return mapper.readTree(json);
    }

    private Map<String, Object> field(List<Map<String, Object>> fields, String path) {
        return fields.stream()
                .filter(f -> path.equals(f.get("path")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(path + " not in " + fields));
    }

    @Test
    void reportsDottedConfigPathsWithBeforeAndAfter() throws Exception {
        List<Map<String, Object>> fields =
                service.nodeFieldChanges(
                        node(
                                "{\"id\":\"l\",\"type\":\"loop\",\"data\":{\"label\":\"Each lead\",\"config\":{\"maxIterations\":10,\"itemsField\":\"items\"}}}"),
                        node(
                                "{\"id\":\"l\",\"type\":\"loop\",\"data\":{\"label\":\"Each lead!\",\"config\":{\"maxIterations\":25,\"itemsField\":\"items\",\"new\":true}}}"));
        assertEquals("10", field(fields, "config.maxIterations").get("before"));
        assertEquals("25", field(fields, "config.maxIterations").get("after"));
        assertEquals("Each lead!", field(fields, "label").get("after"));
        assertEquals(null, field(fields, "config.new").get("before"));
        assertFalse(
                fields.stream().anyMatch(f -> "config.itemsField".equals(f.get("path"))),
                "unchanged fields are omitted");
    }

    @Test
    void secretValuesAreRedactedButTheChangeIsStillShown() throws Exception {
        List<Map<String, Object>> fields =
                service.nodeFieldChanges(
                        node(
                                "{\"id\":\"h\",\"type\":\"http\",\"data\":{\"config\":{\"headers\":{\"Authorization\":\"Bearer old123456789\"},\"apiKey\":\"k-1\"}}}"),
                        node(
                                "{\"id\":\"h\",\"type\":\"http\",\"data\":{\"config\":{\"headers\":{\"Authorization\":\"Bearer new987654321\"},\"apiKey\":\"k-2\"}}}"));
        Map<String, Object> auth = field(fields, "config.headers.Authorization");
        assertEquals("[redacted]", auth.get("before"));
        assertEquals("[redacted]", auth.get("after"));
        assertEquals("[redacted]", field(fields, "config.apiKey").get("after"));
        assertFalse(fields.toString().contains("987654321"));
    }

    @Test
    void secretShapedValuesUnderInnocentKeysAreRedactedToo() throws Exception {
        String key = "sk-" + "A".repeat(30);
        List<Map<String, Object>> fields =
                service.nodeFieldChanges(
                        node(
                                "{\"id\":\"c\",\"type\":\"code\",\"data\":{\"config\":{\"template\":\"hello\"}}}"),
                        node(
                                "{\"id\":\"c\",\"type\":\"code\",\"data\":{\"config\":{\"template\":\"use "
                                        + key
                                        + "\"}}}"));
        assertFalse(String.valueOf(field(fields, "config.template").get("after")).contains(key));
    }

    @Test
    @SuppressWarnings("unchecked")
    void changedNodesInTheGraphDiffCarryFields() {
        String a =
                "{\"nodes\":[{\"id\":\"n\",\"type\":\"code\",\"data\":{\"config\":{\"operation\":\"uppercase\"}}}],\"edges\":[]}";
        String b =
                "{\"nodes\":[{\"id\":\"n\",\"type\":\"code\",\"data\":{\"config\":{\"operation\":\"lowercase\"}}}],\"edges\":[]}";
        Map<String, Object> graph = service.diffGraphJson(a, b);
        Map<String, Object> changed =
                ((List<Map<String, Object>>) graph.get("changedNodes")).get(0);
        assertTrue(
                ((List<String>) changed.get("changes")).contains("config changed"),
                "legacy strings kept");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) changed.get("fields");
        assertEquals("config.operation", fields.get(0).get("path"));
        assertEquals("lowercase", fields.get(0).get("after"));
    }
}
