package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Covers {@link PerUserBudgetService}'s P1 Definition of Done: the per-user
 * dollar gate blocks at/over cap and allows under it, per-modality caps are
 * independent (video excluded by its 0% default), spend is recorded in
 * micro-cents with a one-shot window expiry, any Redis error fails OPEN (never
 * blocks a turn), and the master switch disables metering. {@code configCacheMs=0}
 * so every call re-reads the (mocked) settings fresh.
 */
class PerUserBudgetServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private PlatformSettingsService settings;
    private LlmPricingCatalog pricingCatalog;
    private TenantLimits tenantLimits;
    private PerUserBudgetService service;

    private static final String TENANT = "tenant-a";
    private static final String USER = "user-1";
    // daily-usd 0.15, text 100% => 0.15 * 1e8 micro-cents.
    private static final long TEXT_CAP_MICRO_CENTS = 15_000_000L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        settings = mock(PlatformSettingsService.class);
        pricingCatalog = mock(LlmPricingCatalog.class);
        tenantLimits = mock(TenantLimits.class);
        // FREE by default (multiplier 1) so every pre-existing assertion below
        // (all computed against the unscaled global default) keeps holding.
        when(tenantLimits.plan(anyString())).thenReturn(TenantPlan.FREE);
        // Return each getter's own supplied default -> the effective config is the in-code defaults.
        when(settings.getString(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        service = new PerUserBudgetService(redisTemplate, settings, pricingCatalog, 0L, tenantLimits, 2, 3,
            mock(ObjectProvider.class));
    }

    @Test
    void allowsWhenUnderCap() {
        when(valueOps.get(anyString())).thenReturn(null); // nothing spent yet

        assertThatCode(() -> service.checkBudget(TENANT, USER, Modality.TEXT)).doesNotThrowAnyException();
    }

    @Test
    void blocksWhenAtOrOverCap() {
        // Spend counter at cap, no paid top-up credit (credit key -> null).
        when(valueOps.get(anyString())).thenAnswer(inv ->
            ((String) inv.getArgument(0)).contains("credit") ? null : Long.toString(TEXT_CAP_MICRO_CENTS));

        // P2: the per-user gate now throws the more-specific, payable subtype,
        // which is still a TenantLimitExceededException (so every existing
        // catch-site keeps working) but maps to 402, not 403.
        assertThatThrownBy(() -> service.checkBudget(TENANT, USER, Modality.TEXT))
            .isInstanceOf(TenantLimitExceededException.class)
            .isInstanceOf(PerUserBudgetExhaustedException.class);
    }

    @Test
    void blocksAnMcpGrantSubjectKeyLikeAnyOtherUser() {
        // OpenAI-compat /v1 callers authenticated by a vmcp_ grant key are metered under the synthetic
        // subject "mcp-external:<grantId>" (set by McpApiKeyAuthFilter); the service keys on any string.
        String grantSubject = "mcp-external:" + java.util.UUID.randomUUID();
        when(valueOps.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return key.contains("credit") || !key.contains(grantSubject) ? null : Long.toString(TEXT_CAP_MICRO_CENTS);
        });

        assertThatThrownBy(() -> service.checkBudget(TENANT, grantSubject, Modality.TEXT))
            .isInstanceOf(PerUserBudgetExhaustedException.class);
        // A different caller in the same tenant is unaffected: the grant has its own counter.
        assertThatCode(() -> service.checkBudget(TENANT, USER, Modality.TEXT)).doesNotThrowAnyException();
    }

    @Test
    void exhaustedExceptionCarriesModalityCapSpent() {
        when(valueOps.get(anyString())).thenAnswer(inv ->
            ((String) inv.getArgument(0)).contains("credit") ? null : Long.toString(TEXT_CAP_MICRO_CENTS));

        assertThatThrownBy(() -> service.checkBudget(TENANT, USER, Modality.TEXT))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(PerUserBudgetExhaustedException.class))
            .satisfies(ex -> {
                assertThat(ex.getModality()).isEqualTo("text");
                assertThat(ex.getCapUsd()).isEqualByComparingTo("0.15");
                assertThat(ex.getSpentUsd()).isEqualByComparingTo("0.15");
            });
    }

    @Test
    void videoIsExcludedByDefault() {
        // video split-pct default 0 => cap 0 => any usage (even 0 spent) is blocked.
        when(valueOps.get(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.checkBudget(TENANT, USER, Modality.VIDEO))
            .isInstanceOf(TenantLimitExceededException.class);
    }

    @Test
    void perModalityCapsAreIndependent() {
        // Text counter is exhausted, image counter empty - image must still be allowed.
        when(valueOps.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            // Only the TEXT SPEND counter is exhausted; credit counters and the
            // image counter are empty.
            return (key.contains(":text:") && !key.contains("credit")) ? Long.toString(TEXT_CAP_MICRO_CENTS) : null;
        });

        assertThatThrownBy(() -> service.checkBudget(TENANT, USER, Modality.TEXT))
            .isInstanceOf(TenantLimitExceededException.class);
        assertThatCode(() -> service.checkBudget(TENANT, USER, Modality.IMAGE)).doesNotThrowAnyException();
    }

    @Test
    void recordSpendConvertsUsdToMicroCentsAndSetsExpiryOnFirstWrite() {
        // $0.01 -> 1,000,000 micro-cents. increment returns exactly that => first write => expiry set.
        when(valueOps.increment(anyString(), eq(1_000_000L))).thenReturn(1_000_000L);

        service.recordSpend(TENANT, USER, Modality.TEXT, new BigDecimal("0.01"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).increment(keyCaptor.capture(), eq(1_000_000L));
        assertThat(keyCaptor.getValue()).contains(":text:").contains(TENANT).contains(USER);
        verify(redisTemplate).expire(anyString(), eq(Duration.ofSeconds(86_400)));
    }

    @Test
    void recordSpendDoesNotResetExpiryOnSubsequentWrite() {
        // increment returns a cumulative value != the amount added => not the first write.
        when(valueOps.increment(anyString(), eq(1_000_000L))).thenReturn(5_000_000L);

        service.recordSpend(TENANT, USER, Modality.TEXT, new BigDecimal("0.01"));

        verify(redisTemplate, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void recordSpendIgnoresNonPositiveCost() {
        service.recordSpend(TENANT, USER, Modality.TEXT, BigDecimal.ZERO);
        service.recordSpend(TENANT, USER, Modality.TEXT, new BigDecimal("-0.5"));

        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void checkBudgetFailsOpenOnRedisError() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> service.checkBudget(TENANT, USER, Modality.TEXT)).doesNotThrowAnyException();
    }

    @Test
    void recordSpendForCallUsesPricingCatalog() {
        when(pricingCatalog.estimateCostUsd("claude-x", 100L, 50L)).thenReturn(new BigDecimal("0.02"));
        when(valueOps.increment(anyString(), eq(2_000_000L))).thenReturn(2_000_000L);

        service.recordSpendForCall(TENANT, USER, Modality.TEXT, "claude-x", 100L, 50L);

        verify(valueOps).increment(anyString(), eq(2_000_000L));
    }

    @Test
    void recordSpendForCallFallsBackWhenCatalogCannotPrice() {
        // Catalog returns null for an unknown hosted model -> fallback 0.00015/1k * 2000 tokens = 0.0003 USD = 30,000 micro-cents.
        when(pricingCatalog.estimateCostUsd("groq-unknown", 1000L, 1000L)).thenReturn(null);
        when(valueOps.increment(anyString(), eq(30_000L))).thenReturn(30_000L);

        service.recordSpendForCall(TENANT, USER, Modality.TEXT, "groq-unknown", 1000L, 1000L);

        verify(valueOps).increment(anyString(), eq(30_000L));
    }

    @Test
    void disabledMasterSwitchSkipsMetering() {
        when(settings.getString(eq(PerUserBudgetService.KEY_ENABLED), anyString())).thenReturn("false");
        // Even an exhausted counter must not block when the switch is off.
        when(valueOps.get(anyString())).thenReturn(Long.toString(TEXT_CAP_MICRO_CENTS));

        assertThatCode(() -> service.checkBudget(TENANT, USER, Modality.TEXT)).doesNotThrowAnyException();
        service.recordSpend(TENANT, USER, Modality.TEXT, new BigDecimal("0.01"));
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void paidTopUpCreditRaisesEffectiveCapAndAllows() {
        // Spend counter at the base cap, but a $0.05 (5,000,000 micro-cents)
        // top-up credit is live -> effective cap 20M, spent 15M -> allowed.
        when(valueOps.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.contains("credit") && key.contains(":text:")) {
                return "5000000";
            }
            return (key.contains(":text:")) ? Long.toString(TEXT_CAP_MICRO_CENTS) : null;
        });

        assertThatCode(() -> service.checkBudget(TENANT, USER, Modality.TEXT)).doesNotThrowAnyException();
    }

    @Test
    void creditTopUpIncrementsCreditCounterAndAlignsExpiry() {
        // $0.01 -> 1,000,000 micro-cents onto the credit counter; no spend
        // window in progress (getExpire -> null) so it falls back to a full window.
        service.creditTopUp(TENANT, USER, Modality.TEXT, new BigDecimal("0.01"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).increment(keyCaptor.capture(), eq(1_000_000L));
        assertThat(keyCaptor.getValue()).contains("credit").contains(":text:").contains(TENANT).contains(USER);
        verify(redisTemplate).expire(keyCaptor.getValue(), Duration.ofSeconds(86_400));
    }

    @Test
    void creditTopUpIgnoresNonPositiveAmounts() {
        service.creditTopUp(TENANT, USER, Modality.TEXT, BigDecimal.ZERO);
        service.creditTopUp(TENANT, USER, Modality.TEXT, new BigDecimal("-1.00"));
        service.creditTopUp(TENANT, USER, Modality.TEXT, null);

        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void statusReportsEffectiveCapSpentAndRemaining() {
        // Base cap 15M + 5M credit = 20M effective; 15M spent => 5M ($0.05) remaining.
        when(valueOps.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.contains("credit") && key.contains(":text:")) {
                return "5000000";
            }
            return key.contains(":text:") ? Long.toString(TEXT_CAP_MICRO_CENTS) : null;
        });

        PerUserBudgetService.BudgetStatus status = service.status(TENANT, USER, Modality.TEXT);

        assertThat(status.modality()).isEqualTo("text");
        assertThat(status.capUsd()).isEqualByComparingTo("0.20");
        assertThat(status.spentUsd()).isEqualByComparingTo("0.15");
        assertThat(status.remainingUsd()).isEqualByComparingTo("0.05");
    }

    @Test
    void currentGlobalConfigReportsCapsAndDefaults() {
        PerUserBudgetService.BudgetConfig config = service.currentGlobalConfig();

        assertThat(config.enabled()).isTrue();
        assertThat(config.dailyUsd()).isEqualByComparingTo("0.15");
        assertThat(config.windowSeconds()).isEqualTo(86_400);
        assertThat(config.capMicroCents(Modality.TEXT)).isEqualTo(TEXT_CAP_MICRO_CENTS);
        assertThat(config.capMicroCents(Modality.VIDEO)).isZero();
    }

    // ---- per-plan tiering (§4 precedence: per-plan default) ----------------

    @Test
    void premiumTenantGetsDoubleTheFreeDailyCap() {
        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        // Under the doubled (0.30) cap at 15M spent -> allowed.
        when(valueOps.get(anyString())).thenAnswer(inv ->
            ((String) inv.getArgument(0)).contains("credit") ? null : Long.toString(TEXT_CAP_MICRO_CENTS));

        assertThatCode(() -> service.checkBudget(TENANT, USER, Modality.TEXT)).doesNotThrowAnyException();

        PerUserBudgetService.BudgetStatus status = service.status(TENANT, USER, Modality.TEXT);
        assertThat(status.capUsd()).isEqualByComparingTo("0.30");
    }

    @Test
    void unlimitedTenantGetsTripleTheFreeDailyCap() {
        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.UNLIMITED);

        PerUserBudgetService.BudgetStatus status = service.status(TENANT, USER, Modality.TEXT);
        assertThat(status.capUsd()).isEqualByComparingTo("0.45");
    }

    @Test
    void premiumTenantIsBlockedOnlyAfterExceedingTheScaledCap() {
        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        // Scaled cap is 0.30 (30,000,000 micro-cents); spend already at that ceiling -> blocked.
        when(valueOps.get(anyString())).thenAnswer(inv ->
            ((String) inv.getArgument(0)).contains("credit") ? null : Long.toString(2 * TEXT_CAP_MICRO_CENTS));

        assertThatThrownBy(() -> service.checkBudget(TENANT, USER, Modality.TEXT))
            .isInstanceOf(PerUserBudgetExhaustedException.class);
    }

    @Test
    void freeTenantIsUnaffectedByTheMultiplier() {
        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.FREE);

        PerUserBudgetService.BudgetStatus status = service.status(TENANT, USER, Modality.TEXT);
        assertThat(status.capUsd()).isEqualByComparingTo("0.15");
    }

    @Test
    void planLookupFailureFailsOpenToTheUnscaledGlobalCap() {
        when(tenantLimits.plan(TENANT)).thenThrow(new RuntimeException("tenant lookup failed"));

        PerUserBudgetService.BudgetStatus status = service.status(TENANT, USER, Modality.TEXT);
        assertThat(status.capUsd()).isEqualByComparingTo("0.15");
    }

    // ---- TTL-loss self-healing (2026-07-29 fix) -----------------------------
    // A counter whose INCRBY landed but whose EXPIRE never did (process death
    // mid-write, or a Redis error on just the second call) is left with a
    // value but no TTL - Redis TTL reports -1 for "exists, no expiry" (vs -2
    // for "doesn't exist"). Without a repair path that counter never resets,
    // so checkBudget would block the user forever. These tests lock in that
    // the read path (checkBudget/status) detects and repairs exactly that
    // shape, and does nothing to a counter that already has a normal TTL.

    @Test
    void checkBudgetRepairsASpendCounterThatLostItsTtl() {
        // Spend counter is AT cap (would otherwise block forever) and reports
        // TTL -1 (no expiry) - the read path must notice and set one.
        when(valueOps.get(anyString())).thenAnswer(inv ->
            ((String) inv.getArgument(0)).contains("credit") ? null : Long.toString(TEXT_CAP_MICRO_CENTS));
        when(redisTemplate.getExpire(org.mockito.ArgumentMatchers.contains(":text:"))).thenReturn(-1L);

        assertThatThrownBy(() -> service.checkBudget(TENANT, USER, Modality.TEXT))
            .isInstanceOf(PerUserBudgetExhaustedException.class);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).expire(keyCaptor.capture(), eq(Duration.ofSeconds(86_400)));
        assertThat(keyCaptor.getValue()).contains(":text:").doesNotContain("credit");
    }

    @Test
    void checkBudgetLeavesANormalTtlAlone() {
        // Counter has a real, positive TTL already - repair must be a no-op.
        when(valueOps.get(anyString())).thenAnswer(inv ->
            ((String) inv.getArgument(0)).contains("credit") ? null : Long.toString(TEXT_CAP_MICRO_CENTS));
        when(redisTemplate.getExpire(anyString())).thenReturn(43_200L);

        assertThatThrownBy(() -> service.checkBudget(TENANT, USER, Modality.TEXT))
            .isInstanceOf(PerUserBudgetExhaustedException.class);

        verify(redisTemplate, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void checkBudgetDoesNotRepairAKeyThatDoesNotExist() {
        // Nothing spent yet -> GET returns null -> repair must never even look
        // at TTL for an absent key (getExpire would report -2, not -1, but the
        // read path should short-circuit before reaching it at all).
        when(valueOps.get(anyString())).thenReturn(null);

        assertThatCode(() -> service.checkBudget(TENANT, USER, Modality.TEXT)).doesNotThrowAnyException();

        verify(redisTemplate, never()).getExpire(anyString());
        verify(redisTemplate, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void statusRepairsACreditCounterThatLostItsTtl() {
        // Credit counter has a value but TTL -1 - unbounded top-up credit that
        // never expires is the opposite-direction cost-control bug; repair
        // must fix this counter the same way it fixes the spend counter.
        when(valueOps.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (key.contains("credit") && key.contains(":text:")) {
                return "5000000";
            }
            return null;
        });
        when(redisTemplate.getExpire(org.mockito.ArgumentMatchers.contains("credit"))).thenReturn(-1L);

        service.status(TENANT, USER, Modality.TEXT);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).expire(keyCaptor.capture(), eq(Duration.ofSeconds(86_400)));
        assertThat(keyCaptor.getValue()).contains("credit").contains(":text:");
    }

    // ---- checkAndReserveBudget / reconcileSpendReservationForCall -----------
    // 2026-08-08 usage-counter audit fix: checkBudget above is a pure READ,
    // with no atomic link to the eventual recordSpend write - a burst of
    // concurrent calls near the cap could all read "under budget" before any
    // of their real spend landed. These atomically reserve a conservative
    // estimate FIRST (priced via the fallback per-1k-token rate, since the
    // model that will actually serve the turn isn't chosen yet), then compare
    // the resulting total against the cap - undoing before throwing if it
    // would go over.

    @Test
    void checkAndReserveBudgetReservesAtTheFallbackRateAndAllowsWhenUnderCap() {
        // 1000 tokens * fallback $0.00015/1k = $0.00015 = 15,000 micro-cents.
        when(valueOps.increment(anyString(), eq(15_000L))).thenReturn(15_000L);

        long reserved = service.checkAndReserveBudget(TENANT, USER, Modality.TEXT, 1000L, TenantPlan.FREE);

        assertThat(reserved).isEqualTo(15_000L);
        verify(valueOps).increment(org.mockito.ArgumentMatchers.contains(":text:"), eq(15_000L));
    }

    @Test
    void checkAndReserveBudgetUndoesAndThrowsWhenItWouldExceedTheCap() {
        when(valueOps.increment(anyString(), eq(15_000L))).thenReturn(TEXT_CAP_MICRO_CENTS + 5_000L);

        assertThatThrownBy(() -> service.checkAndReserveBudget(TENANT, USER, Modality.TEXT, 1000L, TenantPlan.FREE))
            .isInstanceOf(PerUserBudgetExhaustedException.class);

        verify(valueOps).increment(anyString(), eq(-15_000L));
    }

    @Test
    void checkAndReserveBudgetReturnsZeroWhenMasterSwitchIsOff() {
        when(settings.getString(eq(PerUserBudgetService.KEY_ENABLED), anyString())).thenReturn("false");

        long reserved = service.checkAndReserveBudget(TENANT, USER, Modality.TEXT, 1000L, TenantPlan.FREE);

        assertThat(reserved).isZero();
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void checkAndReserveBudgetFailsOpenOnRedisError() {
        when(valueOps.increment(anyString(), anyLong())).thenThrow(new RuntimeException("redis down"));

        long reserved = service.checkAndReserveBudget(TENANT, USER, Modality.TEXT, 1000L, TenantPlan.FREE);

        assertThat(reserved).isZero();
    }

    @Test
    void checkAndReserveBudgetReservesNothingForZeroEstimatedTokens() {
        long reserved = service.checkAndReserveBudget(TENANT, USER, Modality.TEXT, 0L, TenantPlan.FREE);

        assertThat(reserved).isZero();
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void reconcileAddsTheSignedDeltaWhenAReservationExists() {
        // $0.0002 = 20,000 micro-cents actual cost; reserved 15,000 -> delta +5,000.
        when(pricingCatalog.estimateCostUsd("claude-x", 100L, 50L)).thenReturn(new BigDecimal("0.0002"));

        service.reconcileSpendReservationForCall(TENANT, USER, Modality.TEXT, 15_000L, "claude-x", 100L, 50L, TenantPlan.FREE);

        verify(valueOps).increment(anyString(), eq(5_000L));
    }

    @Test
    void reconcileReleasesTheFullReservationWhenThereIsNoUsageToReport() {
        service.reconcileSpendReservationForCall(TENANT, USER, Modality.TEXT, 15_000L, null, null, null, TenantPlan.FREE);

        verify(valueOps).increment(anyString(), eq(-15_000L));
    }

    @Test
    void reconcileIsANoOpWhenActualCostExactlyMatchesTheReservation() {
        when(pricingCatalog.estimateCostUsd("claude-x", 100L, 50L)).thenReturn(new BigDecimal("0.00015"));

        service.reconcileSpendReservationForCall(TENANT, USER, Modality.TEXT, 15_000L, "claude-x", 100L, 50L, TenantPlan.FREE);

        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void reconcileFallsBackToPlainAdditiveRecordSpendForCallWhenNothingWasReserved() {
        // reservedMicroCents <= 0 (master switch was off, nothing to price, a
        // Redis outage at reservation time, or a caller that never reserved
        // at all) - real spend must still be recorded via the plain additive
        // path, not silently dropped.
        when(pricingCatalog.estimateCostUsd("claude-x", 100L, 50L)).thenReturn(new BigDecimal("0.0002"));

        service.reconcileSpendReservationForCall(TENANT, USER, Modality.TEXT, 0L, "claude-x", 100L, 50L, TenantPlan.FREE);

        verify(valueOps).increment(anyString(), eq(20_000L));
    }

    @Test
    void releaseSpendReservationIsAConvenienceForAFullRelease() {
        service.releaseSpendReservation(TENANT, USER, Modality.TEXT, 15_000L, TenantPlan.FREE);

        verify(valueOps).increment(anyString(), eq(-15_000L));
    }

    // ---- reconcileSpendReservationForUsage / recordSpendForUsage ------------
    // 2026-10-05 cost-tracing audit: the Usage-carrying twins, so the tracking
    // advisor can hand the service the same merged stream usage llm_call_log
    // sees. A generic Usage mock exposes no provider-specific cache/reasoning
    // fields, so the billable-cache and reasoning arguments to the 7-arg
    // estimate are null here - the extraction itself is pinned by
    // CacheTokenAccountingTest / ReasoningTokenAccountingTest in core/chat.

    @Test
    void reconcileForUsagePricesViaTheFullShapeOverloadAndAppliesTheDelta() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(50);
        // The 7-arg estimate - the overload that also carries the billable
        // cache buckets and out-of-completion reasoning tokens - is what the
        // usage path must call, not the prompt/completion-only 3-arg one.
        when(pricingCatalog.estimateCostUsd(null, "claude-x", 100L, 50L, null, null, null))
            .thenReturn(new BigDecimal("0.0002"));

        service.reconcileSpendReservationForUsage(TENANT, USER, Modality.TEXT, 15_000L, "claude-x", usage, TenantPlan.FREE);

        // $0.0002 = 20,000 micro-cents actual; reserved 15,000 -> +5,000 delta.
        verify(valueOps).increment(anyString(), eq(5_000L));
    }

    @Test
    void reconcileForUsageReleasesTheFullReservationOnNullUsage() {
        service.reconcileSpendReservationForUsage(TENANT, USER, Modality.TEXT, 15_000L, "claude-x", null, TenantPlan.FREE);

        verify(valueOps).increment(anyString(), eq(-15_000L));
    }

    @Test
    void reconcileForUsageFallsBackToPlainAdditiveRecordWhenNothingWasReserved() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(50);
        when(pricingCatalog.estimateCostUsd(null, "claude-x", 100L, 50L, null, null, null))
            .thenReturn(new BigDecimal("0.0002"));

        service.reconcileSpendReservationForUsage(TENANT, USER, Modality.TEXT, 0L, "claude-x", usage, TenantPlan.FREE);

        verify(valueOps).increment(anyString(), eq(20_000L));
    }

    @Test
    void recordSpendForUsageFallsBackToTotalTokensWhenTheCatalogCannotPrice() {
        // Catalog null -> fallback 0.00015/1k * usage total (2000) = $0.0003 = 30,000 micro-cents.
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(1000);
        when(usage.getCompletionTokens()).thenReturn(1000);
        when(usage.getTotalTokens()).thenReturn(2000);
        when(pricingCatalog.estimateCostUsd(null, "groq-unknown", 1000L, 1000L, null, null, null)).thenReturn(null);
        when(valueOps.increment(anyString(), eq(30_000L))).thenReturn(30_000L);

        service.recordSpendForUsage(TENANT, USER, Modality.TEXT, "groq-unknown", usage, TenantPlan.FREE);

        verify(valueOps).increment(anyString(), eq(30_000L));
    }
}
