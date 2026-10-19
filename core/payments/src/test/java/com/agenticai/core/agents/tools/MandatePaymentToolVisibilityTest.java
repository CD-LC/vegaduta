package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agentspi.MandatePaymentToolSource;
import com.agenticai.core.payments.PaymentToolService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * E1 §E1 manifest rule: an agent sees the mandate payment tools ({@code create_payment_link},
 * {@code request_upi_collect}, {@code check_payment_status}) ONLY while it holds an ACTIVE mandate.
 * No mandate - no payment tools in the manifest at all (the legacy checkout-session commerce path
 * stays a separate concern and is itself empty here because no {@code CheckoutSessionService} bean
 * is present).
 *
 * <p>{@code AgentToolGuard#guardAndLog} is stubbed to pass the raw callback through - this test
 * asserts the MANIFEST decision (which tools exist), not the guard wrap, which {@code
 * GuardedToolCallback}'s own suite covers.
 */
class MandatePaymentToolVisibilityTest {

    private AgentToolGuard toolGuard;
    private PaymentToolService paymentToolService;
    private AgentIntegrationToolResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        toolGuard = mock(AgentToolGuard.class);
        when(toolGuard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        paymentToolService = mock(PaymentToolService.class);
        ObjectProvider<PaymentToolService> payments = mock(ObjectProvider.class);
        when(payments.getIfAvailable()).thenReturn(paymentToolService);
        // The real payments-side source, so the mandate rule under test is the production one.
        ObjectProvider<MandatePaymentToolSource> mandateSource = mock(ObjectProvider.class);
        when(mandateSource.getIfAvailable())
                .thenReturn(
                        new com.agenticai.core.payments.agenttools.PaymentMandateToolSource(
                                payments));
        // No CheckoutSessionService bean - the legacy path contributes nothing here.
        ObjectProvider<com.agenticai.core.billing.commerce.CheckoutSessionService> checkout =
                mock(ObjectProvider.class);
        ObjectProvider<WebToolPort> http = mock(ObjectProvider.class);

        resolver =
                new AgentIntegrationToolResolver(
                        toolGuard,
                        null,
                        List.of(),
                        null,
                        null,
                        checkout,
                        http,
                        mandateSource);
    }

    @Test
    void agentWithoutMandateSeesNoPaymentTools() {
        when(paymentToolService.hasActiveMandate(any())).thenReturn(false);

        List<ToolCallback> tools =
                resolver.resolveCommerceTools(
                        UUID.randomUUID(),
                        "s1",
                        new java.util.concurrent.atomic.AtomicInteger(),
                        null,
                        null,
                        false);

        assertTrue(tools.isEmpty(), "no mandate -> no payment tools in the manifest");
    }

    @Test
    void agentWithActiveMandateSeesTheThreeMandateTools() {
        when(paymentToolService.hasActiveMandate(any())).thenReturn(true);

        List<ToolCallback> tools =
                resolver.resolveCommerceTools(
                        UUID.randomUUID(),
                        "s1",
                        new java.util.concurrent.atomic.AtomicInteger(),
                        null,
                        null,
                        false);

        assertEquals(3, tools.size());
        List<String> names =
                tools.stream().map(t -> t.getToolDefinition().name()).sorted().toList();
        assertEquals(
                List.of("check_payment_status", "create_payment_link", "request_upi_collect"),
                names);
    }
}
