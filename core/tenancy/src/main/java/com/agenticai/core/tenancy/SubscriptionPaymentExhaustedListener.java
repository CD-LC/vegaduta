package com.agenticai.core.tenancy;

import com.agenticai.core.billing.gateway.SubscriptionPaymentExhaustedEvent;
import com.agenticai.core.security.TenantPlan;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * D5 (dunning) - the tenancy half of {@code SubscriptionPendingHandler}'s
 * failed-payment tracking: when core/billing reports a tenant's subscription
 * payment has failed the configured number of times ({@link
 * SubscriptionPaymentExhaustedEvent}), apply the EXISTING FREE-downgrade
 * path - {@link Tenant#downgradeToFree} + {@link TenantRepository#save},
 * the exact same transition {@link RazorpayWebhookService} runs for a
 * cancelled/completed subscription - rather than a second, parallel
 * plan-flip mechanism. An application event listener (not a direct call)
 * because billing cannot depend on tenancy; see the event record's javadoc.
 *
 * <p>Guarded to paid plans only, same as {@code
 * RazorpayWebhookService#handleLiveSubscriptionEvent}'s FREE-tenant guard:
 * a FREE tenant here is either the signup-time trial subscription's own
 * lifecycle (deliberately untouched) or a tenant this listener already
 * downgraded on an earlier delivery - both no-ops.
 *
 * <p>The gateway subscription itself is deliberately NOT cancelled here:
 * Razorpay's own retry engine ends it ({@code subscription.halted} /
 * {@code subscription.cancelled}), and those later webhooks land on the
 * FREE-tenant guard as no-ops. No network call in this path means the
 * downgrade cannot be blocked by a gateway outage.
 */
@Component
public class SubscriptionPaymentExhaustedListener {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPaymentExhaustedListener.class);

    /**
     * The {@code subscription_status} value the downgrade leaves behind -
     * not a raw gateway string (Razorpay has no "we gave up" event; this
     * transition is this platform's own decision), so it says exactly what
     * happened instead of impersonating one.
     */
    static final String DUNNING_EXHAUSTED_STATUS = "dunning.exhausted";

    private final TenantRepository tenantRepository;

    public SubscriptionPaymentExhaustedListener(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @EventListener
    public void onPaymentAttemptsExhausted(SubscriptionPaymentExhaustedEvent event) {
        Optional<Tenant> found = tenantRepository.findById(event.tenantId());
        if (found.isEmpty()) {
            log.warn("Dunning exhausted for unknown tenant {} (subscription '{}') - nothing to downgrade",
                     event.tenantId(), event.gatewaySubscriptionId());
            return;
        }
        Tenant tenant = found.get();
        if (tenant.getPlan() == TenantPlan.FREE) {
            log.debug("Dunning exhausted for FREE tenant '{}' (subscription '{}') - trial lifecycle or already downgraded, no-op",
                      tenant.getName(), event.gatewaySubscriptionId());
            return;
        }

        TenantPlan previousPlan = tenant.getPlan();
        tenant.downgradeToFree(DUNNING_EXHAUSTED_STATUS);
        tenantRepository.save(tenant);
        log.warn("Tenant '{}' auto-downgraded {} -> FREE after {} failed payment attempts on subscription '{}' (D5 dunning)",
                 tenant.getName(), previousPlan, event.failedAttempts(), event.gatewaySubscriptionId());
    }
}
