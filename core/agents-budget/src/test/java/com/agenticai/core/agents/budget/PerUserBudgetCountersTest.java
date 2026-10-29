package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService.BudgetConfig;
import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Covers {@link PerUserBudgetCounters} directly: the spend/credit key shapes (including the
 * anonymous-user bucket), fail-open counter reads, the first-write expiry idiom, TTL repair on the
 * read path, the budget-exhausted message wording and the once-per-window notification bell. The
 * same fail-open contract {@code PerUserBudgetServiceTest} asserts through the service is pinned
 * here at the unit that implements it.
 */
class PerUserBudgetCountersTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ObjectProvider<NotificationService> notificationProvider;
    private NotificationService notificationService;
    private PerUserBudgetCounters counters;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        notificationProvider = mock(ObjectProvider.class);
        notificationService = mock(NotificationService.class);
        counters = new PerUserBudgetCounters(redisTemplate, notificationProvider);
    }

    private static BudgetConfig config() {
        Map<Modality, Integer> split = new EnumMap<>(Modality.class);
        split.put(Modality.TEXT, 100);
        return new BudgetConfig(true, new BigDecimal("0.15"), 86_400, split, new BigDecimal("0.00015"));
    }

    // ---- key shapes --------------------------------------------------------

    @Test
    void spendAndCreditKeysUseDistinctNamespaces() {
        assertThat(PerUserBudgetCounters.key("t1", "u1", Modality.TEXT))
                .isEqualTo("agentic:per-user-budget:text:t1:u1");
        assertThat(PerUserBudgetCounters.creditKey("t1", "u1", Modality.VOICE))
                .isEqualTo("agentic:per-user-budget-credit:voice:t1:u1");
        assertThat(PerUserBudgetCounters.key("t1", "u1", Modality.TEXT))
                .isNotEqualTo(PerUserBudgetCounters.creditKey("t1", "u1", Modality.TEXT));
    }

    @Test
    void blankOrMissingUserMetersUnderTheAnonymousBucket() {
        assertThat(PerUserBudgetCounters.key("t1", null, Modality.IMAGE))
                .isEqualTo("agentic:per-user-budget:image:t1:_anon");
        assertThat(PerUserBudgetCounters.key("t1", "  ", Modality.IMAGE))
                .isEqualTo("agentic:per-user-budget:image:t1:_anon");
        assertThat(PerUserBudgetCounters.creditKey("t1", "", Modality.VIDEO))
                .isEqualTo("agentic:per-user-budget-credit:video:t1:_anon");
    }

    // ---- counter reads ------------------------------------------------------

    @Test
    void absentSpendCounterReadsAsZero() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertThat(counters.currentSpendMicroCents("t1", "u1", Modality.TEXT, 86_400)).isZero();
        verify(redisTemplate, never()).getExpire(anyString());
    }

    @Test
    void spendCounterParsesTheStoredValueAndRepairsALostTtl() {
        when(valueOps.get(anyString())).thenReturn(" 15000000 ");
        when(redisTemplate.getExpire(anyString())).thenReturn(-1L);

        assertThat(counters.currentSpendMicroCents("t1", "u1", Modality.TEXT, 86_400))
                .isEqualTo(15_000_000L);
        verify(redisTemplate).expire(anyString(), eq(Duration.ofSeconds(86_400)));
    }

    @Test
    void corruptSpendCounterFailsOpenToZero() {
        when(valueOps.get(anyString())).thenReturn("not-a-number");
        assertThat(counters.currentSpendMicroCents("t1", "u1", Modality.TEXT, 86_400)).isZero();
    }

    @Test
    void aRedisOutageFailsOpenToZeroSpend() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertThat(counters.currentSpendMicroCents("t1", "u1", Modality.TEXT, 86_400)).isZero();
    }

    @Test
    void creditCounterReadsAndFailsOpenTheSameWay() {
        when(valueOps.get(anyString())).thenReturn("5000000");
        assertThat(counters.currentCreditMicroCents("t1", "u1", Modality.TEXT, 86_400))
                .isEqualTo(5_000_000L);

        when(valueOps.get(anyString())).thenReturn("junk");
        assertThat(counters.currentCreditMicroCents("t1", "u1", Modality.TEXT, 86_400)).isZero();

        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertThat(counters.currentCreditMicroCents("t1", "u1", Modality.TEXT, 86_400)).isZero();
    }

    // ---- counter writes -----------------------------------------------------

    @Test
    void incrementSetsTheWindowExpiryOnlyOnTheFirstWrite() {
        // cumulative == amount added => first write this window.
        when(valueOps.increment("k", 5L)).thenReturn(5L);
        counters.incrementBy("k", Duration.ofSeconds(60), 5L);
        verify(redisTemplate).expire("k", Duration.ofSeconds(60));

        // cumulative != amount added => window already live.
        when(valueOps.increment("k", 5L)).thenReturn(9L);
        counters.incrementBy("k", Duration.ofSeconds(60), 5L);
        verify(redisTemplate).expire("k", Duration.ofSeconds(60)); // still exactly once
    }

    @Test
    void incrementByAndGetReturnsTheCumulativeOrNullOnOutage() {
        when(valueOps.increment("k", 7L)).thenReturn(42L);
        assertThat(counters.incrementByAndGet("k", Duration.ofSeconds(60), 7L)).isEqualTo(42L);

        when(valueOps.increment("k", 7L)).thenThrow(new RuntimeException("redis down"));
        assertThat(counters.incrementByAndGet("k", Duration.ofSeconds(60), 7L)).isNull();
    }

    // ---- helpers ------------------------------------------------------------

    @Test
    void microCentsConvertBackToUsdAtFourPlaces() {
        assertThat(PerUserBudgetCounters.microCentsToUsd(15_000_000L)).isEqualByComparingTo("0.15");
        assertThat(PerUserBudgetCounters.microCentsToUsd(1L)).isEqualByComparingTo("0.0000");
    }

    @Test
    void remainingWindowSecondsReportsOnlyPositiveTtls() {
        when(redisTemplate.getExpire("k")).thenReturn(120L, -1L, null);
        assertThat(counters.remainingWindowSeconds("k")).isEqualTo(120L);
        assertThat(counters.remainingWindowSeconds("k")).isNull();
        assertThat(counters.remainingWindowSeconds("k")).isNull();

        when(redisTemplate.getExpire("k")).thenThrow(new RuntimeException("down"));
        assertThat(counters.remainingWindowSeconds("k")).isNull();
    }

    @Test
    void zeroCapMessageIsThePayPerUseWording() {
        String msg = counters.budgetMessage(config(), Modality.VIDEO, 0L, "k");
        assertThat(msg).contains("video").contains("pay-per-use");
    }

    @Test
    void budgetMessageIncludesTheCapAndAHumanizedResetWhenRedisKnowsTheTtl() {
        when(redisTemplate.getExpire("k")).thenReturn(5_400L); // 1h 30m
        String msg = counters.budgetMessage(config(), Modality.TEXT, 15_000_000L, "k");
        assertThat(msg)
                .contains("Daily text budget reached")
                .contains("$0.1500")
                .contains("resets in about 1h 30m");

        when(redisTemplate.getExpire("k")).thenReturn(90L);
        assertThat(counters.budgetMessage(config(), Modality.TEXT, 15_000_000L, "k"))
                .contains("resets in about 1m");

        when(redisTemplate.getExpire("k")).thenReturn(30L);
        assertThat(counters.budgetMessage(config(), Modality.TEXT, 15_000_000L, "k"))
                .contains("resets in about 30s");
    }

    @Test
    void budgetMessageOmitsTheResetClauseWhenTheTtlIsUnknown() {
        when(redisTemplate.getExpire("k")).thenReturn(null);
        assertThat(counters.budgetMessage(config(), Modality.TEXT, 15_000_000L, "k"))
                .doesNotContain("resets in");

        when(redisTemplate.getExpire("k")).thenThrow(new RuntimeException("down"));
        assertThat(counters.budgetMessage(config(), Modality.TEXT, 15_000_000L, "k"))
                .doesNotContain("resets in");
    }

    // ---- the once-per-window bell --------------------------------------------

    @Test
    void notifyFiresOnlyForTheFirstExhaustionInAWindow() {
        when(valueOps.setIfAbsent(eq("k:notified"), eq("1"), any(Duration.class)))
                .thenReturn(true, false);
        when(notificationProvider.getIfAvailable()).thenReturn(notificationService);

        counters.notifyBudgetExhausted("k", 86_400, "t1", "u1", Modality.TEXT, 15_000_000L);
        counters.notifyBudgetExhausted("k", 86_400, "t1", "u1", Modality.TEXT, 15_000_000L);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(notificationService)
                .create(
                        eq(null),
                        eq(NotificationCategory.BILLING),
                        title.capture(),
                        anyString(),
                        eq(NotificationSeverity.WARNING),
                        eq(null),
                        eq(null));
        assertThat(title.getValue()).contains("text budget exhausted");
    }

    @Test
    void notifyIsQuietWhenNoNotificationServiceIsAvailable() {
        when(valueOps.setIfAbsent(eq("k:notified"), eq("1"), any(Duration.class))).thenReturn(true);
        when(notificationProvider.getIfAvailable()).thenReturn(null);

        counters.notifyBudgetExhausted("k", 86_400, "t1", "u1", Modality.TEXT, 15_000_000L);

        verify(notificationService, never())
                .create(any(), any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void notifyFailureNeverPropagates() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        // Must not throw - the bell is best-effort, the exception is the enforcement.
        counters.notifyBudgetExhausted("k", 86_400, "t1", "u1", Modality.TEXT, 15_000_000L);
    }
}
