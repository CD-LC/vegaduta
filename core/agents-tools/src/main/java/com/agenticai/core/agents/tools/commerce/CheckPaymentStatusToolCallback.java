package com.agenticai.core.agents.tools.commerce;

import com.agenticai.core.billing.commerce.CheckoutSession;
import com.agenticai.core.billing.commerce.CheckoutSessionService;
import com.agenticai.core.security.TenantContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code check_payment_status} - the read half of agentic commerce v0: reports whether a {@link
 * CheckoutSession} created by {@link CreatePaymentLinkToolCallback} has been paid (the {@code
 * payment_link.paid} webhook flips it to COMPLETED), is still awaiting payment, or has expired/been
 * canceled. Strictly tenant-scoped: the id is looked up together with the {@link TenantContext}
 * tenant, so another tenant's session id is indistinguishable from a nonexistent one.
 *
 * <p>Goes through the same {@code GuardedToolCallback} gate as every other tool. Note that {@code
 * ApprovalPolicyService}'s default {@code *payment*} pattern matches {@code
 * mcp_tool.check_payment_status} too, so on a fresh install this READ is also approval-gated for
 * SEMI_AUTONOMOUS agents - a conservative default the audit explicitly asked for on money tools
 * (fail-safe over fail-open), and a one-row {@code ApprovalPolicyOverride} ({@code
 * mcp_tool.check_payment_status} = requiresApproval false) is how a tenant-admin lets agents poll
 * freely once they trust the flow. Deliberately not special-cased here: a tool deciding it is "only
 * a read" and exempting itself is the bypass this design forbids.
 */
public class CheckPaymentStatusToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CheckPaymentStatusToolCallback.class);

    public static final String TOOL_NAME = "check_payment_status";

    private final CheckoutSessionService checkoutSessionService;

    public CheckPaymentStatusToolCallback(CheckoutSessionService checkoutSessionService) {
        this.checkoutSessionService = checkoutSessionService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Checks whether a payment link created with create_payment_link has been paid. Returns paid=true "
                                + "with paidAt once the customer's UPI payment is confirmed, otherwise the current status "
                                + "(ready_for_payment = still waiting, expired, canceled). Only report a payment as received "
                                + "when this tool says paid=true - never assume it from the customer saying they paid.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "checkout_session_id": {
                            "type": "string",
                            "description": "The checkoutSessionId returned by create_payment_link"
                        }
                    },
                    "required": ["checkout_session_id"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = CommerceToolSupport.MAPPER.readValue(toolInput, Map.class);
            Object idValue = args.get("checkout_session_id");
            if (idValue == null || idValue.toString().isBlank()) {
                return CommerceToolSupport.errorJson("checkout_session_id is required");
            }
            UUID id;
            try {
                id = UUID.fromString(idValue.toString().trim());
            } catch (IllegalArgumentException notAUuid) {
                return CommerceToolSupport.errorJson(
                        "checkout_session_id is not a valid id: '" + idValue + "'");
            }
            String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
            if (tenantId == null || tenantId.isBlank()) {
                return CommerceToolSupport.errorJson(
                        "No tenant context - cannot look up a payment without knowing the business");
            }
            Optional<CheckoutSession> session = checkoutSessionService.getForTenant(id, tenantId);
            if (session.isEmpty()) {
                return CommerceToolSupport.errorJson(
                        "No payment request with id " + id + " exists for this business");
            }
            return CommerceToolSupport.toJson(CommerceToolSupport.sessionJson(session.get()));
        } catch (Exception e) {
            log.warn("check_payment_status tool call failed", e);
            return CommerceToolSupport.errorJson(
                    "Could not check the payment status: " + e.getMessage());
        }
    }
}
