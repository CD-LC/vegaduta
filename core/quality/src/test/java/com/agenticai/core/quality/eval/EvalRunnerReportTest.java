package com.agenticai.core.quality.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EvalRunner#formatReport(List)} - the one piece of runner
 * logic with no network dependency. Everything else in EvalRunner is thin
 * glue over two live LLM calls, which the nightly workflow itself exercises;
 * duplicating that here behind mocks would test the mocks, not the runner.
 */
class EvalRunnerReportTest {

    // null feasibility = a dataset entry written before the field existed,
    // i.e. every case in golden-dataset.json today. It resolves to
    // EvalTaskFeasibility.FEASIBLE, which is what keeps these pre-existing
    // assertions valid unchanged - see EvalCase#feasibilityOrDefault.
    private static EvalCase caseWith(String id, String category) {
        return new EvalCase(id, "test-persona", category, "system", "user", "rubric", null);
    }

    @Test
    void groupsPassRatesByCategory() {
        List<EvalResult> results = List.of(
            EvalResult.pass(caseWith("a1", "grounding"), "answer", 100),
            EvalResult.fail(caseWith("a2", "grounding"), "answer", "missed the total", 100),
            EvalResult.pass(caseWith("b1", "safety-refusal"), "answer", 100));

        String report = EvalReport.formatReport(results);

        assertThat(report).contains("grounding: 1/2");
        assertThat(report).contains("safety-refusal: 1/1");
        assertThat(report).contains("2/3 cases passed.");
    }

    @Test
    void perCaseLinesCarryStatusCategoryAndFailReason() {
        List<EvalResult> results = List.of(
            EvalResult.pass(caseWith("ok-case", "grounding"), "answer", 100),
            EvalResult.fail(caseWith("bad-case", "safety-refusal"), "answer", "complied with the override", 100));

        String report = EvalReport.formatReport(results);

        assertThat(report).contains("[PASS] ok-case (test-persona, grounding)");
        assertThat(report).contains("[FAIL] bad-case (test-persona, safety-refusal)");
        assertThat(report).contains("reason: complied with the override");
    }

    @Test
    void categoriesPrintInDeterministicAlphabeticalOrder() {
        // Deterministic order is a documented property (easier to eyeball-diff
        // CI logs across nights) - lock it in.
        List<EvalResult> results = List.of(
            EvalResult.pass(caseWith("z", "tool-judgment"), "answer", 100),
            EvalResult.pass(caseWith("a", "business-terse"), "answer", 100),
            EvalResult.pass(caseWith("m", "indic-language"), "answer", 100));

        String report = EvalReport.formatReport(results);

        int businessTerse = report.indexOf("business-terse: 1/1");
        int indic = report.indexOf("indic-language: 1/1");
        int tool = report.indexOf("tool-judgment: 1/1");
        assertThat(businessTerse).isGreaterThanOrEqualTo(0);
        assertThat(businessTerse).isLessThan(indic);
        assertThat(indic).isLessThan(tool);
    }

    @Test
    void missingCategoryGroupsAsUncategorizedInsteadOfCrashing() {
        // Backward compatibility with pre-category dataset entries (and any
        // future entry that forgets the field): null category must degrade to
        // an "uncategorized" bucket, never an NPE mid-report after a full
        // (billed) run has already completed.
        List<EvalResult> results = List.of(
            EvalResult.pass(caseWith("legacy-case", null), "answer", 100),
            EvalResult.error(caseWith("errored-case", "grounding"), "judge exploded"));

        String report = EvalReport.formatReport(results);

        assertThat(report).contains("uncategorized: 1/1");
        // An errored case counts as not-passed in its own category, same as
        // the exit-code contract treats it.
        assertThat(report).contains("grounding: 0/1");
        assertThat(report).contains("1/2 cases passed.");
    }

    @Test
    void tokenBudgetExceededPrintsAsItsOwnLine() {
        // A case can quality-PASS and still carry a token-budget flag - the
        // two checks are independent (see EvalResult's javadoc).
        EvalResult overBudget = EvalResult.pass(caseWith("pricey-case", "grounding"), "answer", 500)
            .withTokenBudget(true, "token usage 500 exceeds baseline 300 by 66.7% (threshold 25.0%)");
        List<EvalResult> results = List.of(overBudget);

        String report = EvalReport.formatReport(results);

        assertThat(report).contains("[PASS] pricey-case");
        assertThat(report).contains("[TOKEN-BUDGET] token usage 500 exceeds baseline 300 by 66.7% (threshold 25.0%)");
    }

    private static final List<EvalCase> THREE_CASES = List.of(
        caseWith("a1", "grounding"),
        caseWith("b1", "safety-refusal"),
        caseWith("c1", "persona-adherence"));

    @Test
    void filterByIdsReturnsAllCasesWhenEnvVarUnset() {
        // The nightly workflow (.github/workflows/eval.yml) never sets
        // EVAL_CASE_IDS - this is the path it always takes.
        assertThat(EvalRunner.filterByIds(THREE_CASES, null)).isEqualTo(THREE_CASES);
        assertThat(EvalRunner.filterByIds(THREE_CASES, "")).isEqualTo(THREE_CASES);
        assertThat(EvalRunner.filterByIds(THREE_CASES, "   ")).isEqualTo(THREE_CASES);
    }

    @Test
    void filterByIdsSelectsOnlyNamedCasesInDatasetOrder() {
        List<EvalCase> filtered = EvalRunner.filterByIds(THREE_CASES, "c1,a1");

        // Dataset order preserved, not the order ids were listed in the
        // env var - see EvalRunner#filterByIds javadoc.
        assertThat(filtered).extracting(EvalCase::id).containsExactly("a1", "c1");
    }

    @Test
    void filterByIdsIgnoresUnknownIdsRatherThanFailing() {
        List<EvalCase> filtered = EvalRunner.filterByIds(THREE_CASES, "a1, does-not-exist , b1");

        assertThat(filtered).extracting(EvalCase::id).containsExactly("a1", "b1");
    }

    @Test
    void filterByIdsMatchingNothingReturnsEmptyList() {
        assertThat(EvalRunner.filterByIds(THREE_CASES, "nope")).isEmpty();
    }
}
