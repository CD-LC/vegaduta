package com.agenticai.core.payments.adapter;

import java.time.Instant;
import java.util.Map;

/**
 * Build Specs §E1's protocol seam: everything money-moving goes through this
 * interface, so the mandate/approval/ledger/reconciliation machinery is
 * gateway-agnostic and the day NPCI's Unified Agent Protocol ships, a {@code
 * NpciUapAdapter} plugs in next to {@link RazorpayAdapter} with zero
 * reshaping - the mandate JSON already mirrors an AP2 envelope for exactly
 * that reason (see {@code MandateCanonicalizer}).
 *
 * <p>Only COLLECTION surfaces exist for now: UPI Payment Links are the
 * NPCI-sanctioned collect flow (the manual-VPA "UPI Collect" flow was
 * deprecated for this merchant category effective 2026-02-28 - see {@code
 * PaymentGatewayClient#createUpiCollectRequest}'s javadoc). Payouts are
 * declared in the schema/AP2 shape but deliberately have no adapter
 * implementation yet (RazorpayX payouts are a separate product
 * integration, not part of E1's collect pilot surface).
 */
public interface UpiAgentProtocolAdapter {

    /** Stable adapter id stamped on {@code payment_transaction.gateway} (e.g. {@code "RAZORPAY"}). */
    String adapterName();

    /**
     * A payable collection surface - today always a hosted UPI payment link.
     * {@code notes} are gateway-side metadata for reconciliation, never the
     * source of truth for crediting (the local {@code payment_transaction}
     * row is).
     */
    AdapterInitiation initiateCollection(CollectionRequest request);

    /**
     * Reads the gateway's own record of a previously-initiated collection.
     * Strictly best-effort: {@link AdapterStatus#UNKNOWN} on any fetch
     * failure rather than throwing - a gateway blip must not mark a payment
     * failed, the caller's poll ladder retries instead.
     */
    AdapterPaymentStatus fetchCollectionStatus(String gatewayEntityId);

    record CollectionRequest(
        String tenantId,
        long amountPaise,
        String description,
        String customerName,
        String customerPhone,
        /** Payee VPA when the collect is VPA-targeted (UPI_COLLECT type); null for an open payment link. */
        String payeeVpa,
        Map<String, String> notes,
        Instant expireBy
    ) {
    }

    record AdapterInitiation(String gatewayEntityId, String shortUrl, String gatewayStatus) {
    }

    enum AdapterStatus {
        PAID,
        PENDING,
        EXPIRED,
        CANCELLED,
        UNKNOWN
    }

    /**
     * @param paymentId     the gateway's captured-payment id ({@code pay_...}) when PAID
     * @param amountPaise   amount actually captured per the gateway's record; null when not stated
     */
    record AdapterPaymentStatus(AdapterStatus status, String paymentId, Long amountPaise) {
    }
}
