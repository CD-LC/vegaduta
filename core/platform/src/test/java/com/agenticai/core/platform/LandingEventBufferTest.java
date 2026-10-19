package com.agenticai.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SuppressWarnings("unchecked")
class LandingEventBufferTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ObjectProvider<StringRedisTemplate> provider;
    private SimpleMeterRegistry meters;
    private final Map<String, Long> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        provider = mock(ObjectProvider.class);
        meters = new SimpleMeterRegistry();
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString(), anyLong())).thenAnswer(inv ->
            store.merge(inv.getArgument(0), (Long) inv.getArgument(1), Long::sum));
    }

    private LandingEventBuffer buffer(int capacity, int batch) {
        return new LandingEventBuffer(provider, meters, capacity, batch);
    }

    private double dropped() {
        return meters.get("landing.events.dropped").counter().count();
    }

    private long counterFor(String event) {
        return store.entrySet().stream()
            .filter(e -> e.getKey().startsWith("agentic:landing:" + event + ":"))
            .mapToLong(Map.Entry::getValue).sum();
    }

    @Test
    void offerBuffersWithoutTouchingRedis() {
        LandingEventBuffer b = buffer(10, 10);
        b.offer("landing_view", "1.1.1.1");
        b.offer("landing_view", "1.1.1.1");
        assertThat(b.size()).isEqualTo(2);
        verify(redis, never()).opsForValue();
    }

    @Test
    void flushAggregatesIntoIncrbyDeltas() {
        LandingEventBuffer b = buffer(10, 10);
        b.offer("landing_view", "1.1.1.1");
        b.offer("landing_view", "1.1.1.1");
        b.offer("hero_try_click", "2.2.2.2");
        assertThat(b.flushOnce()).isTrue();
        assertThat(b.size()).isZero();
        assertThat(counterFor("landing_view")).isEqualTo(2L);
        assertThat(counterFor("hero_try_click")).isEqualTo(1L);
        verify(redis, atLeastOnce()).expire(anyString(), eq(LandingEventBuffer.COUNTER_TTL));
    }

    @Test
    void flushHonoursMaxBatchSize() {
        LandingEventBuffer b = buffer(10, 2);
        for (int i = 0; i < 5; i++) {
            b.offer("landing_view", "1.1.1." + i);
        }
        b.flushOnce();
        assertThat(b.size()).isEqualTo(3);
        assertThat(counterFor("landing_view")).isEqualTo(2L);
    }

    @Test
    void overflowDropsOldestAndCounts() {
        LandingEventBuffer b = buffer(2, 10);
        b.offer("sec_hero", "1.1.1.1");
        b.offer("landing_view", "1.1.1.1");
        b.offer("hero_try_click", "1.1.1.1");
        assertThat(b.size()).isEqualTo(2);
        assertThat(dropped()).isEqualTo(1.0);
        b.flushOnce();
        assertThat(counterFor("sec_hero")).isZero();
        assertThat(counterFor("hero_try_click")).isEqualTo(1L);
    }

    @Test
    void ipLidAppliedAtFlushTime() {
        LandingEventBuffer b = buffer(1000, 1000);
        for (int i = 0; i < 305; i++) {
            b.offer("landing_view", "9.9.9.9");
        }
        b.flushOnce();
        assertThat(counterFor("landing_view")).isEqualTo(300L);
    }

    @Test
    void redisDownRequeuesAndRetriesNextTick() {
        LandingEventBuffer b = buffer(10, 10);
        b.offer("landing_view", "1.1.1.1");
        b.offer("hero_try_click", "2.2.2.2");
        when(ops.increment(anyString(), anyLong())).thenThrow(new IllegalStateException("redis down"));
        assertThat(b.flushOnce()).isFalse();
        assertThat(b.size()).isEqualTo(2);
        assertThat(dropped()).isZero();

        doAnswer(inv -> store.merge(inv.<String>getArgument(0), inv.<Long>getArgument(1), Long::sum))
            .when(ops).increment(anyString(), anyLong());
        assertThat(b.flushOnce()).isTrue();
        assertThat(b.size()).isZero();
        assertThat(counterFor("landing_view")).isEqualTo(1L);
    }

    @Test
    void redisDownMemoryStaysCapped() {
        LandingEventBuffer b = buffer(3, 10);
        when(ops.increment(anyString(), anyLong())).thenThrow(new IllegalStateException("redis down"));
        for (int i = 0; i < 10; i++) {
            b.offer("landing_view", "1.1.1.1");
            b.flushOnce();
        }
        assertThat(b.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void shutdownFlushDrainsEverythingAcrossBatches() {
        LandingEventBuffer b = buffer(100, 2);
        for (int i = 0; i < 7; i++) {
            b.offer("landing_view", "1.1.1." + i);
        }
        b.shutdownFlush();
        assertThat(b.size()).isZero();
        assertThat(counterFor("landing_view")).isEqualTo(7L);
    }

    @Test
    void shutdownFlushAgainstDeadRedisTerminates() {
        LandingEventBuffer b = buffer(100, 2);
        b.offer("landing_view", "1.1.1.1");
        when(ops.increment(anyString(), anyLong())).thenThrow(new IllegalStateException("redis down"));
        b.shutdownFlush();
        assertThat(b.size()).isEqualTo(1);
    }

    @Test
    void absentRedisDiscardsBatchFailOpen() {
        when(provider.getIfAvailable()).thenReturn(null);
        LandingEventBuffer b = buffer(10, 10);
        b.offer("landing_view", "1.1.1.1");
        assertThat(b.flushOnce()).isTrue();
        assertThat(b.size()).isZero();
    }
}
