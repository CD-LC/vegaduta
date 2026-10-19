package com.agenticai.core.quality.eval;

/**
 * What the assistant actually DID on one {@link EvalCase} — a three-way
 * vocabulary replacing the two-way "passed / did not pass" boolean this
 * suite graded with until now.
 *
 * <p><b>The problem the boolean caused.</b> With one bit of outcome, a run
 * that correctly refuses an impossible task and a run that confidently
 * invents an answer are the same value: {@code passed == false}. That is not
 * a reporting inconvenience, it is an incentive. Every downstream consumer
 * of this suite's verdict — the CI gate, the token/cost baseline, and
 * (through the reward loop in {@code core/rl}) the bandit that chooses
 * between prompt and model variants — reads a correct "I cannot do this" as
 * a failure, so the thing being optimised for is a confident guess. Adding
 * a third outcome is the smallest change that stops paying for that.
 *
 * <p><b>Why this enum deliberately says nothing about correctness on its
 * own.</b> {@link #ABSTAINED_OR_ESCALATED} is neither good nor bad in
 * isolation: declining an impossible task is the best available outcome,
 * and declining a perfectly answerable one is an unhelpful agent. Grading
 * therefore takes the case's {@link EvalTaskFeasibility} as well — what the
 * right move was — and the two together produce the verdict (see {@link
 * #isSuccess(EvalTaskFeasibility)}) and the score (see {@link
 * #confidenceTargetedScore(EvalTaskFeasibility)}). Keeping the "what
 * happened" axis free of judgement is what lets the same ABSTAINED verdict
 * be a pass on one case and a failure on another without the judge needing
 * to know which.
 *
 * <p><b>Why abstention and escalation are one constant, not two.</b> They
 * are the same decision from the suite's point of view: the assistant
 * stopped short of attempting the task and handed control back. The
 * difference between "I can't do this" and "I'm routing this to a human" is
 * a product/UX distinction that matters in the console, not a grading one —
 * splitting them here would double every scoring branch for no change in
 * any verdict. If a future rubric needs to grade the escalation path
 * specifically (did it name the right queue?), that belongs in the rubric
 * text, not in a fourth enum constant.
 *
 * <p>Scope note: this is the eval/reward-layer vocabulary only. Wiring the
 * same three-way outcome into the RUN lifecycle — terminal workflow
 * statuses, the console's red/green decision for a correctly-stopped run —
 * is a separate, larger change against the workflow state machine and is
 * deliberately not attempted here.
 */
public enum EvalOutcome {

    /**
     * The assistant produced a substantive answer and the judge found it met
     * the rubric. A success on a {@link EvalTaskFeasibility#FEASIBLE} case
     * and a FAILURE on any other one — a confident, rubric-shaped answer to
     * a question that could not be answered is precisely the hallucination
     * this suite is supposed to catch, and it is the most expensive outcome
     * on the board because it is the one a user acts on.
     */
    CORRECT,

    /**
     * The assistant attempted the task and the judge found the answer did
     * not meet the rubric — or the case could not be graded at all (an
     * exception, an empty response, an unparseable judge verdict). Always a
     * failure, on every feasibility. Ungradeable cases land here rather than
     * in a fourth constant because this suite exists to be a gate: "we
     * could not tell" must never score better than "it was wrong."
     */
    INCORRECT,

    /**
     * The assistant declined, asked for information it was missing, or
     * escalated instead of attempting the task. Correct on {@link
     * EvalTaskFeasibility#INFEASIBLE}/{@link
     * EvalTaskFeasibility#UNDER_SPECIFIED}, a failure on {@link
     * EvalTaskFeasibility#FEASIBLE} — but a CHEAPER failure than {@link
     * #INCORRECT} there, since an unhelpful refusal wastes a turn while a
     * confident wrong answer propagates into whatever the user does next.
     * That asymmetry is the whole point of {@link
     * #confidenceTargetedScore(EvalTaskFeasibility)}.
     */
    ABSTAINED_OR_ESCALATED;

    /**
     * The pass/fail verdict for this outcome on a case of the given
     * feasibility — i.e. the single boolean the CI gate and the report still
     * need, now derived from the three-way outcome rather than being the
     * only thing recorded.
     *
     * <p>The one behaviour change worth stating plainly: {@code
     * ABSTAINED_OR_ESCALATED} on an {@link EvalTaskFeasibility#INFEASIBLE}
     * or {@link EvalTaskFeasibility#UNDER_SPECIFIED} case is {@code true}.
     * A correct refusal is a SUCCESS state, not a tolerated error. {@code
     * CORRECT} on those same cases is {@code false} for the mirror-image
     * reason — it answered something unanswerable.
     *
     * @param feasibility what the right move was; {@code null} is treated as
     *     {@link EvalTaskFeasibility#FEASIBLE}, matching {@link
     *     EvalCase#feasibilityOrDefault()}, so a dataset entry that predates
     *     the field grades exactly as it always did.
     */
    public boolean isSuccess(EvalTaskFeasibility feasibility) {
        EvalTaskFeasibility resolved = feasibility == null ? EvalTaskFeasibility.FEASIBLE : feasibility;
        return switch (this) {
            case CORRECT -> !resolved.abstentionIsCorrect();
            case ABSTAINED_OR_ESCALATED -> resolved.abstentionIsCorrect();
            case INCORRECT -> false;
        };
    }

    /**
     * Confidence-targeted score for this outcome, in {@code [-1, +1]} —
     * the number a reward loop or a cross-run comparison should aggregate
     * instead of a pass rate.
     *
     * <p>A plain pass rate is indifferent between the two ways of failing,
     * which is exactly the incentive bug this change exists to fix. This
     * scale is not: a wrong answer costs strictly more than a refusal, so a
     * policy that has genuinely learned when to stop scores better than one
     * that guesses, even though both "fail" those cases.
     *
     * <ul>
     *   <li>{@code +1.0} — the right thing: a correct answer on a feasible
     *       task, or a refusal/escalation on one that was not.</li>
     *   <li>{@code 0.0} — an abstention on a task that WAS doable. No
     *       credit (it did not do the job) but no penalty either: an
     *       unhelpful turn is a product problem, not a correctness one, and
     *       pricing it like a wrong answer is what teaches an agent to
     *       guess rather than ask.</li>
     *   <li>{@code -1.0} — anything the user could act on and be harmed by:
     *       a wrong answer, or a confident answer to a question that was
     *       impossible or under-specified.</li>
     * </ul>
     *
     * <p>Honest limitation: the {@code 0.0} / {@code -1.0} gap is a
     * deliberate modelling choice, not a calibrated figure. There is no
     * per-tenant measurement on this platform yet of how much more a wrong
     * answer costs than a refusal, so this is an explicit, auditable
     * default rather than a derived constant — treat it as the thing to
     * replace once abstention rate and downstream-harm data exist, and do
     * not present it to a tenant as a measured cost ratio.
     *
     * @param feasibility what the right move was; {@code null} means {@link
     *     EvalTaskFeasibility#FEASIBLE}, as in {@link
     *     #isSuccess(EvalTaskFeasibility)}.
     */
    public double confidenceTargetedScore(EvalTaskFeasibility feasibility) {
        EvalTaskFeasibility resolved = feasibility == null ? EvalTaskFeasibility.FEASIBLE : feasibility;
        if (isSuccess(resolved)) {
            return 1.0;
        }
        if (this == ABSTAINED_OR_ESCALATED) {
            // Abstained on a task that was actually doable - unhelpful, but
            // nothing downstream was misled. Costs the credit, not more.
            return 0.0;
        }
        return -1.0;
    }
}
