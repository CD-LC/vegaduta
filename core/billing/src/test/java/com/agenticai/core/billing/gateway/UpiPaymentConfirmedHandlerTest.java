package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.commerce.CheckoutSession;
import com.agenticai.core.billing.commerce.CheckoutSessionService;
import com.agenticai.core.billing.commerce.CheckoutSessionStatus;
import com.agenticai.core.billing.commerce.PaidPaymentLookup;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link UpiPaymentConfirmedHandler} after agentic commerce v0: the handler
 * is now a thin adapter from the webhook's reference id to {@link
 * CheckoutSessionService#recordGatewayPayment}, so what is pinned here is
 * (a) what it hands the service, (b) that a missing/broken payment lookup
 * degrades to "complete without a payment id" rather than dropping the
 * confirmation, and (c) the unchanged do-not-re-raise posture.
 */
class UpiPaymentConfirmedHandlerTest {

    private CheckoutSessionService service;
    private PaidPaymentLookup lookup;
    private UpiPaymentConfirmedHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(CheckoutSessionService.class);
        lookup = mock(PaidPaymentLookup.class);
        handler = new UpiPaymentConfirmedHandler(service, provider(lookup));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PaidPaymentLookup> provider(PaidPaymentLookup value) {
        ObjectProvider<PaidPaymentLookup> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static BillingEvent paid(String referenceId) {
        return new BillingEvent(BillingEventType.PAYMENT_LINK_PAID, referenceId, "payment_link.paid");
    }

    private static CheckoutSession session(CheckoutSessionStatus status, String paymentId, String reviewNote) {
        return new CheckoutSession(
            UUID.randomUUID(), "tenant_acme", UUID.randomUUID(), "sess-1", "agent", null, null, "INR", 49_900L,
            "2x dosa", "[]", status, "razorpay", "plink_1", "https://rzp.io/i/abc",
            status == CheckoutSessionStatus.COMPLETED ? Instant.now() : null, paymentId, reviewNote,
            Instant.now(), Instant.now(), Instant.now());
    }

    @Test
    void eventWithoutAReferenceIdTouchesNothing() {
        handler.handle(paid(null));
        handler.handle(paid("   "));

        // No id -> no way to attribute money -> must not guess.
        verifyNoInteractions(service, lookup);
    }

    @Test
    void handsTheGatewayPaymentToTheServiceKeyedOnTheWebhooksReferenceId() {
        PaidPaymentLookup.PaidPayment payment = new PaidPaymentLookup.PaidPayment("pay_9", 49_900L, "upi");
        when(lookup.lookup("plink_1")).thenReturn(Optional.of(payment));
        when(service.recordGatewayPayment(eq("plink_1"), any())).thenReturn(new CheckoutSessionService.Reconciliation(
            CheckoutSessionService.ReconciliationResult.COMPLETED, session(CheckoutSessionStatus.COMPLETED, "pay_9", null)));

        handler.handle(paid("plink_1"));

        ArgumentCaptor<PaidPaymentLookup.PaidPayment> captor = ArgumentCaptor.forClass(PaidPaymentLookup.PaidPayment.class);
        verify(service).recordGatewayPayment(eq("plink_1"), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(payment, captor.getValue());
    }

    @Test
    void aMissingLookupBeanStillCompletesTheSessionWithoutAPaymentId() {
        UpiPaymentConfirmedHandler withoutLookup = new UpiPaymentConfirmedHandler(service, provider(null));
        when(service.recordGatewayPayment(eq("plink_1"), isNull())).thenReturn(new CheckoutSessionService.Reconciliation(
            CheckoutSessionService.ReconciliationResult.COMPLETED, session(CheckoutSessionStatus.COMPLETED, null, null)));

        withoutLookup.handle(paid("plink_1"));

        verify(service).recordGatewayPayment("plink_1", null);
    }

    @Test
    void aLookupThatThrowsIsContainedAndTheSessionStillCompletes() {
        when(lookup.lookup("plink_1")).thenThrow(new RuntimeException("razorpay 5xx"));
        when(service.recordGatewayPayment(eq("plink_1"), isNull())).thenReturn(new CheckoutSessionService.Reconciliation(
            CheckoutSessionService.ReconciliationResult.COMPLETED, session(CheckoutSessionStatus.COMPLETED, null, null)));

        assertDoesNotThrow(() -> handler.handle(paid("plink_1")));

        verify(service).recordGatewayPayment("plink_1", null);
    }

    @Test
    void everyReconciliationOutcomeIsLoggedNotThrown() {
        when(lookup.lookup("plink_1")).thenReturn(Optional.empty());
        for (CheckoutSessionService.ReconciliationResult result : CheckoutSessionService.ReconciliationResult.values()) {
            CheckoutSession row = result == CheckoutSessionService.ReconciliationResult.NOT_FOUND
                ? null
                : session(CheckoutSessionStatus.READY_FOR_PAYMENT, null, "mismatch note");
            when(service.recordGatewayPayment(eq("plink_1"), isNull()))
                .thenReturn(new CheckoutSessionService.Reconciliation(result, row));

            assertDoesNotThrow(() -> handler.handle(paid("plink_1")), "outcome " + result + " must not throw");
        }
    }

    @Test
    void aServiceFailurePropagatesToTheDispatcherWhichAlreadySwallowsIt() {
        // Same contract as every other BillingEventHandler: the dispatcher
        // logs and swallows - the handler does not need to double-wrap. This
        // pins that a DB failure is NOT silently turned into a "handled" log
        // line by this class (it must reach the dispatcher's ERROR).
        when(lookup.lookup("plink_1")).thenReturn(Optional.empty());
        when(service.recordGatewayPayment(eq("plink_1"), isNull())).thenThrow(new RuntimeException("db down"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> handler.handle(paid("plink_1")));
    }
}
