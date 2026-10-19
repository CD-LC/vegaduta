package com.agenticai.core.agents.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService;
import com.agenticai.core.chat.FimCompletionEntitlements.Lease;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The adapter that lets {@code POST /api/completions/fim} spend platform money.
 * Every test here is about one of two failure directions, because those are the
 * only two that matter for this class: charging for something the caller never
 * got, or serving something nobody was charged for.
 *
 * <p>The meters themselves are mocked on purpose - their arithmetic has its own
 * suites. What is under test is the CHOREOGRAPHY: which meter is called, in
 * which order, and what is given back when a later step rejects.
 */
class FimCompletionEntitlementsAdapterTest {

    private static final String TENANT = "tenant-a";
    private static final String SUBJECT = "user-7";

    private FreeTierUsageLimiterService usageLimiter;
    private PerUserBudgetService perUserBudget;
    private TenantLimits tenantLimits;
    private FimCompletionEntitlementsAdapter adapter;

    @BeforeEach
    void setUp() {
        usageLimiter = mock(FreeTierUsageLimiterService.class);
        perUserBudget = mock(PerUserBudgetService.class);
        tenantLimits = mock(TenantLimits.class);
        when(tenantLimits.plan(TENANT)).thenReturn(TenantPlan.FREE);
        when(usageLimiter.checkAndReserveTokenBudget(eq(TENANT), any(), anyInt())).thenReturn(128L);
        when(perUserBudget.checkAndReserveBudget(eq(TENANT), eq(SUBJECT), any(), anyLong(), any()))
            .thenReturn(4_200L);
        adapter = new FimCompletionEntitlementsAdapter(usageLimiter, perUserBudget, tenantLimits);
        TenantContext.set(TenantContext.of(TENANT, SUBJECT, Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("reserve takes the token reservation BEFORE the dollar gate, like the chat path")
    void reserveOrdersTheTwoGates() {
        adapter.reserve(64);

        InOrder order = inOrder(usageLimiter, perUserBudget);
        order.verify(usageLimiter).checkAndReserveTokenBudget(TENANT, TenantPlan.FREE, 64);
        order.verify(perUserBudget).checkAndReserveBudget(
            TENANT, SUBJECT, PerUserBudgetService.Modality.TEXT, 128L, TenantPlan.FREE);
    }

    @Test
    @DisplayName("a token-gate rejection releases nothing the caller never got")
    void tokenGateRejectionReleasesNothing() {
        when(usageLimiter.checkAndReserveTokenBudget(eq(TENANT), any(), anyInt()))
            .thenThrow(new TenantLimitExceededException("daily tokens"));

        assertThatThrownBy(() -> adapter.reserve(64)).isInstanceOf(TenantLimitExceededException.class);

        verify(usageLimiter).reconcileTokenReservation(TENANT, 0L, 0L);
        // Nothing was reserved on the dollar side, so nothing is released with
        // a non-zero amount - but the call is still made, releasing zero, which
        // keeps one release path instead of two.
        verify(perUserBudget).releaseSpendReservation(
            TENANT, SUBJECT, PerUserBudgetService.Modality.TEXT, 0L, TenantPlan.FREE);
    }

    @Test
    @DisplayName("a dollar-gate rejection gives back the token reservation in full")
    void budgetGateRejectionReleasesTheTokenReservationToo() {
        when(perUserBudget.checkAndReserveBudget(eq(TENANT), eq(SUBJECT), any(), anyLong(), any()))
            .thenThrow(new TenantLimitExceededException("budget"));

        assertThatThrownBy(() -> adapter.reserve(64)).isInstanceOf(TenantLimitExceededException.class);

        verify(usageLimiter).reconcileTokenReservation(TENANT, 128L, 0L);
        verify(perUserBudget).releaseSpendReservation(
            TENANT, SUBJECT, PerUserBudgetService.Modality.TEXT, 0L, TenantPlan.FREE);
    }

    @Test
    @DisplayName("settle corrects both reservations to what the model really used")
    void settleReconcilesBothMeters() {
        Lease lease = adapter.reserve(64);

        lease.settle("gemini-2.5-flash", 40L, 17L);

        verify(usageLimiter).reconcileTokenReservation(TENANT, 128L, 57L);
        verify(perUserBudget).reconcileSpendReservationForCall(
            TENANT, SUBJECT, PerUserBudgetService.Modality.TEXT, 4_200L,
            "gemini-2.5-flash", 40L, 17L, TenantPlan.FREE);
    }

    @Test
    @DisplayName("settle with no usage reported still reconciles - a provider that reports nothing is not free")
    void settleWithNullUsageStillReconciles() {
        Lease lease = adapter.reserve(64);

        lease.settle("some-model", null, null);

        verify(usageLimiter).reconcileTokenReservation(TENANT, 128L, 0L);
        verify(perUserBudget).reconcileSpendReservationForCall(
            TENANT, SUBJECT, PerUserBudgetService.Modality.TEXT, 4_200L,
            "some-model", null, null, TenantPlan.FREE);
    }

    @Test
    @DisplayName("abandon returns both reservations in full")
    void abandonGivesEverythingBack() {
        Lease lease = adapter.reserve(64);

        lease.abandon();

        verify(usageLimiter).reconcileTokenReservation(TENANT, 128L, 0L);
        verify(perUserBudget).releaseSpendReservation(
            TENANT, SUBJECT, PerUserBudgetService.Modality.TEXT, 4_200L, TenantPlan.FREE);
        verify(perUserBudget, never()).reconcileSpendReservationForCall(
            any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("exactly once: a second terminal call is dropped, not double-refunded")
    void secondTerminalCallIsDropped() {
        Lease lease = adapter.reserve(64);

        lease.abandon();
        lease.abandon();
        lease.settle("m", 1L, 1L);

        verify(usageLimiter, times(1)).reconcileTokenReservation(TENANT, 128L, 0L);
        verify(perUserBudget, times(1)).releaseSpendReservation(
            TENANT, SUBJECT, PerUserBudgetService.Modality.TEXT, 4_200L, TenantPlan.FREE);
        verify(perUserBudget, never()).reconcileSpendReservationForCall(
            any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a lease settled from another thread, after the request's context is gone, still charges the right account")
    void settleFromAnotherThreadAfterContextCleared() throws Exception {
        Lease lease = adapter.reserve(64);

        // Exactly what the service does on a deadline miss: the request thread
        // returns (dropping its TenantContext) and the terminal call lands on a
        // pool worker that never had one.
        TenantContext.clear();
        CompletableFuture.runAsync(() -> lease.settle("gemini-2.5-flash", 10L, 5L)).get();

        verify(usageLimiter).reconcileTokenReservation(TENANT, 128L, 15L);
        verify(perUserBudget).reconcileSpendReservationForCall(
            TENANT, SUBJECT, PerUserBudgetService.Modality.TEXT, 4_200L,
            "gemini-2.5-flash", 10L, 5L, TenantPlan.FREE);
    }

    @Test
    @DisplayName("no subject identity: the tenant meters still run, the per-user window is skipped rather than charged to nobody")
    void withoutASubjectThePerUserGateIsSkipped() {
        TenantContext.set(TenantContext.of(TENANT, null, Set.of()));

        Lease lease = adapter.reserve(64);
        lease.settle("m", 2L, 3L);

        verify(usageLimiter).checkAndReserveTokenBudget(TENANT, TenantPlan.FREE, 64);
        verify(usageLimiter).reconcileTokenReservation(TENANT, 128L, 5L);
        verifyNoMoreInteractions(perUserBudget);
    }
}
