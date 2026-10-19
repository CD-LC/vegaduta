package com.agenticai.core.billing.invoice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * B9-PR4 revenue-path contract - {@link InvoiceHtmlRenderer}, previously
 * untested. Two things matter enough to pin:
 *
 * <ol>
 *   <li><b>It always renders.</b> This HTML is the customer-facing legal
 *       tax document; a null-field NPE at render time would 500 the invoice
 *       download for a paying customer. The record's nullable columns
 *       (plan, period, GSTIN, name, issuedAt, even currency) are all
 *       exercised at their null extreme.</li>
 *   <li><b>Escaping is real, not claimed.</b> The renderer's javadoc
 *       promises HTML-escaping of tenant-controlled fields; billing name /
 *       GSTIN are attacker-writable via the billing-profile endpoint, and
 *       this HTML is opened in a browser. The tests inject actual markup
 *       and assert it comes out inert.</li>
 * </ol>
 *
 * <p>Deliberately NOT asserted: the issue-date text. {@code
 * DateTimeFormatter.ofPattern("dd MMM yyyy")} uses the JVM default locale,
 * so "Jul" is not stable across build machines - asserting it would make
 * the suite fail on a non-English CI locale for a cosmetic reason. (That
 * locale-sensitivity is itself a mild latent wart worth knowing about.)
 */
class InvoiceHtmlRendererTest {

    private static InvoiceSellerProperties seller(String gstin) {
        InvoiceSellerProperties seller = new InvoiceSellerProperties();
        seller.setAddress("42 MG Road, Bengaluru 560001");
        seller.setGstin(gstin);
        return seller;
    }

    private static BillingInvoice fullInrInvoice(String customerName) {
        // Rs.399.00 inclusive: base 338.14 + IGST 60.86 (same split
        // InvoiceServiceTest pins for 39900 paise at 1800 bp).
        return new BillingInvoice(
            UUID.randomUUID(), UUID.randomUUID(), "VD-202607-00042",
            "razorpay", "pay_ABC123", "PREMIUM", "MONTHLY", "INR",
            39_900L, 1800, 6_086L, "Karnataka", "29AAPFU0939F1ZX",
            customerName, Instant.parse("2026-07-31T10:15:30Z"));
    }

    /** Every nullable column at null, zero amount - the minimal legal render. */
    private static BillingInvoice minimalInvoice() {
        return new BillingInvoice(
            UUID.randomUUID(), UUID.randomUUID(), "VD-202607-00001",
            null, null, null, null, null,
            0L, 0, 0L, null, null, null, null);
    }

    private final InvoiceHtmlRenderer renderer = new InvoiceHtmlRenderer(seller("29ABCDE1234F1Z5"));

    // ------------------------------------------------------------------
    // Renders without exception + carries the money-critical fields
    // ------------------------------------------------------------------

    @Test
    void fullInrInvoiceRendersWithAllMoneyFieldsFormatted() {
        String html = renderer.render(fullInrInvoice("Acme Industries Pvt Ltd"));

        assertTrue(html.contains("VD-202607-00042"));
        // INR renders as the rupee entity + minor units shifted to a fixed
        // 2-decimal major amount - grouping-free by design (money() javadoc).
        assertTrue(html.contains("&#8377;399.00"), "GST-inclusive total");
        assertTrue(html.contains("&#8377;60.86"), "IGST amount");
        assertTrue(html.contains("&#8377;338.14"), "taxable value = total - GST");
        assertTrue(html.contains("18%"), "1800 bp renders as 18% (trailing zeros stripped)");
        assertTrue(html.contains("Karnataka"), "place of supply from customer GSTIN");
        assertTrue(html.contains("29AAPFU0939F1ZX"), "customer GSTIN snapshot");
        assertTrue(html.contains("998314"), "SAC for IT/cloud services");
        assertTrue(html.contains("pay_ABC123"), "payment reference for reconciliation");
        assertTrue(html.contains("inclusive of GST"), "INR footnote: price is GST-inclusive IGST supply");
        assertFalse(html.contains("Letter of Undertaking"), "LUT wording is exports-only");
    }

    @Test
    void minimalInvoiceWithEveryNullableFieldNullStillRenders() {
        String html = assertDoesNotThrow(() -> renderer.render(minimalInvoice()));

        assertTrue(html.contains("VD-202607-00001"));
        assertTrue(html.contains("Customer"), "null customer name falls back to a neutral label");
        // Null currency is not INR -> the zero-rated export footnote path.
        assertTrue(html.contains("Letter of Undertaking"));
        assertTrue(html.contains("$0.00"), "non-INR symbol with zero amount");
    }

    @Test
    void usdInvoiceUsesDollarSymbolAndExportWording() {
        BillingInvoice usd = new BillingInvoice(
            UUID.randomUUID(), UUID.randomUUID(), "VD-202607-00002",
            "razorpay", "pay_USD1", "PREMIUM", "MONTHLY", "USD",
            1_900L, 0, 0L, "Outside India (export of services)", null,
            "Overseas Co", Instant.parse("2026-07-31T00:00:00Z"));

        String html = renderer.render(usd);

        assertTrue(html.contains("$19.00"));
        assertTrue(html.contains("0%"), "zero-rated rate column");
        assertTrue(html.contains("Letter of Undertaking"));
        assertFalse(html.contains("&#8377;"), "no rupee symbol on an export invoice");
    }

    @Test
    void blankSellerGstinRendersTheRegistrationPendingPlaceholder() {
        // The shipped default config has a blank seller GSTIN until the real
        // registration lands (InvoiceSellerProperties javadoc) - the invoice
        // must say so honestly rather than print an empty GSTIN line.
        String html = new InvoiceHtmlRenderer(seller("")).render(fullInrInvoice("Acme"));

        assertTrue(html.contains("(registration pending)"));
    }

    // ------------------------------------------------------------------
    // Escaping - tenant-controlled fields must come out inert
    // ------------------------------------------------------------------

    @Test
    void maliciousCustomerNameIsEscapedNotExecuted() {
        String html = renderer.render(fullInrInvoice("<script>alert('pwn')</script> \"Evil\" & Co"));

        assertFalse(html.contains("<script>"), "raw script tag must never survive into the markup");
        assertTrue(html.contains("&lt;script&gt;"), "angle brackets entity-escaped");
        assertTrue(html.contains("&quot;Evil&quot;"), "quotes entity-escaped");
        assertTrue(html.contains("&amp; Co"), "ampersand entity-escaped");
        assertTrue(html.contains("alert(&#39;pwn&#39;)"), "single quotes entity-escaped");
    }

    @Test
    void markupInOtherTenantOrGatewayFieldsIsAlsoEscaped() {
        // GSTIN/plan/payment-id are not free-text in practice, but the
        // renderer must not TRUST that - defense in depth for anything that
        // ever lands in these columns un-validated.
        BillingInvoice hostile = new BillingInvoice(
            UUID.randomUUID(), UUID.randomUUID(), "VD-<b>1</b>",
            "razor<i>pay</i>", "pay_<img src=x onerror=alert(1)>", "<b>PREMIUM</b>", "MONTHLY", "INR",
            100L, 1800, 15L, "<style>bad</style>", "29<svg/onload=alert(1)>", null,
            Instant.parse("2026-07-31T00:00:00Z"));

        String html = renderer.render(hostile);

        assertFalse(html.contains("<img"), "img injection via payment id");
        assertFalse(html.contains("<svg"), "svg injection via GSTIN");
        assertFalse(html.contains("<b>PREMIUM</b>"), "markup via plan name");
        assertFalse(html.contains("<style>bad</style>"), "style injection via place of supply");
        assertTrue(html.contains("&lt;b&gt;PREMIUM&lt;/b&gt;"));
    }
}
