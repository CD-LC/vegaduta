package com.agenticai.core.quality.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.quality.stats.BinomialReliability;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Network-free tests for the CI gate's repeated-rollout logic ({@link EvalRepeats}). */
class EvalRepeatsTest {

    private static final EvalCase CASE = new EvalCase("c1", "p", "grounding", "sys", "user", "rubric", null);

    private static EvalResult ok() {
        return EvalResult.pass(CASE, "answer", 100);
    }

    private static EvalResult bad() {
        return EvalResult.fail(CASE, "answer", "wrong total", 100);
    }

    private static EvalResult overBudget() {
        return EvalResult.pass(CASE, "answer", 100).withTokenBudget(true, "token usage too high");
    }

    private static List<EvalResult> rollouts(int ok, int bad) {
        List<EvalResult> l = new ArrayList<>();
        for (int i = 0; i < ok; i++) {
            l.add(ok());
        }
        for (int i = 0; i < bad; i++) {
            l.add(bad());
        }
        return l;
    }

    @Test
    void parseRepeatsDefaultsToOneAndClamps() {
        assertThat(EvalRepeats.parseRepeats(null)).isEqualTo(1);
        assertThat(EvalRepeats.parseRepeats("")).isEqualTo(1);
        assertThat(EvalRepeats.parseRepeats("  ")).isEqualTo(1);
        assertThat(EvalRepeats.parseRepeats("abc")).isEqualTo(1);
        assertThat(EvalRepeats.parseRepeats("0")).isEqualTo(1);
        assertThat(EvalRepeats.parseRepeats("-4")).isEqualTo(1);
        assertThat(EvalRepeats.parseRepeats("3")).isEqualTo(3);
        assertThat(EvalRepeats.parseRepeats(" 5 ")).isEqualTo(5);
        assertThat(EvalRepeats.parseRepeats("999")).isEqualTo(EvalRepeats.MAX_REPEATS);
    }

    @Test
    void parseMinPassHatKAcceptsOnlyUnitInterval() {
        assertThat(EvalRepeats.parseMinPassHatK(null)).isNull();
        assertThat(EvalRepeats.parseMinPassHatK("")).isNull();
        assertThat(EvalRepeats.parseMinPassHatK("x")).isNull();
        assertThat(EvalRepeats.parseMinPassHatK("1.5")).isNull();
        assertThat(EvalRepeats.parseMinPassHatK("-0.1")).isNull();
        assertThat(EvalRepeats.parseMinPassHatK("0.8")).isEqualTo(0.8);
        assertThat(EvalRepeats.parseMinPassHatK("0")).isEqualTo(0.0);
        assertThat(EvalRepeats.parseMinPassHatK("1")).isEqualTo(1.0);
    }

    @Test
    void summarizeUsesTheSharedBinomialEstimators() {
        // 2 of 3 ok: pass@3 = 1 (a success exists in every draw of 3), pass^3 = 0 (not all ok).
        EvalRepeats.CaseSummary s = EvalRepeats.summarize(rollouts(2, 1));
        assertThat(s.rollouts()).isEqualTo(3);
        assertThat(s.successes()).isEqualTo(2);
        assertThat(s.passAtK()).isEqualTo(BinomialReliability.passAtK(3, 2, 3));
        assertThat(s.passHatK()).isEqualTo(BinomialReliability.passHatK(3, 2, 3));
        assertThat(s.passAtK()).isEqualTo(1.0);
        assertThat(s.passHatK()).isEqualTo(0.0);
    }

    @Test
    void strictMajorityDecidesTheCase() {
        assertThat(EvalRepeats.summarize(rollouts(3, 0)).casePassed()).isTrue();
        assertThat(EvalRepeats.summarize(rollouts(2, 1)).casePassed()).isTrue();
        assertThat(EvalRepeats.summarize(rollouts(1, 2)).casePassed()).isFalse();
        // A tie is NOT a majority: 2 of 4 fails.
        assertThat(EvalRepeats.summarize(rollouts(2, 2)).casePassed()).isFalse();
        assertThat(EvalRepeats.summarize(rollouts(0, 3)).casePassed()).isFalse();
    }

    @Test
    void singleRolloutBehavesLikeTheOriginalGate() {
        assertThat(EvalRepeats.summarize(rollouts(1, 0)).casePassed()).isTrue();
        assertThat(EvalRepeats.summarize(rollouts(0, 1)).casePassed()).isFalse();
    }

    @Test
    void representativeMatchesTheCaseVerdict() {
        assertThat(EvalRepeats.summarize(rollouts(2, 1)).representative().passed()).isTrue();
        EvalResult rep = EvalRepeats.summarize(rollouts(1, 2)).representative();
        assertThat(rep.passed()).isFalse();
        assertThat(rep.judgeReason()).isEqualTo("wrong total");
    }

    @Test
    void aTokenBudgetOverrunIsNotAnOkRollout() {
        List<EvalResult> l = List.of(overBudget(), overBudget(), ok());
        EvalRepeats.CaseSummary s = EvalRepeats.summarize(l);
        assertThat(s.successes()).isEqualTo(1);
        assertThat(s.casePassed()).isFalse();
    }

    @Test
    void emptyRolloutsAreRejected() {
        assertThatThrownBy(() -> EvalRepeats.summarize(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvalRepeats.summarize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gateFailsOnAnyFailedCase() {
        var good = EvalRepeats.summarize(rollouts(3, 0));
        var flaky = EvalRepeats.summarize(rollouts(1, 2));
        assertThat(EvalRepeats.gateFailed(List.of(good, good), null)).isFalse();
        assertThat(EvalRepeats.gateFailed(List.of(good, flaky), null)).isTrue();
    }

    @Test
    void passHatKBarIsOnlyAppliedWhenSet() {
        var perfect = EvalRepeats.summarize(rollouts(3, 0));          // pass^3 = 1
        var flakyButPassing = EvalRepeats.summarize(rollouts(2, 1));  // pass^3 = 0, case still passes
        List<EvalRepeats.CaseSummary> run = List.of(perfect, flakyButPassing); // mean pass^3 = 0.5

        assertThat(EvalRepeats.gateFailed(run, null)).isFalse();
        assertThat(EvalRepeats.gateFailed(run, 0.5)).isFalse();   // exactly at the bar is met
        assertThat(EvalRepeats.gateFailed(run, 0.51)).isTrue();
        assertThat(EvalRepeats.gateFailed(List.of(perfect), 1.0)).isFalse();
    }

    @Test
    void aggregateAveragesPerCaseEstimators() {
        var a = EvalRepeats.summarize(rollouts(3, 0));
        var b = EvalRepeats.summarize(rollouts(0, 3));
        EvalRepeats.Aggregate agg = EvalRepeats.aggregate(List.of(a, b));
        assertThat(agg.k()).isEqualTo(3);
        assertThat(agg.cases()).isEqualTo(2);
        assertThat(agg.meanPassAtK()).isEqualTo(0.5);
        assertThat(agg.meanPassHatK()).isEqualTo(0.5);
        assertThat(EvalRepeats.aggregate(List.of()).cases()).isZero();
    }

    @Test
    void reportNamesPerCaseAndAggregateNumbers() {
        var flaky = EvalRepeats.summarize(rollouts(1, 2));
        String report = EvalRepeats.formatReport(List.of(flaky), 0.9);
        assertThat(report).contains("Reliability over 3 rollouts per case");
        // n=3, c=1, k=3: fewer failures (2) than draws (3), so pass@3 is exactly 1; pass^3 is exactly 0.
        assertThat(report).contains("c1: 1/3 ok, pass@3=1.000, pass^3=0.000");
        assertThat(report).contains("case FAILED (no strict majority)");
        assertThat(report).contains("mean pass@3=");
        assertThat(report).contains("EVAL_MIN_PASS_HAT_K=0.900 -> NOT MET");
        assertThat(EvalRepeats.formatReport(List.of(flaky), null)).doesNotContain("EVAL_MIN_PASS_HAT_K");
    }

    @Test
    void representativesPreserveOrder() {
        var a = EvalRepeats.summarize(rollouts(1, 0));
        var b = EvalRepeats.summarize(rollouts(0, 1));
        assertThat(EvalRepeats.representatives(List.of(a, b))).containsExactly(a.representative(), b.representative());
    }
}
