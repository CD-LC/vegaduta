package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.gateway.SubscriptionPaymentExhaustedEvent;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * D5 dunning - {@link SubscriptionPaymentExhaustedListener}, the tenancy
 * half of {@code SubscriptionPendingHandler}'s failed-payment tracking. The
 * pinned contract: the downgrade goes through the EXISTING {@link
 * Tenant#downgradeToFree} transition (plan -> FREE, past-due closed,
 * gateway ids kept as support references), and the FREE-tenant guard makes
 * a duplicate delivery - or a trial subscription's lifecycle - a no-op
 * rather than a second write.
 */
class SubscriptionPaymentExhaustedListenerTest {

    private static final UUID TENANT_ID = UUID.fromString("3f0a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8");
    private static final String SUB_ID = "sub_42";

    private TenantRepository tenantRepository;
    private SubscriptionPaymentExhaustedListener listener;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        listener = new SubscriptionPaymentExhaustedListener(tenantRepository);
    }

    private static SubscriptionPaymentExhaustedEvent exhausted() {
        return new SubscriptionPaymentExhaustedEvent(TENANT_ID, "Acme", SUB_ID, 3);
    }

    @Test
    void paidTenantIsDowngradedThroughTheExistingPath() {
        Tenant tenant = new Tenant("Acme", TenantPlan.FREE, "acme", "agentic-ai",
            AccountType.B2C, ExperienceMode.ADVANCED);
        tenant.beginUpgrade(TenantPlan.PREMIUM, SUB_ID);
        tenant.confirmUpgrade("subscription.activated");
        tenant.markPastDue("subscription.pending", java.time.Instant.now());
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        listener.onPaymentAttemptsExhausted(exhausted());

        assertEquals(TenantPlan.FREE, tenant.getPlan());
        assertEquals(SubscriptionPaymentExhaustedListener.DUNNING_EXHAUSTED_STATUS, tenant.getSubscriptionStatus());
        // downgradeToFree's own contract: grace window closed, subscription id
        // kept as a support reference.
        assertFalse(tenant.isPastDue());
        assertNotNull(tenant.getGatewaySubscriptionId());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void freeTenantIsANoOp() {
        // A trial subscription's lifecycle, or a redelivered exhaustion for a
        // tenant already downgraded - either way, nothing to write.
        Tenant tenant = new Tenant("Acme", TenantPlan.FREE, "acme", "agentic-ai",
            AccountType.B2C, ExperienceMode.ADVANCED);
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        listener.onPaymentAttemptsExhausted(exhausted());

        verify(tenantRepository, never()).save(ArgumentMatchers.any());
    }

    @Test
    void unknownTenantDoesNotThrow() {
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> listener.onPaymentAttemptsExhausted(exhausted()));

        verify(tenantRepository, never()).save(ArgumentMatchers.any());
    }
}
