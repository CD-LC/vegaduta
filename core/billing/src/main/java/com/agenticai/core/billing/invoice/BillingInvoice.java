package com.agenticai.core.billing.invoice;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable row of {@code control_plane.billing_invoice} (migration
 * V160) - a GST tax invoice for a single captured payment. See
 * {@link InvoiceService} for how the GST split and invoice number are
 * derived; this record is a plain data carrier, written once and never
 * updated.
 *
 * @param amountMinor the TOTAL charged amount in minor units (paise/cents),
 *     GST-inclusive for INR - the taxable (base) value is {@code
 *     amountMinor - gstAmountMinor}
 * @param gstRateBp GST rate in basis points (1800 = 18% IGST); 0 for
 *     zero-rated exports
 * @param customerGstin the tenant's GSTIN as of issue time (snapshot, may
 *     be null for unregistered/B2C customers)
 */
public record BillingInvoice(
    UUID id,
    UUID tenantId,
    String invoiceNumber,
    String gateway,
    String gatewayPaymentId,
    String plan,
    String period,
    String currency,
    long amountMinor,
    int gstRateBp,
    long gstAmountMinor,
    String placeOfSupply,
    String customerGstin,
    String customerName,
    Instant issuedAt
) {

    /** The taxable value (total minus GST) in minor units. */
    public long taxableValueMinor() {
        return amountMinor - gstAmountMinor;
    }
}
