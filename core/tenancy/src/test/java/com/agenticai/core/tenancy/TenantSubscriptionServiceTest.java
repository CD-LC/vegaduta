package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.gateway.PaymentGatewayClient;
import com.agenticai.core.billing.gateway.PaymentGatewayException;
import com.agenticai.core.billing.gateway.PaymentGatewayRegistry;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.TenantSubscriptionStatusResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * D5 (PRICING-MONETIZATION-AUDIT-2026-07-31 §8.7) - the self-serve cancellation half, mirroring
 * {@link TenantUpgradeServiceTest}'s style (plain Mockito, {@link TenantContext} set/cleared per
 * test). The webhook side of the same lifecycle (halted -> past-due, cancelled -> FREE) lives in
 * {@link RazorpayWebhookServiceTest}; {@link TenantTest} has the entity state machine.
 */
class TenantSubscriptionServiceTest {

    private TenantRepository tenantRepository;
    private PaymentGatewayRegistry paymentGatewayRegistry;
    private PaymentGatewayClient paymentGatewayClient;
    private TenantSubscriptionService service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        paymentGatewayRegistry = mock(PaymentGatewayRegistry.class);
        paymentGatewayClient = mock(PaymentGatewayClient.class);
        when(paymentGatewayRegistry.resolve(any())).thenReturn(paymentGatewayClient);
        // The default fixture is a deployment whose gateway credentials are
        // present - cancel() refuses an unconfigured gateway before calling
        // it (see cancelWhenTheHoldingGatewayIsNotConfiguredIsRejected...).
        when(paymentGatewayClient.isConfigured()).thenReturn(true);
        when(paymentGatewayClient.gatewayName()).thenReturn("razorpay");
        service = new TenantSubscriptionService(tenantRepository, paymentGatewayRegistry);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void setContext(String schema) {
        TenantContext.set(TenantContext.of(schema, "keycloak-user-1", Set.of()));
    }

    /** A tenant whose paid upgrade already confirmed - the state a real cancel starts from. */
    private Tenant paidAcmeTenant() {
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
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));
        return tenant;
    }

    @Test
    void cancelCallsTheGatewayCancelAtCycleEndAndRecordsIntentWithoutTouchingThePlan() {
        setContext("acme");
        Tenant tenant = paidAcmeTenant();
        when(paymentGatewayClient.cancelSubscription("sub_live", true)).thenReturn("active");

        TenantSubscriptionStatusResponse response = service.cancel();

        // Always cancel_at_cycle_end - the customer keeps what they paid for.
        verify(paymentGatewayClient).cancelSubscription("sub_live", true);
        // The gateway that HOLDS the subscription is resolved from the tenant
        // row, never a caller-supplied name.
        verify(paymentGatewayRegistry).resolve("razorpay");
        // Intent recorded, plan untouched - the webhook's cancelled event at
        // cycle end is what actually downgrades (RazorpayWebhookServiceTest).
        assertTrue(tenant.isCancelScheduled());
        assertEquals(TenantPlan.PREMIUM, tenant.getPlan());
        assertEquals("active", tenant.getSubscriptionStatus());
        verify(tenantRepository).save(tenant);
        assertEquals(TenantPlan.PREMIUM, response.plan());
        assertTrue(response.cancelScheduled());
        assertFalse(response.pastDue());
    }

    @Test
    void cancelOnAFreePlanIsRejected() {
        setContext("acme");
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

        assertThrows(TenantUpgradeException.class, () -> service.cancel());
        verify(paymentGatewayClient, never()).cancelSubscription(anyString(), anyBoolean());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void cancelOnAComplimentaryGrantPlanIsRejected() {
        // A comp-grant PREMIUM (scholarship/trial/referral) has no gateway
        // subscription behind its plan - it lapses via CompGrantExpiryReverter,
        // and the dormant signup-time trial subscription must NOT get cancelled
        // in its name.
        setContext("acme");
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        tenant.recordGatewaySubscription("razorpay", "sub_trial_0", "created", null);
        tenant.grantComplimentaryPlan(
                TenantPlan.PREMIUM,
                Instant.now(),
                Instant.now().plusSeconds(86400),
                "event-trial:whatsapp-gate");
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

        assertThrows(TenantUpgradeException.class, () -> service.cancel());
        verify(paymentGatewayClient, never()).cancelSubscription(anyString(), anyBoolean());
    }

    @Test
    void cancelWithNoGatewaySubscriptionOnRecordIsRejected() {
        // E.g. a platform-admin changePlan override - paid plan, no gateway row.
        setContext("acme");
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        tenant.changePlan(TenantPlan.PREMIUM);
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

        assertThrows(TenantUpgradeException.class, () -> service.cancel());
        verify(paymentGatewayClient, never()).cancelSubscription(anyString(), anyBoolean());
    }

    @Test
    void cancelWhenAlreadyScheduledIsRejectedWithoutASecondGatewayCall() {
        setContext("acme");
        Tenant tenant = paidAcmeTenant();
        tenant.scheduleCancellation("active");

        assertThrows(TenantUpgradeException.class, () -> service.cancel());
        verify(paymentGatewayClient, never()).cancelSubscription(anyString(), anyBoolean());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void cancelWhenTheHoldingGatewayIsNotConfiguredIsRejectedBeforeAnyGatewayCall() {
        // Credentials rotated/removed after the subscription was created: a
        // clear 400 instead of an opaque gateway 401, and no tenant state written.
        setContext("acme");
        Tenant tenant = paidAcmeTenant();
        when(paymentGatewayClient.isConfigured()).thenReturn(false);

        TenantUpgradeException ex =
                assertThrows(TenantUpgradeException.class, () -> service.cancel());
        assertTrue(ex.getMessage().contains("'razorpay' payment gateway is not configured"));
        verify(paymentGatewayClient, never()).cancelSubscription(anyString(), anyBoolean());
        assertFalse(tenant.isCancelScheduled());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void gatewayFailureOnCancelPropagatesWithoutRecordingIntent() {
        // If Razorpay rejects the cancel, nothing app-side may claim it happened.
        setContext("acme");
        Tenant tenant = paidAcmeTenant();
        when(paymentGatewayClient.cancelSubscription("sub_live", true))
                .thenThrow(
                        new PaymentGatewayException(
                                "Could not cancel Razorpay subscription: boom", null));

        assertThrows(PaymentGatewayException.class, () -> service.cancel());
        assertFalse(tenant.isCancelScheduled());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void cancelOnAnUnconfiguredGatewayFailsCleanlyBeforeTouchingAnything() {
        // Credentials rotated or removed after the subscription was created: a clear
        // TenantUpgradeException naming the gateway, no gateway call, no state written.
        setContext("acme");
        Tenant tenant = paidAcmeTenant();
        when(paymentGatewayClient.isConfigured()).thenReturn(false);

        TenantUpgradeException e =
                assertThrows(TenantUpgradeException.class, () -> service.cancel());
        assertTrue(
                e.getMessage().contains("'razorpay' payment gateway is not configured"),
                e.getMessage());
        verify(paymentGatewayClient, never()).cancelSubscription(anyString(), anyBoolean());
        assertFalse(tenant.isCancelScheduled());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void statusReflectsAPastDueGraceWindow() {
        setContext("acme");
        Tenant tenant = paidAcmeTenant();
        Instant haltedAt = Instant.now();
        tenant.markPastDue("subscription.halted", haltedAt);

        TenantSubscriptionStatusResponse response = service.status();

        assertEquals(TenantPlan.PREMIUM, response.plan());
        assertEquals("subscription.halted", response.subscriptionStatus());
        assertTrue(response.pastDue());
        assertEquals(haltedAt, response.pastDueSince());
        assertFalse(response.cancelScheduled());
    }

    @Test
    void statusForAFreshFreeTenantIsAllQuiet() {
        setContext("acme");
        Tenant tenant =
                new Tenant(
                        "Acme",
                        TenantPlan.FREE,
                        "acme",
                        "agentic-ai",
                        AccountType.B2C,
                        ExperienceMode.ADVANCED);
        when(tenantRepository.findBySchemaName("acme")).thenReturn(Optional.of(tenant));

        TenantSubscriptionStatusResponse response = service.status();

        assertEquals(TenantPlan.FREE, response.plan());
        assertFalse(response.cancelScheduled());
        assertFalse(response.pastDue());
    }

    @Test
    void noTenantContextThrowsIllegalState() {
        assertThrows(IllegalStateException.class, () -> service.status());
        assertThrows(IllegalStateException.class, () -> service.cancel());
    }
}
