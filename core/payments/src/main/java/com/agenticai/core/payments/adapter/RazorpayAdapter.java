package com.agenticai.core.payments.adapter;

import com.agenticai.core.billing.gateway.GatewayUpiCollectRequest;
import com.agenticai.core.billing.gateway.PaymentGatewayClient;
import com.agenticai.core.billing.gateway.PaymentGatewayRegistry;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.razorpay.RazorpayException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@link UpiAgentProtocolAdapter} over Razorpay. Collection initiation delegates to {@link
 * PaymentGatewayClient#createUpiCollectRequest} through the shared {@link PaymentGatewayRegistry}
 * (the NPCI-sanctioned UPI Payment Link flow - manual-VPA collect was deprecated by NPCI for this
 * merchant category in 2026-02, so "VPA-targeted collect" maps to a payment link with the payee VPA
 * recorded mandate-side and enforced by the allow-list, not sent to Razorpay).
 *
 * <p>Status fetch builds its own {@code RazorpayClient} from the same {@code
 * app.billing.razorpay.*} properties - the same posture {@code RazorpayPaidPaymentLookup} documents
 * (the gateway client's SDK instance is private and {@code PaymentGatewayClient} is a shared
 * interface not this feature's to widen). {@code plink_...} ids go through {@code
 * paymentLink.fetch} (status + {@code payments[]}); {@code qr_...} ids through {@code
 * qrCode.fetchAllPayments}.
 *
 * <p>{@code @Profile("security")} like {@code RazorpayGatewayClient} itself: without that profile
 * there are no Razorpay keys, this bean does not exist, and {@code PaymentToolService}'s {@code
 * ObjectProvider} sees "payments unavailable" rather than a half-wired adapter.
 */
@Component
@Profile("security")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class RazorpayAdapter implements UpiAgentProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(RazorpayAdapter.class);

    private static final String PAYMENT_LINK_PREFIX = "plink_";
    private static final String QR_CODE_PREFIX = "qr_";

    private final PaymentGatewayRegistry gatewayRegistry;
    private final com.razorpay.RazorpayClient client;

    public RazorpayAdapter(
            PaymentGatewayRegistry gatewayRegistry,
            @Value("${app.billing.razorpay.key-id}") String keyId,
            @Value("${app.billing.razorpay.key-secret}") String keySecret) {
        this.gatewayRegistry = gatewayRegistry;
        try {
            this.client = new com.razorpay.RazorpayClient(keyId, keySecret);
        } catch (RazorpayException e) {
            throw new IllegalStateException(
                    "Could not initialize the Razorpay client for mandate payments - check RAZORPAY_KEY_ID/RAZORPAY_KEY_SECRET",
                    e);
        }
    }

    @Override
    public String adapterName() {
        return "RAZORPAY";
    }

    @Override
    public AdapterInitiation initiateCollection(CollectionRequest request) {
        PaymentGatewayClient gateway = gatewayRegistry.resolve("razorpay");
        GatewayUpiCollectRequest link =
                gateway.createUpiCollectRequest(
                        request.tenantId(),
                        request.amountPaise(),
                        request.description(),
                        request.customerName(),
                        request.customerPhone(),
                        null,
                        request.notes(),
                        request.expireBy());
        return new AdapterInitiation(link.id(), link.shortUrl(), link.status());
    }

    /**
     * Best-effort by the interface's contract: any fetch error maps to {@link
     * AdapterStatus#UNKNOWN} - the reconciliation ladder keeps polling rather than failing a
     * payment on a transient gateway error.
     */
    @Override
    public AdapterPaymentStatus fetchCollectionStatus(String gatewayEntityId) {
        try {
            if (gatewayEntityId != null && gatewayEntityId.startsWith(PAYMENT_LINK_PREFIX)) {
                return fromPaymentLink(client.paymentLink.fetch(gatewayEntityId).toJson());
            }
            if (gatewayEntityId != null && gatewayEntityId.startsWith(QR_CODE_PREFIX)) {
                return fromQrPayments(client.qrCode.fetchAllPayments(gatewayEntityId));
            }
            log.warn(
                    "Unrecognised gateway entity id '{}' for mandate payment status",
                    gatewayEntityId);
            return new AdapterPaymentStatus(AdapterStatus.UNKNOWN, null, null);
        } catch (RazorpayException | RuntimeException e) {
            log.warn(
                    "Could not fetch mandate payment status for '{}' - treating as UNKNOWN",
                    gatewayEntityId,
                    e);
            return new AdapterPaymentStatus(AdapterStatus.UNKNOWN, null, null);
        }
    }

    private static AdapterPaymentStatus fromPaymentLink(JSONObject link) {
        String status = link.optString("status", "");
        Long amountPaid =
                link.has("amount_paid") && !link.isNull("amount_paid")
                        ? link.getLong("amount_paid")
                        : null;
        if ("paid".equalsIgnoreCase(status)) {
            JSONObject captured = pickCaptured(link.optJSONArray("payments"), "payment_id");
            return new AdapterPaymentStatus(
                    AdapterStatus.PAID,
                    captured == null ? null : captured.optString("payment_id", null),
                    amountPaid);
        }
        if ("expired".equalsIgnoreCase(status)) {
            return new AdapterPaymentStatus(AdapterStatus.EXPIRED, null, null);
        }
        if ("cancelled".equalsIgnoreCase(status)) {
            return new AdapterPaymentStatus(AdapterStatus.CANCELLED, null, null);
        }
        // created / issued / partially_paid - still waiting on the payer.
        return new AdapterPaymentStatus(AdapterStatus.PENDING, null, null);
    }

    private static AdapterPaymentStatus fromQrPayments(
            java.util.List<com.razorpay.QrCode> payments) {
        if (payments == null || payments.isEmpty()) {
            return new AdapterPaymentStatus(AdapterStatus.PENDING, null, null);
        }
        JSONArray array = new JSONArray();
        for (com.razorpay.QrCode payment : payments) {
            array.put(payment.toJson());
        }
        JSONObject chosen = pickCaptured(array, "id");
        if (chosen == null) {
            return new AdapterPaymentStatus(AdapterStatus.PENDING, null, null);
        }
        Long amount =
                chosen.has("amount") && !chosen.isNull("amount") ? chosen.getLong("amount") : null;
        return new AdapterPaymentStatus(AdapterStatus.PAID, chosen.optString("id", null), amount);
    }

    private static JSONObject pickCaptured(JSONArray payments, String idKey) {
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
