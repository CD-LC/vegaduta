package com.agenticai.core.platform.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.platform.reliability.SloMath.InstanceWindow;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/** What the sampler reads from Micrometer, how its ring windows, and the Redis aggregation path. */
@SuppressWarnings("unchecked")
class SloSamplerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SloSample sample(long t, double req, double err) {
        return new SloSample(
                SloSample.CURRENT_VERSION, t, req, err, null, null, null, Map.of(), 0, 0);
    }

    private static SloSampler sampler(StringRedisTemplate redis, boolean viaRedis) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry =
                mock(ObjectProvider.class);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        SloSampler s = new SloSampler(registry, redisProvider, true, 60, 48, viaRedis, 300);
        s.clock = Clock.fixed(Instant.ofEpochMilli(10 * 3_600_000L), ZoneOffset.UTC);
        return s;
    }

    // ---------------------------------------------------------------- collect

    @Test
    void collectCountsServerRequestsAndFiveHundredsAndSkipsActuator() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.timer("http.server.requests", "uri", "/api/agents", "status", "200")
                .record(5, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests", "uri", "/api/agents", "status", "200")
                .record(5, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests", "uri", "/api/agents", "status", "503")
                .record(5, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests", "uri", "/api/agents", "status", "404")
                .record(5, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests", "uri", "/api/agents/{agentId}/chat", "status", "500")
                .record(5, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests", "uri", "/actuator/prometheus", "status", "500")
                .record(5, TimeUnit.MILLISECONDS);

        SloSample s = SloSampler.collect(registry, 1234L);

        assertThat(s.t()).isEqualTo(1234L);
        assertThat(s.v()).isEqualTo(SloSample.CURRENT_VERSION);
        assertThat(s.req()).isEqualTo(5);
        assertThat(s.err5xx()).isEqualTo(2);
        assertThat(s.chat()).isNotNull();
        assertThat(s.chat().count()).isEqualTo(1);
        assertThat(s.api().count()).isEqualTo(4);
        assertThat(s.ttft()).isNull();
    }

    @Test
    void collectKeepsLongLivedStreamsOutOfTheApiLatencyClassButCountsThemForAvailability() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.timer("http.server.requests", "uri", "/api/agents", "status", "200")
                .record(5, TimeUnit.MILLISECONDS);
        registry.timer(
                        "http.server.requests",
                        "uri",
                        "/api/workflows/{workflowId}/runs/{runId}/events",
                        "status",
                        "200")
                .record(5, TimeUnit.MILLISECONDS);
        registry.timer(
                        "http.server.requests",
                        "uri",
                        "/api/agents/{agentId}/agui/run",
                        "status",
                        "500")
                .record(5, TimeUnit.MILLISECONDS);

        SloSample s = SloSampler.collect(registry, 1L);

        assertThat(s.req()).isEqualTo(3);
        assertThat(s.err5xx()).isEqualTo(1);
        assertThat(s.api().count()).isEqualTo(1);
    }

    @Test
    void collectGroupsLlmCallsByProviderAndReadsJobOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("llm.calls", "tenant", "t1", "provider", "OPENAI", "model", "m")
                .increment(3);
        registry.counter("llm.calls", "tenant", "t2", "provider", "OPENAI", "model", "m")
                .increment(2);
        registry.counter("llm.calls", "tenant", "t1", "provider", "GEMINI", "model", "g")
                .increment(4);
        registry.counter("llm.call.errors", "tenant", "t1", "provider", "OPENAI", "model", "m")
                .increment(1);
        registry.counter("agent.job.completed", "outcome", "SUCCEEDED").increment(7);
        registry.counter("agent.job.completed", "outcome", "FAILED").increment(2);

        SloSample s = SloSampler.collect(registry, 1L);

        assertThat(s.llm().get("OPENAI")).containsExactly(5, 1);
        assertThat(s.llm().get("GEMINI")).containsExactly(4, 0);
        assertThat(s.jobOk()).isEqualTo(7);
        assertThat(s.jobFailed()).isEqualTo(2);
    }

    @Test
    void collectFromAnEmptyRegistryIsAllZeroAndAbsent() {
        SloSample s = SloSampler.collect(new SimpleMeterRegistry(), 1L);
        assertThat(s.req()).isZero();
        assertThat(s.chat()).isNull();
        assertThat(s.api()).isNull();
        assertThat(s.llm()).isEmpty();
        assertThat(s.jobOk()).isZero();
    }

    @Test
    void aTimerWithoutBucketsYieldsACountOnlyHistogram() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.timer("agentic.chat.ttft", "mode", "llm").record(10, TimeUnit.MILLISECONDS);
        SloSample s = SloSampler.collect(registry, 1L);
        assertThat(s.ttft()).isNotNull();
        assertThat(s.ttft().count()).isEqualTo(1);
        assertThat(s.ttft().hasBuckets()).isFalse();
    }

    // ---------------------------------------------------------------- ring

    @Test
    void ringKeepsOnlyTheRetentionAndAlwaysAtLeastOneSample() {
        SloSampler s = sampler(null, false); // retention 48 h
        long hour = 3_600_000L;
        s.record(sample(0, 0, 0));
        s.record(sample(10 * hour, 1, 0));
        s.record(sample(60 * hour, 2, 0)); // 60 h later: the first two are older than 48 h

        SloSampler.WindowRead read = s.read(Duration.ofHours(48));
        // clock is fixed at hour 10, so the window start is negative: base is the oldest retained
        // sample
        assertThat(read.windows()).hasSize(1);
        assertThat(read.windows().get(0).base().req()).isEqualTo(2);
    }

    @Test
    void ownWindowStartsAtTheFirstSampleInsideTheWindow() {
        SloSampler s = sampler(null, false);
        long hour = 3_600_000L;
        s.record(sample(1 * hour, 10, 0));
        s.record(sample(5 * hour, 50, 1));
        s.record(sample(9 * hour, 90, 2));

        // window of 6 h ending at hour 10: starts at hour 4 -> first sample at/after is hour 5
        InstanceWindow w = s.ownWindow(4 * hour, 10 * hour);
        assertThat(w.base().t()).isEqualTo(5 * hour);
        assertThat(w.latest().t()).isEqualTo(9 * hour); // no registry: newest ring sample
    }

    @Test
    void ownWindowIsNullBeforeTheFirstSample() {
        assertThat(sampler(null, false).ownWindow(0, 1)).isNull();
    }

    @Test
    void readWithoutRedisIsPerReplica() {
        SloSampler s = sampler(null, false);
        s.record(sample(1_000, 0, 0));
        s.record(sample(2_000, 5, 1));
        SloSampler.WindowRead read = s.read(Duration.ofHours(1));
        assertThat(read.aggregated()).isFalse();
        assertThat(read.windows()).hasSize(1);
    }

    // ---------------------------------------------------------------- redis aggregation

    @Test
    void readAddsOtherReplicasFromRedis() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        long now = 10 * 3_600_000L;
        String base = MAPPER.writeValueAsString(sample(now - 1_800_000L, 0, 0));
        String latest = MAPPER.writeValueAsString(sample(now - 60_000L, 100, 3));
        when(zset.rangeByScore(eq(SloSampler.INSTANCES_KEY), anyDouble(), anyDouble()))
                .thenReturn(Set.of("other"));
        when(zset.rangeByScore(
                        eq(SloSampler.SAMPLES_KEY_PREFIX + "other"),
                        anyDouble(),
                        anyDouble(),
                        eq(0L),
                        eq(1L)))
                .thenReturn(Set.of(base));
        when(zset.reverseRange(SloSampler.SAMPLES_KEY_PREFIX + "other", 0, 0))
                .thenReturn(Set.of(latest));

        SloSampler s = sampler(redis, true);
        s.record(sample(now - 3_000_000L, 0, 0));
        s.record(sample(now - 60_000L, 10, 0));

        SloSampler.WindowRead read = s.read(Duration.ofHours(1));

        assertThat(read.aggregated()).isTrue();
        assertThat(read.windows()).hasSize(2);
        assertThat(read.windows().get(1).instanceId()).isEqualTo("other");
        assertThat(read.windows().get(1).latest().req()).isEqualTo(100);
    }

    @Test
    void aRedisFailureFallsBackToThisReplicaAndSaysSo() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForZSet()).thenThrow(new IllegalStateException("redis down"));

        SloSampler s = sampler(redis, true);
        s.record(sample(1_000, 0, 0));
        s.record(sample(2_000, 5, 1));

        SloSampler.WindowRead read = s.read(Duration.ofHours(1));

        assertThat(read.aggregated()).isFalse();
        assertThat(read.windows()).hasSize(1);
        assertThat(read.notes()).anyMatch(n -> n.contains("only the replica that answered"));
    }

    @Test
    void redisAggregationWithoutARedisBeanFallsBackAndSaysSo() {
        SloSampler s = sampler(null, true);
        s.record(sample(1_000, 0, 0));
        s.record(sample(2_000, 5, 1));
        SloSampler.WindowRead read = s.read(Duration.ofHours(1));
        assertThat(read.aggregated()).isFalse();
        assertThat(read.notes()).isNotEmpty();
    }

    @Test
    void tickWithoutARegistryNeverThrows() {
        SloSampler s = sampler(null, false);
        s.tick(); // registry is null (start() was not called): the NPE must be swallowed
        assertThat(s.isSampling()).isFalse();
    }
}
