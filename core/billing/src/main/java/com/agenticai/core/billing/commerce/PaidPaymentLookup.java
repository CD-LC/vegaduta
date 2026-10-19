package com.agenticai.core.billing.commerce;

import java.util.Optional;

/**
 * Fetches, from the gateway's own records, the captured payment behind a
 * paid collect surface (Razorpay Payment Link {@code plink_...} or QR code
 * {@code qr_...}) - the two facts the {@code payment_link.paid}/{@code
 * qr_code.credited} webhook confirms but {@code BillingEvent} does not
 * carry (it holds only the reference id - see that record's javadoc and
 * {@code RazorpayGatewayClient#extractReferenceId}).
 *
 * <p>Exists as its own seam, rather than extra fields on {@code
 * BillingEvent}, for two reasons: (1) {@code BillingEvent}'s three-field
 * contract is shared with PhonePe and a pile of constructor call sites
 * (the same reason {@code RazorpayGatewayClient#extractCapturedPayment} is
 * a separate method), and (2) reading the amount back from the gateway's
 * authoritative record - not from the webhook body - is the more
 * conservative source for an amount check on money movement anyway.
 *
 * <p>Strictly best-effort: implementations return {@link Optional#empty()}
 * (never throw) when the gateway can't be reached or the id is unknown,
 * and {@code UpiPaymentConfirmedHandler} completes the session without a
 * payment id in that case rather than dropping a confirmed payment.
 */
public interface PaidPaymentLookup {

    /**
     * @param paymentId       the gateway's captured-payment id ({@code pay_...})
     * @param amountPaidPaise amount actually captured, minor units; null when
     *                        the gateway record did not state one
     * @param method          the gateway's payment-method label ({@code upi}), informational
     */
    record PaidPayment(String paymentId, Long amountPaidPaise, String method) {
    }

    Optional<PaidPayment> lookup(String gatewayRefId);
}
