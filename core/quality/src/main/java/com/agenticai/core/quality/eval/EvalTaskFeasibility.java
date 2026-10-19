package com.agenticai.core.quality.eval;

/**
 * Whether a golden-dataset case is a task the assistant is actually
 * supposed to complete — i.e. what the RIGHT move was, independent of what
 * the assistant did.
 *
 * <p><b>Why this has to exist separately from the rubric.</b> {@link
 * EvalOutcome} describes what the assistant DID (answered / got it wrong /
 * declined). On its own that is not gradeable: declining is the correct
 * behaviour on an impossible or under-specified task and the wrong
 * behaviour on a perfectly answerable one, and the identical response text
 * is a pass in one case and a failure in the other. Scoring therefore needs
 * two independent facts per case — what happened, and what should have
 * happened — and this enum is the second one. Keeping it on {@link EvalCase}
 * rather than inferring it from the rubric prose also means the dataset
 * author states it explicitly instead of the judge guessing at it, which is
 * the whole point: a suite that cannot say "this task is impossible" can
 * never reward an agent for noticing that.
 *
 * <p><b>Why the suite needs infeasible cases at all.</b> The dominant real
 * complaint about production agents is not wrongness, it is not stopping:
 * a long run, eleven actions toward a goal that was never reachable, a
 * half-applied side effect, and a confident success report. An
 * accuracy-only score gives exactly zero credit for a correct "I cannot do
 * this", which means it actively trains prompts and bandit policies toward
 * confident guessing — the incentive is the bug, not the model. That is
 * only fixable if the dataset contains tasks where stopping is the right
 * answer, which is what {@link #INFEASIBLE} and {@link #UNDER_SPECIFIED}
 * are for.
 *
 * <p>Absent from a dataset entry's JSON, this deserializes as {@code null}
 * and {@link EvalCase#feasibilityOrDefault()} resolves it to {@link
 * #FEASIBLE} — the correct default, because every pre-existing case in
 * {@code golden-dataset.json} is a task the assistant is meant to complete
 * and their grading must not shift. Same backward-compatible-absent posture
 * {@link EvalCase#categoryOrDefault()} already takes for {@code category}.
 */
public enum EvalTaskFeasibility {

    /**
     * An ordinary case: the task is answerable with what the assistant was
     * given, and the assistant is expected to do it. Declining here is a
     * failure — an unhelpful agent, which is its own product problem — but
     * a cheaper failure than a confident wrong answer, because nobody acts
     * on a refusal. Every case written before this enum existed is this.
     */
    FEASIBLE,

    /**
     * The task is answerable in principle but the request is missing
     * something the assistant genuinely cannot supply — which record, which
     * account, which of two ambiguous readings. The correct behaviour is to
     * ask one clarifying question (or escalate), and a confident answer is
     * a guess dressed as a fact, which is the single most expensive outcome
     * this suite can observe: it is the one a user will act on.
     */
    UNDER_SPECIFIED,

    /**
     * The task cannot be completed at all within what the assistant has:
     * it needs a capability the platform does not have, data that does not
     * exist, or an action that is out of policy. The correct behaviour is
     * to say so plainly, say why, and point at the escalation path. This is
     * the case an accuracy-only score penalises the agent for getting
     * RIGHT, which is why scoring it as a success (see {@link
     * EvalOutcome#isSuccess(EvalTaskFeasibility)}) is the substantive half
     * of this change rather than a relabelling.
     */
    INFEASIBLE;

    /** Whether declining/escalating is the behaviour this case is looking for. */
    public boolean abstentionIsCorrect() {
        return this != FEASIBLE;
    }
}
