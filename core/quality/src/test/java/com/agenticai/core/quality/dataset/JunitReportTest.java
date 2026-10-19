package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link JunitReport}: the CI-contract mapping - one {@code <testcase>} per
 * case result, {@code <failure>} on passed=false, {@code <error>} on replay
 * errors, {@code <skipped>} on not-evaluable - plus the XML-escaping that
 * keeps a case message containing markup from breaking the document.
 */
class JunitReportTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    private static EvalDatasetCase sourceCase(String userMessage, int position) {
        return new EvalDatasetCase(DATASET_ID, userMessage, null, null, position);
    }

    private static EvalRun run() {
        return new EvalRun(DATASET_ID, UUID.randomUUID(), "tenant-1", "ci", "", 0.7, 3);
    }

    @Test
    void mapsPassedFailedErroredAndSkippedToJUnitElements() {
        EvalRunCaseResult passed = EvalRunCaseResult.scored(
            RUN_ID, sourceCase("q0", 0), "a", null, "{}", 0.9, null, Boolean.TRUE);
        EvalRunCaseResult failed = EvalRunCaseResult.scored(
            RUN_ID, sourceCase("q1", 1), "a", null, "{}", 0.2, null, Boolean.FALSE);
        EvalRunCaseResult errored = EvalRunCaseResult.errored(
            RUN_ID, sourceCase("q2", 2), "replay exploded");
        EvalRunCaseResult skipped = EvalRunCaseResult.scored(
            RUN_ID, sourceCase("q3", 3), null, null, "{}", null, null, null);

        String xml = JunitReport.of("ds", run(), List.of(passed, failed, errored, skipped));

        assertTrue(xml.contains("tests=\"4\""));
        assertTrue(xml.contains("failures=\"1\""));
        assertTrue(xml.contains("errors=\"1\""));
        assertTrue(xml.contains("skipped=\"1\""));
        assertTrue(xml.contains("<testcase classname=\"ds\" name=\"case #1\">"));
        assertTrue(xml.contains("<failure message="));
        assertTrue(xml.contains("<error message=\"replay exploded\"/>"));
        assertTrue(xml.contains("<skipped message="));
    }

    @Test
    void aToolAssertionFailureExplainsItselfInTheMessage() {
        EvalRunCaseResult failed = EvalRunCaseResult.scored(
            RUN_ID, sourceCase("q0", 0), "a", null, "{}", 0.9, Boolean.FALSE, Boolean.FALSE);

        String xml = JunitReport.of("ds", run(), List.of(failed));

        assertTrue(xml.contains("<failure message=\"expected-tools assertion violated\"/>"));
    }

    @Test
    void escapesMarkupInNamesAndMessages() {
        EvalRunCaseResult errored = EvalRunCaseResult.errored(
            RUN_ID, sourceCase("q", 0), "broke on <tool> & \"quotes\"");

        String xml = JunitReport.of("ds <beta>", run(), List.of(errored));

        assertTrue(xml.contains("classname=\"ds &lt;beta&gt;\""));
        assertTrue(xml.contains("broke on &lt;tool&gt; &amp; &quot;quotes&quot;"));
    }
}
