package com.agenticai.core.billing.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B9-PR4 revenue-path contract - {@link BillingEventDispatcher}'s routing
 * table. Small on purpose: the dispatcher's whole job is "right handler,
 * exactly once, never crash the webhook" - a misrouting here would apply
 * the wrong money-state transition silently, and a leaked handler
 * exception would turn one bad event into a Razorpay retry storm against
 * {@link BillingWebhookController}. Handlers are Mockito mocks of the
 * concrete classes (the constructor takes concrete types, not the
 * interface - that wiring choice is what makes this compile-checked).
 */
class BillingEventDispatcherTest {

    private SubscriptionChargedHandler chargedHandler;
    private SubscriptionPendingHandler pendingHandler;
    private UpiPaymentConfirmedHandler upiPaymentConfirmedHandler;
    private BillingEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        chargedHandler = mock(SubscriptionChargedHandler.class);
        pendingHandler = mock(SubscriptionPendingHandler.class);
        upiPaymentConfirmedHandler = mock(UpiPaymentConfirmedHandler.class);
        dispatcher = new BillingEventDispatcher(chargedHandler, pendingHandler, upiPaymentConfirmedHandler);
    }

    private static BillingEvent event(BillingEventType type, String rawName) {
        return new BillingEvent(type, "sub_42", rawName);
    }

    @Test
    void chargedEventGoesToTheChargedHandlerOnly() {
        BillingEvent charged = event(BillingEventType.SUBSCRIPTION_CHARGED, "subscription.charged");

        dispatcher.dispatch(charged);

        verify(chargedHandler).handle(charged);
        verifyNoInteractions(pendingHandler);
    }

    @Test
    void pendingEventGoesToThePendingHandlerOnly() {
        BillingEvent pending = event(BillingEventType.SUBSCRIPTION_PENDING, "subscription.pending");

        dispatcher.dispatch(pending);

        verify(pendingHandler).handle(pending);
        verifyNoInteractions(chargedHandler);
    }

    @Test
    void eventTypesWithoutARegisteredHandlerAreSilentlySkipped() {
        // Cancelled/halted/etc. are handled elsewhere (RazorpayWebhookService's
        // tenant state machine in core/tenancy), and UNKNOWN covers future
        // gateway event types - none of these may crash or misroute.
        dispatcher.dispatch(event(BillingEventType.SUBSCRIPTION_CANCELLED, "subscription.cancelled"));
        dispatcher.dispatch(event(BillingEventType.UNKNOWN, "some.future.event"));

        verifyNoInteractions(chargedHandler, pendingHandler, upiPaymentConfirmedHandler);
    }

    @Test
    void paymentLinkPaidGoesToTheUpiHandlerOnly() {
        BillingEvent linkPaid = event(BillingEventType.PAYMENT_LINK_PAID, "payment_link.paid");

        dispatcher.dispatch(linkPaid);

        verify(upiPaymentConfirmedHandler).handle(linkPaid);
        verifyNoInteractions(chargedHandler, pendingHandler);
    }

    @Test
    void qrCodePaymentReceivedGoesToTheSameUpiHandlerOnly() {
        // Same handler bean covers both UPI-collect-follow-up event types -
        // see UpiPaymentConfirmedHandler's javadoc for why.
        BillingEvent qrPaid = event(BillingEventType.QR_CODE_PAYMENT_RECEIVED, "qr_code.credited");

        dispatcher.dispatch(qrPaid);

        verify(upiPaymentConfirmedHandler).handle(qrPaid);
        verifyNoInteractions(chargedHandler, pendingHandler);
    }

    @Test
    void aThrowingHandlerPropagatesSoTheWebhookIsRetried() {
        // JAVA-003: swallowing this marked the event processed and answered
        // 200, permanently losing e.g. a paid renewal on a transient DB error.
        doThrow(new RuntimeException("handler blew up")).when(chargedHandler).handle(any());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
            dispatcher.dispatch(event(BillingEventType.SUBSCRIPTION_CHARGED, "subscription.charged")));
    }

    @Test
    void everyRoutedHandlerIsAttemptedAndTheFirstFailurePropagates() {
        RoutedBillingEventHandler failing = mock(RoutedBillingEventHandler.class);
        RoutedBillingEventHandler healthy = mock(RoutedBillingEventHandler.class);
        org.mockito.Mockito.when(failing.routes(BillingEventType.PAYMENT_LINK_PAID)).thenReturn(true);
        org.mockito.Mockito.when(healthy.routes(BillingEventType.PAYMENT_LINK_PAID)).thenReturn(true);
        doThrow(new IllegalStateException("routed blew up")).when(failing).handle(any());
        dispatcher.setRoutedHandlers(java.util.List.of(failing, healthy));
        BillingEvent paid = event(BillingEventType.PAYMENT_LINK_PAID, "payment_link.paid");

        IllegalStateException thrown =
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(paid));

        org.junit.jupiter.api.Assertions.assertEquals("routed blew up", thrown.getMessage());
        verify(upiPaymentConfirmedHandler).handle(paid);
        verify(healthy).handle(paid);
    }

    @Test
    void nullHandlersAreToleratedAtConstructionAndDispatchStaysSafe() {
        // The constructor null-guards each handler (profile-gated contexts
        // can legitimately omit beans) - dispatch must then behave exactly
        // like "no handler registered", not NPE.
        BillingEventDispatcher sparse = new BillingEventDispatcher(null, null, null);

        assertDoesNotThrow(() ->
            sparse.dispatch(event(BillingEventType.SUBSCRIPTION_CHARGED, "subscription.charged")));
    }
}
