package com.agenticai.core.quality.eval;

/**
 * Outcome of running one {@link EvalCase}: the raw generation, the judge's
 * verdict, whether it passed, and (2026-08 addition) the combined
 * prompt+completion token total across the case's two real LLM calls
 * (generation + judge), so a token/cost regression can be checked
 * independently of the quality verdict - see {@link TokenBaseline} and
 * {@code EvalRunner#checkTokenBudget}.
 *
 * @param outcome what the assistant actually DID, on the three-way {@link
 *     EvalOutcome} vocabulary (2026-09 addition). {@code passed} below is now
 *     DERIVED from this plus the case's {@link
 *     EvalCase#feasibilityOrDefault()} rather than being the only thing
 *     recorded - see {@link EvalOutcome} for why one boolean made a correct
 *     refusal indistinguishable from a wrong answer, and why that was an
 *     incentive problem rather than a reporting one. Kept as its own
 *     component (not re-derived) because the distinction between "answered
 *     wrongly" and "declined" is exactly what is lost by storing only the
 *     boolean, and it is what the report, the Langfuse score and any future
 *     reward mapping need.
 * @param passed the pass/fail gate verdict, i.e. {@code
 *     outcome.isSuccess(evalCase.feasibilityOrDefault())}. Still a stored
 *     component so every existing consumer ({@code EvalRunner}'s exit code,
 *     {@code formatReport}, {@link LangfuseEvalClient}'s {@code rubric_pass}
 *     score) keeps working unchanged. For every case in the suite as it
 *     stands today - all of them {@link EvalTaskFeasibility#FEASIBLE} - this
 *     is the same value it has always been.
 * @param totalTokens combined token usage for this case's generation and
 *     judge calls, or {@code -1} if the run errored before usage could be
 *     read (e.g. exception, empty response).
 * @param tokenBudgetExceeded {@code true} when a baseline existed for this
 *     case AND {@code totalTokens} exceeded it by more than the configured
 *     threshold. Always {@code false} when no baseline is on file yet - see
 *     {@link TokenBaseline}'s javadoc for why an unknown baseline is not
 *     itself a failure.
 * @param tokenBudgetReason human-readable detail when {@code
 *     tokenBudgetExceeded} is true (baseline/current/threshold), else null.
 * @param traceId the 32-hex-char OTel trace id of the span {@code
 *     EvalCaseRun#runCase} wrapped this case's two LLM calls in (2026-09
 *     addition, for {@link LangfuseEvalClient}: it is what links the
 *     Langfuse dataset-run item and the pass/fail score back to the exact
 *     trace the case exported). Null only when a result was built outside
 *     the runner's span (tests, or the factories below before {@link
 *     #withTraceId} is applied). When tracing is a no-op (no auth header
 *     configured) the id is still a real, valid one - see {@code
 *     EvalRunner#buildOpenTelemetry} - it just points at nothing exported,
 *     which is exactly why the Langfuse client is gated on the same env
 *     presence as the exporter's auth and never posts against it.
 */
public record EvalResult(EvalCase evalCase, String generatedAnswer, boolean passed, String judgeReason,
                          int totalTokens, boolean tokenBudgetExceeded, String tokenBudgetReason,
                          String traceId, EvalOutcome outcome) {

    static EvalResult pass(EvalCase evalCase, String generatedAnswer, int totalTokens) {
        return of(evalCase, generatedAnswer, EvalOutcome.CORRECT, null, totalTokens);
    }

    static EvalResult fail(EvalCase evalCase, String generatedAnswer, String reason, int totalTokens) {
        return of(evalCase, generatedAnswer, EvalOutcome.INCORRECT, reason, totalTokens);
    }

    /**
     * The assistant declined, asked for the information it was missing, or
     * escalated rather than attempting the task (see {@link
     * EvalOutcome#ABSTAINED_OR_ESCALATED}). Whether that is a pass is NOT
     * decided here - it depends entirely on {@code evalCase}'s {@link
     * EvalTaskFeasibility}, which is why {@code passed} is computed by
     * {@link #of} rather than hardcoded the way {@link #pass}/{@link #fail}
     * could get away with before this existed.
     *
     * @param reason the judge's one-line explanation of what the assistant
     *     declined to do and why. Carried even on a PASSING abstention -
     *     unlike a pass, "stopped safely" is only useful to a human if the
     *     report can say what it stopped for.
     */
    static EvalResult abstained(EvalCase evalCase, String generatedAnswer, String reason, int totalTokens) {
        return of(evalCase, generatedAnswer, EvalOutcome.ABSTAINED_OR_ESCALATED, reason, totalTokens);
    }

    /**
     * A case that could not be graded at all - an exception, an empty
     * response, an unparseable judge verdict. Deliberately {@link
     * EvalOutcome#INCORRECT} rather than a fourth outcome: this suite exists
     * to be a trusted gate, so "we could not tell" must never score better
     * than "it was wrong." See {@code EvalRunner#doRunCase}.
     */
    static EvalResult error(EvalCase evalCase, String reason) {
        return of(evalCase, null, EvalOutcome.INCORRECT, reason, -1);
    }

    /**
     * Single construction point for all four factories above, so the
     * outcome-to-{@code passed} derivation lives in exactly one place and
     * cannot drift between them. A null {@code evalCase} (only reachable
     * from a test constructing a result by hand) resolves to {@link
     * EvalTaskFeasibility#FEASIBLE} via {@link
     * EvalOutcome#isSuccess(EvalTaskFeasibility)}'s own null handling rather
     * than throwing - a grading helper should not be the thing that NPEs a
     * run.
     */
    private static EvalResult of(
        EvalCase evalCase, String generatedAnswer, EvalOutcome outcome, String reason, int totalTokens
    ) {
        boolean passed = outcome.isSuccess(evalCase == null ? null : evalCase.feasibilityOrDefault());
        return new EvalResult(evalCase, generatedAnswer, passed, reason, totalTokens, false, null, null, outcome);
    }

    /**
     * This case's confidence-targeted score in {@code [-1, +1]} - the number
     * to aggregate across a run instead of a pass rate when the question is
     * "is this policy learning when to stop", since a pass rate is
     * indifferent between a refusal and a wrong answer. See {@link
     * EvalOutcome#confidenceTargetedScore(EvalTaskFeasibility)}, including
     * its note that the penalty gap is an explicit default rather than a
     * measured cost ratio.
     */
    public double confidenceTargetedScore() {
        return outcome.confidenceTargetedScore(evalCase == null ? null : evalCase.feasibilityOrDefault());
    }

    /**
     * Whether this case is a correctly-stopped run: the assistant declined
     * or escalated, and that was the right call. The one distinction the old
     * boolean could not express, and the reason the console rendering of
     * such a run should be green rather than red.
     */
    public boolean abstainedCorrectly() {
        return outcome == EvalOutcome.ABSTAINED_OR_ESCALATED && passed;
    }

    /** Returns a copy with the token-budget verdict attached - see {@code EvalRunner#checkTokenBudget}. */
    EvalResult withTokenBudget(boolean exceeded, String reason) {
        return new EvalResult(evalCase, generatedAnswer, passed, judgeReason, totalTokens, exceeded, reason,
            traceId, outcome);
    }

    /** Returns a copy carrying the trace id of the span the case ran under - see {@code EvalCaseRun#runCase}. */
    EvalResult withTraceId(String traceId) {
        return new EvalResult(evalCase, generatedAnswer, passed, judgeReason, totalTokens,
            tokenBudgetExceeded, tokenBudgetReason, traceId, outcome);
    }
}
