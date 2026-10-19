package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.agenticai.core.brief.LedgerMath.VersionStamp;
import com.agenticai.core.brief.ProofData.EvalRunPoint;
import com.agenticai.core.brief.ProofData.ExperimentRow;
import com.agenticai.core.brief.ProofData.Variant;
import com.agenticai.core.brief.dto.LedgerDtos.Comparison;
import com.agenticai.core.brief.dto.LedgerDtos.RunView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The significance rule: a change is real only when a noise floor was measured and the change is larger than it. */
class LedgerMathTest {

    private static final Instant T0 = Instant.parse("2026-09-20T10:00:00Z");
    private static final List<VersionStamp> VERSIONS = List.of(new VersionStamp(1, T0.minusSeconds(60)));

    private static EvalRunPoint run(String id, int day, double score) {
        return new EvalRunPoint(id, T0.plusSeconds(day * 86400L), score, 0.7, score >= 0.7, score >= 0.7 ? "PASSED" : "FAILED");
    }

    @Test
    void theFirstRunIsTheBaselineAndHasNoDelta() {
        List<RunView> runs = LedgerMath.runs(List.of(run("r1", 0, 0.75)), VERSIONS, 0.04);

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).deltaVsPrev()).isNull();
        assertThat(runs.get(0).change()).isEqualTo("BASELINE");
        assertThat(runs.get(0).significant()).isFalse();
    }

    @Test
    void aChangeInsideTheNoiseBandIsNotSignificant() {
        List<RunView> runs = LedgerMath.runs(List.of(run("r1", 0, 0.75), run("r2", 1, 0.78)), VERSIONS, 0.04);

        assertThat(runs.get(1).deltaVsPrev()).isCloseTo(0.03, within(1e-9));
        assertThat(runs.get(1).significant()).isFalse();
        assertThat(runs.get(1).change()).isEqualTo("WITHIN_NOISE");
    }

    @Test
    void aChangeExactlyAtTheFloorIsNotAboveIt() {
        List<RunView> runs = LedgerMath.runs(List.of(run("r1", 0, 0.50), run("r2", 1, 0.75)), VERSIONS, 0.25);

        assertThat(runs.get(1).significant()).isFalse();
    }

    @Test
    void aChangeBeyondTheNoiseBandIsSignificantInEitherDirection() {
        List<RunView> runs = LedgerMath.runs(
            List.of(run("r1", 0, 0.60), run("r2", 1, 0.80), run("r3", 2, 0.71)), VERSIONS, 0.05);

        assertThat(runs.get(1).change()).isEqualTo("SIGNIFICANT_UP");
        assertThat(runs.get(1).significant()).isTrue();
        assertThat(runs.get(2).change()).isEqualTo("SIGNIFICANT_DOWN");
        assertThat(runs.get(2).significant()).isTrue();
    }

    @Test
    void withNoMeasuredFloorNothingIsEverCalledSignificant() {
        List<RunView> runs = LedgerMath.runs(List.of(run("r1", 0, 0.30), run("r2", 1, 0.95)), VERSIONS, null);

        assertThat(runs.get(1).deltaVsPrev()).isCloseTo(0.65, within(1e-9));
        assertThat(runs.get(1).significant()).isFalse();
        assertThat(runs.get(1).change()).isEqualTo("UNKNOWN_NOISE");
        assertThat(runs.get(1).noiseFloor()).isNull();
    }

    @Test
    void aRunBelongsToTheNewestVersionSavedBeforeItStarted() {
        List<VersionStamp> versions = List.of(
            new VersionStamp(1, T0.minusSeconds(3600)), new VersionStamp(2, T0.plusSeconds(86400 / 2)),
            new VersionStamp(3, T0.plusSeconds(3 * 86400)));

        List<RunView> runs = LedgerMath.runs(List.of(run("r1", 0, 0.7), run("r2", 1, 0.7), run("r3", 4, 0.7)), versions, null);

        assertThat(runs).extracting(RunView::version).containsExactly(1, 2, 3);
    }

    @Test
    void aRunOlderThanEveryVersionFallsBackToTheOldestVersion() {
        assertThat(LedgerMath.versionAt(
            List.of(new VersionStamp(2, T0.plusSeconds(10)), new VersionStamp(5, T0.plusSeconds(20))), T0)).isEqualTo(2);
        assertThat(LedgerMath.versionAt(List.of(), T0)).isEqualTo(1);
    }

    @Test
    void aRatioIsNullNotZeroWhenNothingWasRated() {
        assertThat(LedgerMath.ratio(0, 0)).isNull();
        assertThat(LedgerMath.ratio(7, 10)).isEqualTo(0.7);
    }

    // ------------------------------------------------------------------ experiments

    private static ExperimentRow experiment(long controlN, double controlPct, long treatmentN, double treatmentPct) {
        return new ExperimentRow("e1", "agent", "brief:x running vs earlier version", "RUNNING", T0,
            new Variant("c", 100, controlN, controlN == 0 ? null : controlPct, null),
            new Variant("t", 100, treatmentN, treatmentN == 0 ? null : treatmentPct, null));
    }

    @Test
    void anExperimentWithTooLittleFeedbackOnEitherArmIsNotComparedAtAll() {
        assertThat(LedgerMath.compare(experiment(9, 90, 40, 50))).isNull();
        assertThat(LedgerMath.compare(experiment(40, 90, 9, 50))).isNull();
        assertThat(LedgerMath.compare(experiment(0, 0, 0, 0))).isNull();
    }

    @Test
    void aLargeThumbsGapOverEnoughTurnsIsARealDifference() {
        Comparison c = LedgerMath.compare(experiment(60, 85, 60, 55));

        assertThat(c.significant()).isTrue();
        assertThat(c.delta()).isCloseTo(0.30, within(1e-9));
        assertThat(c.summary()).contains("a real difference");
    }

    @Test
    void aSmallGapOverAFewTurnsIsNotDistinguishableFromChance() {
        Comparison c = LedgerMath.compare(experiment(12, 75, 12, 67));

        assertThat(c.significant()).isFalse();
        assertThat(c.summary()).contains("not distinguishable from chance");
    }

    @Test
    void identicalPerfectArmsDoNotDivideByZero() {
        Comparison c = LedgerMath.compare(experiment(20, 100, 20, 100));

        assertThat(c.significant()).isFalse();
        assertThat(c.delta()).isZero();
    }
}
