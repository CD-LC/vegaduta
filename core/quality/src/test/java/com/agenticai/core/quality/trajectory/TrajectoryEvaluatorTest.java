package com.agenticai.core.quality.trajectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrajectoryEvaluatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double EPS = 1e-9;

    /** Spec from a compact step list: "a", "b" or "a{\"q\":...}" style is overkill - tools only unless given raw JSON. */
    private static TrajectorySpec spec(String mode, String... stepJson) {
        return specWith("\"mode\":\"" + mode + "\"", stepJson);
    }

    private static TrajectorySpec specWith(String extra, String... stepJson) {
        return TrajectorySpec.parse("{\"type\":\"trajectory\"," + extra + ",\"steps\":[" + String.join(",", stepJson) + "]}");
    }

    private static String step(String tool) {
        return "{\"tool\":\"" + tool + "\"}";
    }

    private static String stepArgs(String tool, String argsJson) {
        return "{\"tool\":\"" + tool + "\",\"args\":" + argsJson + "}";
    }

    private static TrajectoryToolCall call(String tool) {
        return new TrajectoryToolCall(tool, MAPPER.createObjectNode(), true);
    }

    private static TrajectoryToolCall call(String tool, String argsJson) {
        try {
            return new TrajectoryToolCall(tool, MAPPER.readTree(argsJson), true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<TrajectoryToolCall> calls(String... tools) {
        List<TrajectoryToolCall> out = new ArrayList<>();
        for (String t : tools) {
            out.add(call(t));
        }
        return out;
    }

    private static TrajectoryReport run(TrajectorySpec spec, String... tools) {
        return TrajectoryEvaluator.evaluate(spec, calls(tools));
    }

    // ------------------------------------------------------------------ exact / in-order / any-order

    @Test
    void identicalTrajectoriesMatchEveryWay() {
        TrajectoryReport r = run(spec("exact", step("a"), step("b"), step("c")), "a", "b", "c");
        assertTrue(r.metrics().exactMatch());
        assertTrue(r.metrics().inOrderMatch());
        assertTrue(r.metrics().anyOrderMatch());
        assertEquals(1.0, r.metrics().toolPrecision(), EPS);
        assertEquals(1.0, r.metrics().toolRecall(), EPS);
        assertEquals(1.0, r.metrics().toolF1(), EPS);
        assertEquals(1.0, r.metrics().argMatchRate(), EPS);
        assertEquals(0, r.metrics().extraCalls());
        assertEquals(0.0, r.metrics().extraCallPenalty(), EPS);
        assertEquals(1.0, r.metrics().efficiency(), EPS);
        assertEquals(1.0, r.score(), EPS);
        assertTrue(r.passed());
        assertEquals(List.of("match", "match", "match"), r.expected().stream().map(TrajectoryReport.ExpectedRow::status).toList());
    }

    @Test
    void anExtraCallBreaksExactButNotInOrder() {
        TrajectorySpec exact = spec("exact", step("a"), step("b"));
        TrajectoryReport r = run(exact, "a", "x", "b");
        assertFalse(r.metrics().exactMatch());
        assertTrue(r.metrics().inOrderMatch());
        assertTrue(r.metrics().anyOrderMatch());
        assertEquals(1, r.metrics().extraCalls());
        assertEquals(1.0 / 3.0, r.metrics().extraCallPenalty(), EPS);
        assertEquals(2.0 / 3.0, r.metrics().efficiency(), EPS);
        // exact score: only position 0 lines up (a vs a); position 1 is x vs b -> 1 of max(2,3)
        assertEquals(1.0 / 3.0, r.score(), EPS);
        assertFalse(r.passed());
        assertEquals("extra", r.actual().get(1).status());

        TrajectoryReport inOrder = run(spec("in_order", step("a"), step("b")), "a", "x", "b");
        assertEquals(1.0, inOrder.score(), EPS);
        assertTrue(inOrder.passed());
    }

    @Test
    void outOfOrderIsAnyOrderButNotInOrder() {
        TrajectoryReport r = run(spec("in_order", step("a"), step("b")), "b", "a");
        assertFalse(r.metrics().exactMatch());
        assertFalse(r.metrics().inOrderMatch());
        assertTrue(r.metrics().anyOrderMatch());
        assertEquals(0.5, r.score(), EPS); // longest in-order matching is 1 of 2
        assertFalse(r.passed());
        // One of the pair is "match" (the in-order one), the other "out_of_order".
        long outOfOrder = r.expected().stream().filter(x -> x.status().equals("out_of_order")).count();
        assertEquals(1, outOfOrder);

        TrajectoryReport any = run(spec("any_order", step("a"), step("b")), "b", "a");
        assertEquals(1.0, any.score(), EPS);
        assertTrue(any.passed());
    }

    @Test
    void duplicatesNeedDuplicateCalls() {
        TrajectorySpec twoA = spec("any_order", step("a"), step("a"));
        TrajectoryReport one = run(twoA, "a");
        assertFalse(one.metrics().anyOrderMatch());
        assertFalse(one.metrics().inOrderMatch());
        assertEquals(0.5, one.score(), EPS);
        assertEquals(0.5, one.metrics().toolRecall(), EPS);
        assertEquals(1.0, one.metrics().toolPrecision(), EPS);
        // One duplicate step is matched, the other has no call left to match: exactly one "missing" row.
        assertEquals(1, one.expected().stream().filter(x -> x.status().equals("missing")).count());
        assertEquals(1, one.expected().stream().filter(x -> x.status().equals("match")).count());

        TrajectoryReport two = run(twoA, "a", "a");
        assertTrue(two.metrics().exactMatch());

        // The other direction: one expected step, two identical calls -> one is extra.
        TrajectoryReport extra = run(spec("in_order", step("a")), "a", "a");
        assertTrue(extra.metrics().inOrderMatch());
        assertFalse(extra.metrics().exactMatch());
        assertEquals(1, extra.metrics().extraCalls());
        assertEquals(0.5, extra.metrics().efficiency(), EPS);
        assertEquals(1.0, extra.metrics().toolRecall(), EPS);
        assertEquals(0.5, extra.metrics().toolPrecision(), EPS);
    }

    // ------------------------------------------------------------------ empty sides

    @Test
    void bothEmptyIsAPerfectMatch() {
        TrajectoryReport r = TrajectoryEvaluator.evaluate(spec("exact"), List.of());
        assertTrue(r.metrics().exactMatch());
        assertTrue(r.metrics().inOrderMatch());
        assertTrue(r.metrics().anyOrderMatch());
        assertEquals(1.0, r.metrics().toolPrecision(), EPS);
        assertEquals(1.0, r.metrics().toolRecall(), EPS);
        assertEquals(1.0, r.metrics().toolF1(), EPS);
        assertNull(r.metrics().argMatchRate());
        assertEquals(1.0, r.metrics().efficiency(), EPS);
        assertEquals(0.0, r.metrics().extraCallPenalty(), EPS);
        assertEquals(1.0, r.score(), EPS);
        assertTrue(r.passed());
    }

    @Test
    void expectingNothingButCallingSomethingFailsExactAndTheExtraCap() {
        TrajectoryReport exact = run(spec("exact"), "a");
        assertFalse(exact.metrics().exactMatch());
        assertEquals(0.0, exact.score(), EPS);
        assertFalse(exact.passed());
        assertEquals(0.0, exact.metrics().toolPrecision(), EPS);
        assertEquals(1.0, exact.metrics().toolRecall(), EPS);
        assertEquals(0.0, exact.metrics().toolF1(), EPS);
        assertEquals(0.0, exact.metrics().efficiency(), EPS);
        assertEquals(1, exact.metrics().extraCalls());

        // In-order with nothing expected is vacuously satisfied; maxExtraCalls is how to say "no tools".
        TrajectoryReport vacuous = run(spec("in_order"), "a");
        assertEquals(1.0, vacuous.score(), EPS);
        assertTrue(vacuous.passed());
        TrajectoryReport capped = TrajectoryEvaluator.evaluate(
            specWith("\"mode\":\"in_order\",\"maxExtraCalls\":0"), calls("a"));
        assertFalse(capped.passed());
    }

    @Test
    void expectingStepsButCallingNothingScoresZero() {
        TrajectorySpec s = spec("in_order", step("a"), step("b"));
        TrajectoryReport r = TrajectoryEvaluator.evaluate(s, List.of());
        assertFalse(r.metrics().inOrderMatch());
        assertFalse(r.metrics().anyOrderMatch());
        assertEquals(0.0, r.score(), EPS);
        assertEquals(0.0, r.metrics().toolRecall(), EPS);
        assertEquals(0.0, r.metrics().toolPrecision(), EPS);
        assertEquals(0.0, r.metrics().efficiency(), EPS);
        assertEquals(0.0, r.metrics().extraCallPenalty(), EPS);
        assertNull(r.metrics().argMatchRate());
        assertEquals(List.of("missing", "missing"), r.expected().stream().map(TrajectoryReport.ExpectedRow::status).toList());
        // Null actual list behaves as empty.
        assertEquals(0.0, TrajectoryEvaluator.evaluate(s, null).score(), EPS);
    }

    // ------------------------------------------------------------------ precision / recall / F1

    @Test
    void toolNamePrecisionRecallAndF1AreMultisetBased() {
        TrajectorySpec s = spec("f1", step("a"), step("b"), step("c"));
        TrajectoryReport r = run(s, "a", "a", "c", "z");
        // tp = a:1 + c:1 = 2; precision 2/4, recall 2/3.
        assertEquals(0.5, r.metrics().toolPrecision(), EPS);
        assertEquals(2.0 / 3.0, r.metrics().toolRecall(), EPS);
        double f1 = 2 * 0.5 * (2.0 / 3.0) / (0.5 + 2.0 / 3.0);
        assertEquals(f1, r.metrics().toolF1(), EPS);
        assertEquals(f1, r.score(), EPS);
        assertFalse(r.passed()); // minScore defaults to 1.0

        TrajectoryReport lenient = TrajectoryEvaluator.evaluate(
            specWith("\"mode\":\"f1\",\"minScore\":0.5", step("a"), step("b"), step("c")), calls("a", "a", "c", "z"));
        assertTrue(lenient.passed());
    }

    @Test
    void f1IgnoresOrderAndArguments() {
        TrajectorySpec s = specWith("\"mode\":\"f1\"", stepArgs("a", "{\"x\":1}"), step("b"));
        TrajectoryReport r = TrajectoryEvaluator.evaluate(s, List.of(call("b"), call("a", "{\"x\":999}")));
        assertEquals(1.0, r.score(), EPS);
        assertTrue(r.passed());
    }

    // ------------------------------------------------------------------ arguments

    @Test
    void argumentMatchersLiteralRegexPresentAbsentAndNested() {
        String expectedArgs = "{\"limit\":5,\"query\":{\"regex\":\"refund\",\"ignoreCase\":true},"
            + "\"id\":{\"present\":true},\"debug\":{\"absent\":true},\"opts.retries\":3,\"tags\":[\"a\",\"b\"]}";
        TrajectorySpec s = specWith("\"mode\":\"exact\"", stepArgs("search", expectedArgs));
        String good = "{\"limit\":5.0,\"query\":\"Where is my REFUND?\",\"id\":\"x\",\"opts\":{\"retries\":3},\"tags\":[\"a\",\"b\"],\"extra\":1}";
        TrajectoryReport ok = TrajectoryEvaluator.evaluate(s, List.of(call("search", good)));
        assertTrue(ok.passed(), ok.summary());
        assertEquals(1.0, ok.metrics().argMatchRate(), EPS);

        for (String bad : new String[] {
            "{\"limit\":6,\"query\":\"refund\",\"id\":\"x\",\"opts\":{\"retries\":3},\"tags\":[\"a\",\"b\"]}",
            "{\"limit\":5,\"query\":\"hello\",\"id\":\"x\",\"opts\":{\"retries\":3},\"tags\":[\"a\",\"b\"]}",
            "{\"limit\":5,\"query\":\"refund\",\"opts\":{\"retries\":3},\"tags\":[\"a\",\"b\"]}",
            "{\"limit\":5,\"query\":\"refund\",\"id\":\"x\",\"debug\":true,\"opts\":{\"retries\":3},\"tags\":[\"a\",\"b\"]}",
            "{\"limit\":5,\"query\":\"refund\",\"id\":\"x\",\"opts\":{\"retries\":4},\"tags\":[\"a\",\"b\"]}",
            "{\"limit\":5,\"query\":\"refund\",\"id\":\"x\",\"opts\":{\"retries\":3},\"tags\":[\"b\",\"a\"]}"
        }) {
            TrajectoryReport r = TrajectoryEvaluator.evaluate(s, List.of(call("search", bad)));
            assertFalse(r.passed(), bad);
            assertEquals("args_mismatch", r.expected().get(0).status(), bad);
            assertEquals("args_mismatch", r.actual().get(0).status(), bad);
            assertEquals(0.0, r.metrics().argMatchRate(), EPS);
            // A right-tool-wrong-args call is paired, not extra.
            assertEquals(0, r.metrics().extraCalls(), bad);
        }
    }

    @Test
    void aWrongArgumentsCallNamesTheFailingArguments() {
        TrajectorySpec s = specWith("\"mode\":\"in_order\"",
            stepArgs("search", "{\"limit\":5,\"query\":\"refund\"}"));
        TrajectoryReport r = TrajectoryEvaluator.evaluate(s, List.of(call("search", "{\"limit\":9,\"query\":\"hello\"}")));
        assertEquals("arguments differ: limit, query", r.expected().get(0).detail());
        assertTrue(r.summary().contains("wrong arguments: search"), r.summary());
    }

    @Test
    void theRightArgumentsOnALaterCallStillMatch() {
        TrajectorySpec s = specWith("\"mode\":\"in_order\"", stepArgs("search", "{\"q\":\"x\"}"));
        TrajectoryReport r = TrajectoryEvaluator.evaluate(s,
            List.of(call("search", "{\"q\":\"wrong\"}"), call("search", "{\"q\":\"x\"}")));
        assertTrue(r.passed());
        assertEquals("extra", r.actual().get(0).status());
        assertEquals("match", r.actual().get(1).status());
        assertEquals(1, r.metrics().extraCalls());
    }

    @Test
    void unparseableRecordedArgumentsFailAnyMatcherButAnArglessStepStillMatches() {
        TrajectoryToolCall noJson = new TrajectoryToolCall("search", null, true);
        assertFalse(TrajectoryEvaluator.evaluate(
            specWith("\"mode\":\"exact\"", stepArgs("search", "{\"q\":\"x\"}")), List.of(noJson)).passed());
        assertTrue(TrajectoryEvaluator.evaluate(
            specWith("\"mode\":\"exact\"", step("search")), List.of(noJson)).passed());
        assertTrue(TrajectoryEvaluator.evaluate(
            specWith("\"mode\":\"exact\"", stepArgs("search", "{\"q\":{\"absent\":true}}")), List.of(noJson)).passed());
    }

    // ------------------------------------------------------------------ caps, efficiency, failures

    @Test
    void maxExtraCallsAndMinEfficiencyAreHardConditionsOnTopOfTheScore() {
        TrajectorySpec capped = specWith("\"mode\":\"in_order\",\"maxExtraCalls\":1", step("a"), step("b"));
        assertTrue(TrajectoryEvaluator.evaluate(capped, calls("a", "x", "b")).passed());
        assertFalse(TrajectoryEvaluator.evaluate(capped, calls("a", "x", "y", "b")).passed());

        TrajectorySpec efficient = specWith("\"mode\":\"in_order\",\"minEfficiency\":0.75", step("a"), step("b"), step("c"));
        assertTrue(TrajectoryEvaluator.evaluate(efficient, calls("a", "b", "c", "x")).passed()); // 3/4 = 0.75
        assertFalse(TrajectoryEvaluator.evaluate(efficient, calls("a", "b", "c", "x", "y")).passed()); // 0.6
    }

    @Test
    void failedCallsAreCountedAndStillParticipate() {
        TrajectorySpec s = spec("in_order", step("a"), step("b"));
        List<TrajectoryToolCall> actual = List.of(call("a"), new TrajectoryToolCall("b", MAPPER.createObjectNode(), false));
        TrajectoryReport r = TrajectoryEvaluator.evaluate(s, actual);
        assertEquals(1, r.metrics().failedCalls());
        assertTrue(r.passed());
        assertFalse(r.actual().get(1).ok());
    }

    @Test
    void anOverlongActualTrajectoryIsTruncatedAndSaysSo() {
        TrajectorySpec s = spec("in_order", step("a"));
        List<TrajectoryToolCall> many = new ArrayList<>();
        for (int i = 0; i < TrajectoryEvaluator.MAX_ACTUAL_STEPS + 50; i++) {
            many.add(call("a"));
        }
        TrajectoryReport r = TrajectoryEvaluator.evaluate(s, many);
        assertTrue(r.actualTruncated());
        assertEquals(TrajectoryEvaluator.MAX_ACTUAL_STEPS, r.metrics().actualSteps());
        assertEquals(TrajectoryEvaluator.MAX_ACTUAL_STEPS, r.actual().size());
    }

    // ------------------------------------------------------------------ maximum matching

    @Test
    void anyOrderFindsTheMaximumMatchingNotJustAGreedyOne() {
        // Expected: [a(x=1), a]. Actual: [a(x=1), a(x=2)]... greedy pairing of step 2 (any a) with the FIRST
        // call would strand step 1; a maximum matching pairs them crosswise.
        TrajectorySpec s = specWith("\"mode\":\"any_order\"", stepArgs("a", "{\"x\":1}"), step("a"));
        TrajectoryReport r = TrajectoryEvaluator.evaluate(s, List.of(call("a", "{\"x\":1}"), call("a", "{\"x\":2}")));
        assertTrue(r.metrics().anyOrderMatch());
        assertEquals(1.0, r.score(), EPS);

        // Reverse the actual order so the specific step can only match the second call.
        TrajectoryReport reversed = TrajectoryEvaluator.evaluate(s, List.of(call("a", "{\"x\":2}"), call("a", "{\"x\":1}")));
        assertTrue(reversed.metrics().anyOrderMatch());
        assertFalse(reversed.metrics().inOrderMatch() && reversed.metrics().exactMatch());
    }

    // ------------------------------------------------------------------ report shape

    @Test
    void theReportSerialisesToAPlainMapForStorage() {
        TrajectoryReport r = run(spec("in_order", step("a"), step("b")), "a", "x");
        var m = r.toMap();
        assertEquals("in_order", m.get("mode"));
        assertEquals(Boolean.FALSE, m.get("passed"));
        assertTrue(m.get("metrics") instanceof java.util.Map<?, ?>);
        assertTrue(((List<?>) m.get("expected")).size() == 2);
        assertTrue(((List<?>) m.get("actual")).size() == 2);
        assertTrue(r.summary().contains("missing: b"), r.summary());
        assertTrue(r.summary().contains("1 extra call"), r.summary());
        assertTrue(r.summary().endsWith("- failed"), r.summary());
        JsonNode asJson = MAPPER.valueToTree(m);
        assertEquals("a", asJson.get("expected").get(0).get("tool").asText());
    }
}
