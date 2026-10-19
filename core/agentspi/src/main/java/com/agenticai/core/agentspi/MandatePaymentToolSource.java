package com.agenticai.core.agentspi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * The mandate-aware payment tools (E1 Agentic UPI Payments). Kernel-owned (ADR-0011 rule a): {@code
 * tools.AgentIntegrationToolResolver} uses them in place of the checkout-session tools when
 * present, and never imports the payments vendor. The implementation is {@code
 * com.agenticai.core.payments.agenttools.PaymentMandateToolSource}.
 */
public interface MandatePaymentToolSource {

    /**
     * The unguarded mandate tools for {@code agentId} when it holds an ACTIVE signed payment
     * mandate, otherwise empty - an agent that loses its mandate stops seeing these on the very
     * next turn.
     */
    Optional<List<ToolCallback>> mandateTools(UUID agentId, String sessionId);
}
