package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Characterisation of {@link DataSortOps}: key typing, stability, missing-last, multi-key. */
class DataSortOpsTest {
    private static final ObjectMapper M = com.agenticai.core.json.JsonMappers.plain();

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static Map<String, Object> self(Object... kv) {
        Map<String, Object> key = m("field", "");
        for (int i = 0; i < kv.length; i += 2) {
            key.put((String) kv[i], kv[i + 1]);
        }
        return m("sortKeys", List.of(key));
    }

    private static Map<String, Object> withCase(Map<String, Object> config) {
        config.put("caseSensitive", true);
        return config;
    }

    private static String sort(Map<String, Object> config, String input) {
        WorkflowDataOps ops = new WorkflowDataOps(M);
        return DataSortOps.sort(
                        M, config, ops.items(input, WorkflowDataOps.str(config.get("itemsField"))))
                .output();
    }

    @Test
    void autoPicksNumbersSoTenSortsAfterNine() throws Exception {
        assertEquals(
                M.readTree("[{\"n\":2},{\"n\":9},{\"n\":10}]"),
                M.readTree(sort(m("field", "n"), "[{\"n\":10},{\"n\":2},{\"n\":9}]")));
    }

    @Test
    void autoDoesNotTreatLeadingZeroTextAsNumbersButExplicitNumberDoes() throws Exception {
        assertEquals(
                M.readTree("[\"02\",\"10\",\"3\"]"),
                M.readTree(sort(self(), "[\"10\",\"02\",\"3\"]")));
        assertEquals(
                M.readTree("[\"02\",\"3\",\"10\"]"),
                M.readTree(sort(self("type", "number"), "[\"10\",\"02\",\"3\"]")));
    }

    @Test
    void descendingKeepsMissingValuesLast() throws Exception {
        assertEquals(
                M.readTree("[{\"n\":3},{\"n\":1},{\"x\":0},{\"n\":null}]"),
                M.readTree(
                        sort(
                                m("field", "n", "direction", "desc"),
                                "[{\"x\":0},{\"n\":1},{\"n\":null},{\"n\":3}]")));
    }

    @Test
    void datesSortChronologicallyUnderAuto() throws Exception {
        assertEquals(
                M.readTree("[\"2020-01-02\",\"2021-01-01T00:00:00Z\"]"),
                M.readTree(sort(self(), "[\"2021-01-01T00:00:00Z\",\"2020-01-02\"]")));
    }

    @Test
    void stringSortIsCaseInsensitiveUnlessCaseSensitive() throws Exception {
        assertEquals(
                M.readTree("[\"a\",\"B\"]"),
                M.readTree(sort(self("type", "string"), "[\"B\",\"a\"]")));
        assertEquals(
                M.readTree("[\"B\",\"a\"]"),
                M.readTree(sort(withCase(self("type", "string")), "[\"a\",\"B\"]")));
    }

    @Test
    void multiKeyIsStableOnTies() throws Exception {
        List<Map<String, Object>> keys =
                List.of(m("field", "g"), m("field", "n", "direction", "desc"));
        assertEquals(
                M.readTree(
                        "[{\"g\":1,\"n\":5,\"i\":0},{\"g\":1,\"n\":5,\"i\":1},{\"g\":2,\"n\":9,\"i\":2}]"),
                M.readTree(
                        sort(
                                m("sortKeys", keys),
                                "[{\"g\":2,\"n\":9,\"i\":2},{\"g\":1,\"n\":5,\"i\":0},{\"g\":1,\"n\":5,\"i\":1}]")));
    }

    @Test
    void noKeyLeavesOrderAndItemsFieldKeepsSiblings() throws Exception {
        assertEquals(M.readTree("[2,1]"), M.readTree(sort(m(), "[2,1]")));
        assertEquals(
                M.readTree("{\"keep\":true,\"l\":[1,2]}"),
                M.readTree(
                        sort(
                                m("itemsField", "l", "sortKeys", List.of(m("field", ""))),
                                "{\"keep\":true,\"l\":[2,1]}")));
    }

    @Test
    void sortTypeResolveHonoursExplicitTypeAndFallsBackToString() {
        assertEquals(
                DataSortOps.SortType.NUMBER, DataSortOps.SortType.resolve("NUMBER", List.of()));
        assertEquals(DataSortOps.SortType.STRING, DataSortOps.SortType.resolve(null, List.of()));
        assertEquals(
                DataSortOps.SortType.STRING,
                DataSortOps.SortType.resolve("auto", List.of(M.getNodeFactory().textNode("x"))));
    }
}
