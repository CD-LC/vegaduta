package com.agenticai.core.billing.gateway;

/**
 * Result of {@link PaymentGatewayClient#fetchUpiQrCode} - a lookup of a QR
 * code previously created by {@link PaymentGatewayClient#createUpiQrCode}
 * (Razorpay: {@code GET /v1/payments/qr_codes/:id}). Deliberately minimal -
 * this exists for one purpose: letting {@code RazorpayCheckoutController}
 * read back {@code notes.tenant_id} (stamped at creation time, see {@link
 * GatewayUpiQrCode}'s creation-time counterpart) before closing a QR code,
 * since Razorpay's close endpoint itself is a bare id lookup with no
 * per-tenant scoping.
 *
 * @param id the QR code id (Razorpay: {@code qr_...}), echoed back
 * @param status the gateway's own current status string (Razorpay: e.g.
 *     {@code "active"}, {@code "closed"}), carried through unmodified - see
 *     {@link GatewaySubscription}'s javadoc for why
 * @param notes the QR code's notes key/value metadata exactly as Razorpay
 *     returns it - never {@code null} (an empty map when the QR code has no
 *     notes at all), so callers can safely do a plain {@code
 *     notes().get("tenant_id")} without a null check on the map itself
 */
public record GatewayQrCodeDetails(String id, String status, java.util.Map<String, String> notes) {
}
