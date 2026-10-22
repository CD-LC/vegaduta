package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.WorkflowDataOps.Outcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Every data-node operation, including the edge cases (empty lists, nulls, mixed types, ties,
 * hostile regexes). This class covers paths, Set and Filter (with regex safety); Sort / Aggregate /
 * Limit / Dedupe are in {@link WorkflowDataOpsReshapeTest}, Switch / Merge and the number helpers
 * in {@link WorkflowDataOpsRoutingTest}.
 */
class WorkflowDataOpsTest extends WorkflowDataOpsTestBase {

    // =====================================================================================================
    // paths
    // =====================================================================================================

    @Test
    void pathsReadNestedKeysIndexesAndAbsentValues() {
        JsonNode root = j("{\"a\":{\"b\":[{\"c\":1},{\"c\":2}]},\"odd key\":3}");
        assertEquals(1, WorkflowDataOps.get(root, "a.b[0].c").asInt());
        assertEquals(2, WorkflowDataOps.get(root, "a.b[-1].c").asInt());
        assertEquals(3, WorkflowDataOps.get(root, "[\"odd key\"]").asInt());
        assertTrue(WorkflowDataOps.get(root, "a.x.y").isMissingNode());
        assertTrue(WorkflowDataOps.get(root, "a.b[9]").isMissingNode());
        assertEquals(root, WorkflowDataOps.get(root, ""));
        assertEquals(root, WorkflowDataOps.get(root, null));
    }

    // =====================================================================================================
    // SET
    // =====================================================================================================

    @Test
    void setAssignsNestedPathsAndKeepsTheRest() {
        Outcome o = ops.set(m("assignments", List.of(assign("b.c", "x", null))), "{\"a\":1}");
        assertJson("{\"a\":1,\"b\":{\"c\":\"x\"}}", o.output());
        assertFalse(o.skipped());
    }

    @Test
    void setAutoTypingOnlyChangesUnambiguousLiterals() {
        Outcome o =
                ops.set(
                        m(
                                "assignments",
                                List.of(
                                        assign("n", "42", "auto"),
                                        assign("b", "true", null),
                                        assign("zip", "01234", null),
                                        assign("o", "{\"k\":1}", null),
                                        assign("nul", "null", null),
                                        assign("s", "hello", null),
                                        assign("f", "2.50", null))),
                        "{}");
        assertJson(
                "{\"n\":42,\"b\":true,\"zip\":\"01234\",\"o\":{\"k\":1},\"nul\":null,\"s\":\"hello\",\"f\":2.5}",
                o.output());
    }

    @Test
    void setAutoKeepsIdentifierLikeNumbersAsText() {
        Outcome o =
                ops.set(
                        m(
                                "assignments",
                                List.of(
                                        assign("code", "1e3", null),
                                        assign("card", "1234567890123456", null),
                                        assign("phone", "9876543210", null))),
                        "{}");
        assertJson(
                "{\"code\":\"1e3\",\"card\":\"1234567890123456\",\"phone\":9876543210}",
                o.output());
        Outcome forced = ops.set(m("assignments", List.of(assign("code", "1e3", "number"))), "{}");
        assertJson("{\"code\":1000}", forced.output());
    }

    @Test
    void setExplicitTypesAndTypeErrors() {
        Outcome o =
                ops.set(
                        m(
                                "assignments",
                                List.of(
                                        assign("num", "3.50", "number"),
                                        assign("str", "42", "string"),
                                        assign("flag", "FALSE", "boolean"),
                                        assign("arr", "[1,2]", "json"),
                                        assign("nothing", "ignored", "null"),
                                        assign("blankNum", "", "number"))),
                        "{}");
        assertJson(
                "{\"num\":3.5,\"str\":\"42\",\"flag\":false,\"arr\":[1,2],\"nothing\":null,\"blankNum\":null}",
                o.output());

        assertThrows(
                IllegalArgumentException.class,
                () -> ops.set(m("assignments", List.of(assign("x", "abc", "number"))), "{}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ops.set(m("assignments", List.of(assign("x", "maybe", "boolean"))), "{}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ops.set(m("assignments", List.of(assign("x", "{oops", "json"))), "{}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ops.set(m("assignments", List.of(assign("x", "1", "weird"))), "{}"));
    }

    @Test
    void setHonoursRealJsonTypesFromApiAuthoredConfig() {
        Outcome o =
                ops.set(
                        m(
                                "assignments",
                                List.of(
                                        assign("n", 7, null),
                                        assign("b", Boolean.TRUE, null),
                                        assign("asText", 7, "string"),
                                        assign("o", Map.of("k", "v"), null))),
                        "{}");
        assertJson("{\"n\":7,\"b\":true,\"asText\":\"7\",\"o\":{\"k\":\"v\"}}", o.output());
    }

    @Test
    void setKeepOnlySetDropsEverythingElse() {
        Outcome o =
                ops.set(
                        m("assignments", List.of(assign("c", "v", null)), "keepOnlySet", true),
                        "{\"a\":1,\"b\":2}");
        assertJson("{\"c\":\"v\"}", o.output());
    }

    @Test
    void setUpdatesEveryObjectOfAnArrayAndLeavesScalarsAlone() {
        Outcome o =
                ops.set(
                        m("assignments", List.of(assign("flag", "y", null))),
                        "[{\"a\":1},{\"a\":2},5]");
        assertJson("[{\"a\":1,\"flag\":\"y\"},{\"a\":2,\"flag\":\"y\"},5]", o.output());
        assertTrue(o.log().stream().anyMatch(l -> l.contains("non-object")));
    }

    @Test
    void setStartsFromAnEmptyObjectWhenTheInputIsNotJson() {
        Outcome o = ops.set(m("assignments", List.of(assign("x", "1", null))), "plain text");
        assertJson("{\"x\":1}", o.output());
    }

    @Test
    void setWithNothingToSetIsSkipped() {
        Outcome o = ops.set(m(), "{\"a\":1}");
        assertTrue(o.skipped());
        assertEquals("{\"a\":1}", o.output());
    }

    @Test
    void setDoesNotMutateTheInputTreeAcrossArrayElements() {
        Outcome o =
                ops.set(
                        m("assignments", List.of(assign("a.b", "1", null))),
                        "[{\"a\":{\"z\":0}},{}]");
        assertJson("[{\"a\":{\"z\":0,\"b\":1}},{\"a\":{\"b\":1}}]", o.output());
    }

    // =====================================================================================================
    // FILTER
    // =====================================================================================================

    @Test
    void filterOrderingOperatorsAreNumericAwareAndNullsNeverMatch() {
        assertEquals(List.of("b", "A"), filterNames("gt", "p", "10", false));
        assertEquals(List.of("b", "A"), filterNames("gte", "p", "15", false));
        assertEquals(List.of("a", "c"), filterNames("lt", "d", "2024-02-01", false));
        assertEquals(List.of("a"), filterNames("lte", "p", "5", false));
    }

    @Test
    void filterEqualityTreatsCanonicalNumericTextAsNumbers() {
        assertEquals(List.of("b"), filterNames("eq", "p", "15", false));
        assertEquals(List.of("A"), filterNames("eq", "p", "20", false));
        assertEquals(List.of("b", "c", "A"), filterNames("neq", "n", "a", false));
    }

    @Test
    void filterEqualityDoesNotConflateLeadingZeroIds() {
        Outcome o =
                ops.filter(
                        m("conditions", List.of(cond("zip", "eq", "1234"))),
                        "[{\"zip\":\"01234\"},{\"zip\":\"1234\"}]");
        assertEquals(List.of("1234"), col(o.output(), "zip"));
    }

    @Test
    void filterTextOperators() {
        assertEquals(List.of("a"), filterNames("contains", "t", "y.com", false));
        assertEquals(List.of("a"), filterNames("starts", "n", "a", false));
        assertEquals(List.of("a", "A"), filterNames("starts", "n", "a", true));
        assertEquals(List.of("c"), filterNames("ends", "t", ".org", false));
    }

    @Test
    void filterEmptyAndNotEmptyCoverBlankMissingAndNull() {
        assertEquals(List.of("b", "A"), filterNames("empty", "t", "", false));
        assertEquals(List.of("a", "c"), filterNames("notEmpty", "t", "", false));
        assertEquals(List.of("c"), filterNames("empty", "p", "", false));
    }

    @Test
    void filterOperatorSpellingsAreNormalised() {
        assertEquals(List.of("b", "A"), filterNames(">", "p", "10", false));
        assertEquals(List.of("a", "b", "A"), filterNames("is_not_empty", "p", "", false));
        assertEquals(List.of("a"), filterNames("startsWith", "n", "a", false));
    }

    @Test
    void filterRegex() {
        assertEquals(List.of("a"), filterNames("regex", "t", "^[a-z]+@y\\.com$", false));
        assertEquals(List.of("a", "A"), filterNames("regex", "n", "^a$", true));
    }

    @Test
    void filterAndOrCombinators() {
        Outcome or =
                ops.filter(
                        m(
                                "combinator",
                                "or",
                                "conditions",
                                List.of(cond("n", "eq", "a"), cond("p", "gt", "10"))),
                        F);
        assertEquals(List.of("a", "b", "A"), col(or.output(), "n"));
        Outcome and =
                ops.filter(m("conditions", List.of(cond("p", "gt", "1"), cond("n", "eq", "b"))), F);
        assertEquals(List.of("b"), col(and.output(), "n"));
    }

    @Test
    void filterContainsLooksInsideArrays() {
        Outcome o =
                ops.filter(
                        m("conditions", List.of(cond("tags", "contains", "vip"))),
                        "[{\"id\":1,\"tags\":[\"new\",\"vip\"]},{\"id\":2,\"tags\":[\"new\"]},{\"id\":3}]");
        assertEquals(List.of("1"), col(o.output(), "id"));
    }

    @Test
    void filterFiltersScalarItemsWithABlankField() {
        Outcome o = ops.filter(m("conditions", List.of(cond("", "gt", "2"))), "[1,2,3,4]");
        assertJson("[3,4]", o.output());
    }

    @Test
    void filterEmptyAndBlankInputsYieldAnEmptyList() {
        assertJson("[]", ops.filter(m("conditions", List.of(cond("a", "eq", "1"))), "[]").output());
        assertJson("[]", ops.filter(m("conditions", List.of(cond("a", "eq", "1"))), "").output());
        assertJson("[]", ops.filter(m("conditions", List.of(cond("a", "eq", "1"))), null).output());
    }

    @Test
    void filterWithItemsFieldReplacesOnlyThatField() {
        Outcome o =
                ops.filter(
                        m("itemsField", "rows", "conditions", List.of(cond("v", "gt", "1"))),
                        "{\"rows\":[{\"v\":1},{\"v\":2}],\"meta\":\"m\"}");
        assertJson("{\"rows\":[{\"v\":2}],\"meta\":\"m\"}", o.output());
        Outcome nested =
                ops.filter(
                        m("itemsField", "data.rows", "conditions", List.of(cond("v", "gt", "1"))),
                        "{\"data\":{\"rows\":[{\"v\":1},{\"v\":2}],\"k\":1}}");
        assertJson("{\"data\":{\"rows\":[{\"v\":2}],\"k\":1}}", nested.output());
    }

    @Test
    void filterWithoutConditionsKeepsEverything() {
        Outcome o = ops.filter(m(), "[1,2]");
        assertJson("[1,2]", o.output());
    }

    @Test
    void filterRejectsNonArraysAndUnknownOperators() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ops.filter(m("conditions", List.of(cond("a", "eq", "1"))), "{\"a\":1}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ops.filter(m("conditions", List.of(cond("a", "eq", "1"))), "not json"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ops.filter(
                                m(
                                        "itemsField",
                                        "rows",
                                        "conditions",
                                        List.of(cond("a", "eq", "1"))),
                                "[1]"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ops.filter(
                                m(
                                        "itemsField",
                                        "rows",
                                        "conditions",
                                        List.of(cond("a", "eq", "1"))),
                                "{\"rows\":5}"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ops.filter(
                                m("conditions", List.of(cond("a", "wobbles", "1"))),
                                "[{\"a\":1}]"));
    }

    @Test
    void filterSummaryCountsKeptItems() {
        Outcome o = ops.filter(m("conditions", List.of(cond("p", "gt", "10"))), F);
        assertEquals("Filter kept 2 of 4 item(s).", o.summary());
    }

    // ---- regex safety

    @Test
    void regexPatternLongerThanTheCapIsRejected() {
        String pattern = "a".repeat(WorkflowDataOps.MAX_REGEX_PATTERN_CHARS + 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ops.filter(
                                m("conditions", List.of(cond("a", "regex", pattern))),
                                "[{\"a\":\"a\"}]"));
        // exactly at the cap is fine
        String atCap = "a".repeat(WorkflowDataOps.MAX_REGEX_PATTERN_CHARS);
        assertJson(
                "[]",
                ops.filter(m("conditions", List.of(cond("a", "regex", atCap))), "[{\"a\":\"a\"}]")
                        .output());
    }

    @Test
    void invalidRegexIsAnIllegalArgument() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ops.filter(
                                m("conditions", List.of(cond("a", "regex", "(unclosed"))),
                                "[{\"a\":\"a\"}]"));
    }

    @Test
    void regexInputLongerThanTheCapIsNotMatchedAndSaysSo() {
        String huge = "a".repeat(WorkflowDataOps.MAX_REGEX_INPUT_CHARS + 1);
        Outcome o =
                ops.filter(
                        m("conditions", List.of(cond("v", "regex", "a"))),
                        "[{\"v\":\"" + huge + "\"},{\"v\":\"a\"}]");
        assertEquals(1, j(o.output()).size());
        assertTrue(o.log().stream().anyMatch(l -> l.contains("longer than")), o.log().toString());
    }

    @Test
    void catastrophicBacktrackingIsBoundedInTime() {
        String evil = "a".repeat(40) + "b";
        long start = System.nanoTime();
        Outcome o =
                ops.filter(
                        m("conditions", List.of(cond("v", "regex", "(a+)+$"))),
                        "[{\"v\":\"" + evil + "\"}]");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertJson("[]", o.output());
        assertTrue(elapsedMs < 5_000, "regex ran for " + elapsedMs + " ms");
    }

    @Test
    void deeplyRecursiveRegexOnALongValueNeverEscapesAsAnError() {
        String longValue = "a".repeat(WorkflowDataOps.MAX_REGEX_INPUT_CHARS);
        // Whether the JVM stack holds 10k levels of (a|b)* or not, the node must finish cleanly
        // (match or no match).
        Outcome o =
                ops.filter(
                        m("conditions", List.of(cond("v", "regex", "^(a|b)*$"))),
                        "[{\"v\":\"" + longValue + "\"}]");
        assertTrue(j(o.output()).isArray(), o.output());
    }

    @Test
    void theDeadlineCharSequenceInterruptsALongRead() {
        DataFilterOps.DeadlineCharSequence seq =
                new DataFilterOps.DeadlineCharSequence("x".repeat(2000), System.nanoTime() - 1);
        assertThrows(
                DataFilterOps.RegexTimeout.class,
                () -> {
                    for (int i = 0; i < 1000; i++) {
                        seq.charAt(i);
                    }
                });
        // a generous deadline never trips
        DataFilterOps.DeadlineCharSequence ok =
                new DataFilterOps.DeadlineCharSequence("abc", System.nanoTime() + 60_000_000_000L);
        assertEquals('b', ok.charAt(1));
        assertEquals("abc", ok.toString());
        assertEquals(3, ok.length());
        assertEquals("bc", ok.subSequence(1, 3).toString());
    }
}
