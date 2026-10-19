package com.agenticai.core.payments.agenttools;

import com.agenticai.core.payments.MandateViolationException;
import com.agenticai.core.payments.PaymentToolService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code create_payment_link} - the mandate-aware successor to the v0 commerce tool of the same
 * name. Offered to an agent ONLY while it holds an ACTIVE {@code PaymentMandate} (see {@code
 * AgentIntegrationToolResolver#resolveCommerceTools} - without a mandate the tool does not exist in
 * the manifest at all, §E1's visibility rule).
 *
 * <p>All authorization lives in {@link PaymentToolService}: the Ed25519- signed mandate is
 * re-verified on every call (signature, status, validity window, caps via the append-only ledger
 * under row lock, allow-lists), and the mandate's own approval policy decides whether a human
 * approval must precede the gateway call - independent of the agent's autonomy tier, so voice and
 * FULLY_AUTONOMOUS agents are gated identically. The generic {@code mcp_tool.create_payment_link}
 * approval gate still wraps this callback like every other tool.
 */
public class MandateCreatePaymentLinkToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(MandateCreatePaymentLinkToolCallback.class);

    public static final String TOOL_NAME = "create_payment_link";

    private final PaymentToolService paymentToolService;
    private final UUID agentId;
    private final String sessionId;

    public MandateCreatePaymentLinkToolCallback(
            PaymentToolService paymentToolService, UUID agentId, String sessionId) {
        this.paymentToolService = paymentToolService;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Creates a UPI payment link (India, INR) under your signed payment mandate - the customer opens "
                                + "it in any UPI app to pay. Your mandate caps (per-transaction, daily, monthly) and "
                                + "approval policy apply; a call may come back as approval-pending, in which case the link "
                                + "is created only after a human approves - do not retry it. Payment is confirmed only when "
                                + "check_payment_status reports paid=true. Identical repeat calls return the same transaction.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "amount_inr": {
                            "type": "number",
                            "description": "Amount to collect in rupees, at most two decimal places, e.g. 499 or 1250.50. Minimum 1."
                        },
                        "description": {
                            "type": "string",
                            "description": "What the payment is for, shown to the customer on the payment page. Under 500 characters."
                        },
                        "customer_phone": {
                            "type": "string",
                            "description": "Optional. Customer mobile number, pre-fills the payment page."
                        },
                        "customer_name": {
                            "type": "string",
                            "description": "Optional. Customer name, pre-fills the payment page."
                        },
                        "merchant_category": {
                            "type": "string",
                            "description": "Optional. Merchant/payee category label - must be on the mandate's allow-list when one is configured."
                        }
                    },
                    "required": ["amount_inr", "description"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = PaymentToolSupport.MAPPER.readValue(toolInput, Map.class);
            long amountPaise = PaymentToolSupport.inrToPaise(args.get("amount_inr"));
            Object description = args.get("description");
            if (description == null || description.toString().isBlank()) {
                return PaymentToolSupport.errorJson("description is required");
            }
            Object customerPhone = args.get("customer_phone");
            Object customerName = args.get("customer_name");
            Object merchantCategory = args.get("merchant_category");

            var outcome =
                    paymentToolService.createPaymentLink(
                            agentId,
                            sessionId,
                            amountPaise,
                            description.toString(),
                            customerName == null ? null : customerName.toString(),
                            customerPhone == null ? null : customerPhone.toString(),
                            merchantCategory == null ? null : merchantCategory.toString());
            return PaymentToolSupport.toJson(PaymentToolSupport.outcomeJson(outcome));
        } catch (IllegalArgumentException e) {
            return PaymentToolSupport.errorJson(e.getMessage());
        } catch (MandateViolationException e) {
            log.info(
                    "create_payment_link refused by mandate ({}): {}", e.getCode(), e.getMessage());
            return PaymentToolSupport.errorJson(
                    "Mandate refused this payment [" + e.getCode() + "]: " + e.getMessage());
        } catch (Exception e) {
            log.warn("create_payment_link (mandate) failed for agent {}", agentId, e);
            return PaymentToolSupport.errorJson(
                    "Could not create the payment link: " + e.getMessage());
        }
    }
}
