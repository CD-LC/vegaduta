package com.agenticai.core.agents.tools.commerce;

import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.billing.commerce.CheckoutSession;
import com.agenticai.core.billing.commerce.CheckoutSessionService;
import com.agenticai.core.billing.gateway.PaymentGatewayException;
import com.agenticai.core.security.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code create_payment_link} - agentic commerce v0. Lets an agent raise a "please pay ₹X for Y"
 * request for the person it is talking to: creates a {@link CheckoutSession} (ACP-shaped, see
 * core/billing's {@code com.agenticai.core.billing.commerce} package-info) backed by a Razorpay UPI
 * Payment Link and hands the model the hosted {@code shortUrl} to paste into the conversation.
 * Confirmation is asynchronous - the customer pays in their own UPI app, Razorpay's {@code
 * payment_link.paid} webhook marks the session COMPLETED - so the model is told to use {@link
 * CheckPaymentStatusToolCallback} (or wait for the workflow event) rather than assume payment.
 *
 * <p><b>Approval gate - money movement.</b> This callback does NOT decide whether it may run. Like
 * every other built-in tool it is wrapped by {@code AgentToolProvider#guardAndLog} in {@code
 * GuardedToolCallback}, whose {@code "mcp_tool." + toolName} action type is checked against {@code
 * ApprovalPolicyService}. That service's DEFAULT sensitive patterns already include {@code
 * *payment*} - added, per its own comment, precisely so that "if/when a payment tool IS registered
 * ... it is default-gated the moment it exists" - so {@code mcp_tool.create_payment_link} requires
 * a tenant-admin's approval out of the box, on a fresh install, with zero configuration. A
 * SUPERVISED-tier agent is gated regardless (ALWAYS_GATE); a FULLY_AUTONOMOUS one is not
 * (NEVER_GATE) - that is the tenant's explicit autonomy choice for that agent, same as for every
 * other consequential tool. Loosening for a SEMI_AUTONOMOUS agent is an explicit {@code
 * ApprovalPolicyOverride} on {@code mcp_tool.create_payment_link}. When gated, the approved replay
 * ({@code AgentToolCallApprovalExecutor}) re-invokes this exact callback with the recorded
 * arguments, inside the originating tenant's restored context - nothing here needs to know.
 *
 * <p>{@code agentId}/{@code sessionId} are captured at construction time (built fresh per turn by
 * {@code AgentToolProvider}, exactly like {@code RememberFactToolCallback}'s {@code agentId}/{@code
 * subjectKey} and {@code RequestHumanToolCallback}'s {@code sessionId}) - the model never supplies
 * or overrides which tenant/agent/conversation a payment is attributed to, only the amount, the
 * description and the optional customer pre-fill. The tenant comes from {@link TenantContext},
 * which {@code TenantContextPropagatingToolCallback} restores around this call.
 */
public class CreatePaymentLinkToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentLinkToolCallback.class);

    public static final String TOOL_NAME = "create_payment_link";

    private final CheckoutSessionService checkoutSessionService;
    private final UUID agentId;
    private final String sessionId;

    public CreatePaymentLinkToolCallback(
            CheckoutSessionService checkoutSessionService, UUID agentId, String sessionId) {
        this.checkoutSessionService = checkoutSessionService;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Creates a UPI payment link (India, INR only) the customer can open in any UPI app to pay the "
                                + "business you work for - use it when the user has agreed to buy something or pay an amount "
                                + "and you need to collect the money. Returns a shortUrl to share with the customer and a "
                                + "checkoutSessionId to check later. Creating a link does NOT mean it has been paid: payment "
                                + "is confirmed separately - call check_payment_status with the checkoutSessionId before "
                                + "telling anyone a payment went through. This action moves money and normally requires a "
                                + "human's approval first; if the result says approval is pending, tell the user the link "
                                + "will be shared once approved and do not retry. Only create one link per purchase.")
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
                            "description": "What the payment is for, shown to the customer on the payment page, e.g. \\"2x Masala Dosa - order #118\\". Keep it under 500 characters."
                        },
                        "customer_phone": {
                            "type": "string",
                            "description": "Optional. The customer's mobile number (10-digit Indian or +country format); pre-fills the payment page and lets the business reach them about this payment."
                        },
                        "customer_name": {
                            "type": "string",
                            "description": "Optional. The customer's name, pre-fills the payment page."
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
            Map<String, Object> args = CommerceToolSupport.MAPPER.readValue(toolInput, Map.class);
            String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
            if (tenantId == null || tenantId.isBlank()) {
                // Money must never be attributed by guesswork - a missing
                // tenant is a wiring bug, surfaced, not defaulted.
                return CommerceToolSupport.errorJson(
                        "No tenant context - this payment request cannot be attributed to a business, so it was not created");
            }
            long amountPaise = CommerceToolSupport.inrToPaise(args.get("amount_inr"));
            Object description = args.get("description");
            if (description == null || description.toString().isBlank()) {
                return CommerceToolSupport.errorJson("description is required");
            }
            String customerPhone = CommerceToolSupport.normalisePhone(args.get("customer_phone"));
            Object customerName = args.get("customer_name");

            CheckoutSession session =
                    checkoutSessionService.createPaymentLink(
                            new CheckoutSessionService.CreatePaymentLinkCommand(
                                    tenantId,
                                    agentId,
                                    sessionId,
                                    channelFor(sessionId),
                                    amountPaise,
                                    description.toString(),
                                    customerPhone,
                                    customerName == null ? null : customerName.toString()));

            Map<String, Object> out = CommerceToolSupport.sessionJson(session);
            out.put(
                    "message",
                    "Payment link created. Share shortUrl with the customer; payment is confirmed only "
                            + "once check_payment_status reports paid=true.");
            return CommerceToolSupport.toJson(out);
        } catch (IllegalArgumentException e) {
            // Validation - the model can fix these (bad amount, missing description).
            return CommerceToolSupport.errorJson(e.getMessage());
        } catch (PaymentGatewayException e) {
            log.warn(
                    "create_payment_link: gateway refused the link (agent {}, session {})",
                    agentId,
                    sessionId,
                    e);
            return CommerceToolSupport.errorJson(
                    "The payment gateway could not create the link right now: "
                            + e.getMessage()
                            + ". Tell the user payment collection is temporarily unavailable; do not retry more than once.");
        } catch (Exception e) {
            log.warn(
                    "create_payment_link tool call failed (agent {}, session {})",
                    agentId,
                    sessionId,
                    e);
            return CommerceToolSupport.errorJson(
                    "Could not create a payment link: " + e.getMessage());
        }
    }

    /**
     * Where the request originated, for the row's {@code channel} column: {@code job} for an
     * autonomous agent job's session (derived, never model-supplied - see {@link AgentJobSession}),
     * {@code agent} for any interactive turn. Omnichannel sessions are not distinguished here in v0
     * - the session id is opaque to this module by design.
     */
    private static String channelFor(String sessionId) {
        return AgentJobSession.isJobSession(sessionId) ? "job" : "agent";
    }
}
