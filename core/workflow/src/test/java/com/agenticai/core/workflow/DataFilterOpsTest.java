package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Characterisation of {@link DataFilterOps}: operator aliases, typed comparison, combinators,
 * switch routing, regex safety.
 */
class DataFilterOpsTest {
    private static final ObjectMapper M = com.agenticai.core.json.JsonMappers.plain();

    private static JsonNode j(String s) throws Exception {
        return M.readTree(s);
    }

    private static boolean test(
            String itemJson, String field, String op, String value, boolean ignoreCase)
            throws Exception {
        return new DataFilterOps.ConditionEvaluator(ignoreCase)
                .test(j(itemJson), new WorkflowDataOps.Condition(field, op, value));
    }

    @Test
    void operatorAliasesNormaliseToCanonicalIds() {
        assertEquals("eq", DataFilterOps.normalizeOperator("=="));
        assertEquals("neq", DataFilterOps.normalizeOperator("Not_Equals"));
        assertEquals("gte", DataFilterOps.normalizeOperator(">="));
        assertEquals("starts", DataFilterOps.normalizeOperator("starts-with"));
        assertEquals("notEmpty", DataFilterOps.normalizeOperator("is not empty"));
        assertEquals("regex", DataFilterOps.normalizeOperator("matches"));
        assertNull(DataFilterOps.normalizeOperator("like"));
        assertNull(DataFilterOps.normalizeOperator(null));
        for (String id : WorkflowDataOps.OPERATORS) {
            assertEquals(id, DataFilterOps.normalizeOperator(id), id);
        }
    }

    @Test
    void equalityIsNumericOnlyForCanonicalNumbers() throws Exception {
        assertTrue(test("{\"n\":5}", "n", "eq", "5.0", false));
        assertTrue(test("{\"n\":\"5\"}", "n", "eq", "5", false));
        assertFalse(test("{\"zip\":\"01234\"}", "zip", "eq", "1234", false));
        assertTrue(test("{\"b\":true}", "b", "eq", "TRUE", false));
        assertTrue(test("{\"s\":\"Abc\"}", "s", "eq", "abc", true));
        assertFalse(test("{\"s\":\"Abc\"}", "s", "eq", "abc", false));
        assertTrue(test("{\"s\":\"Abc\"}", "s", "neq", "abc", false));
    }

    @Test
    void orderingIsNumericThenDateThenText() throws Exception {
        assertTrue(test("{\"n\":\"10\"}", "n", "gt", "9", false));
        assertTrue(test("{\"d\":\"2021-01-01\"}", "d", "lt", "2022-01-01T00:00:00Z", false));
        assertTrue(test("{\"s\":\"b\"}", "s", "gte", "a", false));
        assertFalse(test("{\"x\":1}", "missing", "gt", "0", false));
        assertTrue(test("{\"n\":3}", "n", "lte", "3", false));
        assertTrue(test("{\"n\":3}", "n", "lt", "4", false));
    }

    @Test
    void containsSearchesArraysByEqualityAndTextBySubstring() throws Exception {
        assertTrue(test("{\"t\":[\"a\",\"b\"]}", "t", "contains", "b", false));
        assertFalse(test("{\"t\":[\"abc\"]}", "t", "contains", "b", false));
        assertTrue(test("{\"t\":\"Hello\"}", "t", "contains", "ELL", true));
        assertTrue(test("{\"t\":\"Hello\"}", "t", "starts", "He", false));
        assertTrue(test("{\"t\":\"Hello\"}", "t", "ends", "lo", false));
    }

    @Test
    void emptyAndNotEmptyTreatBlankAndContainersAsEmpty() throws Exception {
        assertTrue(test("{\"t\":\" \"}", "t", "empty", "", false));
        assertTrue(test("{\"t\":[]}", "t", "empty", "", false));
        assertTrue(test("{}", "t", "empty", "", false));
        assertTrue(test("{\"t\":0}", "t", "notEmpty", "", false));
    }

    @Test
    void unknownOperatorFailsAndRegexIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> test("{}", "a", "like", "x", false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        test(
                                "{\"a\":\"x\"}",
                                "a",
                                "regex",
                                "a".repeat(DataFilterOps.MAX_REGEX_PATTERN_CHARS + 1),
                                false));
    }

    @Test
    void regexMatchesFindsAndLongValuesAreSkippedWithAWarning() throws Exception {
        assertTrue(test("{\"a\":\"order-42\"}", "a", "regex", "\\d+", false));
        DataFilterOps.ConditionEvaluator ev = new DataFilterOps.ConditionEvaluator(false);
        String longValue = "a".repeat(DataFilterOps.MAX_REGEX_INPUT_CHARS + 1);
        ObjectNode item = M.createObjectNode().put("a", longValue);
        assertFalse(ev.test(item, new WorkflowDataOps.Condition("a", "regex", "a")));
        List<String> log = new ArrayList<>();
        ev.addWarnings(log);
        assertEquals(1, log.size());
    }

    @Test
    void combinatorsAndOrWithShortCircuit() throws Exception {
        List<WorkflowDataOps.Condition> cs =
                List.of(
                        new WorkflowDataOps.Condition("a", "eq", "1"),
                        new WorkflowDataOps.Condition("b", "eq", "2"));
        DataFilterOps.ConditionEvaluator ev = new DataFilterOps.ConditionEvaluator(false);
        assertTrue(ev.matches(j("{\"a\":1,\"b\":2}"), cs, false));
        assertFalse(ev.matches(j("{\"a\":1,\"b\":3}"), cs, false));
        assertTrue(ev.matches(j("{\"a\":1,\"b\":3}"), cs, true));
        assertFalse(ev.matches(j("{\"a\":9,\"b\":3}"), cs, true));
        assertTrue(DataFilterOps.isOr(" OR "));
        assertFalse(DataFilterOps.isOr(null));
    }

    @Test
    void conditionsDefaultOperatorToEqAndNullValueToEmpty() {
        List<WorkflowDataOps.Condition> cs = DataFilterOps.conditions(List.of(m("field", " a ")));
        assertEquals(new WorkflowDataOps.Condition("a", "eq", ""), cs.get(0));
    }

    @Test
    void switchRoutesToFirstMatchingCaseElseDefault() {
        Map<String, Object> config =
                m(
                        "cases",
                        List.of(
                                m(
                                        "name",
                                        "big",
                                        "conditions",
                                        List.of(m("field", "n", "operator", ">", "value", "10"))),
                                m(
                                        "name",
                                        "any",
                                        "conditions",
                                        List.of(m("field", "n", "operator", "notEmpty")))));
        assertEquals("big", DataFilterOps.switchRoute(M, config, "{\"n\":11}").branch());
        assertEquals("any", DataFilterOps.switchRoute(M, config, "{\"n\":1}").branch());
        assertEquals(
                WorkflowDataOps.SWITCH_DEFAULT_HANDLE,
                DataFilterOps.switchRoute(M, config, "{}").branch());
        assertEquals("{}", DataFilterOps.switchRoute(M, config, "{}").output());
    }

    @Test
    void filterKeepsMatchesAndEveryItemWhenNoConditions() throws Exception {
        WorkflowDataOps ops = new WorkflowDataOps(M);
        String in = "[{\"n\":1},{\"n\":5}]";
        Map<String, Object> config =
                m("conditions", List.of(m("field", "n", "operator", "gt", "value", "2")));
        assertEquals(
                j("[{\"n\":5}]"),
                j(DataFilterOps.filter(M, config, () -> ops.items(in, "")).output()));
        assertEquals(j(in), j(DataFilterOps.filter(M, m(), () -> ops.items(in, "")).output()));
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }
}
