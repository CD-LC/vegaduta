package com.agenticai.core.agents.tools.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.commerce.CheckoutSession;
import com.agenticai.core.billing.commerce.CheckoutSessionService;
import com.agenticai.core.billing.commerce.CheckoutSessionStatus;
import com.agenticai.core.billing.gateway.PaymentGatewayException;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link CreatePaymentLinkToolCallback}. Manual {@code mock(X.class)} + constructor
 * injection, same convention {@code RequestHumanToolCallbackTest} uses (no Spring context). The
 * approval gate is NOT exercised here - it lives in {@code GuardedToolCallback}, which wraps this
 * callback in {@code AgentToolProvider}; see {@code GuardedToolCallbackApprovalReplayTest} in
 * core/guardrails for that contract and {@code ApprovalPolicyServiceTest} for the {@code *payment*}
 * default pattern that gates this tool's action type.
 */
class CreatePaymentLinkToolCallbackTest {

    private static final UUID AGENT_ID = UUID.fromString("0f2c1b3a-4d5e-6f70-8192-a3b4c5d6e7f8");

    private CheckoutSessionService service;
    private CreatePaymentLinkToolCallback callback;

    @BeforeEach
    void setUp() {
        service = mock(CheckoutSessionService.class);
        callback = new CreatePaymentLinkToolCallback(service, AGENT_ID, "sess-1");
        TenantContext.set(TenantContext.of("tenant_acme", "user@acme", Set.of("tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static CheckoutSession ready(long amountPaise) {
        UUID id = UUID.fromString("7a1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b");
        return new CheckoutSession(
                id,
                "tenant_acme",
                AGENT_ID,
                "sess-1",
                "agent",
                "+919876543210",
                "Asha",
                "INR",
                amountPaise,
                "2x dosa",
                "[]",
                CheckoutSessionStatus.READY_FOR_PAYMENT,
                "razorpay",
                "plink_1",
                "https://rzp.io/i/abc",
                null,
                null,
                null,
                Instant.parse("2026-09-09T10:00:00Z"),
                Instant.parse("2026-09-09T10:00:00Z"),
                Instant.parse("2026-09-10T10:00:00Z"));
    }

    @Test
    void toolDefinitionHasTheGatedNameAndRequiredArgs() {
        // The name is load-bearing: "mcp_tool.create_payment_link" is what
        // ApprovalPolicyService's default "*payment*" pattern matches.
        assertEquals("create_payment_link", callback.getToolDefinition().name());
        assertTrue(callback.getToolDefinition().inputSchema().contains("\"amount_inr\""));
        assertTrue(
                callback.getToolDefinition()
                        .inputSchema()
                        .contains("\"required\": [\"amount_inr\", \"description\"]"));
    }

    @Test
    void createsTheSessionForTheContextTenantAndConstructorAgentNeverModelSuppliedOnes() {
        when(service.createPaymentLink(any())).thenReturn(ready(49_900L));

        // Even if the model smuggles a tenant/agent into the input, only the
        // documented args are read - attribution is fixed at construction.
        String out =
                callback.call(
                        "{\"amount_inr\": 499, \"description\": \"2x dosa\", \"customer_phone\": \"9876543210\", "
                                + "\"customer_name\": \"Asha\", \"tenantId\": \"attacker\", \"agent_id\": \"attacker\"}");

        ArgumentCaptor<CheckoutSessionService.CreatePaymentLinkCommand> captor =
                ArgumentCaptor.forClass(CheckoutSessionService.CreatePaymentLinkCommand.class);
        verify(service).createPaymentLink(captor.capture());
        CheckoutSessionService.CreatePaymentLinkCommand cmd = captor.getValue();
        assertEquals("tenant_acme", cmd.tenantId());
        assertEquals(AGENT_ID, cmd.agentId());
        assertEquals("sess-1", cmd.sessionId());
        assertEquals("agent", cmd.channel());
        assertEquals(49_900L, cmd.amountPaise(), "499 rupees -> 49900 paise");
        assertEquals("2x dosa", cmd.description());
        assertEquals("+919876543210", cmd.customerPhone(), "bare 10-digit Indian number gets +91");
        assertEquals("Asha", cmd.customerName());

        assertTrue(
                out.contains("\"checkoutSessionId\":\"7a1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b\""),
                out);
        assertTrue(out.contains("\"shortUrl\":\"https://rzp.io/i/abc\""), out);
        assertTrue(out.contains("\"status\":\"ready_for_payment\""), out);
        assertTrue(out.contains("\"paid\":false"), out);
        assertTrue(out.contains("\"expiresAt\":\"2026-09-10T10:00:00Z\""), out);
        assertTrue(
                out.contains("check_payment_status"),
                "must steer the model to confirm before claiming payment: " + out);
    }

    @Test
    void jobSessionsAreRecordedWithTheJobChannel() {
        CreatePaymentLinkToolCallback jobCallback =
                new CreatePaymentLinkToolCallback(service, AGENT_ID, "job:" + UUID.randomUUID());
        when(service.createPaymentLink(any())).thenReturn(ready(500L));

        jobCallback.call("{\"amount_inr\": \"5.00\", \"description\": \"test\"}");

        ArgumentCaptor<CheckoutSessionService.CreatePaymentLinkCommand> captor =
                ArgumentCaptor.forClass(CheckoutSessionService.CreatePaymentLinkCommand.class);
        verify(service).createPaymentLink(captor.capture());
        assertEquals("job", captor.getValue().channel());
        assertEquals(500L, captor.getValue().amountPaise(), "numeric string amounts are accepted");
    }

    @Test
    void refusesToCreateAnythingWithoutATenantContext() {
        TenantContext.clear();

        String out = callback.call("{\"amount_inr\": 499, \"description\": \"2x dosa\"}");

        assertTrue(out.contains("\"error\""), out);
        assertTrue(out.contains("tenant"), out);
        verify(service, never()).createPaymentLink(any());
    }

    @Test
    void rejectsSubPaiseAmountsMissingDescriptionAndNonNumericAmountsBeforeCallingTheService() {
        assertTrue(
                callback.call("{\"amount_inr\": 10.005, \"description\": \"x\"}")
                        .contains("two decimal places"));
        assertTrue(
                callback.call("{\"amount_inr\": 0, \"description\": \"x\"}")
                        .contains("greater than zero"));
        assertTrue(
                callback.call("{\"amount_inr\": \"five hundred\", \"description\": \"x\"}")
                        .contains("must be a number"));
        assertTrue(callback.call("{\"description\": \"x\"}").contains("amount_inr is required"));
        assertTrue(callback.call("{\"amount_inr\": 100}").contains("description is required"));
        assertTrue(
                callback.call(
                                "{\"amount_inr\": 100, \"description\": \"x\", \"customer_phone\": \"12\"}")
                        .contains("does not look like a phone number"));
        verify(service, never()).createPaymentLink(any());
    }

    @Test
    void serviceValidationErrorsAreReturnedVerbatimAsModelReadableErrors() {
        when(service.createPaymentLink(any()))
                .thenThrow(
                        new IllegalArgumentException(
                                "Amount exceeds the configured per-payment ceiling of 10000000 paise"));

        String out = callback.call("{\"amount_inr\": 250000, \"description\": \"x\"}");

        assertTrue(
                out.contains("\"error\":\"Amount exceeds the configured per-payment ceiling"), out);
    }

    @Test
    void gatewayFailureIsCleanJsonThatTellsTheModelNotToHammerRetry() {
        when(service.createPaymentLink(any()))
                .thenThrow(
                        new PaymentGatewayException(
                                "Could not create Razorpay UPI payment link: \"401\"", null));

        String out = callback.call("{\"amount_inr\": 499, \"description\": \"2x dosa\"}");

        assertTrue(out.startsWith("{") && out.endsWith("}"), out);
        assertTrue(out.contains("temporarily unavailable"), out);
        assertFalse(out.contains("shortUrl"), "no link must be reported on failure: " + out);
    }
}
