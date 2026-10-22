package com.agenticai.core.tenancy;

import com.agenticai.core.billing.gateway.BillingEvent;
import com.agenticai.core.billing.gateway.PaymentGatewayClient;
import com.agenticai.core.billing.gateway.PaymentGatewayRegistry;
import com.agenticai.core.billing.invoice.BillingInvoice;
import com.agenticai.core.billing.invoice.InvoiceService;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.logging.LogSafe;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * #811 - the piece the signup flow's own comments already anticipated but that never got built
 * ("Stage 4's webhook handler" - see {@code SignupCompletePage}/{@code signup/page.js}'s comments
 * in {@code web/}, both written expecting this to exist already). Only {@link #handleVerifiedEvent}
 * is called from a signature-verified request (see {@link RazorpayWebhookController}) - this class
 * never re-verifies.
 *
 * <p>Reacts to two kinds of subscription, tried in order per event:
 *
 * <ol>
 *   <li><b>Pending upgrades</b> (matched via {@link
 *       TenantRepository#findByPendingGatewaySubscriptionId}) - the original #811 confirm/abandon
 *       flow, unchanged.
 *   <li><b>Live paid subscriptions</b> (D5, PRICING-MONETIZATION-AUDIT- 2026-07-31 §8.7 - matched
 *       via {@link TenantRepository#findByGatewaySubscriptionId}) - an already-paying tenant's
 *       halted (→ past-due grace), cancelled/completed (→ FREE downgrade), and recovery events. See
 *       {@link #handleLiveSubscriptionEvent}'s state-machine javadoc.
 * </ol>
 *
 * <p>The signup-time trial subscription every tenant already gets (Stage 3, {@code
 * TenantProvisioningService}) is still intentionally left alone: a FREE tenant matched by the
 * second lookup is skipped there. That subscription's own lifecycle (trial ending, first real
 * charge) remains a separate, not-yet-decided question this service deliberately does not resolve.
 */
@Service
@Profile("security")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class RazorpayWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);

    private final TenantRepository tenantRepository;

    /**
     * The gateway whose webhook this class handles, as registered in {@link
     * PaymentGatewayRegistry}.
     */
    static final String GATEWAY = "razorpay";

    // #825 Multi-gateway follow-up - this class only ever handles Razorpay's own
    // webhook. Resolved by name from the registry rather than injected by type
    // (ambiguous once PhonePeGatewayClient is a second PaymentGatewayClient
    // bean), and held as the gateway-neutral interface (ADR-0011 rule a).
    private final PaymentGatewayClient paymentGatewayClient;
    // D6 (GST invoicing) - the wiring InvoiceService's own javadoc reserved for
    // this class: one immutable invoice row per captured payment.
    private final InvoiceService invoiceService;

    @Autowired
    public RazorpayWebhookService(
            TenantRepository tenantRepository,
            PaymentGatewayRegistry gatewayRegistry,
            InvoiceService invoiceService) {
        this(tenantRepository, gatewayRegistry.resolve(GATEWAY), invoiceService);
    }

    RazorpayWebhookService(
            TenantRepository tenantRepository,
            PaymentGatewayClient paymentGatewayClient,
            InvoiceService invoiceService) {
        this.tenantRepository = tenantRepository;
        this.paymentGatewayClient = paymentGatewayClient;
        this.invoiceService = invoiceService;
    }

    /**
     * Caller ({@link RazorpayWebhookController}) has already called {@code verifyWebhookSignature}
     * - this only parses and applies.
     */
    public void handleVerifiedEvent(String rawBody) {
        BillingEvent event = paymentGatewayClient.parseWebhookEvent(rawBody);
        if (event.gatewaySubscriptionId() == null) {
            log.info(
                    "Razorpay webhook '{}' carried no subscription id - nothing to apply",
                    LogSafe.clean(event.rawEventName()));
            return;
        }

        Optional<Tenant> pendingTenant =
                tenantRepository.findByPendingGatewaySubscriptionId(event.gatewaySubscriptionId());
        if (pendingTenant.isEmpty()) {
            // Not a pending upgrade. D5 (PRICING-MONETIZATION-AUDIT-2026-07-31
            // §8.7): before giving up, check whether this is an event about a
            // tenant's LIVE, already-confirmed subscription - a paying
            // tenant's cancel/halt/completion used to be silently ignored
            // here, leaving them PREMIUM forever. Anything matching neither
            // lookup is the same benign noise as before (a FREE tenant's
            // dormant signup-time trial subscription, or a stale/duplicate
            // delivery for an upgrade already confirmed) - Razorpay retries
            // webhooks it doesn't get a 2xx for, so silently ignoring those
            // is correct, not a bug to log loudly about.
            Optional<Tenant> activeTenant =
                    tenantRepository.findByGatewaySubscriptionId(event.gatewaySubscriptionId());
            if (activeTenant.isPresent()) {
                handleLiveSubscriptionEvent(activeTenant.get(), event);
                return;
            }
            log.debug(
                    "Razorpay webhook '{}' for subscription '{}' - no pending upgrade or live subscription tracked for it, ignoring",
                    LogSafe.clean(event.rawEventName()),
                    LogSafe.clean(event.gatewaySubscriptionId()));
            return;
        }

        Tenant tenant = pendingTenant.get();
        switch (event.type()) {
            case SUBSCRIPTION_ACTIVATED, SUBSCRIPTION_CHARGED -> {
                tenant.confirmUpgrade(event.rawEventName());
                tenantRepository.save(tenant);
                log.info(
                        "Tenant '{}' upgrade confirmed via Razorpay '{}'",
                        tenant.getName(),
                        LogSafe.clean(event.rawEventName()));
                recordInvoiceForCapturedPayment(tenant, event);
            }
            case SUBSCRIPTION_CANCELLED, SUBSCRIPTION_HALTED, SUBSCRIPTION_COMPLETED -> {
                tenant.cancelPendingUpgrade();
                tenantRepository.save(tenant);
                log.info(
                        "Tenant '{}' pending upgrade cleared via Razorpay '{}' - plan unchanged",
                        tenant.getName(),
                        LogSafe.clean(event.rawEventName()));
            }
            case SUBSCRIPTION_AUTHENTICATED ->
                    // Mandate registration succeeded (card or UPI Autopay - Razorpay
                    // does not emit a separate UPI-specific event, see BillingEventType's
                    // javadoc) but no payment has been captured yet, so the upgrade stays
                    // pending until ACTIVATED/CHARGED confirms it. Logged distinctly from
                    // the generic default below purely for support/debugging visibility
                    // into where a stalled UPI Autopay signup is stuck.
                    log.info(
                            "Tenant '{}' Razorpay subscription '{}' authenticated (mandate registered) - awaiting first charge before upgrade is confirmed",
                            tenant.getName(),
                            LogSafe.clean(event.gatewaySubscriptionId()));
            case SUBSCRIPTION_UPDATED ->
                    // Subscription attributes changed gateway-side (e.g. a scheduled
                    // plan/quantity change taking effect). Doesn't by itself confirm or
                    // cancel a pending upgrade - ACTIVATED/CHARGED/CANCELLED/HALTED/
                    // COMPLETED remain the only events that mutate tenant state here.
                    log.info(
                            "Tenant '{}' Razorpay subscription '{}' updated gateway-side - no tenant state change applied",
                            tenant.getName(),
                            LogSafe.clean(event.gatewaySubscriptionId()));
            default ->
                    log.debug(
                            "Razorpay webhook '{}' for tenant '{}' pending upgrade - no action needed for this event type",
                            LogSafe.clean(event.rawEventName()),
                            tenant.getName());
        }
    }

    /**
     * D5 - lifecycle events for a subscription that already IS this tenant's confirmed paid
     * subscription ({@code Tenant.gatewaySubscriptionId}, set by {@code Tenant#confirmUpgrade}).
     * Guarded to paid plans only: a FREE tenant matched here can only be the signup-time trial
     * subscription's own dormant lifecycle, which this class's javadoc has always deliberately left
     * alone (or a tenant already downgraded by an earlier delivery of this same event - equally a
     * no-op).
     *
     * <p>The state machine, per event:
     *
     * <ul>
     *   <li>HALTED - every charge retry failed. Open the past-due grace window ({@code
     *       Tenant#markPastDue}); the plan is NOT touched - cancelled/completed below is the only
     *       downgrade trigger.
     *   <li>CANCELLED / COMPLETED - the subscription is finished for good (tenant-initiated cancel
     *       reaching cycle end, gateway-side cancel, or the last billing cycle completing).
     *       Downgrade to FREE ({@code Tenant#downgradeToFree}).
     *   <li>ACTIVATED / CHARGED / RESUMED while past-due - the customer fixed their payment method:
     *       close the grace window, plan keeps being paid ({@code Tenant#clearPastDue}).
     * </ul>
     */
    private void handleLiveSubscriptionEvent(Tenant tenant, BillingEvent event) {
        if (tenant.getPlan() == TenantPlan.FREE) {
            log.debug(
                    "Razorpay webhook '{}' for FREE tenant '{}' subscription '{}' - trial-subscription lifecycle, deliberately untouched",
                    LogSafe.clean(event.rawEventName()),
                    tenant.getName(),
                    LogSafe.clean(event.gatewaySubscriptionId()));
            return;
        }
        switch (event.type()) {
            case SUBSCRIPTION_HALTED -> {
                tenant.markPastDue(event.rawEventName(), Instant.now());
                tenantRepository.save(tenant);
                log.warn(
                        "Tenant '{}' paid subscription '{}' HALTED (payment retries exhausted) - marked past-due since {}, plan {} kept during grace",
                        tenant.getName(),
                        LogSafe.clean(event.gatewaySubscriptionId()),
                        tenant.getPastDueSince(),
                        tenant.getPlan());
            }
            case SUBSCRIPTION_CANCELLED, SUBSCRIPTION_COMPLETED -> {
                TenantPlan previousPlan = tenant.getPlan();
                tenant.downgradeToFree(event.rawEventName());
                tenantRepository.save(tenant);
                log.info(
                        "Tenant '{}' paid subscription '{}' ended via Razorpay '{}' - downgraded {} -> FREE",
                        tenant.getName(),
                        LogSafe.clean(event.gatewaySubscriptionId()),
                        LogSafe.clean(event.rawEventName()),
                        previousPlan);
            }
            case SUBSCRIPTION_ACTIVATED, SUBSCRIPTION_CHARGED, SUBSCRIPTION_RESUMED -> {
                if (tenant.isPastDue()) {
                    tenant.clearPastDue(event.rawEventName());
                    tenantRepository.save(tenant);
                    log.info(
                            "Tenant '{}' paid subscription '{}' recovered via Razorpay '{}' - past-due cleared, plan {} unchanged",
                            tenant.getName(),
                            LogSafe.clean(event.gatewaySubscriptionId()),
                            LogSafe.clean(event.rawEventName()),
                            tenant.getPlan());
                } else {
                    // A routine renewal charge on a healthy subscription -
                    // nothing to change on the tenant; the invoice below is
                    // the only record this event needs to leave.
                    log.debug(
                            "Tenant '{}' live subscription '{}' event '{}' - healthy, no state change",
                            tenant.getName(),
                            LogSafe.clean(event.gatewaySubscriptionId()),
                            LogSafe.clean(event.rawEventName()));
                }
                recordInvoiceForCapturedPayment(tenant, event);
            }
            default ->
                    log.debug(
                            "Razorpay webhook '{}' for tenant '{}' live subscription - no action needed for this event type",
                            LogSafe.clean(event.rawEventName()),
                            tenant.getName());
        }
    }

    /**
     * D6 (GST invoicing, PRICING-MONETIZATION-AUDIT-2026-07-31 §10) - record one immutable {@code
     * billing_invoice} row for the captured payment a verified charge event carries. Only
     * charge-carrying webhooks have a {@code payload.payment.entity} block ({@code
     * subscription.charged}; a chargeless activation doesn't), so an empty extraction is the normal
     * no-payment case and writes nothing. {@link InvoiceService} is idempotent on the payment id,
     * so Razorpay redeliveries never double-invoice. Failures are logged, never re-raised: the
     * tenant plan state has already been applied and saved, and failing the webhook now would only
     * make Razorpay redeliver an event whose state changes are all idempotent anyway - the retry is
     * exactly how a transiently-lost invoice gets issued after all.
     *
     * <p>The billing period (MONTHLY/ANNUAL) is not tracked on the tenant row and the webhook
     * doesn't state it, so it is recorded as null - nullable on the invoice row and rendered
     * accordingly.
     */
    private void recordInvoiceForCapturedPayment(Tenant tenant, BillingEvent event) {
        try {
            // parseWebhookEvent already extracted the captured payment from this same body.
            java.util.Optional.ofNullable(event.capturedPayment())
                    .ifPresent(
                            payment -> {
                                BillingInvoice invoice =
                                        invoiceService.recordPaymentInvoice(
                                                tenant.getId(),
                                                paymentGatewayClient.gatewayName(),
                                                payment.paymentId(),
                                                tenant.getPlan() != null
                                                        ? tenant.getPlan().name()
                                                        : null,
                                                null,
                                                payment.currency(),
                                                payment.amountMinor());
                                log.info(
                                        "Invoice {} recorded for tenant '{}' payment '{}' ({} {} minor units) via Razorpay '{}'",
                                        invoice.invoiceNumber(),
                                        tenant.getName(),
                                        LogSafe.clean(payment.paymentId()),
                                        LogSafe.clean(payment.currency()),
                                        payment.amountMinor(),
                                        LogSafe.clean(event.rawEventName()));
                            });
        } catch (Exception e) {
            log.error(
                    "Invoice generation failed for tenant '{}' on '{}' (subscription '{}') - plan state already applied; "
                            + "issue the invoice via a gateway webhook redelivery or reconcile manually against the Razorpay dashboard",
                    tenant.getName(),
                    LogSafe.clean(event.rawEventName()),
                    LogSafe.clean(event.gatewaySubscriptionId()),
                    e);
        }
    }
}
