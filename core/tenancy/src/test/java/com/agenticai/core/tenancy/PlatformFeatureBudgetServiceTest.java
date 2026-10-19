package com.agenticai.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Covers {@link PlatformFeatureBudgetService}'s contract: the master switch
 * gates every feature, each feature's own switch/cap is independent of every
 * other feature's, the shared master cap can still stop a feature under its
 * own cap, spend is recorded in micro-cents into BOTH buckets with a one-shot
 * window expiry, and any Redis error fails OPEN (never blocks a feature).
 * {@code configCacheMs=0} so every call re-reads the (mocked) settings fresh.
 *
 * <p>The reservation block (2026-09-28) backs the Redis counters with an
 * in-memory {@link AtomicLong} map so {@code INCRBY}'s atomic
 * return-the-new-total semantics are real - including under a genuinely
 * concurrent burst, which is the race {@code reserve} exists to close.
 */
class PlatformFeatureBudgetServiceTest {

    private static final String FEATURE = "error-explanation";
    // default-feature-daily-usd 0.25 => 0.25 * 1e8 micro-cents.
    private static final long FEATURE_CAP_MICRO_CENTS = 25_000_000L;
    // master-daily-usd 2.00 => 2.00 * 1e8 micro-cents.
    private static final long MASTER_CAP_MICRO_CENTS = 200_000_000L;

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private PlatformSettingsService settings;
    private PlatformFeatureBudgetService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        settings = mock(PlatformSettingsService.class);
        // Return each getter's own supplied default -> the effective config is the in-code defaults.
        when(settings.getString(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        service = new PlatformFeatureBudgetService(redisTemplate, settings, 0L);
    }

    @Test
    void allowsWhenUnderCap() {
        when(valueOps.get(anyString())).thenReturn(null); // nothing spent yet

        assertThat(service.checkBudget(FEATURE).allowed()).isTrue();
    }

    @Test
    void blocksWhenMasterSwitchOff() {
        when(settings.getString(eq(PlatformFeatureBudgetService.KEY_ENABLED), anyString())).thenReturn("false");

        PlatformFeatureBudgetService.BudgetDecision decision = service.checkBudget(FEATURE);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("smart-ai-disabled");
    }

    @Test
    void blocksWhenFeatureSwitchOff() {
        when(settings.getBoolean(eq("platform.smart-ai.feature." + FEATURE + ".enabled"), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(false);

        PlatformFeatureBudgetService.BudgetDecision decision = service.checkBudget(FEATURE);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("feature-disabled");
    }

    @Test
    void blocksWhenFeatureCapExhausted() {
        when(valueOps.get(anyString())).thenAnswer(inv ->
            ((String) inv.getArgument(0)).contains("feature:" + FEATURE) ? Long.toString(FEATURE_CAP_MICRO_CENTS) : null);

        PlatformFeatureBudgetService.BudgetDecision decision = service.checkBudget(FEATURE);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("feature-budget-exhausted");
    }

    @Test
    void blocksWhenMasterCapExhaustedEvenIfFeatureUnderCap() {
        when(valueOps.get(anyString())).thenAnswer(inv ->
            ((String) inv.getArgument(0)).contains("__master__") ? Long.toString(MASTER_CAP_MICRO_CENTS) : null);

        PlatformFeatureBudgetService.BudgetDecision decision = service.checkBudget(FEATURE);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("master-budget-exhausted");
    }

    @Test
    void featuresAreIndependent() {
        // "copilot" is exhausted; the FEATURE constant's own bucket and the master bucket are empty.
        when(valueOps.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return key.contains("feature:copilot") ? Long.toString(FEATURE_CAP_MICRO_CENTS) : null;
        });

        assertThat(service.checkBudget("copilot").allowed()).isFalse();
        assertThat(service.checkBudget(FEATURE).allowed()).isTrue();
    }

    @Test
    void recordSpendConvertsUsdToMicroCentsAndIncrementsBothBuckets() {
        // $0.01 -> 1,000,000 micro-cents. increment returns exactly that => first write => expiry set.
        when(valueOps.increment(anyString(), eq(1_000_000L))).thenReturn(1_000_000L);

        service.recordSpend(FEATURE, new BigDecimal("0.01"));

        verify(valueOps).increment(eq("agentic:smart-ai-budget:feature:" + FEATURE), eq(1_000_000L));
        verify(valueOps).increment(eq("agentic:smart-ai-budget:__master__"), eq(1_000_000L));
        verify(redisTemplate).expire(eq("agentic:smart-ai-budget:feature:" + FEATURE), eq(Duration.ofSeconds(86_400)));
        verify(redisTemplate).expire(eq("agentic:smart-ai-budget:__master__"), eq(Duration.ofSeconds(86_400)));
    }

    @Test
    void recordSpendIgnoresNullOrNonPositiveCost() {
        service.recordSpend(FEATURE, null);
        service.recordSpend(FEATURE, BigDecimal.ZERO);
        service.recordSpend(FEATURE, new BigDecimal("-1"));

        org.mockito.Mockito.verifyNoInteractions(valueOps);
    }

    @Test
    void failsOpenWhenRedisReadThrows() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(service.checkBudget(FEATURE).allowed()).isTrue();
    }

    @Test
    void failsOpenWhenRedisWriteThrows() {
        when(valueOps.increment(anyString(), org.mockito.ArgumentMatchers.anyLong()))
            .thenThrow(new RuntimeException("redis down"));

        org.assertj.core.api.Assertions.assertThatCode(() -> service.recordSpend(FEATURE, new BigDecimal("0.01")))
            .doesNotThrowAnyException();
    }

    // ---- atomic reservation --------------------------------------------------

    private static final String FEATURE_BUCKET = "agentic:smart-ai-budget:feature:" + FEATURE;
    private static final String MASTER_BUCKET = "agentic:smart-ai-budget:__master__";

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** Real INCRBY/GET semantics over an in-memory map (INCRBY is atomic per key, as in Redis). */
    private void withInMemoryCounters() {
        when(valueOps.increment(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv ->
            counters.computeIfAbsent(inv.getArgument(0), k -> new AtomicLong()).addAndGet(inv.<Long>getArgument(1)));
        when(valueOps.get(anyString())).thenAnswer(inv -> {
            AtomicLong v = counters.get(inv.<String>getArgument(0));
            return v == null ? null : Long.toString(v.get());
        });
    }

    private long counter(String key) {
        AtomicLong v = counters.get(key);
        return v == null ? 0L : v.get();
    }

    @Test
    void reserveHoldsTheEstimateInBothWindowsBeforeTheCall() {
        withInMemoryCounters();

        PlatformFeatureBudgetService.Reservation r = service.reserve(FEATURE, new BigDecimal("0.01"));

        assertThat(r.allowed()).isTrue();
        // Held BEFORE the call - this is what a concurrent caller now sees.
        assertThat(counter(FEATURE_BUCKET)).isEqualTo(1_000_000L);
        assertThat(counter(MASTER_BUCKET)).isEqualTo(1_000_000L);
        verify(redisTemplate).expire(eq(FEATURE_BUCKET), eq(Duration.ofSeconds(86_400)));
    }

    @Test
    void reserveRefusesAnEstimateThatWouldOvershootTheFeatureCapAndReservesNothing() {
        withInMemoryCounters();
        // Under the cap by $0.005 - the old read-only check would have said
        // "allowed" and let a $0.01 call overshoot.
        counters.put(FEATURE_BUCKET, new AtomicLong(FEATURE_CAP_MICRO_CENTS - 500_000L));

        PlatformFeatureBudgetService.Reservation r = service.reserve(FEATURE, new BigDecimal("0.01"));

        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).isEqualTo("feature-budget-exhausted");
        assertThat(r.decision().allowed()).isFalse();
        assertThat(counter(FEATURE_BUCKET)).isEqualTo(FEATURE_CAP_MICRO_CENTS - 500_000L);
        assertThat(counter(MASTER_BUCKET)).isZero();
    }

    @Test
    void masterCapRefusalUndoesTheFeatureReservationToo() {
        withInMemoryCounters();
        counters.put(MASTER_BUCKET, new AtomicLong(MASTER_CAP_MICRO_CENTS - 1L));

        PlatformFeatureBudgetService.Reservation r = service.reserve(FEATURE, new BigDecimal("0.01"));

        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).isEqualTo("master-budget-exhausted");
        assertThat(counter(FEATURE_BUCKET)).isZero();
        assertThat(counter(MASTER_BUCKET)).isEqualTo(MASTER_CAP_MICRO_CENTS - 1L);
    }

    @Test
    void settleReconcilesTheReservationToTheActualCostExactlyOnce() {
        withInMemoryCounters();
        PlatformFeatureBudgetService.Reservation r = service.reserve(FEATURE, new BigDecimal("0.01"));

        service.settle(r, new BigDecimal("0.004"));
        service.settle(r, new BigDecimal("0.004")); // second settle is a no-op
        service.release(r); // and so is a release after settle

        assertThat(counter(FEATURE_BUCKET)).isEqualTo(400_000L);
        assertThat(counter(MASTER_BUCKET)).isEqualTo(400_000L);
    }

    @Test
    void settleAboveTheEstimateRecordsTheFullActualCost() {
        withInMemoryCounters();
        PlatformFeatureBudgetService.Reservation r = service.reserve(FEATURE, new BigDecimal("0.001"));

        service.settle(r, new BigDecimal("0.003"));

        // Under-estimates are not a way to under-count: the counter ends at the actual.
        assertThat(counter(FEATURE_BUCKET)).isEqualTo(300_000L);
        assertThat(counter(MASTER_BUCKET)).isEqualTo(300_000L);
    }

    @Test
    void releaseRefundsAReservationWhoseCallNeverHappened() {
        withInMemoryCounters();
        PlatformFeatureBudgetService.Reservation r = service.reserve(FEATURE, new BigDecimal("0.01"));

        service.release(r);

        assertThat(counter(FEATURE_BUCKET)).isZero();
        assertThat(counter(MASTER_BUCKET)).isZero();
    }

    @Test
    void refusedReservationIsNeverSettled() {
        withInMemoryCounters();
        counters.put(FEATURE_BUCKET, new AtomicLong(FEATURE_CAP_MICRO_CENTS));
        PlatformFeatureBudgetService.Reservation r = service.reserve(FEATURE, new BigDecimal("0.01"));

        service.settle(r, new BigDecimal("0.01"));

        assertThat(counter(FEATURE_BUCKET)).isEqualTo(FEATURE_CAP_MICRO_CENTS);
    }

    @Test
    void missingEstimateStillGatesAtomicallyAtTheCap() {
        withInMemoryCounters();
        counters.put(FEATURE_BUCKET, new AtomicLong(FEATURE_CAP_MICRO_CENTS - 1L));
        // One micro-cent of headroom: a null estimate reserves exactly that and fits...
        assertThat(service.reserve(FEATURE, null).allowed()).isTrue();
        // ...and now the window is AT the cap, so the next one is refused -
        // checkBudget's ">= cap blocks" semantics, but atomic.
        assertThat(service.reserve(FEATURE, null).allowed()).isFalse();
    }

    @Test
    void concurrentBurstNeverOvershootsTheCap() throws Exception {
        withInMemoryCounters();
        // Cap $0.25, each call estimated at $0.05: exactly five fit. With the
        // old check-then-record pair all fifty would pass the check (nothing
        // is recorded until after the call) and spend $2.50.
        int callers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PlatformFeatureBudgetService.Reservation>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return service.reserve(FEATURE, new BigDecimal("0.05"));
                }));
            }
            go.countDown();
            int granted = 0;
            for (Future<PlatformFeatureBudgetService.Reservation> f : futures) {
                if (f.get(10, TimeUnit.SECONDS).allowed()) {
                    granted++;
                }
            }
            assertThat(granted).isEqualTo(5);
            assertThat(counter(FEATURE_BUCKET)).isEqualTo(FEATURE_CAP_MICRO_CENTS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reserveFailsOpenWhenRedisWriteThrows() {
        when(valueOps.increment(anyString(), org.mockito.ArgumentMatchers.anyLong()))
            .thenThrow(new RuntimeException("redis down"));

        PlatformFeatureBudgetService.Reservation r = service.reserve(FEATURE, new BigDecimal("0.01"));

        assertThat(r.allowed()).isTrue();
        org.assertj.core.api.Assertions.assertThatCode(() -> service.settle(r, new BigDecimal("0.01")))
            .doesNotThrowAnyException();
    }

    @Test
    void reserveHonoursTheMasterAndFeatureSwitchesWithoutTouchingRedis() {
        when(settings.getString(eq(PlatformFeatureBudgetService.KEY_ENABLED), anyString())).thenReturn("false");

        PlatformFeatureBudgetService.Reservation r = service.reserve(FEATURE, new BigDecimal("0.01"));

        assertThat(r.allowed()).isFalse();
        assertThat(r.reason()).isEqualTo("smart-ai-disabled");
        org.mockito.Mockito.verifyNoInteractions(valueOps);
    }

    @Test
    void updateGlobalConfigUpsertsProvidedFieldsOnly() {
        service.updateGlobalConfig(false, new BigDecimal("5.00"), null, null, "admin@staging.example.com");

        verify(settings).update(PlatformFeatureBudgetService.KEY_ENABLED, "false", "admin@staging.example.com");
        verify(settings).update(PlatformFeatureBudgetService.KEY_MASTER_DAILY_USD, "5.00", "admin@staging.example.com");
        verify(settings, org.mockito.Mockito.never()).update(eq(PlatformFeatureBudgetService.KEY_WINDOW_SECONDS), anyString(), anyString());
        verify(settings, org.mockito.Mockito.never())
            .update(eq(PlatformFeatureBudgetService.KEY_DEFAULT_FEATURE_DAILY_USD), anyString(), anyString());
    }
}
