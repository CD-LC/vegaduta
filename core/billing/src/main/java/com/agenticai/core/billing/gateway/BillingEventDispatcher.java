package com.agenticai.core.billing.gateway;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.logging.LogSafe;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Routes {@link BillingEvent}s to their respective handlers based on event type. Handlers are
 * registered in the Spring context and auto-discovered via constructor injection. New event types
 * can be added by implementing {@link BillingEventHandler} and letting Spring inject the new
 * handler into this dispatcher's constructor.
 *
 * <p>Dispatch is synchronous and <b>failures propagate</b> (JAVA-003): the webhook controller has
 * NOT answered the gateway yet when it calls {@link #dispatch}. A handler exception makes the
 * controller release the dedupe claim and answer 5xx, so the gateway redelivers and the event is
 * applied on the retry. Swallowing it (the previous behaviour) marked the event processed and
 * answered 200 - a transient DB error in {@code SubscriptionChargedHandler} permanently lost a paid
 * renewal. Handlers are row-level idempotent, so the redelivery cannot double-apply.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class BillingEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BillingEventDispatcher.class);

    private final Map<BillingEventType, BillingEventHandler> handlers;

    /**
     * E1 Agentic UPI Payments - secondary consumers keyed off {@link
     * RoutedBillingEventHandler#routes} (the mandate payments module needs a copy of
     * payment_link.paid / qr_code.credited while {@code UpiPaymentConfirmedHandler} keeps the
     * primary slot). Setter-injected, NOT a constructor parameter, deliberately: the constructor's
     * three-arg signature is compile-pinned by {@code BillingEventDispatcherTest} and every direct
     * call site, and "empty when the module isn't on the classpath" is exactly what {@code List<T>}
     * injection gives for free. The primary per-type handler always runs first; routed handlers run
     * after it, each isolated so a broken one can never affect the primary path.
     */
    private java.util.List<RoutedBillingEventHandler> routedHandlers = java.util.List.of();

    /**
     * Constructor injection auto-discovers all {@link BillingEventHandler} beans in the Spring
     * context and registers them by their handled event type. A handler can be added without
     * modifying this class - just implement BillingEventHandler and Spring will inject it.
     */
    public BillingEventDispatcher(
            SubscriptionChargedHandler chargedHandler,
            SubscriptionPendingHandler pendingHandler,
            // UPI-collect follow-up - one handler bean covers both events (see
            // its own javadoc for why), registered under both map keys below.
            UpiPaymentConfirmedHandler upiPaymentConfirmedHandler
            // Add more handlers here as they are implemented (D1, D2, D5, etc.)
            ) {
        this.handlers = new HashMap<>();
        if (chargedHandler != null) {
            handlers.put(BillingEventType.SUBSCRIPTION_CHARGED, chargedHandler);
        }
        if (pendingHandler != null) {
            handlers.put(BillingEventType.SUBSCRIPTION_PENDING, pendingHandler);
        }
        if (upiPaymentConfirmedHandler != null) {
            handlers.put(BillingEventType.PAYMENT_LINK_PAID, upiPaymentConfirmedHandler);
            handlers.put(BillingEventType.QR_CODE_PAYMENT_RECEIVED, upiPaymentConfirmedHandler);
        }
        // Register other handlers as implemented
        log.info("BillingEventDispatcher initialized with {} handlers", handlers.size());
    }

    /** E1 seam - see the {@code routedHandlers} field's javadoc. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRoutedHandlers(java.util.List<RoutedBillingEventHandler> routedHandlers) {
        this.routedHandlers = routedHandlers == null ? java.util.List.of() : routedHandlers;
    }

    /**
     * Dispatch event to its handler. If no handler is registered for this event type, log it and
     * continue (not an error - we may receive events we don't care about). If the primary handler
     * throws, the exception propagates (the routed handlers are not run; the gateway retry re-runs
     * everything). Routed secondary handlers (E1) run after the primary, isolated from EACH OTHER -
     * every one is attempted - and if any failed the first failure is re-thrown (others attached as
     * suppressed) so the delivery is retried.
     */
    public void dispatch(BillingEvent event) {
        BillingEventHandler handler = handlers.get(event.type());
        if (handler == null) {
            log.info(
                    "No handler registered for event type {} (raw event: {})",
                    event.type(),
                    LogSafe.clean(event.rawEventName()));
        } else {
            handler.handle(event);
        }

        RuntimeException routedFailure = null;
        for (RoutedBillingEventHandler routed : routedHandlers) {
            if (!routed.routes(event.type())) {
                continue;
            }
            try {
                routed.handle(event);
            } catch (RuntimeException e) {
                log.error(
                        "Routed handler {} failed for event type {} (reference: {})",
                        routed.getClass().getSimpleName(),
                        event.type(),
                        LogSafe.clean(event.gatewaySubscriptionId()),
                        e);
                if (routedFailure == null) {
                    routedFailure = e;
                } else {
                    routedFailure.addSuppressed(e);
                }
            }
        }
        if (routedFailure != null) {
            throw routedFailure;
        }
    }
}
