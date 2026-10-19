package com.agenticai.core.platform.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.platform.reliability.ReliabilityApi.LatencySlo;
import com.agenticai.core.platform.reliability.ReliabilityApi.SloResponse;
import com.agenticai.core.platform.reliability.SloMath.InstanceWindow;
import com.agenticai.core.platform.reliability.SloSample.Hist;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** The response composition: what is shown, what is omitted, and what the notes say. */
class SloServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final long DAY_MS = 24L * 3_600_000L;

    private final SloSampler sampler = mock(SloSampler.class);
    private final SloService service = new SloService(sampler, true, 0.995, 1000, 4000, 100);

    private static SloSample sample(
        long t, double req, double err, Hist api, Map<String, double[]> llm, double jobOk, double jobFailed
    ) {
        return new SloSample(SloSample.CURRENT_VERSION, t, req, err, null, api, null, llm, jobOk, jobFailed);
    }

    private SloResponse build(int hours, boolean aggregated, boolean viaRedis, long coveredMs, SloSample latestSpec) {
        SloSample base = sample(0, 0, 0, null, Map.of(), 0, 0);
        SloSample latest = new SloSample(latestSpec.v(), coveredMs, latestSpec.req(), latestSpec.err5xx(),
            latestSpec.chat(), latestSpec.api(), latestSpec.ttft(), latestSpec.llm(), latestSpec.jobOk(),
            latestSpec.jobFailed());
        SloSampler.WindowRead read = new SloSampler.WindowRead(
            List.of(new InstanceWindow("a", base, latest)), aggregated, List.of());
        return service.build(NOW, hours, read, 60, viaRedis);
    }

    private SloResponse build(SloSample latestSpec) {
        return build(24, false, false, DAY_MS, latestSpec);
    }

    // ---------------------------------------------------------------- availability

    @Test
    void availabilityAndBurnWithinTheBudgetReadOk() {
        SloResponse r = build(sample(0, 100_000, 400, null, Map.of(), 0, 0));
        assertThat(r.availability().availability()).isCloseTo(0.996, within(1e-9));
        assertThat(r.availability().errorRatio()).isCloseTo(0.004, within(1e-9));
        assertThat(r.availability().burnRate()).isCloseTo(0.8, within(1e-6));
        assertThat(r.availability().budgetRemaining()).isCloseTo(0.2, within(1e-6));
        assertThat(r.availability().verdict()).isEqualTo("OK");
        assertThat(r.objectives().availabilityTarget()).isEqualTo(0.995);
    }

    @Test
    void aBurnRateOfEightIsABreach() {
        SloResponse r = build(sample(0, 100_000, 4_000, null, Map.of(), 0, 0));
        assertThat(r.availability().burnRate()).isCloseTo(8.0, within(1e-6));
        assertThat(r.availability().verdict()).isEqualTo("BREACH");
        assertThat(r.availability().budgetRemaining()).isLessThan(0.0);
    }

    @Test
    void tooFewRequestsGiveNoVerdict() {
        SloResponse r = build(sample(0, 50, 5, null, Map.of(), 0, 0));
        assertThat(r.availability().verdict()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void noRequestsMeansNoAvailabilityNotAHundredPercent() {
        SloResponse r = build(sample(0, 0, 0, null, Map.of(), 0, 0));
        assertThat(r.availability()).isNull();
        assertThat(r.notes()).anyMatch(n -> n.contains("No server requests were recorded"));
    }

    // ---------------------------------------------------------------- latency

    @Test
    void latencyPercentilesTargetAndSlowShareComeFromTheBuckets() {
        Hist api = new Hist(1000, new double[] {0.1, 0.5, 1.0, 2.5}, new double[] {500, 900, 960, 1000});
        SloResponse r = build(sample(0, 1000, 0, api, Map.of(), 0, 0));

        assertThat(r.latency()).hasSize(1);
        LatencySlo l = r.latency().get(0);
        assertThat(l.key()).isEqualTo("other_api");
        assertThat(l.p50Ms()).isCloseTo(100.0, within(1e-6));
        assertThat(l.p95Ms()).isCloseTo(916.6667, within(1e-3));
        assertThat(l.p99Ms()).isCloseTo(2125.0, within(1e-6));
        assertThat(l.targetMs()).isEqualTo(1000.0);
        assertThat(l.meetsTarget()).isTrue();
        assertThat(l.slowShare()).isCloseTo(0.04, within(1e-9));
        assertThat(l.slowShareBoundMs()).isCloseTo(1000.0, within(1e-6));
        assertThat(l.burnRate()).isCloseTo(0.8, within(1e-6));
        assertThat(l.verdict()).isEqualTo("OK");
        // the two series that have no data are named, not invented
        assertThat(r.notes()).anyMatch(n -> n.contains("Chat time to first token") && n.contains("No observations"));
    }

    @Test
    void aSlowP95FailsTheTarget() {
        Hist api = new Hist(1000, new double[] {0.1, 1.0, 2.5}, new double[] {100, 400, 1000});
        SloResponse r = build(sample(0, 1000, 0, api, Map.of(), 0, 0));
        LatencySlo l = r.latency().get(0);
        assertThat(l.meetsTarget()).isFalse();
        assertThat(l.slowShare()).isCloseTo(0.6, within(1e-9));
        assertThat(l.verdict()).isEqualTo("BREACH");
    }

    @Test
    void aPercentileAboveTheLargestBucketIsNotShownAsAFigure() {
        Hist api = new Hist(1000, new double[] {0.1, 0.5}, new double[] {500, 800});
        SloResponse r = build(sample(0, 1000, 0, api, Map.of(), 0, 0));
        LatencySlo l = r.latency().get(0);
        assertThat(l.p50Ms()).isCloseTo(100.0, within(1e-6));
        assertThat(l.p95Ms()).isNull();
        assertThat(l.p99Ms()).isNull();
        // the floor (0.5 s) is under the 1 s target, so the target cannot be judged from the percentile
        assertThat(l.meetsTarget()).isNull();
        assertThat(r.notes()).anyMatch(n -> n.contains("p95") && n.contains("largest histogram bucket"));
    }

    @Test
    void noVerdictIsDerivedFromABucketFarBelowTheTarget() {
        // target 4000 ms but the nearest bound at or under it is 2500 ms
        SloService svc = new SloService(sampler, true, 0.995, 4000, 4000, 100);
        Hist api = new Hist(1000, new double[] {0.5, 2.5, 5.0}, new double[] {400, 800, 1000});
        SloSample base = sample(0, 0, 0, null, Map.of(), 0, 0);
        SloSample latest = new SloSample(SloSample.CURRENT_VERSION, DAY_MS, 1000, 0, null, api, null, Map.of(), 0, 0);
        SloSampler.WindowRead read = new SloSampler.WindowRead(
            List.of(new InstanceWindow("a", base, latest)), false, List.of());
        SloResponse r = svc.build(NOW, 24, read, 60, false);
        LatencySlo l = r.latency().get(0);
        assertThat(l.slowShareBoundMs()).isCloseTo(2500.0, within(1e-6));
        assertThat(l.verdict()).isNull();
        assertThat(l.burnRate()).isNull();
        assertThat(r.notes()).anyMatch(n -> n.contains("No histogram bucket sits close to"));
    }

    @Test
    void aTimerWithoutBucketsIsOmittedWithAnHonestNote() {
        SloResponse r = build(sample(0, 500, 0, new Hist(500, new double[0], new double[0]), Map.of(), 0, 0));
        assertThat(r.latency()).isEmpty();
        assertThat(r.notes()).anyMatch(n -> n.contains("latency-histograms"));
    }

    // ---------------------------------------------------------------- providers and jobs

    @Test
    void providerErrorRatesAreSortedByVolumeAndSkipSilentProviders() {
        SloResponse r = build(sample(0, 1000, 0, null, Map.of(
            "OPENAI", new double[] {100, 5},
            "GEMINI", new double[] {300, 3},
            "IDLE", new double[] {0, 0}), 0, 0));
        assertThat(r.llmProviders()).extracting(p -> p.provider()).containsExactly("GEMINI", "OPENAI");
        assertThat(r.llmProviders().get(1).errorRate()).isCloseTo(0.05, within(1e-9));
    }

    @Test
    void jobSuccessIsOmittedWhenNoJobFinished() {
        SloResponse none = build(sample(0, 1000, 0, null, Map.of(), 0, 0));
        assertThat(none.jobs()).isNull();
        assertThat(none.notes()).anyMatch(n -> n.contains("No agent jobs finished"));

        SloResponse some = build(sample(0, 1000, 0, null, Map.of(), 8, 2));
        assertThat(some.jobs().finished()).isEqualTo(10);
        assertThat(some.jobs().successRate()).isCloseTo(0.8, within(1e-9));
    }

    // ---------------------------------------------------------------- coverage and scope

    @Test
    void aWindowLongerThanTheSamplesSaysSoAndReportsTheRealSpan() {
        SloResponse r = build(24, false, false, 2 * 3_600_000L, sample(0, 1000, 0, null, Map.of(), 0, 0));
        assertThat(r.coveredSeconds()).isEqualTo(7200);
        assertThat(r.notes()).anyMatch(n -> n.contains("span") && n.contains("requested 24 h"));
    }

    @Test
    void aFullyCoveredWindowHasNoCoverageNote() {
        SloResponse r = build(sample(0, 1000, 0, null, Map.of(), 0, 0));
        assertThat(r.notes()).noneMatch(n -> n.contains("requested 24 h"));
    }

    @Test
    void scopeSaysWhetherReplicasWereCombined() {
        SloResponse single = build(24, false, false, DAY_MS, sample(0, 1000, 0, null, Map.of(), 0, 0));
        assertThat(single.scope()).isEqualTo("replica");
        assertThat(single.replicas()).isEqualTo(1);
        assertThat(single.notes()).anyMatch(n -> n.contains("PLATFORM_RELIABILITY_SLO_REDIS"));

        SloResponse combined = build(24, true, true, DAY_MS, sample(0, 1000, 0, null, Map.of(), 0, 0));
        assertThat(combined.scope()).isEqualTo("platform");
        assertThat(combined.notes()).noneMatch(n -> n.contains("PLATFORM_RELIABILITY_SLO_REDIS"));
        assertThat(combined.notes()).anyMatch(n -> n.contains("Combined across 1 replica"));
    }

    @Test
    void noSamplesYetIsAnHonestEmptyAnswer() {
        SloSampler.WindowRead read = new SloSampler.WindowRead(List.of(), false, List.of());
        SloResponse r = service.build(NOW, 24, read, 60, false);
        assertThat(r.replicas()).isZero();
        assertThat(r.availability()).isNull();
        assertThat(r.latency()).isEmpty();
        assertThat(r.jobs()).isNull();
        assertThat(r.notes()).anyMatch(n -> n.contains("No samples cover this window yet"));
    }

    // ---------------------------------------------------------------- entry point

    @Test
    void theWindowMustBeBetweenOneHourAndTheRetention() {
        when(sampler.retentionHours()).thenReturn(48L);
        assertThatThrownBy(() -> service.slo(0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1..48");
        assertThatThrownBy(() -> service.slo(49))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1..48");
    }

    @Test
    void sloReadsTheWindowFromTheSampler() {
        when(sampler.retentionHours()).thenReturn(48L);
        when(sampler.intervalSeconds()).thenReturn(60L);
        when(sampler.read(any(Duration.class))).thenReturn(new SloSampler.WindowRead(List.of(), false, List.of()));
        SloResponse r = service.slo(24);
        assertThat(r.windowHours()).isEqualTo(24);
        assertThat(r.replicas()).isZero();
    }

    @Test
    void disabledMeansNotFound() {
        SloService off = new SloService(sampler, false, 0.995, 1000, 4000, 100);
        assertThatThrownBy(() -> off.slo(24)).isInstanceOf(ResponseStatusException.class);
    }
}
