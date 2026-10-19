package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.gateway.BillingEvent;
import com.agenticai.core.billing.gateway.BillingEventType;
import com.agenticai.core.billing.gateway.RazorpayGatewayClient;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B9-PR4 revenue-path contract - redelivery idempotency of {@link RazorpayWebhookService} as a
 * SEQUENCE, complementing {@code RazorpayWebhookServiceTest}'s per-event coverage. Razorpay retries
 * any delivery that didn't 2xx and can also legitimately send overlapping events (activated AND
 * charged for the same activation), so "the same event arrives twice" is routine production
 * traffic, not a corner case.
 *
 * <p>The single-event suite stubs each repository lookup statically; that cannot catch a regression
 * where the SECOND delivery re-applies the mutation, because the stub keeps answering as if the
 * first never happened. Here the repository mock answers FROM THE TENANT'S LIVE STATE (a real
 * {@link Tenant} object mutated by the service itself), so the second delivery flows through
 * whichever lookup path the first delivery's state change actually routes it to - the same thing
 * the real DB-backed lookups would do.
 *
 * <p>Honest scope note: "invoice created once" (the other half of the money loop) is still NOT
 * asserted here even now that {@code InvoiceService} IS wired in ({@code
 * recordInvoiceForCapturedPayment}): the service deliberately calls {@code recordPaymentInvoice} on
 * EVERY charge-carrying delivery and relies on InvoiceService's own gateway-payment-id idempotency
 * (application pre-check + V160's unique index) to make redeliveries return the same row - a
 * call-count assertion at this layer would pin the wrong contract. That idempotency is covered in
 * isolation by {@code InvoiceServiceTest} in core/billing. The stubbed events carry no captured
 * payment, so no test here invoices.
 */
class RazorpayWebhookRedeliveryIdempotencyTest {

    private static final String SUB_ID = "sub_live_1";

    private TenantRepository tenantRepository;
    private RazorpayGatewayClient paymentGatewayClient;
    private RazorpayWebhookService service;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        paymentGatewayClient = mock(RazorpayGatewayClient.class);
        service =
                new RazorpayWebhookService(
                        tenantRepository,
                        paymentGatewayClient,
                        mock(com.agenticai.core.billing.invoice.InvoiceService.class));

        tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);

        // State-driven lookups: answer exactly like the real queries would
        // against this tenant's CURRENT pending/live subscription columns,
        // so the service's own first-delivery mutations change what the
        // second delivery finds - the mechanism idempotency depends on.
        when(tenantRepository.findByPendingGatewaySubscriptionId(SUB_ID))
                .thenAnswer(
                        inv ->
                                SUB_ID.equals(tenant.getPendingGatewaySubscriptionId())
                                        ? Optional.of(tenant)
                                        : Optional.empty());
        when(tenantRepository.findByGatewaySubscriptionId(SUB_ID))
                .thenAnswer(
                        inv ->
                                SUB_ID.equals(tenant.getGatewaySubscriptionId())
                                        ? Optional.of(tenant)
                                        : Optional.empty());
    }

    private void stubEvent(String rawBody, BillingEventType type, String rawName) {
        when(paymentGatewayClient.parseWebhookEvent(rawBody))
                .thenReturn(new BillingEvent(type, SUB_ID, rawName));
    }

    @Test
    void chargedDeliveredTwiceFlipsThePlanOnceAndSavesOnce() {
        tenant.beginUpgrade(TenantPlan.PREMIUM, SUB_ID);
        stubEvent("raw", BillingEventType.SUBSCRIPTION_CHARGED, "subscription.charged");

        service.handleVerifiedEvent("raw");
        service.handleVerifiedEvent("raw");

        // First delivery confirms (pending -> live, plan flips, one save);
        // the second no longer matches the pending lookup, lands on the
        // live-subscription path, and a healthy paid tenant there is an
        // explicit no-op - so exactly one write total.
        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        assertNull(tenant.getPendingPlan());
        assertFalse(tenant.isPastDue());
        verify(tenantRepository, times(1)).save(tenant);
    }

    @Test
    void activatedThenChargedForTheSameActivationSavesOnce() {
        // Razorpay's normal happy path emits BOTH events for one paid
        // activation - not a retry, two distinct deliveries. Only the first
        // may write.
        tenant.beginUpgrade(TenantPlan.PREMIUM, SUB_ID);
        stubEvent(
                "raw-activated", BillingEventType.SUBSCRIPTION_ACTIVATED, "subscription.activated");
        stubEvent("raw-charged", BillingEventType.SUBSCRIPTION_CHARGED, "subscription.charged");

        service.handleVerifiedEvent("raw-activated");
        service.handleVerifiedEvent("raw-charged");

        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        verify(tenantRepository, times(1)).save(tenant);
        // The confirm recorded the activating event's name, and the healthy
        // follow-up charge didn't overwrite it.
        assertEquals("subscription.activated", tenant.getSubscriptionStatus());
    }

    @Test
    void cancelledDeliveredTwiceDowngradesOnceAndSavesOnce() {
        // A live paid tenant (upgrade already confirmed).
        tenant.beginUpgrade(TenantPlan.PREMIUM, SUB_ID);
        tenant.confirmUpgrade("subscription.activated");
        stubEvent("raw", BillingEventType.SUBSCRIPTION_CANCELLED, "subscription.cancelled");

        service.handleVerifiedEvent("raw");
        service.handleVerifiedEvent("raw");

        // First delivery downgrades PREMIUM -> FREE and saves. downgradeToFree
        // deliberately KEEPS gatewaySubscriptionId (support-reference posture,
        // see Tenant#downgradeToFree), so the redelivery still matches the
        // live lookup - it's the FREE-tenant guard, not a missed lookup, that
        // makes the second pass a no-op. That guard is what this pins.
        assertEquals(TenantPlan.FREE, tenant.getPlan());
        verify(tenantRepository, times(1)).save(tenant);
    }

    @Test
    void chargedRecoveryAfterHaltIsAppliedOnceAcrossRedeliveries() {
        tenant.beginUpgrade(TenantPlan.PREMIUM, SUB_ID);
        tenant.confirmUpgrade("subscription.activated");
        tenant.markPastDue("subscription.halted", java.time.Instant.now());
        stubEvent("raw", BillingEventType.SUBSCRIPTION_CHARGED, "subscription.charged");

        service.handleVerifiedEvent("raw");
        service.handleVerifiedEvent("raw");

        // First delivery closes the grace window (one save); the redelivery
        // sees a healthy tenant and writes nothing.
        assertFalse(tenant.isPastDue());
        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        verify(tenantRepository, times(1)).save(tenant);
    }
}
