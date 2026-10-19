package com.agenticai.core.agents.tools.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.commerce.CheckoutSession;
import com.agenticai.core.billing.commerce.CheckoutSessionService;
import com.agenticai.core.billing.commerce.CheckoutSessionStatus;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CheckPaymentStatusToolCallback} - same manual-mock convention as {@code
 * RequestHumanToolCallbackTest}.
 */
class CheckPaymentStatusToolCallbackTest {

    private static final UUID ID = UUID.fromString("7a1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b");

    private CheckoutSessionService service;
    private CheckPaymentStatusToolCallback callback;

    @BeforeEach
    void setUp() {
        service = mock(CheckoutSessionService.class);
        callback = new CheckPaymentStatusToolCallback(service);
        TenantContext.set(TenantContext.of("tenant_acme", "user@acme", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static CheckoutSession session(CheckoutSessionStatus status, String paymentId) {
        Instant paidAt =
                status == CheckoutSessionStatus.COMPLETED
                        ? Instant.parse("2026-09-09T10:05:00Z")
                        : null;
        return new CheckoutSession(
                ID,
                "tenant_acme",
                UUID.randomUUID(),
                "sess-1",
                "agent",
                null,
                null,
                "INR",
                49_900L,
                "2x dosa",
                "[]",
                status,
                "razorpay",
                "plink_1",
                "https://rzp.io/i/abc",
                paidAt,
                paymentId,
                null,
                Instant.parse("2026-09-09T10:00:00Z"),
                Instant.parse("2026-09-09T10:00:00Z"),
                Instant.parse("2026-09-10T10:00:00Z"));
    }

    @Test
    void toolDefinitionHasExpectedNameAndRequiredArg() {
        assertEquals("check_payment_status", callback.getToolDefinition().name());
        assertTrue(callback.getToolDefinition().inputSchema().contains("\"checkout_session_id\""));
    }

    @Test
    void reportsPaidTrueWithPaidAtAndPaymentIdOnceCompleted() {
        when(service.getForTenant(ID, "tenant_acme"))
                .thenReturn(Optional.of(session(CheckoutSessionStatus.COMPLETED, "pay_9")));

        String out = callback.call("{\"checkout_session_id\": \"" + ID + "\"}");

        assertTrue(out.contains("\"paid\":true"), out);
        assertTrue(out.contains("\"status\":\"completed\""), out);
        assertTrue(out.contains("\"paidAt\":\"2026-09-09T10:05:00Z\""), out);
        assertTrue(out.contains("\"paymentId\":\"pay_9\""), out);
    }

    @Test
    void reportsPaidFalseWhileStillWaiting() {
        when(service.getForTenant(ID, "tenant_acme"))
                .thenReturn(Optional.of(session(CheckoutSessionStatus.READY_FOR_PAYMENT, null)));

        String out = callback.call("{\"checkout_session_id\": \"" + ID + "\"}");

        assertTrue(out.contains("\"paid\":false"), out);
        assertTrue(out.contains("\"status\":\"ready_for_payment\""), out);
        assertTrue(out.contains("\"paidAt\":null"), out);
    }

    @Test
    void anotherTenantsSessionLooksNonexistent() {
        // The lookup is keyed on (id, tenant) - the service returns empty
        // for a foreign id and the tool must not leak more than "no such".
        when(service.getForTenant(any(), anyString())).thenReturn(Optional.empty());

        String out = callback.call("{\"checkout_session_id\": \"" + ID + "\"}");

        assertTrue(out.contains("\"error\""), out);
        assertTrue(out.contains("No payment request"), out);
        verify(service).getForTenant(ID, "tenant_acme");
    }

    @Test
    void rejectsMissingOrMalformedIdsWithoutTouchingTheService() {
        assertTrue(callback.call("{}").contains("checkout_session_id is required"));
        assertTrue(
                callback.call("{\"checkout_session_id\": \"not-a-uuid\"}")
                        .contains("not a valid id"));
        verify(service, never()).getForTenant(any(), anyString());
    }

    @Test
    void refusesWithoutATenantContext() {
        TenantContext.clear();

        String out = callback.call("{\"checkout_session_id\": \"" + ID + "\"}");

        assertTrue(out.contains("\"error\""), out);
        verify(service, never()).getForTenant(any(), anyString());
    }

    @Test
    void anUnexpectedServiceFailureIsCleanJsonNotAThrow() {
        when(service.getForTenant(any(), anyString()))
                .thenThrow(new RuntimeException("db \"down\""));

        String out = callback.call("{\"checkout_session_id\": \"" + ID + "\"}");

        assertTrue(out.startsWith("{") && out.endsWith("}"), out);
        assertTrue(out.contains("Could not check the payment status"), out);
    }
}
