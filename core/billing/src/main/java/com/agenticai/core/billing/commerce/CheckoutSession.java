package com.agenticai.core.billing.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code control_plane.checkout_session} (migration V221) - a
 * single "collect this amount from this customer" request, ACP-shaped (see
 * this package's {@code package-info}). A plain record, not a JPA entity,
 * for the same reason {@code BillingInvoice} is one: this module has no
 * JPA entity scan and control-plane tables are schema-fixed.
 *
 * @param id             this app's own id - also stamped into the gateway
 *                       link's {@code notes.checkout_session_id} so a
 *                       Razorpay-dashboard record can always be traced back
 *                       here even if the local row update after link
 *                       creation failed
 * @param tenantId       the tenant's schema name - what {@code
 *                       TenantContext#getTenantId()} carries, what the agent
 *                       tool has in hand at call time, and what {@code
 *                       WorkflowEvent#tenantId} expects downstream. NOT the
 *                       {@code control_plane.tenant.id} UUID {@code
 *                       billing_invoice.tenant_id} uses: the webhook handler
 *                       and the tool both start from the schema name, and
 *                       resolving the UUID would cost a {@code
 *                       control_plane.tenant} lookup on every call for no
 *                       consumer that needs it
 * @param agentId        the agent that raised this request (null for a
 *                       future non-agent channel)
 * @param sessionId      the chat/channel session the request was raised in -
 *                       opaque here, threaded through for the completed
 *                       event so a workflow can route "payment received"
 *                       back to the right conversation
 * @param channel        where the request originated ({@code agent} for the
 *                       tool; {@code whatsapp}/{@code web}/{@code a2a} are
 *                       reserved for later writers)
 * @param customerPhone  optional - pre-fills the hosted payment page and is
 *                       the address a "payment received" WhatsApp reply
 *                       would go to
 * @param currency       ISO-4217 - always {@code INR} today (UPI is INR-only)
 * @param amountPaise    total in minor units, never a floating-point major
 *                       amount - same money rule as every other amount in
 *                       core/billing
 * @param lineItemsJson  ACP-shaped {@code [{id, name, quantity,
 *                       unit_amount, total}]} as a JSON string - TEXT, not
 *                       JSONB, same reasoning as V132/V185 (nothing queries
 *                       into it)
 * @param paymentProvider gateway name from {@code PaymentGatewayClient
 *                       #gatewayName()} ({@code razorpay})
 * @param gatewayRefId   the gateway's own id for the collect surface -
 *                       Razorpay {@code plink_...} (Payment Link) or {@code
 *                       qr_...} (QR code). This is the key the {@code
 *                       payment_link.paid}/{@code qr_code.credited} webhook
 *                       hands {@code UpiPaymentConfirmedHandler}
 * @param shortUrl       the hosted page the customer opens to pay
 * @param paidAt         set on the COMPLETED transition
 * @param paymentId      the gateway's captured-payment id ({@code pay_...})
 *                       when {@link PaidPaymentLookup} could resolve it,
 *                       else null (see package-info's "known v0 limits")
 * @param reviewNote     non-null only when a human needs to look: today,
 *                       an amount mismatch between what the gateway reports
 *                       as paid and {@code amountPaise}
 * @param expiresAt      app-side expiry (see package-info) - a READY row is
 *                       reported EXPIRED once this passes
 */
public record CheckoutSession(
    UUID id,
    String tenantId,
    UUID agentId,
    String sessionId,
    String channel,
    String customerPhone,
    String customerName,
    String currency,
    long amountPaise,
    String description,
    String lineItemsJson,
    CheckoutSessionStatus status,
    String paymentProvider,
    String gatewayRefId,
    String shortUrl,
    Instant paidAt,
    String paymentId,
    String reviewNote,
    Instant createdAt,
    Instant updatedAt,
    Instant expiresAt
) {

    /** {@code amountPaise} as a two-decimal major-unit value (e.g. {@code 499.00}) for display only - never for arithmetic. */
    public BigDecimal amountMajor() {
        return BigDecimal.valueOf(amountPaise).movePointLeft(2).setScale(2);
    }

    /** True when the session is still READY but its app-side expiry has passed (the lazy EXPIRED transition, see {@link CheckoutSessionStatus}). */
    public boolean isPastExpiry(Instant now) {
        return status == CheckoutSessionStatus.READY_FOR_PAYMENT
            && expiresAt != null
            && now.isAfter(expiresAt);
    }

    public CheckoutSession withStatus(CheckoutSessionStatus newStatus) {
        return new CheckoutSession(
            id, tenantId, agentId, sessionId, channel, customerPhone, customerName, currency, amountPaise,
            description, lineItemsJson, newStatus, paymentProvider, gatewayRefId, shortUrl, paidAt, paymentId,
            reviewNote, createdAt, updatedAt, expiresAt
        );
    }
}
