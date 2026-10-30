package com.agenticai.core.billing.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The invoice record's one derived value - the taxable base is always {@code amountMinor -
 * gstAmountMinor} in minor units - and the seller {@code @ConfigurationProperties} bean's defaults:
 * a real operating-company name with a deliberately blank GSTIN until deployment config supplies
 * the registration.
 */
class BillingInvoiceTypesTest {

    private static BillingInvoice invoice(long amountMinor, long gstMinor) {
        return new BillingInvoice(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "INV-2026-0001",
                "razorpay",
                "pay_1",
                "PREMIUM",
                "monthly",
                "INR",
                amountMinor,
                1800,
                gstMinor,
                "KA",
                "29AAAAA0000A1Z5",
                "Acme",
                Instant.now());
    }

    @Test
    void taxableValueIsTotalMinusGstInMinorUnits() {
        // 1180 paise total at 18% GST -> 180 paise GST, 1000 paise taxable.
        assertEquals(1_000, invoice(1_180, 180).taxableValueMinor());
    }

    @Test
    void zeroRatedInvoiceHasTheWholeAmountTaxable() {
        assertEquals(500, invoice(500, 0).taxableValueMinor());
    }

    @Test
    void theInvoiceRecordCarriesEveryGstField() {
        BillingInvoice inv = invoice(1_180, 180);
        assertEquals("INV-2026-0001", inv.invoiceNumber());
        assertEquals("razorpay", inv.gateway());
        assertEquals("pay_1", inv.gatewayPaymentId());
        assertEquals("INR", inv.currency());
        assertEquals(1_800, inv.gstRateBp());
        assertEquals("KA", inv.placeOfSupply());
        assertEquals("29AAAAA0000A1Z5", inv.customerGstin());
    }

    @Test
    void sellerPropertiesDefaultToTheCompanyWithABlankGstin() {
        InvoiceSellerProperties props = new InvoiceSellerProperties();
        assertEquals("Cloudspace Learning and Development Pvt Ltd", props.getName());
        assertEquals("", props.getAddress());
        assertEquals(
                "",
                props.getGstin(),
                "the invoice renders 'GSTIN: (registration pending)' until config supplies it");
    }

    @Test
    void sellerPropertiesSettersRoundTrip() {
        InvoiceSellerProperties props = new InvoiceSellerProperties();
        props.setName("Seller Ltd");
        props.setAddress("1 Main St, Bengaluru");
        props.setGstin("29BBBBB1111B1Z5");
        assertEquals("Seller Ltd", props.getName());
        assertEquals("1 Main St, Bengaluru", props.getAddress());
        assertEquals("29BBBBB1111B1Z5", props.getGstin());
    }
}
