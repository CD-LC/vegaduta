package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Characterisation of {@link DataJoinOps}: append / join (left, inner, outer) / first_non_empty and
 * minInputs.
 */
class DataJoinOpsTest {
    private static final ObjectMapper M = com.agenticai.core.json.JsonMappers.plain();

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static JsonNode merge(Map<String, Object> config, String input) throws Exception {
        return M.readTree(DataJoinOps.merge(M, config, input).output());
    }

    private static final String LEFT = "[{\"id\":1,\"a\":\"x\"},{\"id\":2,\"a\":\"y\"}]";
    private static final String RIGHT = "[{\"id\":\"1\",\"b\":\"p\"},{\"id\":3,\"b\":\"q\"}]";

    @Test
    void appendConcatenatesArraysAndWrapsScalars() throws Exception {
        assertEquals(
                M.readTree("[1,2,{\"k\":1},\"t\"]"),
                merge(m("sources", List.of("[1,2]", "{\"k\":1}", "t")), ""));
    }

    @Test
    void blankSourceIsAbsentAndMinInputsSkips() throws Exception {
        assertEquals(M.readTree("[1]"), merge(m("sources", List.of("[1]", " ")), ""));
        WorkflowDataOps.Outcome o =
                DataJoinOps.merge(M, m("sources", List.of("[1]", ""), "minInputs", 2), "IN");
        assertTrue(o.skipped());
        assertEquals("IN", o.output());
    }

    @Test
    void leftJoinKeepsUnmatchedLeftRowsAndMatchesNumbersAcrossTypes() throws Exception {
        assertEquals(
                M.readTree("[{\"id\":\"1\",\"a\":\"x\",\"b\":\"p\"},{\"id\":2,\"a\":\"y\"}]"),
                merge(m("mode", "join", "key", "id", "sources", List.of(LEFT, RIGHT)), ""));
    }

    @Test
    void innerJoinDropsUnmatchedAndOuterAddsUnmatchedRight() throws Exception {
        assertEquals(
                M.readTree("[{\"id\":\"1\",\"a\":\"x\",\"b\":\"p\"}]"),
                merge(
                        m(
                                "mode",
                                "join",
                                "key",
                                "id",
                                "joinType",
                                "inner",
                                "sources",
                                List.of(LEFT, RIGHT)),
                        ""));
        JsonNode outer =
                merge(
                        m(
                                "mode",
                                "join",
                                "key",
                                "id",
                                "joinType",
                                "outer",
                                "sources",
                                List.of(LEFT, RIGHT)),
                        "");
        assertEquals(3, outer.size());
        assertEquals("q", outer.get(2).get("b").asString());
    }

    @Test
    void joinErrorContract() {
        assertThrows(
                IllegalArgumentException.class,
                () -> merge(m("mode", "join", "sources", List.of(LEFT)), ""));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        merge(
                                m(
                                        "mode",
                                        "join",
                                        "key",
                                        "id",
                                        "joinType",
                                        "cross",
                                        "sources",
                                        List.of(LEFT)),
                                ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> merge(m("mode", "zip", "sources", List.of(LEFT)), ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> merge(m("mode", "join", "key", "id", "sources", List.of("[1]", "[2]")), ""));
    }

    @Test
    void firstNonEmptyPicksFirstUsableAndFallsBackToEmpty() throws Exception {
        assertEquals(
                "b",
                DataJoinOps.merge(
                                M,
                                m("mode", "first_non_empty", "sources", List.of("[]", "b", "c")),
                                "")
                        .output());
        assertEquals(
                "",
                DataJoinOps.merge(
                                M, m("mode", "first_non_empty", "sources", List.of("[]", "{}")), "")
                        .output());
    }

    @Test
    void withoutSourcesTheInputArrayEntriesAreTheInputsAndJsonTextIsParsed() throws Exception {
        assertEquals(M.readTree("[1,2,3]"), merge(m(), "[\"[1,2]\", 3]"));
    }

    @Test
    void joinKeyDistinguishesNumbersFromTextAndIgnoresBlank() {
        assertEquals("n:1", DataJoinOps.joinKey(M.getNodeFactory().textNode("1.0")));
        assertEquals("s:abc", DataJoinOps.joinKey(M.getNodeFactory().textNode("abc")));
        assertNull(DataJoinOps.joinKey(M.getNodeFactory().textNode(" ")));
        assertNull(DataJoinOps.joinKey(M.getNodeFactory().nullNode()));
    }
}
