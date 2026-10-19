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
 * {@code request_upi_collect} - a payee-VPA-targeted collection under the agent's signed mandate.
 * The VPA is validated against the mandate's {@code allowed_vpas} allow-list inside {@link
 * PaymentToolService} before anything reaches the gateway. Same mandate/approval/ledger path as
 * {@link MandateCreatePaymentLinkToolCallback}; offered only while an ACTIVE mandate exists.
 */
public class MandateRequestUpiCollectToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(MandateRequestUpiCollectToolCallback.class);

    public static final String TOOL_NAME = "request_upi_collect";

    private final PaymentToolService paymentToolService;
    private final UUID agentId;
    private final String sessionId;

    public MandateRequestUpiCollectToolCallback(
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
                        "Requests a UPI payment to a specific payee VPA under your signed payment mandate. The vpa must "
                                + "be on the mandate's allow-list when one is configured. Returns a transactionId - the "
                                + "customer pays via the link/QR; confirm with check_payment_status before treating it as "
                                + "received. May return approval-pending per your mandate's approval policy - do not retry.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "amount_inr": {
                            "type": "number",
                            "description": "Amount to collect in rupees, at most two decimal places."
                        },
                        "vpa": {
                            "type": "string",
                            "description": "The payee's UPI Virtual Payment Address, e.g. shop@okhdfcbank - must be on the mandate's allowed-VPA list when configured."
                        },
                        "description": {
                            "type": "string",
                            "description": "What the payment is for, shown to the customer. Under 500 characters."
                        },
                        "merchant_category": {
                            "type": "string",
                            "description": "Optional merchant category - must be on the mandate's allow-list when configured."
                        }
                    },
                    "required": ["amount_inr", "vpa", "description"]
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
            Object vpa = args.get("vpa");
            if (vpa == null || vpa.toString().isBlank()) {
                return PaymentToolSupport.errorJson("vpa is required - the payee's UPI address");
            }
            Object description = args.get("description");
            if (description == null || description.toString().isBlank()) {
                return PaymentToolSupport.errorJson("description is required");
            }
            Object merchantCategory = args.get("merchant_category");

            var outcome =
                    paymentToolService.requestUpiCollect(
                            agentId,
                            sessionId,
                            amountPaise,
                            vpa.toString(),
                            description.toString(),
                            merchantCategory == null ? null : merchantCategory.toString());
            return PaymentToolSupport.toJson(PaymentToolSupport.outcomeJson(outcome));
        } catch (IllegalArgumentException e) {
            return PaymentToolSupport.errorJson(e.getMessage());
        } catch (MandateViolationException e) {
            log.info(
                    "request_upi_collect refused by mandate ({}): {}", e.getCode(), e.getMessage());
            return PaymentToolSupport.errorJson(
                    "Mandate refused this payment [" + e.getCode() + "]: " + e.getMessage());
        } catch (Exception e) {
            log.warn("request_upi_collect failed for agent {}", agentId, e);
            return PaymentToolSupport.errorJson(
                    "Could not request the UPI collect: " + e.getMessage());
        }
    }
}
