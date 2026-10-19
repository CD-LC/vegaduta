package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.WorkflowDataOps.Outcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link WorkflowDataOps}: Switch, Merge (append, join, first non-empty) and the number/date helpers. */
class WorkflowDataOpsRoutingTest extends WorkflowDataOpsTestBase {

    // =====================================================================================================
    // SWITCH
    // =====================================================================================================

    private Map<String, Object> switchConfig() {
        return m("cases", List.of(
            m("name", "big", "conditions", List.of(cond("p", "gt", "100"))),
            m("name", "vip", "conditions", List.of(cond("tier", "eq", "gold")))));
    }

    @Test
    void switchFirstMatchingCaseWins() {
        Outcome o = ops.switchRoute(switchConfig(), "{\"p\":150,\"tier\":\"gold\"}");
        assertEquals("big", o.branch());
        assertEquals("{\"p\":150,\"tier\":\"gold\"}", o.output());
        assertTrue(o.summary().contains("routed to 'big'"), o.summary());
        assertEquals("vip", ops.switchRoute(switchConfig(), "{\"p\":5,\"tier\":\"gold\"}").branch());
    }

    @Test
    void switchFallsToTheDefaultPortWhenNothingMatches() {
        Outcome o = ops.switchRoute(switchConfig(), "{\"p\":5}");
        assertEquals("default", o.branch());
        assertTrue(o.summary().contains("defaulted to 'default'"), o.summary());
        assertEquals("default", ops.switchRoute(switchConfig(), "not json at all").branch());
    }

    @Test
    void switchTreatsTextThatMerelyStartsWithAJsonScalarAsText() {
        Map<String, Object> cfg = m("cases", List.of(
            m("name", "review", "conditions", List.of(cond("", "contains", "items")))));
        assertEquals("review", ops.switchRoute(cfg, "3 items need review").branch());
        assertEquals("review", ops.switchRoute(cfg, "true items story").branch());
        assertEquals("default", ops.switchRoute(cfg, "null pointer in X").branch());
    }

    @Test
    void switchCanTestPlainTextWithABlankField() {
        Outcome o = ops.switchRoute(m("cases", List.of(
            m("name", "urgent", "conditions", List.of(cond("", "contains", "urgent"))))), "this is urgent");
        assertEquals("urgent", o.branch());
    }

    @Test
    void switchOrCombinatorAndSkippedIncompleteCases() {
        Outcome o = ops.switchRoute(m("cases", List.of(
            m("name", "", "conditions", List.of(cond("a", "eq", "1"))),
            m("name", "noConditions"),
            m("name", "either", "combinator", "or", "conditions", List.of(cond("a", "eq", "1"), cond("b", "eq", "2"))))),
            "{\"a\":0,\"b\":2}");
        assertEquals("either", o.branch());
    }

    @Test
    void switchSupportsRegexAndIgnoreCase() {
        Outcome o = ops.switchRoute(m("ignoreCase", true, "cases", List.of(
            m("name", "mail", "conditions", List.of(cond("e", "regex", "@EXAMPLE\\.com$"))))), "{\"e\":\"a@example.com\"}");
        assertEquals("mail", o.branch());
    }

    @Test
    void switchWithAnUnknownOperatorThrows() {
        assertThrows(IllegalArgumentException.class, () -> ops.switchRoute(
            m("cases", List.of(m("name", "x", "conditions", List.of(cond("a", "zzz", "1"))))), "{\"a\":1}"));
    }

    // =====================================================================================================
    // MERGE
    // =====================================================================================================

    @Test
    void mergeAppendsTheElementsOfAnInputArray() {
        Outcome o = ops.merge(m(), "[[1,2],[3],{\"a\":1},\"text\"]");
        assertJson("[1,2,3,{\"a\":1},\"text\"]", o.output());
    }

    @Test
    void mergeParsesJsonTextEntriesLikeAParallelJoinProduces() {
        Outcome o = ops.merge(m(), "[\"[{\\\"id\\\":1}]\",\"{\\\"id\\\":2}\"]");
        assertJson("[{\"id\":1},{\"id\":2}]", o.output());
    }

    @Test
    void mergeUsesConfiguredSourcesAndCountsMissingOnes() {
        Outcome o = ops.merge(m("sources", List.of("{\"a\":1}", "", "[{\"b\":2}]")), "ignored");
        assertJson("[{\"a\":1},{\"b\":2}]", o.output());
        assertTrue(o.log().get(0).contains("2 of 3"), o.log().toString());
    }

    @Test
    void mergeMinInputsMakesAThinResultVisible() {
        Outcome o = ops.merge(m("sources", List.of("{\"a\":1}", ""), "minInputs", 2), "orig");
        assertTrue(o.skipped());
        assertEquals("orig", o.output());
        assertFalse(ops.merge(m("sources", List.of("{\"a\":1}", "{\"b\":1}"), "minInputs", "2"), "orig").skipped());
    }

    @Test
    void mergeKeepsFreeTextThatStartsWithANumber() {
        assertJson("[\"12 apples\"]", ops.merge(m(), "12 apples").output());
    }

    @Test
    void mergeNonArrayInputIsOneInput() {
        assertJson("[{\"a\":1}]", ops.merge(m(), "{\"a\":1}").output());
        assertJson("[]", ops.merge(m(), "").output());
    }

    private static final String LEFT = "[{\"id\":1,\"a\":\"x\"},{\"id\":2,\"a\":\"y\"}]";
    private static final String RIGHT = "[{\"id\":1,\"b\":\"p\"},{\"id\":3,\"b\":\"q\"}]";

    @Test
    void mergeJoinByKeyLeftInnerOuter() {
        Map<String, Object> base = m("mode", "join", "key", "id", "sources", List.of(LEFT, RIGHT));
        assertJson("[{\"id\":1,\"a\":\"x\",\"b\":\"p\"},{\"id\":2,\"a\":\"y\"}]", ops.merge(base, "").output());
        Map<String, Object> inner = new LinkedHashMap<>(base);
        inner.put("joinType", "inner");
        assertJson("[{\"id\":1,\"a\":\"x\",\"b\":\"p\"}]", ops.merge(inner, "").output());
        Map<String, Object> outer = new LinkedHashMap<>(base);
        outer.put("joinType", "outer");
        assertJson("[{\"id\":1,\"a\":\"x\",\"b\":\"p\"},{\"id\":2,\"a\":\"y\"},{\"id\":3,\"b\":\"q\"}]", ops.merge(outer, "").output());
    }

    @Test
    void mergeJoinMatchesNumbersAgainstNumericText() {
        Outcome o = ops.merge(m("mode", "join", "key", "id", "sources", List.of("[{\"id\":\"1\",\"a\":\"x\"}]", "[{\"id\":1,\"b\":\"p\"}]")), "");
        // the later input wins on conflicting fields, so the numeric id of the right-hand row is what survives
        assertJson("[{\"id\":1,\"a\":\"x\",\"b\":\"p\"}]", o.output());
    }

    @Test
    void mergeJoinOneToManyAndConflictsLaterInputWins() {
        Outcome many = ops.merge(m("mode", "join", "key", "id", "sources",
            List.of("[{\"id\":1}]", "[{\"id\":1,\"b\":\"p\"},{\"id\":1,\"b\":\"q\"}]")), "");
        assertJson("[{\"id\":1,\"b\":\"p\"},{\"id\":1,\"b\":\"q\"}]", many.output());
        Outcome clash = ops.merge(m("mode", "join", "key", "id", "sources",
            List.of("{\"id\":1,\"v\":\"L\"}", "{\"id\":1,\"v\":\"R\"}")), "");
        assertJson("[{\"id\":1,\"v\":\"R\"}]", clash.output());
    }

    @Test
    void mergeJoinRowsWithoutAKeyNeverMatch() {
        Map<String, Object> left = m("mode", "join", "key", "id", "sources", List.of("[{\"id\":null,\"a\":1}]", "[{\"id\":null,\"b\":2}]"));
        assertJson("[{\"id\":null,\"a\":1}]", ops.merge(left, "").output());
        Map<String, Object> inner = new LinkedHashMap<>(left);
        inner.put("joinType", "inner");
        assertJson("[]", ops.merge(inner, "").output());
    }

    @Test
    void mergeJoinNeedsAKeyAndObjects() {
        assertThrows(IllegalArgumentException.class, () -> ops.merge(m("mode", "join", "sources", List.of("[{\"id\":1}]")), ""));
        assertThrows(IllegalArgumentException.class, () -> ops.merge(m("mode", "join", "key", "id", "sources", List.of("[1,2]")), ""));
        assertThrows(IllegalArgumentException.class, () -> ops.merge(m("mode", "join", "key", "id", "joinType", "cross", "sources", List.of("[{\"id\":1}]")), ""));
    }

    @Test
    void mergeFirstNonEmpty() {
        Outcome o = ops.merge(m("mode", "first_non_empty", "sources", List.of("", "[]", "{}", "  ", "{\"a\":1}", "{\"b\":2}")), "");
        assertEquals("{\"a\":1}", o.output());
        assertEquals("plain text", ops.merge(m("mode", "first_non_empty", "sources", List.of("", "plain text")), "").output());
        Outcome none = ops.merge(m("mode", "first_non_empty", "sources", List.of("", "[]")), "");
        assertEquals("", none.output());
        assertTrue(none.log().stream().anyMatch(l -> l.contains("empty")), none.log().toString());
    }

    @Test
    void mergeUnknownModeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ops.merge(m("mode", "zip"), "[]"));
    }

    // =====================================================================================================
    // helpers under test
    // =====================================================================================================

    @Test
    void numberHelpersKeepIntegersIntegral() {
        assertEquals("5", WorkflowDataOps.numberNode(new java.math.BigDecimal("5.000")).toString());
        assertEquals("1000", WorkflowDataOps.numberNode(new java.math.BigDecimal("1E+3")).toString());
        assertEquals("2.5", WorkflowDataOps.numberNode(new java.math.BigDecimal("2.50")).toString());
        assertEquals("0", WorkflowDataOps.numberNode(java.math.BigDecimal.ZERO).toString());
        assertNull(WorkflowDataOps.canonicalNumber("007"));
        assertEquals(0, new java.math.BigDecimal("7").compareTo(WorkflowDataOps.lenientNumber("007")));
        assertNull(WorkflowDataOps.lenientNumber("1e9999"));
        assertNull(WorkflowDataOps.lenientNumber("12 apples"));
    }

    @Test
    void parseDateAcceptsIsoShapesOnly() {
        assertEquals(WorkflowDataOps.parseDate("2024-01-05T00:00:00Z"), WorkflowDataOps.parseDate("2024-01-05"));
        assertEquals(WorkflowDataOps.parseDate("2024-01-05T05:30:00+05:30"), WorkflowDataOps.parseDate("2024-01-05T00:00:00Z"));
        assertNull(WorkflowDataOps.parseDate("05/01/2024"));
        assertNull(WorkflowDataOps.parseDate("2024-13-45"));
        assertNull(WorkflowDataOps.parseDate("12345"));
    }
}
