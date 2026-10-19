package com.agenticai.core.agents.tools.commerce;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.commerce.CheckoutSession;
import com.agenticai.core.billing.commerce.CheckoutSessionCompletedEvent;
import com.agenticai.core.billing.commerce.CheckoutSessionStatus;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link CheckoutPaymentReceivedWorkflowEventBridge}: the tenant-context handling around the
 * publish is the whole reason the class exists, so it is what gets pinned - the publisher must
 * observe the session's tenant as the current context, and the thread must be left as it was found.
 */
class CheckoutPaymentReceivedWorkflowEventBridgeTest {

    private final WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
    private final CheckoutPaymentReceivedWorkflowEventBridge bridge =
            new CheckoutPaymentReceivedWorkflowEventBridge(publisher);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static CheckoutSession completed(String tenantId) {
        return new CheckoutSession(
                UUID.fromString("7a1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b"),
                tenantId,
                UUID.fromString("0f2c1b3a-4d5e-6f70-8192-a3b4c5d6e7f8"),
                "sess-1",
                "agent",
                "+919876543210",
                null,
                "INR",
                49_900L,
                "2x dosa",
                "[]",
                CheckoutSessionStatus.COMPLETED,
                "razorpay",
                "plink_1",
                "https://rzp.io/i/abc",
                Instant.parse("2026-09-09T10:05:00Z"),
                "pay_9",
                null,
                Instant.parse("2026-09-09T10:00:00Z"),
                Instant.parse("2026-09-09T10:05:00Z"),
                Instant.parse("2026-09-10T10:00:00Z"));
    }

    @Test
    void publishesUnderTheSessionsTenantContextAndClearsItAfterwardsOnABareThread() {
        // The webhook thread has no context - which is exactly the case that
        // would otherwise stamp the public schema onto the event.
        TenantContext.clear();
        AtomicReference<String> tenantSeenByPublisher = new AtomicReference<>();
        when(publisher.publishNewRun(anyString(), any()))
                .thenAnswer(
                        inv -> {
                            tenantSeenByPublisher.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            return "run-1";
                        });

        bridge.onCheckoutSessionCompleted(
                new CheckoutSessionCompletedEvent(completed("tenant_acme")));

        assertEquals(
                "tenant_acme",
                tenantSeenByPublisher.get(),
                "publisher must run inside the session's tenant context");
        assertTrue(TenantContext.current().isEmpty(), "a bare thread must be left bare");
    }

    @Test
    void restoresAPreExistingContextInsteadOfClearingIt() {
        TenantContext previous = TenantContext.of("tenant_other", "someone", Set.of());
        TenantContext.set(previous);
        when(publisher.publishNewRun(anyString(), any())).thenReturn("run-1");

        bridge.onCheckoutSessionCompleted(
                new CheckoutSessionCompletedEvent(completed("tenant_acme")));

        assertEquals("tenant_other", TenantContext.current().orElseThrow().getTenantId());
    }

    @Test
    void payloadCarriesEverythingAWorkflowNeedsWithoutNullPlaceholders() {
        when(publisher.publishNewRun(anyString(), any())).thenReturn("run-1");

        bridge.onCheckoutSessionCompleted(
                new CheckoutSessionCompletedEvent(completed("tenant_acme")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(publisher)
                .publishNewRun(
                        eq(CheckoutSessionCompletedEvent.WORKFLOW_EVENT_TYPE), payload.capture());
        assertEquals(
                "commerce.payment.received", CheckoutSessionCompletedEvent.WORKFLOW_EVENT_TYPE);
        Map<String, Object> p = payload.getValue();
        assertEquals("7a1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b", p.get("checkoutSessionId"));
        assertEquals("tenant_acme", p.get("tenantId"));
        assertEquals("sess-1", p.get("sessionId"));
        assertEquals(49_900L, p.get("amountPaise"));
        assertEquals("499.00", p.get("amountInr"));
        assertEquals("completed", p.get("status"));
        assertEquals("+919876543210", p.get("customerPhone"));
        assertEquals("pay_9", p.get("paymentId"));
        assertEquals("2026-09-09T10:05:00Z", p.get("paidAt"));
        assertFalse(p.containsKey("customerName"), "absent optional fields are omitted, not null");
    }

    @Test
    void aPublisherFailureIsContainedAndTheContextStillRestored() {
        TenantContext.clear();
        when(publisher.publishNewRun(anyString(), any()))
                .thenThrow(new RuntimeException("outbox down"));

        assertDoesNotThrow(
                () ->
                        bridge.onCheckoutSessionCompleted(
                                new CheckoutSessionCompletedEvent(completed("tenant_acme"))));

        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void anEventWithoutATenantIsNotBridged() {
        bridge.onCheckoutSessionCompleted(new CheckoutSessionCompletedEvent(completed("")));

        verifyNoInteractions(publisher);
    }
}
