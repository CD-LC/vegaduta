package com.agenticai.core.billing.commerce;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.logging.LogSafe;
import com.razorpay.RazorpayException;
import java.util.List;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@link PaidPaymentLookup} over Razorpay's Java SDK. Verified against razorpay-java 1.4.9's actual
 * client surface before writing (javap on the resolved jar, not guessed): {@code
 * PaymentLinkClient#fetch(String)} ({@code GET /v1/payment_links/:id} - response carries {@code
 * amount_paid} and a {@code payments[]} array of {@code {payment_id, amount, status, method}}) and
 * {@code QrCodeClient#fetchAllPayments(String)} ({@code GET /v1/payments/qr_codes/:id/payments} - a
 * list of payment entities, {@code {id, amount, status, method}}).
 *
 * <p>Builds its own {@code RazorpayClient} from the same {@code app.billing.razorpay.*} properties
 * {@code RazorpayGatewayClient} uses rather than reaching into that class: its SDK client is
 * private, and {@code PaymentGatewayClient} (the interface a fetch would belong on) is shared by
 * PhonePe and not this feature's to widen. Same {@code @Profile("security")} gate as that class -
 * without the profile there is no Razorpay key and this bean must not exist, so {@code
 * UpiPaymentConfirmedHandler}'s {@code ObjectProvider} sees null and degrades to "complete without
 * a payment id".
 */
@Component
@Profile("security")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class RazorpayPaidPaymentLookup implements PaidPaymentLookup {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaidPaymentLookup.class);

    private static final String PAYMENT_LINK_PREFIX = "plink_";
    private static final String QR_CODE_PREFIX = "qr_";

    private final com.razorpay.RazorpayClient client;

    public RazorpayPaidPaymentLookup(
            @Value("${app.billing.razorpay.key-id}") String keyId,
            @Value("${app.billing.razorpay.key-secret}") String keySecret) {
        try {
            this.client = new com.razorpay.RazorpayClient(keyId, keySecret);
        } catch (RazorpayException e) {
            throw new IllegalStateException(
                    "Could not initialize the Razorpay client for checkout-session reconciliation - check "
                            + "RAZORPAY_KEY_ID/RAZORPAY_KEY_SECRET in core/.env.local",
                    e);
        }
    }

    @Override
    public Optional<PaidPayment> lookup(String gatewayRefId) {
        if (gatewayRefId == null || gatewayRefId.isBlank()) {
            return Optional.empty();
        }
        try {
            if (gatewayRefId.startsWith(PAYMENT_LINK_PREFIX)) {
                return fromPaymentLink(client.paymentLink.fetch(gatewayRefId).toJson());
            }
            if (gatewayRefId.startsWith(QR_CODE_PREFIX)) {
                return fromQrPayments(client.qrCode.fetchAllPayments(gatewayRefId));
            }
            log.warn(
                    "Checkout reconciliation: unrecognised gateway reference id prefix on '{}' - no payment lookup",
                    LogSafe.clean(gatewayRefId));
            return Optional.empty();
        } catch (RazorpayException | RuntimeException e) {
            // Best-effort by contract (see PaidPaymentLookup) - the caller
            // still completes the session, just without a payment id.
            log.warn(
                    "Checkout reconciliation: could not fetch the captured payment behind '{}' from Razorpay - "
                            + "the session will complete without a payment id",
                    LogSafe.clean(gatewayRefId),
                    e);
            return Optional.empty();
        }
    }

    /**
     * A Payment Link's {@code payments[]} can, in principle, hold more than one entry (a failed
     * attempt followed by a captured one), so the captured entry is preferred; {@code amount_paid}
     * on the link itself is the authoritative paid total either way.
     */
    private static Optional<PaidPayment> fromPaymentLink(JSONObject link) {
        Long amountPaid =
                link.has("amount_paid") && !link.isNull("amount_paid")
                        ? link.getLong("amount_paid")
                        : null;
        JSONArray payments = link.optJSONArray("payments");
        JSONObject chosen = pickCaptured(payments, "payment_id");
        String paymentId = chosen == null ? null : chosen.optString("payment_id", null);
        String method = chosen == null ? null : chosen.optString("method", null);
        if (paymentId == null && amountPaid == null) {
            return Optional.empty();
        }
        return Optional.of(new PaidPayment(paymentId, amountPaid, method));
    }

    private static Optional<PaidPayment> fromQrPayments(List<com.razorpay.QrCode> payments) {
        if (payments == null || payments.isEmpty()) {
            return Optional.empty();
        }
        JSONArray array = new JSONArray();
        for (com.razorpay.QrCode payment : payments) {
            array.put(payment.toJson());
        }
        JSONObject chosen = pickCaptured(array, "id");
        if (chosen == null) {
            return Optional.empty();
        }
        Long amount =
                chosen.has("amount") && !chosen.isNull("amount") ? chosen.getLong("amount") : null;
        return Optional.of(
                new PaidPayment(
                        chosen.optString("id", null), amount, chosen.optString("method", null)));
    }

    /**
     * The first {@code captured} entry, else the last entry present (so a lookup never returns
     * nothing merely because status casing differs).
     */
    private static JSONObject pickCaptured(JSONArray payments, String idKey) {
        // length() == 0 rather than isEmpty(): the org.json version is whatever
        // razorpay-java transitively pins, and isEmpty() only exists on newer ones.
        if (payments == null || payments.length() == 0) {
            return null;
        }
        for (int i = 0; i < payments.length(); i++) {
            JSONObject payment = payments.optJSONObject(i);
            if (payment != null
                    && "captured".equalsIgnoreCase(payment.optString("status", ""))
                    && payment.optString(idKey, null) != null) {
                return payment;
            }
        }
        return payments.optJSONObject(payments.length() - 1);
    }
}
