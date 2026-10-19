package com.agenticai.core.billing.invoice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The seller (supplier-of-record) block printed on every tax invoice -
 * {@code app.billing.invoice.seller.*} config keys, registered via
 * {@link InvoiceConfiguration} (same per-module
 * {@code @EnableConfigurationProperties} pattern as
 * {@code RazorpayPlanCatalog}).
 *
 * <p>Defaults to the operating company with a BLANK GSTIN - the rendered
 * invoice shows "GSTIN: (registration pending)" until
 * {@code app.billing.invoice.seller.gstin} (env
 * {@code APP_BILLING_INVOICE_SELLER_GSTIN}) is set to the real
 * registration. Address likewise defaults empty and should be set to the
 * registered place of business before invoices are customer-facing.
 */
@ConfigurationProperties(prefix = "app.billing.invoice.seller")
public class InvoiceSellerProperties {

    private String name = "Cloudspace Learning and Development Pvt Ltd";
    private String address = "";
    private String gstin = "";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getGstin() {
        return gstin;
    }

    public void setGstin(String gstin) {
        this.gstin = gstin;
    }
}
