package com.agenticai.core.chat.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.LlmSpendReader;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@link LlmGatewayBudgetService} is the monthly BYOK-spend guardrail from #848's gateway seam - it
 * exists to protect a tenant from their own runaway agent racking up spend on the tenant's own
 * provider key (see the class's own javadoc). Had zero test coverage before this suite despite
 * governing real spend decisions.
 *
 * <p>Uses Mockito for both collaborators: {@link LlmSpendReader} is a Spring Data JPA repository
 * interface (no real DB in this sandbox - see this codebase's standing "no Maven/Java 17 in this
 * sandbox" limitation noted across every other test suite here), and {@link TenantLimits} is a
 * plain interface with no simple real implementation to construct directly.
 */
class LlmGatewayBudgetServiceTest {

    private static final String PREMIUM_CAP = "200";
    private static final String FREE_CAP = "0";

    private LlmSpendReader repository;
    private TenantLimits tenantLimits;
    private LlmGatewayBudgetService service;

    @BeforeEach
    void setUp() {
        repository = mock(LlmSpendReader.class);
        tenantLimits = mock(TenantLimits.class);
        // Infra audit (2026-07-27) - real SimpleMeterRegistry, same reasoning
        // as LlmGatewayRateLimiterServiceTest's identical field comment.
        service =
                new LlmGatewayBudgetService(
                        repository, tenantLimits, PREMIUM_CAP, FREE_CAP, new SimpleMeterRegistry());
    }

    @Test
    void nullTenantIdSkipsTheCheckEntirelyWithoutTouchingCollaborators() {
        assertDoesNotThrow(() -> service.assertWithinBudget(null));

        verifyNoInteractions(repository, tenantLimits);
    }

    @Test
    void unlimitedPlanHasNoCapAndNeverThrowsRegardlessOfSpend() {
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.UNLIMITED);
        when(repository.costSince(any(Instant.class))).thenReturn(new BigDecimal("999999"));

        assertDoesNotThrow(() -> service.assertWithinBudget("tenant-a"));
    }

    @Test
    void premiumPlanUnderTheCapDoesNotThrow() {
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.PREMIUM);
        when(repository.costSince(any(Instant.class))).thenReturn(new BigDecimal("150"));

        assertDoesNotThrow(() -> service.assertWithinBudget("tenant-a"));
    }

    @Test
    void premiumPlanAtExactlyTheCapThrows() {
        // >= cap, not just > cap - the boundary itself must reject.
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.PREMIUM);
        when(repository.costSince(any(Instant.class))).thenReturn(new BigDecimal("200"));

        LlmGatewayLimitExceededException ex =
                assertThrows(
                        LlmGatewayLimitExceededException.class,
                        () -> service.assertWithinBudget("tenant-a"));

        assertTrue(ex.getMessage().contains("200"));
    }

    @Test
    void premiumPlanOverTheCapThrows() {
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.PREMIUM);
        when(repository.costSince(any(Instant.class))).thenReturn(new BigDecimal("250"));

        assertThrows(
                LlmGatewayLimitExceededException.class,
                () -> service.assertWithinBudget("tenant-a"));
    }

    @Test
    void freePlanHasAZeroDefaultCapAndRejectsAnySpendAtAll() {
        // FREE tenants are documented to never actually reach this check in
        // practice (routed to the hosted pool first), but this still locks
        // in the defensive backstop: a zero cap must reject as soon as any
        // spend at all has been logged, in case a future call site forgets
        // that routing branch.
        when(tenantLimits.plan("tenant-free")).thenReturn(TenantPlan.FREE);
        when(repository.costSince(any(Instant.class))).thenReturn(new BigDecimal("0.01"));

        assertThrows(
                LlmGatewayLimitExceededException.class,
                () -> service.assertWithinBudget("tenant-free"));
    }

    @Test
    void freePlanWithZeroSpentSoFarStillThrowsBecauseZeroMeetsAZeroCap() {
        // 0 >= 0 is true - the very first FREE-tenant BYOK call (if this
        // defensive backstop is ever actually reached) must still be
        // rejected, not silently allowed through on a technicality.
        when(tenantLimits.plan("tenant-free")).thenReturn(TenantPlan.FREE);
        when(repository.costSince(any(Instant.class))).thenReturn(BigDecimal.ZERO);

        assertThrows(
                LlmGatewayLimitExceededException.class,
                () -> service.assertWithinBudget("tenant-free"));
    }

    @Test
    void nullCostFromRepositoryIsTreatedAsNoSpendAndDoesNotThrow() {
        // costSince() can return null (e.g. no rows at all yet this month) -
        // must not NPE on a raw BigDecimal.compareTo call.
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.PREMIUM);
        when(repository.costSince(any(Instant.class))).thenReturn(null);

        assertDoesNotThrow(() -> service.assertWithinBudget("tenant-a"));
    }

    @Test
    void repositoryLookupFailureFailsOpenRatherThanBlockingTheCall() {
        // Documented "best-effort governance, never the reason a real chat
        // turn fails" posture - a budget-check plumbing failure must not
        // become a hard failure for the tenant's actual request.
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.PREMIUM);
        when(repository.costSince(any(Instant.class)))
                .thenThrow(new RuntimeException("db unreachable"));

        assertDoesNotThrow(() -> service.assertWithinBudget("tenant-a"));
    }

    @Test
    void tenantLimitsLookupFailureAlsoFailsOpen() {
        when(tenantLimits.plan("tenant-a")).thenThrow(new RuntimeException("tenant lookup failed"));

        assertDoesNotThrow(() -> service.assertWithinBudget("tenant-a"));
    }

    @Test
    void counterRowIsUsedAndTheSumIsNotRunWhenTheRowExists() {
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.PREMIUM);
        when(repository.monthlySpend(any(LocalDate.class))).thenReturn(new BigDecimal("200"));

        assertThrows(
                LlmGatewayLimitExceededException.class,
                () -> service.assertWithinBudget("tenant-a"));
        verify(repository, never()).costSince(any(Instant.class));
    }

    @Test
    void counterUnderTheCapAllowsTheCallWithoutTheSum() {
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.PREMIUM);
        when(repository.monthlySpend(any(LocalDate.class))).thenReturn(new BigDecimal("199.99"));

        assertDoesNotThrow(() -> service.assertWithinBudget("tenant-a"));
        verify(repository, never()).costSince(any(Instant.class));
    }

    @Test
    void missingCounterRowFallsBackToTheSumSoTheAnswerIsUnchanged() {
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.PREMIUM);
        when(repository.monthlySpend(any(LocalDate.class))).thenReturn(null);
        when(repository.costSince(any(Instant.class))).thenReturn(new BigDecimal("250"));

        assertThrows(
                LlmGatewayLimitExceededException.class,
                () -> service.assertWithinBudget("tenant-a"));
    }

    @Test
    void counterReadFailureFailsOpen() {
        when(tenantLimits.plan("tenant-a")).thenReturn(TenantPlan.PREMIUM);
        when(repository.monthlySpend(any(LocalDate.class)))
                .thenThrow(new RuntimeException("table missing"));

        assertDoesNotThrow(() -> service.assertWithinBudget("tenant-a"));
    }
}
