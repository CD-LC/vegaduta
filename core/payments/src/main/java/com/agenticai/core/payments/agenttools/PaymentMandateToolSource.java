package com.agenticai.core.payments.agenttools;

import com.agenticai.core.agentspi.MandatePaymentToolSource;
import com.agenticai.core.payments.PaymentToolService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * E1 Agentic UPI Payments through the kernel's {@link MandatePaymentToolSource} (ADR-0011 rule a):
 * when the agent holds an ACTIVE signed {@code PaymentMandate}, the mandate-aware {@code
 * create_payment_link}, {@code request_upi_collect} and {@code check_payment_status} tools. The
 * invisibility rule is {@link PaymentToolService#hasActiveMandate}; a revoked mandate also fails
 * any in-flight call at execution re-verification. ObjectProvider: slim contexts without the
 * payments service offer nothing.
 */
@Component
public class PaymentMandateToolSource implements MandatePaymentToolSource {

    private final ObjectProvider<PaymentToolService> paymentToolService;

    public PaymentMandateToolSource(ObjectProvider<PaymentToolService> paymentToolService) {
        this.paymentToolService = paymentToolService;
    }

    @Override
    public Optional<List<ToolCallback>> mandateTools(UUID agentId, String sessionId) {
        PaymentToolService service = paymentToolService.getIfAvailable();
        if (service == null || !service.hasActiveMandate(agentId)) {
            return Optional.empty();
        }
        return Optional.of(
                List.of(
                        new MandateCreatePaymentLinkToolCallback(service, agentId, sessionId),
                        new MandateRequestUpiCollectToolCallback(service, agentId, sessionId),
                        new MandateCheckPaymentStatusToolCallback(service, agentId)));
    }
}
