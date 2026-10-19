package com.agenticai.core.quality.eval;

import java.util.List;

/**
 * The {@link EvalRunner}'s human-readable report: per-case lines, a per-category pass-rate breakdown, the
 * abstention block and the overall total. Pure string building, no network, so it is unit tested directly.
 */
final class EvalReport {

    private EvalReport() {
    }

    /**
     * Builds the human-readable report: per-case lines (with each case's
     * category alongside its persona), then a per-category pass-rate
     * breakdown, then the overall total. Separated from {@code EvalRunner#printReport}
     * and package-private purely so {@code EvalRunnerReportTest} can assert
     * on the grouping without capturing stdout - the report is the one piece
     * of this runner with real logic that never needs a network call to test.
     *
     * <p>Status labels are three-way as of 2026-09: a case the assistant
     * correctly declined prints {@code [STOPPED]}, not {@code [FAIL]} and
     * not a plain {@code [PASS]}. That single line is most of the lesson of
     * {@link EvalOutcome} - a correctly-stopped run is a success with its own
     * name, and a CI log that calls it a failure is the thing that trains
     * agents to guess. {@code [ABSTAIN]} is its counterpart: declined a task
     * that was actually doable, which IS a failure, just a cheaper one than a
     * wrong answer.
     */
    static String formatReport(List<EvalResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("=== Golden-dataset eval report ===").append('\n');
        long passed = results.stream().filter(EvalResult::passed).count();
        for (EvalResult result : results) {
            sb.append("[").append(statusLabel(result)).append("] ").append(result.evalCase().id())
                .append(" (").append(result.evalCase().persona())
                .append(", ").append(result.evalCase().categoryOrDefault()).append(")")
                .append('\n');
            if (!result.passed()) {
                sb.append("        reason: ").append(result.judgeReason()).append('\n');
            } else if (result.abstainedCorrectly()) {
                // A passing case normally needs no explanation, but "stopped
                // safely" is only actionable if the log says what it stopped
                // for - the escalation path is the product surface here.
                sb.append("        stopped safely: ").append(result.judgeReason()).append('\n');
            }
            if (result.tokenBudgetExceeded()) {
                sb.append("        [TOKEN-BUDGET] ").append(result.tokenBudgetReason()).append('\n');
            }
        }
        sb.append('\n');
        sb.append("--- By category ---").append('\n');
        // TreeMap: deterministic (alphabetical) category order, so two runs
        // of the same dataset always print the same report shape - easier to
        // eyeball-diff CI logs across nights.
        java.util.Map<String, List<EvalResult>> byCategory = results.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                r -> r.evalCase().categoryOrDefault(),
                java.util.TreeMap::new,
                java.util.stream.Collectors.toList()));
        for (var entry : byCategory.entrySet()) {
            long categoryPassed = entry.getValue().stream().filter(EvalResult::passed).count();
            sb.append(entry.getKey()).append(": ")
                .append(categoryPassed).append('/').append(entry.getValue().size())
                .append('\n');
        }
        appendAbstentionSummary(sb, results);
        sb.append('\n');
        sb.append(passed).append('/').append(results.size()).append(" cases passed.").append('\n');
        sb.append("===================================").append('\n');
        return sb.toString();
    }

    /** Per-case status label - see {@link #formatReport}'s javadoc for what the three labels mean. */
    private static String statusLabel(EvalResult result) {
        if (result.outcome() == EvalOutcome.ABSTAINED_OR_ESCALATED) {
            return result.passed() ? "STOPPED" : "ABSTAIN";
        }
        return result.passed() ? "PASS" : "FAIL";
    }

    /**
     * Appends the abstention block: how often the assistant stopped, whether
     * it was right to, and the mean confidence-targeted score across the run.
     *
     * <p>Printed only when the run has something abstention-related to say -
     * at least one abstention, or at least one case whose {@link
     * EvalTaskFeasibility} is not {@code FEASIBLE}. On the suite exactly as
     * it stands today (every case feasible, no abstention branch reachable
     * unless a model genuinely refuses) that means the report is
     * byte-identical to before, which is deliberate: a block of three zeros
     * on every nightly run trains people to skip the section that matters
     * once it stops being zero.
     *
     * <p>The three counts it prints are deliberately the three distinct
     * failure-mode questions, not one rate:
     * <ul>
     *   <li><b>correctly stopped</b> - declined a task that could not or
     *       should not be done. A success.</li>
     *   <li><b>stopped when the task was doable</b> - an unhelpful agent.
     *       A failure, scored {@code 0.0} rather than {@code -1.0}.</li>
     *   <li><b>answered a task it should have declined</b> - the expensive
     *       one: a confident answer to something impossible or
     *       under-specified, which is what a user acts on.</li>
     * </ul>
     */
    private static void appendAbstentionSummary(StringBuilder sb, List<EvalResult> results) {
        long correctlyStopped = results.stream().filter(EvalResult::abstainedCorrectly).count();
        long stoppedButShouldNotHave = results.stream()
            .filter(r -> r.outcome() == EvalOutcome.ABSTAINED_OR_ESCALATED && !r.passed())
            .count();
        long answeredButShouldHaveStopped = results.stream()
            .filter(r -> r.outcome() == EvalOutcome.CORRECT && !r.passed())
            .count();
        boolean anyNonFeasibleCase = results.stream()
            .anyMatch(r -> r.evalCase().feasibilityOrDefault().abstentionIsCorrect());
        if (correctlyStopped == 0 && stoppedButShouldNotHave == 0
            && answeredButShouldHaveStopped == 0 && !anyNonFeasibleCase) {
            return;
        }
        double meanScore = results.stream().mapToDouble(EvalResult::confidenceTargetedScore).average().orElse(0.0);
        sb.append('\n');
        sb.append("--- Abstention ---").append('\n');
        sb.append("correctly stopped: ").append(correctlyStopped).append('\n');
        sb.append("stopped when the task was doable: ").append(stoppedButShouldNotHave).append('\n');
        sb.append("answered a task it should have declined: ").append(answeredButShouldHaveStopped).append('\n');
        // Locale.ROOT and an explicit '\n': the report is diffed across CI
        // runs on different hosts, so neither the decimal separator nor the
        // line ending may depend on the runner's locale/platform (the rest of
        // this method appends '\n' directly for the same reason).
        sb.append("mean confidence-targeted score: ")
            .append(String.format(java.util.Locale.ROOT, "%.3f", meanScore)).append('\n');
    }
}
