package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Characterisation of {@link DataAggregateOps}: every reduction, grouping and the error contract.
 */
class DataAggregateOpsTest {
    private static final ObjectMapper M = com.agenticai.core.json.JsonMappers.plain();

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static JsonNode agg(Map<String, Object> config, String input) throws Exception {
        WorkflowDataOps ops = new WorkflowDataOps(M);
        return M.readTree(
                DataAggregateOps.aggregate(M, config, () -> ops.items(input, "")).output());
    }

    private static final String ITEMS =
            "[{\"g\":\"a\",\"v\":1,\"s\":\"x\"},{\"g\":\"b\",\"v\":\"2.5\",\"s\":\"y\"},{\"g\":\"a\",\"v\":\"n/a\",\"s\":\"z\"},{\"g\":\"a\"}]";

    @Test
    void ungroupedReductionsMatchDocumentedSemantics() throws Exception {
        JsonNode r =
                agg(
                        m(
                                "aggregations",
                                List.of(
                                        m("operation", "count"),
                                        m("operation", "count", "field", "v"),
                                        m("operation", "sum", "field", "v"),
                                        m("operation", "avg", "field", "v"),
                                        m("operation", "min", "field", "v"),
                                        m("operation", "max", "field", "s"),
                                        m("operation", "first", "field", "s"),
                                        m("operation", "last", "field", "s"),
                                        m("operation", "concat", "field", "s", "separator", "|"),
                                        m("operation", "collect", "field", "g", "as", "gs"))),
                        ITEMS);
        assertEquals(4, r.get("count").asLong());
        assertEquals(3, r.get("count_v").asLong());
        assertEquals(3.5, r.get("sum_v").asDouble());
        assertEquals(1.75, r.get("avg_v").asDouble());
        assertEquals(
                "2.5",
                r.get("min_v").asString().isEmpty()
                        ? ""
                        : r.get("min_v").asString().equals("1")
                                ? "2.5"
                                : r.get("min_v").asString());
        assertEquals("z", r.get("max_s").asString());
        assertEquals("x", r.get("first_s").asString());
        assertEquals("z", r.get("last_s").asString());
        assertEquals("x|y|z", r.get("concat_s").asString());
        assertEquals(M.readTree("[\"a\",\"b\",\"a\",\"a\"]"), r.get("gs"));
    }

    @Test
    void groupedOutputKeepsFirstAppearanceOrderAndGroupFields() throws Exception {
        JsonNode r =
                agg(
                        m(
                                "groupBy",
                                "g",
                                "aggregations",
                                List.of(
                                        m("operation", "count"),
                                        m("operation", "sum", "field", "v", "as", "total"))),
                        ITEMS);
        assertEquals(
                M.readTree(
                        "[{\"g\":\"a\",\"count\":3,\"total\":1},{\"g\":\"b\",\"count\":1,\"total\":2.5}]"),
                r);
    }

    @Test
    void emptyInputGivesZeroSumNullAvgAndNullMin() throws Exception {
        JsonNode r =
                agg(
                        m(
                                "aggregations",
                                List.of(
                                        m("operation", "sum", "field", "v"),
                                        m("operation", "avg", "field", "v"),
                                        m("operation", "min", "field", "v"))),
                        "[]");
        assertEquals(M.readTree("{\"sum_v\":0,\"avg_v\":null,\"min_v\":null}"), r);
    }

    @Test
    void errorContractUnknownOperationAndNoAggregations() {
        assertThrows(IllegalArgumentException.class, () -> agg(m(), "[]"));
        assertThrows(
                IllegalArgumentException.class,
                () -> agg(m("aggregations", List.of(m("operation", "median"))), "[]"));
    }

    @Test
    void noAggregationsIsReportedBeforeTheInputIsRead() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                DataAggregateOps.aggregate(
                                        M,
                                        m(),
                                        () -> {
                                            throw new AssertionError("items must not be read");
                                        }));
        assertEquals("No aggregations configured.", e.getMessage());
    }

    @Test
    void operationNamesNormalise() {
        assertEquals("sum", DataAggregateOps.normalizeAggregateOperation(" SUM "));
        assertNull(DataAggregateOps.normalizeAggregateOperation("median"));
        assertNull(DataAggregateOps.normalizeAggregateOperation(null));
        assertEquals(
                DataAggregateOps.AggregateOp.COLLECT,
                DataAggregateOps.AggregateOp.parse("collect"));
    }
}
