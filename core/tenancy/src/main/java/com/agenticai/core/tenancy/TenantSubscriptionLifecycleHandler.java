package com.agenticai.core.tenancy;

import com.agenticai.core.billing.gateway.BillingEvent;
import com.agenticai.core.billing.gateway.BillingEventType;
import com.agenticai.core.billing.gateway.RoutedBillingEventHandler;
import com.agenticai.core.billing.invoice.BillingInvoice;
import com.agenticai.core.billing.invoice.InvoiceService;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The plan-state applier for the GENERIC {@code POST /api/billing/webhook/
 * {gateway}} endpoint - every gateway that isn't Razorpay (Stripe, PhonePe,
 * and any future {@code PaymentGatewayClient}). Razorpay keeps its dedicated
 * {@link RazorpayWebhookController}/{@link RazorpayWebhookService} path
 * unchanged; this handler is the routed secondary consumer ({@link
 * RoutedBillingEventHandler}) that {@code BillingEventDispatcher} fans the
 * already-parsed, already-signature-verified {@link BillingEvent} out to.
 *
 * <p>Applies the exact same state machine {@link RazorpayWebhookService}
 * implements for the dedicated Razorpay path, kept deliberately parallel so
 * the two stay comparable rather than diverging silently:
 * <ol>
 *   <li><b>Pending upgrades</b> (matched via {@link
 *       TenantRepository#findByPendingGatewaySubscriptionId}):
 *       ACTIVATED/CHARGED confirm the upgrade ({@link
 *       Tenant#confirmUpgrade(String, String)} - stamped with {@code
 *       event.gatewayName()} so {@code Tenant.paymentGateway} records which
 *       gateway actually holds the subscription); CANCELLED/HALTED/COMPLETED
 *       abandon it ({@link Tenant#cancelPendingUpgrade}).</li>
 *   <li><b>Live paid subscriptions</b> (matched via {@link
 *       TenantRepository#findByGatewaySubscriptionId}): HALTED opens the
 *       past-due grace window, CANCELLED/COMPLETED downgrades to FREE,
 *       ACTIVATED/CHARGED/RESUMED closes a grace window.</li>
 * </ol>
 *
 * <p>SUBSCRIPTION_PENDING is deliberately NOT routed here - {@code
 * SubscriptionPendingHandler} is already the primary handler for it on the
 * generic path and does dunning by {@code gateway_subscription_id}
 * gateway-agnostically. UNKNOWN/other types carry no plan meaning.
 *
 * <p>Two deliberate divergences from the Razorpay service, both forced by
 * the seam: invoices are recorded off {@link BillingEvent#capturedPayment}
 * (the routed-handler signature carries no raw webhook body, so each gateway
 * extracts payment details at parse time instead), and {@code AUTHENTICATED}
 * on a PENDING upgrade is logged but not applied - same as Razorpay.
 */
@Component
@Profile("security")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class TenantSubscriptionLifecycleHandler implements RoutedBillingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantSubscriptionLifecycleHandler.class);

    private final TenantRepository tenantRepository;
    private final InvoiceService invoiceService;

    public TenantSubscriptionLifecycleHandler(TenantRepository tenantRepository, InvoiceService invoiceService) {
        this.tenantRepository = tenantRepository;
        this.invoiceService = invoiceService;
    }

    @Override
    public boolean routes(BillingEventType type) {
        return switch (type) {
            case SUBSCRIPTION_AUTHENTICATED, SUBSCRIPTION_ACTIVATED, SUBSCRIPTION_CHARGED,
                 SUBSCRIPTION_HALTED, SUBSCRIPTION_PAUSED, SUBSCRIPTION_RESUMED,
                 SUBSCRIPTION_CANCELLED, SUBSCRIPTION_COMPLETED, SUBSCRIPTION_UPDATED -> true;
            default -> false;
        };
    }

    @Override
    public void handle(BillingEvent event) {
        String subscriptionId = event.gatewaySubscriptionId();
        if (subscriptionId == null || subscriptionId.isBlank()) {
            log.debug("{} lifecycle event '{}' carried no subscription id - nothing to apply",
                gatewayLabel(event), event.rawEventName());
            return;
        }

        Optional<Tenant> pendingTenant = tenantRepository.findByPendingGatewaySubscriptionId(subscriptionId);
        if (pendingTenant.isEmpty()) {
            // Same second lookup RazorpayWebhookService makes: an event about
            // a tenant's LIVE paid subscription, not a pending upgrade.
            Optional<Tenant> activeTenant = tenantRepository.findByGatewaySubscriptionId(subscriptionId);
            if (activeTenant.isPresent()) {
                handleLiveSubscriptionEvent(activeTenant.get(), event);
            } else {
                log.debug(
                    "{} webhook '{}' for subscription '{}' - no pending upgrade or live subscription tracked for it, ignoring",
                    gatewayLabel(event), event.rawEventName(), subscriptionId
                );
            }
            return;
        }

        Tenant tenant = pendingTenant.get();
        switch (event.type()) {
            case SUBSCRIPTION_ACTIVATED, SUBSCRIPTION_CHARGED -> {
                tenant.confirmUpgrade(event.rawEventName(), event.gatewayName());
                tenantRepository.save(tenant);
                log.info("Tenant '{}' upgrade confirmed via {} '{}'",
                    tenant.getName(), gatewayLabel(event), event.rawEventName());
                recordInvoiceForCapturedPayment(tenant, event);
            }
            case SUBSCRIPTION_CANCELLED, SUBSCRIPTION_HALTED, SUBSCRIPTION_COMPLETED -> {
                tenant.cancelPendingUpgrade();
                tenantRepository.save(tenant);
                log.info("Tenant '{}' pending upgrade cleared via {} '{}' - plan unchanged",
                    tenant.getName(), gatewayLabel(event), event.rawEventName());
            }
            case SUBSCRIPTION_AUTHENTICATED -> log.info(
                "Tenant '{}' {} subscription '{}' authenticated - awaiting first charge before upgrade is confirmed",
                tenant.getName(), gatewayLabel(event), subscriptionId);
            default -> log.debug(
                "{} webhook '{}' for tenant '{}' pending upgrade - no action needed for this event type",
                gatewayLabel(event), event.rawEventName(), tenant.getName());
        }
    }

    /**
     * Lifecycle events for a subscription that already IS the tenant's
     * confirmed paid subscription - the same three-way state machine {@code
     * RazorpayWebhookService#handleLiveSubscriptionEvent} documents:
     * HALTED opens past-due grace, CANCELLED/COMPLETED downgrades to FREE,
     * ACTIVATED/CHARGED/RESUMED closes an open grace window. A FREE tenant
     * matched here is the dormant signup-time trial subscription's own
     * lifecycle (or an already-applied downgrade's redelivery) - untouched.
     */
    private void handleLiveSubscriptionEvent(Tenant tenant, BillingEvent event) {
        if (tenant.getPlan() == TenantPlan.FREE) {
            log.debug(
                "{} webhook '{}' for FREE tenant '{}' subscription '{}' - trial-subscription lifecycle, deliberately untouched",
                gatewayLabel(event), event.rawEventName(), tenant.getName(), event.gatewaySubscriptionId()
            );
            return;
        }
        switch (event.type()) {
            case SUBSCRIPTION_HALTED -> {
                tenant.markPastDue(event.rawEventName(), Instant.now());
                tenantRepository.save(tenant);
                log.warn(
                    "Tenant '{}' paid subscription '{}' HALTED via {} (payment retries exhausted) - marked past-due, plan {} kept during grace",
                    tenant.getName(), event.gatewaySubscriptionId(), gatewayLabel(event), tenant.getPlan()
                );
            }
            case SUBSCRIPTION_CANCELLED, SUBSCRIPTION_COMPLETED -> {
                TenantPlan previousPlan = tenant.getPlan();
                tenant.downgradeToFree(event.rawEventName());
                tenantRepository.save(tenant);
                log.info(
                    "Tenant '{}' paid subscription '{}' ended via {} '{}' - downgraded {} -> FREE",
                    tenant.getName(), event.gatewaySubscriptionId(), gatewayLabel(event), event.rawEventName(), previousPlan
                );
            }
            case SUBSCRIPTION_ACTIVATED, SUBSCRIPTION_CHARGED, SUBSCRIPTION_RESUMED -> {
                if (tenant.isPastDue()) {
                    tenant.clearPastDue(event.rawEventName());
                    tenantRepository.save(tenant);
                    log.info(
                        "Tenant '{}' paid subscription '{}' recovered via {} '{}' - past-due cleared",
                        tenant.getName(), event.gatewaySubscriptionId(), gatewayLabel(event), event.rawEventName()
                    );
                } else {
                    log.debug(
                        "Tenant '{}' live subscription '{}' {} event '{}' - healthy, no state change",
                        tenant.getName(), event.gatewaySubscriptionId(), gatewayLabel(event), event.rawEventName()
                    );
                }
                recordInvoiceForCapturedPayment(tenant, event);
            }
            default -> log.debug(
                "{} webhook '{}' for tenant '{}' live subscription - no action needed for this event type",
                gatewayLabel(event), event.rawEventName(), tenant.getName()
            );
        }
    }

    /**
     * Records one immutable {@code billing_invoice} row for the captured
     * payment the event carries (populated by the gateway client at parse
     * time - see {@link BillingEvent#capturedPayment}). Null means a
     * chargeless event and writes nothing. {@code InvoiceService} is
     * idempotent on the payment id, so webhook redeliveries never
     * double-invoice; failures are logged, never re-raised - identical
     * posture to {@code RazorpayWebhookService#recordInvoiceForCapturedPayment}.
     */
    private void recordInvoiceForCapturedPayment(Tenant tenant, BillingEvent event) {
        if (event.capturedPayment() == null) {
            return;
        }
        try {
            BillingInvoice invoice = invoiceService.recordPaymentInvoice(
                tenant.getId(),
                event.gatewayName(),
                event.capturedPayment().paymentId(),
                tenant.getPlan() != null ? tenant.getPlan().name() : null,
                null,
                event.capturedPayment().currency(),
                event.capturedPayment().amountMinor()
            );
            log.info(
                "Invoice {} recorded for tenant '{}' payment '{}' ({} {} minor units) via {} '{}'",
                invoice.invoiceNumber(), tenant.getName(), event.capturedPayment().paymentId(),
                event.capturedPayment().currency(), event.capturedPayment().amountMinor(),
                gatewayLabel(event), event.rawEventName()
            );
        } catch (Exception e) {
            log.error(
                "Invoice generation failed for tenant '{}' on '{}' (subscription '{}') - plan state already applied; "
                    + "issue the invoice via a gateway webhook redelivery or reconcile manually",
                tenant.getName(), event.rawEventName(), event.gatewaySubscriptionId(), e
            );
        }
    }

    private static String gatewayLabel(BillingEvent event) {
        return event.gatewayName() != null ? event.gatewayName() : "billing-gateway";
    }
}
