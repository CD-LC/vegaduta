package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.gateway.BillingEvent;
import com.agenticai.core.billing.gateway.BillingEventType;
import com.agenticai.core.billing.gateway.CapturedPaymentDetails;
import com.agenticai.core.billing.gateway.RazorpayGatewayClient;
import com.agenticai.core.billing.invoice.BillingInvoice;
import com.agenticai.core.billing.invoice.InvoiceService;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * No test previously existed for {@link RazorpayWebhookService} - the confirmation half of the #811
 * upgrade flow, and the one place {@code Tenant.plan} actually flips on a real payment. The
 * controller layer ({@link RazorpayWebhookController}) is intentionally not covered here - it's a
 * two-line pass-through (verify signature, delegate) with no branch logic of its own; this class is
 * where the actual event-handling behavior lives. Signature verification and BillingEvent parsing
 * themselves are already covered by {@code RazorpayGatewayClientTest} (core/billing) - not
 * duplicated here.
 */
class RazorpayWebhookServiceTest {

    private TenantRepository tenantRepository;
    private RazorpayGatewayClient paymentGatewayClient;
    private InvoiceService invoiceService;
    private RazorpayWebhookService service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        // Mockito's default answer returns Optional.empty() for the mock's
        // extractCapturedPayment, so no test here accidentally invoices.
        paymentGatewayClient = mock(RazorpayGatewayClient.class);
        invoiceService = mock(InvoiceService.class);
        service =
                new RazorpayWebhookService(tenantRepository, paymentGatewayClient, invoiceService);
    }

    private static Tenant pendingUpgradeTenant() {
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_123");
        return tenant;
    }

    @Test
    void subscriptionActivatedConfirmsThePendingUpgrade() {
        Tenant tenant = pendingUpgradeTenant();
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(
                        new BillingEvent(
                                BillingEventType.SUBSCRIPTION_ACTIVATED,
                                "sub_123",
                                "subscription.activated"));
        when(tenantRepository.findByPendingGatewaySubscriptionId("sub_123"))
                .thenReturn(Optional.of(tenant));

        service.handleVerifiedEvent("raw");

        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        assertNull(tenant.getPendingPlan());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void subscriptionChargedAlsoConfirmsThePendingUpgrade() {
        // The first real charge after an activation-without-immediate-charge
        // sequence should also confirm - not just "activated".
        Tenant tenant = pendingUpgradeTenant();
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(
                        new BillingEvent(
                                BillingEventType.SUBSCRIPTION_CHARGED,
                                "sub_123",
                                "subscription.charged"));
        when(tenantRepository.findByPendingGatewaySubscriptionId("sub_123"))
                .thenReturn(Optional.of(tenant));

        service.handleVerifiedEvent("raw");

        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
    }

    @Test
    void subscriptionCancelledClearsThePendingUpgradeWithoutChangingPlan() {
        Tenant tenant = pendingUpgradeTenant();
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(
                        new BillingEvent(
                                BillingEventType.SUBSCRIPTION_CANCELLED,
                                "sub_123",
                                "subscription.cancelled"));
        when(tenantRepository.findByPendingGatewaySubscriptionId("sub_123"))
                .thenReturn(Optional.of(tenant));

        service.handleVerifiedEvent("raw");

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertNull(tenant.getPendingPlan());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void subscriptionHaltedAlsoClearsThePendingUpgrade() {
        Tenant tenant = pendingUpgradeTenant();
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(
                        new BillingEvent(
                                BillingEventType.SUBSCRIPTION_HALTED,
                                "sub_123",
                                "subscription.halted"));
        when(tenantRepository.findByPendingGatewaySubscriptionId("sub_123"))
                .thenReturn(Optional.of(tenant));

        service.handleVerifiedEvent("raw");

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertNull(tenant.getPendingPlan());
    }

    @Test
    void subscriptionPendingEventDoesNothing() {
        // Not activated/charged (confirm) and not a terminal failure/cancel
        // (clear) - an intermediate lifecycle event should leave the pending
        // upgrade exactly as it was.
        Tenant tenant = pendingUpgradeTenant();
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(
                        new BillingEvent(
                                BillingEventType.SUBSCRIPTION_PENDING,
                                "sub_123",
                                "subscription.pending"));
        when(tenantRepository.findByPendingGatewaySubscriptionId("sub_123"))
                .thenReturn(Optional.of(tenant));

        service.handleVerifiedEvent("raw");

        assertEquals(TenantPlan.PREMIUM, tenant.getPendingPlan());
        verify(tenantRepository, never()).save(tenant);
    }

    @Test
    void eventWithNoSubscriptionIdIsIgnoredWithoutTouchingTheRepository() {
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(new BillingEvent(BillingEventType.UNKNOWN, null, "some.other.event"));

        service.handleVerifiedEvent("raw");

        verify(tenantRepository, never()).findByPendingGatewaySubscriptionId(anyString());
        verify(tenantRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void eventForASubscriptionWithNoPendingUpgradeIsIgnored() {
        // E.g. the signup-time trial subscription's own lifecycle, or a
        // stale/duplicate delivery for an upgrade already confirmed - see
        // this class's own javadoc. Must not throw.
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(
                        new BillingEvent(
                                BillingEventType.SUBSCRIPTION_ACTIVATED,
                                "sub_untracked",
                                "subscription.activated"));
        when(tenantRepository.findByPendingGatewaySubscriptionId("sub_untracked"))
                .thenReturn(Optional.empty());

        service.handleVerifiedEvent("raw");

        verify(tenantRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lateDuplicateActivatedEventAfterAlreadyConfirmedIsANoOp() {
        // Same subscription id, but this tenant's pending fields were
        // already cleared by an earlier delivery of the same event -
        // findByPendingGatewaySubscriptionId would no longer match it, so
        // this is really the same case as "no pending upgrade tracked" -
        // asserting it explicitly since it's the exact retry scenario
        // Razorpay's own delivery-retry behavior (documented on
        // RazorpayWebhookController) makes routine, not a corner case.
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(
                        new BillingEvent(
                                BillingEventType.SUBSCRIPTION_ACTIVATED,
                                "sub_123",
                                "subscription.activated"));
        when(tenantRepository.findByPendingGatewaySubscriptionId("sub_123"))
                .thenReturn(Optional.empty());

        service.handleVerifiedEvent("raw");

        verify(tenantRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aChargeCarryingACapturedPaymentIsInvoicedFromTheParsedEvent() {
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_123");
        when(paymentGatewayClient.gatewayName()).thenReturn("razorpay");
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(
                        new BillingEvent(
                                BillingEventType.SUBSCRIPTION_CHARGED,
                                "sub_123",
                                "subscription.charged",
                                "razorpay",
                                new CapturedPaymentDetails("pay_1", 49_900L, "INR")));
        when(tenantRepository.findByPendingGatewaySubscriptionId("sub_123"))
                .thenReturn(Optional.of(tenant));
        when(invoiceService.recordPaymentInvoice(
                        org.mockito.ArgumentMatchers.any(),
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        anyString(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(mock(BillingInvoice.class));

        service.handleVerifiedEvent("raw");

        verify(invoiceService)
                .recordPaymentInvoice(
                        tenant.getId(), "razorpay", "pay_1", "PREMIUM", null, "INR", 49_900L);
    }

    // ------------------------------------------------------------------
    // D5 (PRICING-MONETIZATION-AUDIT-2026-07-31 §8.7) - lifecycle events on
    // a tenant's LIVE, already-confirmed paid subscription. Before D5 these
    // were all silently ignored - a cancelled PREMIUM stayed PREMIUM forever.
    // ------------------------------------------------------------------

    /**
     * A tenant whose upgrade already confirmed - gatewaySubscriptionId is live, nothing pending.
     */
    private static Tenant paidTenant() {
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        tenant.recordGatewaySubscription("razorpay", "sub_trial_0", "created", null);
        tenant.beginUpgrade(TenantPlan.PREMIUM, "sub_live");
        tenant.confirmUpgrade("subscription.activated");
        return tenant;
    }

    private void stubLiveEvent(BillingEventType type, String rawEventName, Tenant tenant) {
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(new BillingEvent(type, "sub_live", rawEventName));
        when(tenantRepository.findByPendingGatewaySubscriptionId("sub_live"))
                .thenReturn(Optional.empty());
        when(tenantRepository.findByGatewaySubscriptionId("sub_live"))
                .thenReturn(Optional.of(tenant));
    }

    @Test
    void haltedOnALivePaidSubscriptionMarksPastDueWithoutDowngrading() {
        Tenant tenant = paidTenant();
        stubLiveEvent(BillingEventType.SUBSCRIPTION_HALTED, "subscription.halted", tenant);

        service.handleVerifiedEvent("raw");

        // Grace, not punishment: the plan survives the halt.
        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        assertTrue(tenant.isPastDue());
        assertEquals("subscription.halted", tenant.getSubscriptionStatus());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void redeliveredHaltedEventDoesNotResetThePastDueClock() {
        Tenant tenant = paidTenant();
        stubLiveEvent(BillingEventType.SUBSCRIPTION_HALTED, "subscription.halted", tenant);

        service.handleVerifiedEvent("raw");
        java.time.Instant firstHalt = tenant.getPastDueSince();
        service.handleVerifiedEvent("raw");

        // First halt wins - Razorpay's retry deliveries must not stretch the grace window.
        assertEquals(firstHalt, tenant.getPastDueSince());
    }

    @Test
    void cancelledOnALivePaidSubscriptionDowngradesToFree() {
        Tenant tenant = paidTenant();
        tenant.scheduleCancellation("active"); // the tenant-initiated D5 cancel reaching cycle end
        stubLiveEvent(BillingEventType.SUBSCRIPTION_CANCELLED, "subscription.cancelled", tenant);

        service.handleVerifiedEvent("raw");

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertFalse(tenant.isCancelScheduled());
        assertFalse(tenant.isPastDue());
        assertEquals("subscription.cancelled", tenant.getSubscriptionStatus());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void completedOnALivePaidSubscriptionAlsoDowngradesToFree() {
        Tenant tenant = paidTenant();
        stubLiveEvent(BillingEventType.SUBSCRIPTION_COMPLETED, "subscription.completed", tenant);

        service.handleVerifiedEvent("raw");

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void chargedOnAPastDueLiveSubscriptionClearsThePastDueGraceWindow() {
        Tenant tenant = paidTenant();
        tenant.markPastDue("subscription.halted", java.time.Instant.now());
        stubLiveEvent(BillingEventType.SUBSCRIPTION_CHARGED, "subscription.charged", tenant);

        service.handleVerifiedEvent("raw");

        assertFalse(tenant.isPastDue());
        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        assertEquals("subscription.charged", tenant.getSubscriptionStatus());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void chargedOnAHealthyLiveSubscriptionIsANoOp() {
        // A routine renewal charge - nothing to change, nothing written.
        Tenant tenant = paidTenant();
        stubLiveEvent(BillingEventType.SUBSCRIPTION_CHARGED, "subscription.charged", tenant);

        service.handleVerifiedEvent("raw");

        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        verify(tenantRepository, never()).save(tenant);
    }

    @Test
    void liveSubscriptionEventForAFreeTenantIsIgnored() {
        // The signup-time trial subscription's own lifecycle on a tenant that
        // never upgraded - this class's javadoc has always deliberately left
        // it alone, and the D5 live-subscription path must keep doing so.
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        tenant.recordGatewaySubscription("razorpay", "sub_live", "created", null);
        stubLiveEvent(BillingEventType.SUBSCRIPTION_CANCELLED, "subscription.cancelled", tenant);

        service.handleVerifiedEvent("raw");

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        verify(tenantRepository, never()).save(tenant);
    }

    @Test
    void pendingUpgradeMatchStillTakesPrecedenceOverTheLiveSubscriptionLookup() {
        // The pre-D5 pending-upgrade behavior must stay byte-for-byte: when a
        // subscription id matches a pending upgrade, the live-subscription
        // lookup is never even consulted.
        Tenant tenant = pendingUpgradeTenant();
        when(paymentGatewayClient.parseWebhookEvent("raw"))
                .thenReturn(
                        new BillingEvent(
                                BillingEventType.SUBSCRIPTION_CANCELLED,
                                "sub_123",
                                "subscription.cancelled"));
        when(tenantRepository.findByPendingGatewaySubscriptionId("sub_123"))
                .thenReturn(Optional.of(tenant));

        service.handleVerifiedEvent("raw");

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertNull(tenant.getPendingPlan());
        verify(tenantRepository, never()).findByGatewaySubscriptionId(anyString());
    }
}
