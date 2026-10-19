package com.agenticai.core.platform.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.agenticai.core.platform.reliability.SloMath.Delta;
import com.agenticai.core.platform.reliability.SloMath.InstanceWindow;
import com.agenticai.core.platform.reliability.SloMath.Share;
import com.agenticai.core.platform.reliability.SloSample.Hist;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/** The SLO arithmetic: window differencing, replica merge, percentiles, budget burn. */
class SloMathTest {

    private static Hist hist(double count, double[] bounds, double[] cum) {
        return new Hist(count, bounds, cum);
    }

    private static SloSample sample(long t, double req, double err, Hist api) {
        return new SloSample(SloSample.CURRENT_VERSION, t, req, err, null, api, null, Map.of(), 0, 0);
    }

    // ---------------------------------------------------------------- classification

    @Test
    void classifiesChatTurnTemplates() {
        assertThat(SloMath.isChatTurn("/api/agents/{agentId}/chat")).isTrue();
        assertThat(SloMath.isChatTurn("/api/share/agents/{shareId}/chat/stream")).isTrue();
        assertThat(SloMath.isChatTurn("/api/share/agents/{shareId}/chat")).isTrue();
        assertThat(SloMath.isChatTurn("/v1/chat/completions")).isTrue();
        assertThat(SloMath.isChatTurn("/api/agents/{agentId}/chat/sessions/{sessionId}/cancel")).isFalse();
        assertThat(SloMath.isChatTurn("/api/agents/{agentId}/chat/attachments")).isFalse();
        assertThat(SloMath.isChatTurn("/api/agents")).isFalse();
        assertThat(SloMath.isChatTurn(null)).isFalse();
    }

    @Test
    void classifiesActuatorAndServerErrors() {
        assertThat(SloMath.isActuator("/actuator/prometheus")).isTrue();
        assertThat(SloMath.isActuator("/actuator")).isTrue();
        assertThat(SloMath.isActuator("/api/actuator-like")).isFalse();
        assertThat(SloMath.isActuator(null)).isFalse();
        assertThat(SloMath.isServerError("500")).isTrue();
        assertThat(SloMath.isServerError("503")).isTrue();
        assertThat(SloMath.isServerError("404")).isFalse();
        assertThat(SloMath.isServerError("UNKNOWN")).isFalse();
        assertThat(SloMath.isServerError(null)).isFalse();
    }

    // ---------------------------------------------------------------- quantiles

    @Test
    void quantileInterpolatesInsideTheBucketHoldingTheRank() {
        Hist h = hist(100, new double[] {0.1, 0.5, 1.0}, new double[] {50, 90, 100});
        assertThat(SloMath.quantile(h, 0.50).getAsDouble()).isCloseTo(0.1, within(1e-9));
        assertThat(SloMath.quantile(h, 0.95).getAsDouble()).isCloseTo(0.75, within(1e-9));
        assertThat(SloMath.quantile(h, 0.99).getAsDouble()).isCloseTo(0.95, within(1e-9));
    }

    @Test
    void quantileAboveTheLastBoundReturnsTheLastBound() {
        Hist h = hist(100, new double[] {0.1, 0.5}, new double[] {50, 60});
        assertThat(SloMath.quantile(h, 0.95).getAsDouble()).isEqualTo(0.5);
    }

    @Test
    void quantileEstimateFlagsARankAboveEveryBucketAsAFloor() {
        Hist h = hist(100, new double[] {0.1, 0.5}, new double[] {50, 60});
        SloMath.Quantile above = SloMath.quantileEstimate(h, 0.95).orElseThrow();
        assertThat(above.aboveLastBound()).isTrue();
        assertThat(above.seconds()).isEqualTo(0.5);
        SloMath.Quantile inside = SloMath.quantileEstimate(h, 0.50).orElseThrow();
        assertThat(inside.aboveLastBound()).isFalse();
    }

    @Test
    void longLivedTemplatesAreRecognisedAndChatAndPlainApiAreNot() {
        assertThat(SloMath.isLongLived("/api/agents/{agentId}/jobs/{jobId}/trace/stream")).isTrue();
        assertThat(SloMath.isLongLived("/api/workflows/{workflowId}/runs/{runId}/events")).isTrue();
        assertThat(SloMath.isLongLived("/api/agents/{agentId}/agui/run")).isTrue();
        assertThat(SloMath.isLongLived("/api/voice/realtime/session")).isTrue();
        assertThat(SloMath.isLongLived("/api/research/start")).isTrue();
        assertThat(SloMath.isLongLived("/v1/responses")).isTrue();
        assertThat(SloMath.isLongLived("/api/agents")).isFalse();
        assertThat(SloMath.isLongLived("/api/agents/{agentId}")).isFalse();
        assertThat(SloMath.isLongLived("/api/workflows")).isFalse();
        assertThat(SloMath.isLongLived(null)).isFalse();
    }

    @Test
    void quantileIsEmptyWhenThereIsNothingToEstimateFrom() {
        assertThat(SloMath.quantile(null, 0.95)).isEqualTo(OptionalDouble.empty());
        assertThat(SloMath.quantile(hist(0, new double[] {1}, new double[] {0}), 0.95)).isEmpty();
        assertThat(SloMath.quantile(hist(10, new double[0], new double[0]), 0.95)).isEmpty();
        Hist h = hist(100, new double[] {1}, new double[] {100});
        assertThat(SloMath.quantile(h, 0)).isEmpty();
        assertThat(SloMath.quantile(h, 1)).isEmpty();
    }

    @Test
    void shareAtOrBelowUsesTheLargestBoundNotAboveTheTarget() {
        Hist h = hist(100, new double[] {0.1, 0.5, 1.0}, new double[] {50, 90, 100});
        Optional<Share> s = SloMath.shareAtOrBelow(h, 0.6);
        assertThat(s).isPresent();
        assertThat(s.get().boundSeconds()).isEqualTo(0.5);
        assertThat(s.get().shareAtOrBelow()).isCloseTo(0.9, within(1e-9));
        assertThat(SloMath.shareAtOrBelow(h, 1.0).get().shareAtOrBelow()).isEqualTo(1.0);
        assertThat(SloMath.shareAtOrBelow(h, 0.05)).isEmpty();
        assertThat(SloMath.shareAtOrBelow(hist(0, new double[] {1}, new double[] {0}), 1.0)).isEmpty();
    }

    // ---------------------------------------------------------------- budget

    @Test
    void availabilityIsTheNonErrorShare() {
        assertThat(SloMath.availability(1000, 5).getAsDouble()).isCloseTo(0.995, within(1e-12));
        assertThat(SloMath.availability(0, 0)).isEmpty();
        assertThat(SloMath.availability(10, 50).getAsDouble()).isEqualTo(0.0);
    }

    @Test
    void burnRateIsErrorRatioOverTheBudget() {
        assertThat(SloMath.burnRate(5, 1000, 0.995).getAsDouble()).isCloseTo(1.0, within(1e-9));
        assertThat(SloMath.burnRate(10, 1000, 0.995).getAsDouble()).isCloseTo(2.0, within(1e-9));
        assertThat(SloMath.burnRate(0, 1000, 0.995).getAsDouble()).isEqualTo(0.0);
        assertThat(SloMath.burnRate(1, 0, 0.995)).isEmpty();
        assertThat(SloMath.burnRate(1, 100, 1.0)).isEmpty();
    }

    @Test
    void verdictNeedsEvidenceAndThenFollowsTheBurn() {
        assertThat(SloMath.verdict(OptionalDouble.of(0.5), 1000, 100)).isEqualTo("OK");
        assertThat(SloMath.verdict(OptionalDouble.of(1.0), 1000, 100)).isEqualTo("WARN");
        assertThat(SloMath.verdict(OptionalDouble.of(5.9), 1000, 100)).isEqualTo("WARN");
        assertThat(SloMath.verdict(OptionalDouble.of(6.0), 1000, 100)).isEqualTo("BREACH");
        assertThat(SloMath.verdict(OptionalDouble.of(50.0), 99, 100)).isEqualTo("INSUFFICIENT_DATA");
        assertThat(SloMath.verdict(OptionalDouble.empty(), 1000, 100)).isEqualTo("INSUFFICIENT_DATA");
    }

    // ---------------------------------------------------------------- histogram algebra

    @Test
    void subtractTakesTheEarlierReadingOff() {
        Hist cur = hist(10, new double[] {1, 2}, new double[] {4, 10});
        Hist base = hist(4, new double[] {1, 2}, new double[] {2, 4});
        Hist d = SloMath.subtract(cur, base);
        assertThat(d.count()).isEqualTo(6);
        assertThat(d.cum()).containsExactly(2, 6);
        assertThat(d.bounds()).containsExactly(1, 2);
    }

    @Test
    void subtractWithNoEarlierReadingKeepsEverything() {
        Hist cur = hist(10, new double[] {1, 2}, new double[] {4, 10});
        Hist d = SloMath.subtract(cur, null);
        assertThat(d.count()).isEqualTo(10);
        assertThat(d.cum()).containsExactly(4, 10);
        assertThat(SloMath.subtract(null, cur)).isNull();
    }

    @Test
    void subtractRefusesCountsThatWentBackwards() {
        Hist cur = hist(10, new double[] {1, 2}, new double[] {4, 10});
        assertThat(SloMath.subtract(cur, hist(12, new double[] {1, 2}, new double[] {2, 4}))).isNull();
        assertThat(SloMath.subtract(cur, hist(4, new double[] {1, 2}, new double[] {9, 10}))).isNull();
    }

    @Test
    void addSumsMatchingBucketsAndFlagsAMismatch() {
        boolean[] mismatch = {false};
        Hist a = hist(6, new double[] {1, 2}, new double[] {2, 6});
        Hist b = hist(3, new double[] {1, 2}, new double[] {1, 3});
        Hist sum = SloMath.add(a, b, mismatch);
        assertThat(sum.count()).isEqualTo(9);
        assertThat(sum.cum()).containsExactly(3, 9);
        assertThat(mismatch[0]).isFalse();

        Hist other = hist(3, new double[] {2, 3}, new double[] {1, 3});
        Hist partial = SloMath.add(a, other, mismatch);
        assertThat(partial.bounds()).containsExactly(2);
        assertThat(partial.cum()).containsExactly(7);
        assertThat(mismatch[0]).isTrue();
    }

    @Test
    void addDropsBucketsWhenOnlyOneSideHasThem() {
        boolean[] mismatch = {false};
        Hist a = hist(6, new double[] {1, 2}, new double[] {2, 6});
        Hist none = hist(3, new double[0], new double[0]);
        Hist sum = SloMath.add(a, none, mismatch);
        assertThat(sum.count()).isEqualTo(9);
        assertThat(sum.hasBuckets()).isFalse();
        assertThat(mismatch[0]).isTrue();
        assertThat(SloMath.add(null, a, new boolean[] {false})).isSameAs(a);
    }

    // ---------------------------------------------------------------- combine

    @Test
    void combineSumsEveryReplicasWindowDelta() {
        SloSample base1 = new SloSample(1, 0L, 100, 1, null, null, null,
            Map.of("OPENAI", new double[] {10, 1}), 5, 1);
        SloSample latest1 = new SloSample(1, 3_600_000L, 1100, 11, null, null, null,
            Map.of("OPENAI", new double[] {110, 3}, "GEMINI", new double[] {5, 0}), 15, 2);
        SloSample base2 = sample(0L, 0, 0, null);
        SloSample latest2 = sample(1_800_000L, 200, 4, null);

        Delta d = SloMath.combine(List.of(
            new InstanceWindow("a", base1, latest1), new InstanceWindow("b", base2, latest2)));

        assertThat(d.instances()).isEqualTo(2);
        assertThat(d.req()).isEqualTo(1200);
        assertThat(d.err5xx()).isEqualTo(14);
        assertThat(d.jobOk()).isEqualTo(10);
        assertThat(d.jobFailed()).isEqualTo(1);
        assertThat(d.llm().get("OPENAI")).containsExactly(100, 2);
        assertThat(d.llm().get("GEMINI")).containsExactly(5, 0);
        assertThat(d.maxCoveredMs()).isEqualTo(3_600_000L);
        assertThat(d.minCoveredMs()).isEqualTo(1_800_000L);
    }

    @Test
    void combineSkipsAReplicaWhoseWindowHasNoLength() {
        SloSample s = sample(5L, 10, 0, null);
        Delta d = SloMath.combine(List.of(new InstanceWindow("a", s, s)));
        assertThat(d.instances()).isZero();
        assertThat(d.minCoveredMs()).isZero();
        assertThat(d.req()).isZero();
    }

    @Test
    void combineClampsACounterThatWentBackwardsAtZero() {
        Delta d = SloMath.combine(List.of(new InstanceWindow("a", sample(0, 100, 5, null), sample(1000, 90, 2, null))));
        assertThat(d.req()).isZero();
        assertThat(d.err5xx()).isZero();
    }

    @Test
    void combineDropsAHistogramWhoseCountsWentBackwardsAndSaysSo() {
        Hist base = hist(10, new double[] {1}, new double[] {10});
        Hist cur = hist(10, new double[] {1}, new double[] {5});
        Delta d = SloMath.combine(List.of(new InstanceWindow("a", sample(0, 0, 0, base), sample(1000, 1, 0, cur))));
        assertThat(d.histogramDropped()).isTrue();
        assertThat(d.api()).isNull();
    }

    @Test
    void combineMergesHistogramsAcrossReplicas() {
        Hist b1 = hist(0, new double[] {1, 2}, new double[] {0, 0});
        Hist l1 = hist(10, new double[] {1, 2}, new double[] {6, 10});
        Hist b2 = hist(0, new double[] {1, 2}, new double[] {0, 0});
        Hist l2 = hist(10, new double[] {1, 2}, new double[] {4, 10});
        Delta d = SloMath.combine(List.of(
            new InstanceWindow("a", sample(0, 0, 0, b1), sample(1000, 10, 0, l1)),
            new InstanceWindow("b", sample(0, 0, 0, b2), sample(1000, 10, 0, l2))));
        assertThat(d.api().count()).isEqualTo(20);
        assertThat(d.api().cum()).containsExactly(10, 20);
        assertThat(d.bucketMismatch()).isFalse();
    }
}
