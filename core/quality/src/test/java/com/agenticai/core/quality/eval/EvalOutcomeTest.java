package com.agenticai.core.quality.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The full outcome x feasibility truth table, asserted explicitly rather
 * than by looping the enums, because the POINT of this change is that four
 * specific cells are not what a pass-rate would give you and each of them
 * deserves to fail on its own line if someone "simplifies" the rule:
 *
 * <ul>
 *   <li>abstaining on an infeasible task is a PASS, not a tolerated error;</li>
 *   <li>answering an infeasible task is a FAILURE even though the judge
 *       graded the text as meeting the rubric;</li>
 *   <li>abstaining on a doable task fails but scores {@code 0.0}, strictly
 *       better than a wrong answer's {@code -1.0};</li>
 *   <li>a null feasibility (every dataset entry written before the field
 *       existed) must behave exactly like {@code FEASIBLE}, so adding the
 *       field cannot silently reclassify the existing suite.</li>
 * </ul>
 */
class EvalOutcomeTest {

    @Test
    void feasibleTaskRewardsAnsweringAndFailsBothWaysOfNotAnswering() {
        assertThat(EvalOutcome.CORRECT.isSuccess(EvalTaskFeasibility.FEASIBLE)).isTrue();
        assertThat(EvalOutcome.INCORRECT.isSuccess(EvalTaskFeasibility.FEASIBLE)).isFalse();
        assertThat(EvalOutcome.ABSTAINED_OR_ESCALATED.isSuccess(EvalTaskFeasibility.FEASIBLE))
            .as("declining a task that was actually doable is an unhelpful agent, not a success")
            .isFalse();
    }

    @Test
    void infeasibleTaskRewardsStoppingAndPunishesAnswering() {
        assertThat(EvalOutcome.ABSTAINED_OR_ESCALATED.isSuccess(EvalTaskFeasibility.INFEASIBLE))
            .as("a correct 'I cannot do this' is the substantive change here - it must be a PASS")
            .isTrue();
        assertThat(EvalOutcome.CORRECT.isSuccess(EvalTaskFeasibility.INFEASIBLE))
            .as("a confident, rubric-shaped answer to an impossible task is the hallucination this suite exists to catch")
            .isFalse();
        assertThat(EvalOutcome.INCORRECT.isSuccess(EvalTaskFeasibility.INFEASIBLE)).isFalse();
    }

    @Test
    void underSpecifiedTaskRewardsAskingRatherThanGuessing() {
        assertThat(EvalOutcome.ABSTAINED_OR_ESCALATED.isSuccess(EvalTaskFeasibility.UNDER_SPECIFIED)).isTrue();
        assertThat(EvalOutcome.CORRECT.isSuccess(EvalTaskFeasibility.UNDER_SPECIFIED))
            .as("answering an under-specified request is a guess dressed as a fact")
            .isFalse();
    }

    @Test
    void aWrongAnswerCostsStrictlyMoreThanARefusalOnTheSameCase() {
        // This asymmetry is the entire reason confidenceTargetedScore exists
        // alongside the boolean - a plain pass rate is indifferent between
        // these two, which is what trains a policy to guess.
        double wrongAnswer = EvalOutcome.INCORRECT.confidenceTargetedScore(EvalTaskFeasibility.FEASIBLE);
        double refusal = EvalOutcome.ABSTAINED_OR_ESCALATED.confidenceTargetedScore(EvalTaskFeasibility.FEASIBLE);

        assertThat(wrongAnswer).isEqualTo(-1.0);
        assertThat(refusal).isEqualTo(0.0);
        assertThat(refusal).isGreaterThan(wrongAnswer);
    }

    @Test
    void everyCorrectBehaviourScoresTheSamePlusOne() {
        assertThat(EvalOutcome.CORRECT.confidenceTargetedScore(EvalTaskFeasibility.FEASIBLE)).isEqualTo(1.0);
        assertThat(EvalOutcome.ABSTAINED_OR_ESCALATED.confidenceTargetedScore(EvalTaskFeasibility.INFEASIBLE))
            .isEqualTo(1.0);
        assertThat(EvalOutcome.ABSTAINED_OR_ESCALATED.confidenceTargetedScore(EvalTaskFeasibility.UNDER_SPECIFIED))
            .isEqualTo(1.0);
    }

    @Test
    void answeringWhenItShouldHaveStoppedIsPricedAsHarshlyAsBeingWrong() {
        assertThat(EvalOutcome.CORRECT.confidenceTargetedScore(EvalTaskFeasibility.INFEASIBLE)).isEqualTo(-1.0);
        assertThat(EvalOutcome.CORRECT.confidenceTargetedScore(EvalTaskFeasibility.UNDER_SPECIFIED)).isEqualTo(-1.0);
    }

    @Test
    void nullFeasibilityBehavesExactlyLikeFeasibleSoTheExistingSuiteIsUnaffected() {
        for (EvalOutcome outcome : EvalOutcome.values()) {
            assertThat(outcome.isSuccess(null))
                .as("%s with an absent feasibility", outcome)
                .isEqualTo(outcome.isSuccess(EvalTaskFeasibility.FEASIBLE));
            assertThat(outcome.confidenceTargetedScore(null))
                .as("%s score with an absent feasibility", outcome)
                .isEqualTo(outcome.confidenceTargetedScore(EvalTaskFeasibility.FEASIBLE));
        }
    }

    @Test
    void onlyNonFeasibleCasesWantAnAbstention() {
        assertThat(EvalTaskFeasibility.FEASIBLE.abstentionIsCorrect()).isFalse();
        assertThat(EvalTaskFeasibility.INFEASIBLE.abstentionIsCorrect()).isTrue();
        assertThat(EvalTaskFeasibility.UNDER_SPECIFIED.abstentionIsCorrect()).isTrue();
    }
}
