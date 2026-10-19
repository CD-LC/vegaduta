package com.agenticai.core.payments.agenttools;

import com.agenticai.core.payments.PaymentToolService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code check_payment_status} - the mandate-aware read half: reports a {@code payment_transaction}
 * row's status. Same tenant-scoping rule as the v0 tool (a transaction not owned by this agent is
 * indistinguishable from absent - the model can never confirm money it cannot see). Offered only
 * while the agent holds an ACTIVE mandate.
 */
public class MandateCheckPaymentStatusToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(MandateCheckPaymentStatusToolCallback.class);

    public static final String TOOL_NAME = "check_payment_status";

    private final PaymentToolService paymentToolService;
    private final UUID agentId;

    public MandateCheckPaymentStatusToolCallback(
            PaymentToolService paymentToolService, UUID agentId) {
        this.paymentToolService = paymentToolService;
        this.agentId = agentId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Checks a mandate payment transaction created with create_payment_link or request_upi_collect. "
                                + "Returns status (PENDING_APPROVAL / AWAITING_PAYMENT / CAPTURED / FAILED / EXPIRED), "
                                + "paid=true once confirmed, and receiptUrl/shortUrl when available. Only report a payment "
                                + "as received when paid=true - never assume it from the customer saying they paid.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "transaction_id": {
                            "type": "string",
                            "description": "The transactionId returned by create_payment_link or request_upi_collect"
                        }
                    },
                    "required": ["transaction_id"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = PaymentToolSupport.MAPPER.readValue(toolInput, Map.class);
            Object idValue = args.get("transaction_id");
            if (idValue == null || idValue.toString().isBlank()) {
                return PaymentToolSupport.errorJson("transaction_id is required");
            }
            UUID id;
            try {
                id = UUID.fromString(idValue.toString().trim());
            } catch (IllegalArgumentException notAUuid) {
                return PaymentToolSupport.errorJson(
                        "transaction_id is not a valid id: '" + idValue + "'");
            }
            return PaymentToolSupport.toJson(paymentToolService.checkPaymentStatus(agentId, id));
        } catch (Exception e) {
            log.warn("check_payment_status (mandate) failed", e);
            return PaymentToolSupport.errorJson(
                    "Could not check the payment status: " + e.getMessage());
        }
    }
}
