package com.agenticai.core.billing.gateway;

/**
 * Result of {@link PaymentGatewayClient#createUpiQrCode} - a scannable UPI
 * QR code for a fixed one-off amount (Razorpay: a QR Code entity, {@code
 * type: "upi_qr"}). See {@link GatewayUpiCollectRequest}'s javadoc for why
 * this and the Payment-Link-based UPI collect request are the two surfaces
 * this app builds against, rather than Razorpay's now-deprecated
 * manual-VPA-entry Collect flow.
 *
 * @param id the QR code id (Razorpay: {@code qr_...}) - pass to
 *     {@link PaymentGatewayClient#closeUpiQrCode} once paid or abandoned; a
 *     closed QR code cannot be reopened
 * @param imageUrl a hosted image of the QR code the frontend can render
 *     directly (e.g. in an {@code <img>} tag) - Razorpay's own {@code
 *     image_url}, already a complete scannable code, not raw image bytes
 * @param status the gateway's own initial status string (Razorpay:
 *     {@code "active"}), carried through unmodified - see
 *     {@link GatewaySubscription}'s javadoc for why
 * @param amountInSubunits the fixed amount this QR code was created for
 *     (smallest currency unit - paise), echoed back so the frontend can
 *     display it without a second round trip
 */
public record GatewayUpiQrCode(String id, String imageUrl, String status, long amountInSubunits) {
}
