package com.agenticai.core.billing.gateway;

/**
 * Result of {@link PaymentGatewayClient#createUpiCollectRequest} - a UPI
 * payment request the customer completes via their own UPI app (Razorpay: a
 * Payment Link created with {@code upi_link: true}).
 *
 * <p>Named "collect request" to match the original ask, but deliberately
 * <b>not</b> Razorpay's manual-VPA-entry "UPI Collect" flow ({@code
 * upi.flow: "collect"} on the S2S Create Payment API, {@code POST
 * /payments/create/upi}). Confirmed against Razorpay's own docs while
 * building this (razorpay.com/docs/payments/payment-gateway/s2s-integration/
 * payment-methods/upi/collect/): NPCI deprecated that exact manual-VPA-entry
 * flow for general merchants effective 2026-02-28 (already past as of this
 * writing). The only exempted categories are IPO/secondary-market MCC
 * (6012/6211), iOS-app-only and mobile-web transactions, UPI-mandate-only
 * operations, eRupi vouchers, and cross-border PACB businesses - none apply
 * to this platform's billing top-ups. Razorpay's own docs there say
 * non-exempted merchants "must migrate to UPI Intent or QR code methods."
 *
 * <p>A UPI Payment Link is Razorpay's own supported Intent-based surface:
 * the customer opens {@code shortUrl} and picks any UPI app to complete
 * payment via intent - never typing a VPA into this app or Razorpay's API.
 * {@link GatewayUpiQrCode} (via {@link PaymentGatewayClient#createUpiQrCode})
 * is this class's QR-code sibling, the other NPCI-named migration target.
 *
 * @param id the Payment Link id (Razorpay: {@code plink_...})
 * @param shortUrl the hosted page the customer opens to pay - safe to send
 *     to the browser, render as a link/QR, or share via WhatsApp/SMS
 * @param status the gateway's own initial status string (Razorpay:
 *     {@code "created"}), carried through unmodified - see
 *     {@link GatewaySubscription}'s javadoc for why
 */
public record GatewayUpiCollectRequest(String id, String shortUrl, String status) {
}
