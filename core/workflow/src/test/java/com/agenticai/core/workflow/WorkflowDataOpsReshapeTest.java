package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.WorkflowDataOps.Outcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link WorkflowDataOps}: Sort, Aggregate, Limit and Dedupe. */
class WorkflowDataOpsReshapeTest extends WorkflowDataOpsTestBase {

    // =====================================================================================================
    // SORT
    // =====================================================================================================

    private List<String> sortedV(String input, Map<String, Object> config) {
        return col(ops.sort(config, input).output(), "v");
    }

    @Test
    void sortNumbersAscendingWithNullsAndMissingLastInOriginalOrder() {
        String input = "[{\"v\":10},{\"v\":2},{\"v\":\"7\"},{\"v\":null},{\"w\":1}]";
        Outcome o = ops.sort(m("sortKeys", List.of(m("field", "v", "direction", "asc"))), input);
        assertJson("[{\"v\":2},{\"v\":\"7\"},{\"v\":10},{\"v\":null},{\"w\":1}]", o.output());
    }

    @Test
    void sortDescendingStillPutsNullsLast() {
        String input = "[{\"v\":10},{\"v\":null},{\"v\":2},{\"v\":\"7\"},{\"w\":1}]";
        Outcome o = ops.sort(m("sortKeys", List.of(m("field", "v", "direction", "desc"))), input);
        assertJson("[{\"v\":10},{\"v\":\"7\"},{\"v\":2},{\"v\":null},{\"w\":1}]", o.output());
    }

    @Test
    void sortStringsIsCaseInsensitiveByDefaultAndStable() {
        Map<String, Object> cfg = m("sortKeys", List.of(m("field", "", "direction", "asc")));
        assertJson("[\"a\",\"b\",\"B\"]", ops.sort(cfg, "[\"b\",\"B\",\"a\"]").output());
        Map<String, Object> sensitive = m("caseSensitive", true, "sortKeys", List.of(m("field", "", "direction", "asc")));
        assertJson("[\"B\",\"a\",\"b\"]", ops.sort(sensitive, "[\"b\",\"B\",\"a\"]").output());
    }

    @Test
    void sortDatesChronologically() {
        String input = "[{\"v\":\"2024-03-01\"},{\"v\":\"2023-12-31T23:00:00Z\"},{\"v\":\"2024-01-05\"}]";
        assertEquals(List.of("2023-12-31T23:00:00Z", "2024-01-05", "2024-03-01"),
            sortedV(input, m("sortKeys", List.of(m("field", "v")))));
    }

    @Test
    void sortMixedNumbersAndTextFallsBackToTextOrder() {
        String input = "[{\"v\":\"abc\"},{\"v\":\"9\"},{\"v\":\"10\"}]";
        assertEquals(List.of("10", "9", "abc"), sortedV(input, m("sortKeys", List.of(m("field", "v")))));
    }

    @Test
    void sortLeadingZeroTextIsNotTreatedAsANumber() {
        assertJson("[\"010\",\"9\"]", ops.sort(m("sortKeys", List.of(m("field", ""))), "[\"9\",\"010\"]").output());
    }

    @Test
    void sortForcedNumberPutsGarbageLast() {
        String input = "[{\"v\":\"x\"},{\"v\":3},{\"v\":1}]";
        assertJson("[{\"v\":1},{\"v\":3},{\"v\":\"x\"}]",
            ops.sort(m("sortKeys", List.of(m("field", "v", "type", "number"))), input).output());
    }

    @Test
    void sortByTwoKeys() {
        String input = "[{\"g\":\"b\",\"n\":2},{\"g\":\"a\",\"n\":3},{\"g\":\"b\",\"n\":1},{\"g\":\"a\",\"n\":1}]";
        Outcome o = ops.sort(m("sortKeys", List.of(m("field", "g", "direction", "asc"), m("field", "n", "direction", "desc"))), input);
        assertJson("[{\"g\":\"a\",\"n\":3},{\"g\":\"a\",\"n\":1},{\"g\":\"b\",\"n\":2},{\"g\":\"b\",\"n\":1}]", o.output());
    }

    @Test
    void sortIsStableForTies() {
        String input = "[{\"k\":1,\"id\":\"a\"},{\"k\":1,\"id\":\"b\"},{\"k\":0,\"id\":\"c\"},{\"k\":1,\"id\":\"d\"}]";
        Outcome o = ops.sort(m("sortKeys", List.of(m("field", "k"))), input);
        assertEquals(List.of("c", "a", "b", "d"), col(o.output(), "id"));
    }

    @Test
    void sortSingleKeyShorthandAndNoKey() {
        assertEquals(List.of("3", "2", "1"), sortedV("[{\"v\":2},{\"v\":3},{\"v\":1}]", m("field", "v", "direction", "desc")));
        Outcome none = ops.sort(m(), "[3,1,2]");
        assertJson("[3,1,2]", none.output());
    }

    @Test
    void sortEmptyListAndItemsField() {
        assertJson("[]", ops.sort(m("sortKeys", List.of(m("field", "v"))), "[]").output());
        Outcome o = ops.sort(m("itemsField", "rows", "sortKeys", List.of(m("field", "v"))), "{\"rows\":[{\"v\":2},{\"v\":1}],\"x\":0}");
        assertJson("{\"rows\":[{\"v\":1},{\"v\":2}],\"x\":0}", o.output());
    }

    // =====================================================================================================
    // AGGREGATE
    // =====================================================================================================

    private static final String A = "[{\"g\":\"x\",\"v\":1,\"s\":\"a\"},{\"g\":\"y\",\"v\":\"2.5\",\"s\":\"b\"},"
        + "{\"g\":\"x\",\"v\":3,\"s\":null},{\"g\":\"y\",\"v\":\"n/a\",\"s\":\"c\"}]";

    @Test
    void aggregateGroupedEveryOperation() {
        Outcome o = ops.aggregate(m("groupBy", "g", "aggregations", List.of(
            m("operation", "count", "as", "n"),
            m("operation", "sum", "field", "v", "as", "total"),
            m("operation", "avg", "field", "v", "as", "mean"),
            m("operation", "min", "field", "v", "as", "min"),
            m("operation", "max", "field", "v", "as", "max"),
            m("operation", "first", "field", "s", "as", "first"),
            m("operation", "last", "field", "s", "as", "last"),
            m("operation", "concat", "field", "s", "separator", "|", "as", "joined"),
            m("operation", "collect", "field", "s", "as", "all"))), A);
        assertJson("[{\"g\":\"x\",\"n\":2,\"total\":4,\"mean\":2,\"min\":1,\"max\":3,\"first\":\"a\",\"last\":\"a\",\"joined\":\"a\",\"all\":[\"a\"]},"
            + "{\"g\":\"y\",\"n\":2,\"total\":2.5,\"mean\":2.5,\"min\":\"2.5\",\"max\":\"n/a\",\"first\":\"b\",\"last\":\"c\","
            + "\"joined\":\"b|c\",\"all\":[\"b\",\"c\"]}]", o.output());
        assertTrue(o.log().stream().anyMatch(l -> l.contains("non-numeric")), o.log().toString());
    }

    @Test
    void aggregateWithoutGroupingIsOneObjectWithDefaultNames() {
        Outcome o = ops.aggregate(m("aggregations", List.of(
            m("operation", "count"),
            m("operation", "sum", "field", "price.usd"))), "[{\"price\":{\"usd\":1.5}},{\"price\":{\"usd\":2}},{\"price\":{}}]");
        assertJson("{\"count\":3,\"sum_price_usd\":3.5}", o.output());
    }

    @Test
    void aggregateCountWithAFieldCountsNonEmptyValues() {
        Outcome o = ops.aggregate(m("aggregations", List.of(m("operation", "count", "field", "e", "as", "withEmail"))),
            "[{\"e\":\"a@b\"},{\"e\":\"\"},{\"e\":null},{}]");
        assertJson("{\"withEmail\":1}", o.output());
    }

    @Test
    void aggregateOfAnEmptyListUsesSensibleIdentities() {
        Outcome o = ops.aggregate(m("aggregations", List.of(
            m("operation", "count"), m("operation", "sum", "field", "v"), m("operation", "avg", "field", "v"),
            m("operation", "min", "field", "v"), m("operation", "first", "field", "v"),
            m("operation", "collect", "field", "v"), m("operation", "concat", "field", "v"))), "[]");
        assertJson("{\"count\":0,\"sum_v\":0,\"avg_v\":null,\"min_v\":null,\"first_v\":null,\"collect_v\":[],\"concat_v\":\"\"}", o.output());
        Outcome grouped = ops.aggregate(m("groupBy", "g", "aggregations", List.of(m("operation", "count"))), "[]");
        assertJson("[]", grouped.output());
    }

    @Test
    void aggregateOverScalarItems() {
        Outcome o = ops.aggregate(m("aggregations", List.of(m("operation", "sum"), m("operation", "max"))), "[3,1,2]");
        assertJson("{\"sum\":6,\"max\":3}", o.output());
    }

    @Test
    void aggregateGroupsNullAndMissingKeysTogether() {
        Outcome o = ops.aggregate(m("groupBy", "g", "aggregations", List.of(m("operation", "sum", "field", "v"))),
            "[{\"g\":null,\"v\":1},{\"v\":2},{\"g\":\"a\",\"v\":3}]");
        assertJson("[{\"g\":null,\"sum_v\":3},{\"g\":\"a\",\"sum_v\":3}]", o.output());
    }

    @Test
    void aggregateGroupsByTwoFields() {
        Outcome o = ops.aggregate(m("groupBy", List.of("a", "b"), "aggregations", List.of(m("operation", "count", "as", "n"))),
            "[{\"a\":1,\"b\":\"x\"},{\"a\":1,\"b\":\"y\"},{\"a\":1,\"b\":\"x\"}]");
        assertJson("[{\"a\":1,\"b\":\"x\",\"n\":2},{\"a\":1,\"b\":\"y\",\"n\":1}]", o.output());
    }

    @Test
    void aggregateAveragesWithPrecisionAndNoFloatNoise() {
        Outcome o = ops.aggregate(m("aggregations", List.of(m("operation", "sum", "field", "v", "as", "t"))),
            "[{\"v\":0.1},{\"v\":0.2}]");
        assertJson("{\"t\":0.3}", o.output());
    }

    @Test
    void aggregateRejectsBadConfig() {
        assertThrows(IllegalArgumentException.class, () -> ops.aggregate(m(), "[1]"));
        assertThrows(IllegalArgumentException.class, () -> ops.aggregate(m("aggregations", List.of(m("operation", "median"))), "[1]"));
        assertThrows(IllegalArgumentException.class, () -> ops.aggregate(m("aggregations", List.of(m("operation", "sum"))), "{\"a\":1}"));
    }

    // =====================================================================================================
    // LIMIT
    // =====================================================================================================

    @Test
    void limitFirstAndLast() {
        assertJson("[1,2]", ops.limit(m("count", 2), "[1,2,3]").output());
        assertJson("[2,3]", ops.limit(m("count", "2", "mode", "last"), "[1,2,3]").output());
        assertJson("[]", ops.limit(m("count", 0), "[1,2,3]").output());
        assertJson("[1,2,3]", ops.limit(m("count", 10), "[1,2,3]").output());
        assertJson("[1,2,3]", ops.limit(m("count", 10, "mode", "last"), "[1,2,3]").output());
        assertJson("[]", ops.limit(m("count", 2), "[]").output());
    }

    @Test
    void limitWithItemsField() {
        assertJson("{\"rows\":[1],\"k\":true}", ops.limit(m("count", 1, "itemsField", "rows"), "{\"rows\":[1,2,3],\"k\":true}").output());
    }

    @Test
    void limitRejectsAMissingNegativeOrFractionalCount() {
        assertThrows(IllegalArgumentException.class, () -> ops.limit(m(), "[1]"));
        assertThrows(IllegalArgumentException.class, () -> ops.limit(m("count", "x"), "[1]"));
        assertThrows(IllegalArgumentException.class, () -> ops.limit(m("count", -1), "[1]"));
        assertThrows(IllegalArgumentException.class, () -> ops.limit(m("count", 1.5), "[1]"));
    }

    // =====================================================================================================
    // DEDUPE
    // =====================================================================================================

    private static final String D = "[{\"id\":1,\"v\":\"a\"},{\"id\":2,\"v\":\"b\"},{\"id\":1,\"v\":\"c\"}]";

    @Test
    void dedupeKeepFirstAndKeepLast() {
        assertEquals(List.of("a", "b"), col(ops.dedupe(m("dedupeBy", List.of("id")), D).output(), "v"));
        assertEquals(List.of("b", "c"), col(ops.dedupe(m("dedupeBy", "id", "keep", "last"), D).output(), "v"));
    }

    @Test
    void dedupeWholeItemsKeepsDistinctTypesApart() {
        assertJson("[1,\"1\",2]", ops.dedupe(m(), "[1,\"1\",1,2,2]").output());
    }

    @Test
    void dedupeIgnoreCaseAndMultipleFields() {
        assertJson("[\"A\",\"b\"]", ops.dedupe(m("ignoreCase", true), "[\"A\",\"a\",\"b\"]").output());
        Outcome o = ops.dedupe(m("dedupeBy", "a, b"), "[{\"a\":1,\"b\":1},{\"a\":1,\"b\":2},{\"a\":1,\"b\":1}]");
        assertEquals(2, j(o.output()).size());
    }

    @Test
    void dedupeTreatsNullAndMissingAsTheSameValue() {
        assertJson("[{\"id\":null},{\"id\":1}]", ops.dedupe(m("dedupeBy", "id"), "[{\"id\":null},{},{\"id\":1}]").output());
    }

    @Test
    void dedupeEmptyAndSummary() {
        assertJson("[]", ops.dedupe(m("dedupeBy", "id"), "[]").output());
        assertEquals("Removed 1 duplicate(s); 2 item(s) remain.", ops.dedupe(m("dedupeBy", "id"), D).summary());
    }
}
