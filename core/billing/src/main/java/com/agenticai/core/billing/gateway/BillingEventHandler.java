package com.agenticai.core.billing.gateway;

/**
 * Handles a specific {@link BillingEvent} type. Implementations are
 * discovered by {@link BillingEventDispatcher} and can perform side effects
 * (update subscription status, send email, trigger refund, etc.) when their
 * event fires.
 *
 * <p>Handlers must be Spring {@code @Service} beans so the dispatcher can
 * inject them. If a handler throws, the exception is logged by the
 * dispatcher and not re-raised (the webhook already returned 200).
 */
@FunctionalInterface
public interface BillingEventHandler {
    void handle(BillingEvent event);
}
