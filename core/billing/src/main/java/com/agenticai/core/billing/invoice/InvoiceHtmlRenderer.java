package com.agenticai.core.billing.invoice;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Renders one {@link BillingInvoice} as a clean, fully self-contained HTML tax invoice (inline CSS,
 * no external assets - printable / save-as-PDF friendly). Seller block comes from {@link
 * InvoiceSellerProperties} ({@code app.billing.invoice.seller.*}); the service line carries HSN/SAC
 * 998314 ("Information technology design and development services" - the standard SAC for
 * cloud/SaaS supplies).
 *
 * <p>All dynamic values are HTML-escaped - tenant-controlled fields (billing name/address, GSTIN)
 * land in this markup.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class InvoiceHtmlRenderer {

    /** SAC for information-technology / cloud services. */
    static final String HSN_SAC = "998314";

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneOffset.UTC);

    private final InvoiceSellerProperties seller;

    public InvoiceHtmlRenderer(InvoiceSellerProperties seller) {
        this.seller = seller;
    }

    public String render(BillingInvoice invoice) {
        boolean gstCharged = invoice.gstRateBp() > 0;
        String symbol = "INR".equalsIgnoreCase(invoice.currency()) ? "&#8377;" : "$";
        String description =
                "VegaDuta platform subscription"
                        + (invoice.plan() != null ? " - " + escape(invoice.plan()) : "")
                        + (invoice.period() != null ? " (" + escape(invoice.period()) + ")" : "");
        String sellerGstin =
                seller.getGstin() == null || seller.getGstin().isBlank()
                        ? "(registration pending)"
                        : escape(seller.getGstin());
        String ratePct =
                new BigDecimal(invoice.gstRateBp())
                        .movePointLeft(2)
                        .stripTrailingZeros()
                        .toPlainString();

        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>Tax Invoice ")
                .append(escape(invoice.invoiceNumber()))
                .append("</title>")
                .append("<style>")
                .append(
                        "body{font-family:Arial,Helvetica,sans-serif;color:#1a1a2e;margin:0;padding:32px;background:#fff}")
                .append(
                        ".invoice{max-width:760px;margin:0 auto;border:1px solid #d8d8e0;padding:32px}")
                .append("h1{font-size:20px;letter-spacing:1px;margin:0 0 2px}")
                .append(".muted{color:#666;font-size:12px}")
                .append(".row{display:flex;justify-content:space-between;gap:24px;margin-top:24px}")
                .append(
                        ".block{flex:1}.block h2{font-size:12px;text-transform:uppercase;letter-spacing:1px;color:#666;margin:0 0 6px}")
                .append(".block p{margin:0;font-size:13px;line-height:1.5;white-space:pre-line}")
                .append("table{width:100%;border-collapse:collapse;margin-top:24px;font-size:13px}")
                .append(
                        "th{text-align:left;border-bottom:2px solid #1a1a2e;padding:8px 6px;font-size:11px;text-transform:uppercase;letter-spacing:1px}")
                .append("td{border-bottom:1px solid #e4e4ec;padding:10px 6px;vertical-align:top}")
                .append(".num{text-align:right;white-space:nowrap}")
                .append(".totals td{border-bottom:none;padding:4px 6px}")
                .append(
                        ".totals .grand td{border-top:2px solid #1a1a2e;font-weight:bold;font-size:14px;padding-top:8px}")
                .append(".note{margin-top:28px;font-size:11px;color:#666;line-height:1.6}")
                .append("</style></head><body><div class=\"invoice\">");

        // Header
        html.append("<h1>TAX INVOICE</h1>")
                .append("<div class=\"muted\">Invoice No. <strong>")
                .append(escape(invoice.invoiceNumber()))
                .append("</strong> &middot; Date ")
                .append(invoice.issuedAt() != null ? DATE.format(invoice.issuedAt()) : "-")
                .append("</div>");

        // Seller / buyer blocks
        html.append("<div class=\"row\"><div class=\"block\"><h2>Supplier</h2><p><strong>")
                .append(escape(seller.getName()))
                .append("</strong>");
        if (seller.getAddress() != null && !seller.getAddress().isBlank()) {
            html.append("\n").append(escape(seller.getAddress()));
        }
        html.append("\nGSTIN: ").append(sellerGstin).append("</p></div>");

        html.append("<div class=\"block\"><h2>Billed to</h2><p><strong>")
                .append(
                        escape(
                                invoice.customerName() != null
                                        ? invoice.customerName()
                                        : "Customer"))
                .append("</strong>");
        if (invoice.customerGstin() != null && !invoice.customerGstin().isBlank()) {
            html.append("\nGSTIN: ").append(escape(invoice.customerGstin()));
        }
        html.append("\nPlace of supply: ")
                .append(escape(invoice.placeOfSupply() != null ? invoice.placeOfSupply() : "-"))
                .append("</p></div></div>");

        // Line items
        html.append("<table><thead><tr><th>Description</th><th>HSN/SAC</th>")
                .append("<th class=\"num\">Taxable value</th><th class=\"num\">IGST rate</th>")
                .append(
                        "<th class=\"num\">IGST amount</th><th class=\"num\">Total</th></tr></thead><tbody><tr>")
                .append("<td>")
                .append(description);
        if (invoice.gateway() != null || invoice.gatewayPaymentId() != null) {
            html.append("<div class=\"muted\">Payment ref: ")
                    .append(escape(invoice.gateway() != null ? invoice.gateway() : ""))
                    .append(" ")
                    .append(
                            escape(
                                    invoice.gatewayPaymentId() != null
                                            ? invoice.gatewayPaymentId()
                                            : ""))
                    .append("</div>");
        }
        html.append("</td><td>")
                .append(HSN_SAC)
                .append("</td>")
                .append("<td class=\"num\">")
                .append(symbol)
                .append(money(invoice.taxableValueMinor()))
                .append("</td>")
                .append("<td class=\"num\">")
                .append(gstCharged ? ratePct + "%" : "0%")
                .append("</td>")
                .append("<td class=\"num\">")
                .append(symbol)
                .append(money(invoice.gstAmountMinor()))
                .append("</td>")
                .append("<td class=\"num\">")
                .append(symbol)
                .append(money(invoice.amountMinor()))
                .append("</td>")
                .append("</tr></tbody></table>");

        // Totals
        html.append("<table class=\"totals\"><tbody>")
                .append(
                        "<tr><td></td><td class=\"num\" style=\"width:200px\">Taxable value</td><td class=\"num\" style=\"width:120px\">")
                .append(symbol)
                .append(money(invoice.taxableValueMinor()))
                .append("</td></tr>")
                .append("<tr><td></td><td class=\"num\">IGST")
                .append(gstCharged ? " @ " + ratePct + "%" : "")
                .append("</td><td class=\"num\">")
                .append(symbol)
                .append(money(invoice.gstAmountMinor()))
                .append("</td></tr>")
                .append("<tr class=\"grand\"><td></td><td class=\"num\">Total (")
                .append(escape(invoice.currency()))
                .append(")</td><td class=\"num\">")
                .append(symbol)
                .append(money(invoice.amountMinor()))
                .append("</td></tr>")
                .append("</tbody></table>");

        // Footnotes
        html.append("<div class=\"note\">");
        if (gstCharged) {
            html.append("Price is inclusive of GST. Tax charged as IGST @ ")
                    .append(ratePct)
                    .append(
                            "% on inter-state supply of online information and database access or retrieval (OIDAR) / ")
                    .append("information technology services, SAC ")
                    .append(HSN_SAC)
                    .append(".");
        } else {
            html.append(
                            "Export of services without payment of IGST under Letter of Undertaking (LUT) - ")
                    .append("zero-rated supply under Section 16, IGST Act 2017. SAC ")
                    .append(HSN_SAC)
                    .append(".");
        }
        html.append("<br>This is a computer-generated invoice and does not require a signature.")
                .append("</div></div></body></html>");
        return html.toString();
    }

    /**
     * Minor units -> "1,234.56"-style major-unit string (plain grouping-free formatting, 2
     * decimals).
     */
    private static String money(long minor) {
        return new BigDecimal(minor).movePointLeft(2).setScale(2).toPlainString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
