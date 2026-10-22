package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowExpressionApi.SchemaPath;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Path and type inference for the data picker, and the caps the API contract promises. */
class UpstreamSchemaInfererTest {

    private final UpstreamSchemaInferer inferer = new UpstreamSchemaInferer(new ObjectMapper());

    private static Map<String, SchemaPath> byPath(List<SchemaPath> paths) {
        return paths.stream()
                .collect(
                        Collectors.toMap(
                                SchemaPath::path,
                                p -> p,
                                (a, b) -> a,
                                java.util.LinkedHashMap::new));
    }

    @Test
    void listsContainersBeforeTheirChildrenWithTypesAndScalarSamples() {
        List<SchemaPath> paths =
                inferer.infer(
                        "{\"customer\":{\"name\":\"Ada\",\"vip\":true},\"items\":[{\"price\":10},{\"price\":20.5}],\"note\":null}");

        assertEquals(
                List.of(
                        "customer",
                        "customer.name",
                        "customer.vip",
                        "items",
                        "items[0]",
                        "items[0].price",
                        "items[1]",
                        "items[1].price",
                        "note"),
                paths.stream().map(SchemaPath::path).toList());
        Map<String, SchemaPath> m = byPath(paths);
        assertEquals("object", m.get("customer").type());
        assertNull(m.get("customer").sample(), "containers have no sample");
        assertEquals("string", m.get("customer.name").type());
        assertEquals("Ada", m.get("customer.name").sample());
        assertEquals("boolean", m.get("customer.vip").type());
        assertEquals("true", m.get("customer.vip").sample());
        assertEquals("array", m.get("items").type());
        assertEquals("number", m.get("items[1].price").type());
        assertEquals("20.5", m.get("items[1].price").sample());
        assertEquals("null", m.get("note").type());
        assertNull(m.get("note").sample());
    }

    @Test
    void onlyTheFirstThreeElementsOfAnArrayAreWalked() {
        List<SchemaPath> paths =
                inferer.infer("{\"rows\":[{\"a\":1},{\"a\":2},{\"a\":3},{\"a\":4},{\"a\":5}]}");
        List<String> names = paths.stream().map(SchemaPath::path).toList();
        assertTrue(names.contains("rows[2].a"));
        assertFalse(names.contains("rows[3]"), names.toString());
        assertEquals(1 + 3 * 2, names.size());
    }

    @Test
    void pathsAreAtMostSixSegmentsDeep() {
        List<SchemaPath> paths =
                inferer.infer("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":1}}}}}}}");
        List<String> names = paths.stream().map(SchemaPath::path).toList();
        assertEquals(List.of("a", "a.b", "a.b.c", "a.b.c.d", "a.b.c.d.e", "a.b.c.d.e.f"), names);
    }

    @Test
    void theNumberOfPathsPerNodeIsCappedAt200() {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 500; i++) {
            json.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":").append(i);
        }
        List<SchemaPath> paths = inferer.infer(json.append("}").toString());
        assertEquals(200, paths.size());
        assertEquals("k0", paths.get(0).path());
        assertEquals("k199", paths.get(199).path());
    }

    @Test
    void theCapAlsoHoldsWhenItIsReachedInsideNestedStructure() {
        StringBuilder json = new StringBuilder("{\"list\":[");
        for (int i = 0; i < 3; i++) {
            json.append(i == 0 ? "" : ",").append("{");
            for (int k = 0; k < 150; k++) {
                json.append(k == 0 ? "" : ",").append("\"f").append(k).append("\":").append(k);
            }
            json.append("}");
        }
        assertEquals(200, inferer.infer(json.append("]}").toString()).size());
    }

    @Test
    void keysThatAreNotIdentifiersAreAddressedWithBrackets() {
        List<String> names =
                inferer
                        .infer("{\"e-mail\":\"a@b.co\",\"n\":{\"first name\":\"A\",\"ok_1\":1}}")
                        .stream()
                        .map(SchemaPath::path)
                        .toList();
        assertEquals(List.of("[\"e-mail\"]", "n", "n[\"first name\"]", "n.ok_1"), names);
    }

    @Test
    void aRootArrayIsIndexedFromTheRoot() {
        List<String> names =
                inferer.infer("[{\"id\":1},{\"id\":2}]").stream().map(SchemaPath::path).toList();
        assertEquals(List.of("[0]", "[0].id", "[1]", "[1].id"), names);
    }

    @Test
    void longSamplesAreTruncated() {
        SchemaPath p = inferer.infer("{\"t\":\"" + "x".repeat(500) + "\"}").get(0);
        assertEquals(UpstreamSchemaInferer.MAX_SAMPLE_CHARS + 3, p.sample().length());
        assertTrue(p.sample().endsWith("..."));
    }

    @Test
    void nonJsonAndScalarOutputsHaveNoSubPaths() {
        assertEquals(List.of(), inferer.infer("just some text"));
        assertEquals(List.of(), inferer.infer("42"));
        assertEquals(List.of(), inferer.infer("\"a string\""));
        assertEquals(List.of(), inferer.infer(""));
        assertEquals(List.of(), inferer.infer(null));
        assertEquals(List.of(), inferer.infer("{\"broken\": "));
    }
}
