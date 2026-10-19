package com.agenticai.core.quality.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The abstention half of {@link EvalRunner}'s reporting and scoring, plus
 * the {@link EvalResult} derivation that decides whether a refusal counted
 * as a pass. Kept separate from {@link EvalRunnerReportTest} (which locks in
 * the pre-existing per-category grouping) so the two concerns fail
 * independently - and so the assertion that the report is UNCHANGED for an
 * all-feasible run lives next to the assertions about what changes.
 *
 * <p>Same no-network posture as {@link EvalRunnerReportTest}: the judge call
 * itself is not mocked, because mocking it would test the mock. What is
 * tested is every piece of real branching between a judge verdict and an
 * exit code.
 */
class EvalRunnerAbstentionTest {

    private static EvalCase feasibleCase(String id) {
        return new EvalCase(id, "test-persona", "grounding", "system", "user", "rubric", EvalTaskFeasibility.FEASIBLE);
    }

    private static EvalCase impossibleCase(String id) {
        return new EvalCase(id, "test-persona", "tool-judgment", "system", "user", "rubric",
            EvalTaskFeasibility.INFEASIBLE);
    }

    private static EvalCase underSpecifiedCase(String id) {
        return new EvalCase(id, "test-persona", "grounding", "system", "user", "rubric",
            EvalTaskFeasibility.UNDER_SPECIFIED);
    }

    /** A dataset entry written before the feasibility field existed - the entire current golden dataset. */
    private static EvalCase legacyCase(String id) {
        return new EvalCase(id, "test-persona", "grounding", "system", "user", "rubric", null);
    }

    // ------------------------------------------------------------------
    // EvalResult: outcome -> passed derivation
    // ------------------------------------------------------------------

    @Test
    void abstainingOnAnImpossibleTaskIsAPassNotAFailure() {
        EvalResult result = EvalResult.abstained(impossibleCase("no-such-api"), "I can't do that because...",
            "declined - the platform has no integration for this", 400);

        assertThat(result.outcome()).isEqualTo(EvalOutcome.ABSTAINED_OR_ESCALATED);
        assertThat(result.passed()).as("a correctly-stopped run is a success state").isTrue();
        assertThat(result.abstainedCorrectly()).isTrue();
        assertThat(result.confidenceTargetedScore()).isEqualTo(1.0);
    }

    @Test
    void abstainingOnADoableTaskFailsButCostsLessThanBeingWrong() {
        EvalResult refused = EvalResult.abstained(feasibleCase("easy"), "I'd rather not", "declined a doable task", 400);
        EvalResult wrong = EvalResult.fail(feasibleCase("easy"), "wrong answer", "missed the total", 400);

        assertThat(refused.passed()).isFalse();
        assertThat(refused.abstainedCorrectly()).isFalse();
        assertThat(refused.confidenceTargetedScore()).isEqualTo(0.0);
        assertThat(wrong.confidenceTargetedScore()).isEqualTo(-1.0);
        assertThat(refused.confidenceTargetedScore()).isGreaterThan(wrong.confidenceTargetedScore());
    }

    @Test
    void answeringAnUnderSpecifiedTaskFailsEvenThoughTheJudgeSaidTheAnswerMetTheRubric() {
        // EvalResult.pass means "the judge graded the text as meeting the
        // rubric". On an under-specified case that is still the wrong
        // behaviour, and this is the one place where a PASS verdict from the
        // judge does NOT produce passed == true.
        EvalResult result = EvalResult.pass(underSpecifiedCase("which-invoice"), "Here is the answer.", 400);

        assertThat(result.outcome()).isEqualTo(EvalOutcome.CORRECT);
        assertThat(result.passed()).isFalse();
        assertThat(result.confidenceTargetedScore()).isEqualTo(-1.0);
    }

    @Test
    void aCaseWithNoFeasibilityFieldGradesExactlyAsItAlwaysDid() {
        assertThat(EvalResult.pass(legacyCase("legacy"), "answer", 100).passed()).isTrue();
        assertThat(EvalResult.fail(legacyCase("legacy"), "answer", "reason", 100).passed()).isFalse();
        assertThat(EvalResult.error(legacyCase("legacy"), "judge broke").passed()).isFalse();
    }

    @Test
    void anUngradeableCaseIsIncorrectRatherThanAFourthOutcome() {
        // This suite is a gate: "we could not tell" must never score better
        // than "it was wrong."
        EvalResult result = EvalResult.error(impossibleCase("boom"), "exception during run: timeout");

        assertThat(result.outcome()).isEqualTo(EvalOutcome.INCORRECT);
        assertThat(result.passed()).isFalse();
        assertThat(result.totalTokens()).isEqualTo(-1);
        assertThat(result.confidenceTargetedScore()).isEqualTo(-1.0);
    }

    @Test
    void withTokenBudgetAndWithTraceIdPreserveTheOutcome() {
        // Both copy-constructors gained a component; dropping it would
        // silently reset every result to whatever the record default is,
        // which for an enum is null - and would NPE confidenceTargetedScore.
        EvalResult original = EvalResult.abstained(impossibleCase("c1"), "answer", "declined", 500);

        EvalResult afterBudget = original.withTokenBudget(true, "over by 30%");
        EvalResult afterTrace = afterBudget.withTraceId("0af7651916cd43dd8448eb211c80319c");

        assertThat(afterTrace.outcome()).isEqualTo(EvalOutcome.ABSTAINED_OR_ESCALATED);
        assertThat(afterTrace.passed()).isTrue();
        assertThat(afterTrace.tokenBudgetExceeded()).isTrue();
        assertThat(afterTrace.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    // ------------------------------------------------------------------
    // Report rendering
    // ------------------------------------------------------------------

    @Test
    void aCorrectlyStoppedCaseRendersAsStoppedNotFailed() {
        // The single UI decision the agenda calls most of the lesson: a run
        // that correctly refused must not read as red in a CI log.
        String report = EvalReport.formatReport(List.of(
            EvalResult.abstained(impossibleCase("no-such-api"), "answer",
                "declined - no integration exists for this", 400)));

        assertThat(report).contains("[STOPPED] no-such-api");
        assertThat(report).doesNotContain("[FAIL]");
        assertThat(report).contains("stopped safely: declined - no integration exists for this");
        assertThat(report).contains("1/1 cases passed.");
    }

    @Test
    void anAbstentionOnADoableTaskRendersAsAbstainWithItsReason() {
        String report = EvalReport.formatReport(List.of(
            EvalResult.abstained(feasibleCase("easy"), "answer", "declined a task it could have done", 400)));

        assertThat(report).contains("[ABSTAIN] easy");
        assertThat(report).contains("reason: declined a task it could have done");
        assertThat(report).contains("0/1 cases passed.");
    }

    @Test
    void theAbstentionBlockSeparatesTheThreeDistinctFailureModes() {
        String report = EvalReport.formatReport(List.of(
            EvalResult.abstained(impossibleCase("stopped-right"), "a", "declined", 100),
            EvalResult.abstained(feasibleCase("stopped-wrong"), "a", "declined", 100),
            EvalResult.pass(impossibleCase("answered-anyway"), "a", 100),
            EvalResult.pass(feasibleCase("plain-pass"), "a", 100)));

        assertThat(report).contains("--- Abstention ---");
        assertThat(report).contains("correctly stopped: 1");
        assertThat(report).contains("stopped when the task was doable: 1");
        assertThat(report).contains("answered a task it should have declined: 1");
        // (1.0 + 0.0 + -1.0 + 1.0) / 4
        assertThat(report).contains("mean confidence-targeted score: 0.250");
        assertThat(report).contains("2/4 cases passed.");
    }

    @Test
    void anAllFeasibleAllAnsweredRunPrintsNoAbstentionBlockAtAll() {
        // Deliberate: the golden dataset as it stands today is entirely
        // feasible, so the nightly report must stay byte-identical rather
        // than growing three permanent zeros people learn to skip.
        String report = EvalReport.formatReport(List.of(
            EvalResult.pass(legacyCase("a1"), "answer", 100),
            EvalResult.fail(legacyCase("a2"), "answer", "missed the total", 100)));

        assertThat(report).doesNotContain("--- Abstention ---");
        assertThat(report).doesNotContain("mean confidence-targeted score");
        assertThat(report).contains("[PASS] a1");
        assertThat(report).contains("[FAIL] a2");
        assertThat(report).contains("1/2 cases passed.");
    }

    @Test
    void theAbstentionBlockAppearsAsSoonAsTheDatasetHasANonFeasibleCase() {
        // Even when nothing abstained: if the suite CONTAINS an impossible
        // case, "correctly stopped: 0" is the number a reader needs to see.
        String report = EvalReport.formatReport(List.of(EvalResult.pass(feasibleCase("a1"), "answer", 100),
            EvalResult.fail(impossibleCase("should-have-stopped"), "answer", "wrong", 100)));

        assertThat(report).contains("--- Abstention ---");
        assertThat(report).contains("correctly stopped: 0");
    }

    // ------------------------------------------------------------------
    // Judge-verdict reason parsing
    // ------------------------------------------------------------------

    @Test
    void theReasonIsTakenFromAfterTheColonForBothFailAndAbstain() {
        assertThat(EvalCaseRun.reasonAfterColon("FAIL: missed the rubric's second clause", "FAIL", "fallback"))
            .isEqualTo("missed the rubric's second clause");
        assertThat(EvalCaseRun.reasonAfterColon("ABSTAIN: asked which of the two invoices was meant", "ABSTAIN", "fb"))
            .isEqualTo("asked which of the two invoices was meant");
    }

    @Test
    void aBareVerdictWithNoColonFallsBackInsteadOfReturningTheVerdictTokenAsItsOwnReason() {
        assertThat(EvalCaseRun.reasonAfterColon("FAIL", "FAIL", "no specific reason")).isEqualTo("no specific reason");
        assertThat(EvalCaseRun.reasonAfterColon("ABSTAIN", "ABSTAIN", "no stated reason")).isEqualTo("no stated reason");
    }

    @Test
    void aVerdictWithAWhitespaceOnlyReasonFallsBackRatherThanPersistingAnEmptyString() {
        assertThat(EvalCaseRun.reasonAfterColon("FAIL:    ", "FAIL", "no specific reason"))
            .isEqualTo("no specific reason");
    }
}
