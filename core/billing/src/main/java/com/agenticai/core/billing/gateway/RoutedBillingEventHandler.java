package com.agenticai.core.billing.gateway;

/**
 * E1 Agentic UPI Payments - the additive seam that lets a downstream module
 * (core/payments) react to a {@link BillingEventType} that already has a
 * primary {@link BillingEventHandler}, without replacing that handler.
 * {@code payment_link.paid} and {@code qr_code.credited} are the motivating
 * case: {@code UpiPaymentConfirmedHandler} owns the control-plane
 * checkout-session path, and the mandate payments module's handler needs a
 * copy of the same event to settle {@code payment_transaction} rows - two
 * independent consumers, one event.
 *
 * <p>The dispatcher fans out to the primary per-type handler first
 * (unchanged behavior), then to every routed handler whose {@link #routes}
 * returns true for the type - each in its own try/catch, so an optional
 * module's failure can never break the primary path. Implementations live
 * outside billing (billing must stay a leaf - it cannot depend on the
 * payments module, which is why this interface is defined HERE).
 */
public interface RoutedBillingEventHandler extends BillingEventHandler {

    /** Which event types this handler wants a copy of. */
    boolean routes(BillingEventType type);
}
