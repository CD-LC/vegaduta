package com.agenticai.core.billing.invoice;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain-JDBC access to {@code control_plane.billing_invoice} (migration
 * V160). Fully-qualified table names throughout, same rationale as
 * {@code NewsletterService}: control-plane data is schema-fixed, so no JPA
 * entity and no coupling to the schema-per-tenant connection provider's
 * current-schema state.
 */
@Repository
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class InvoiceRepository {

    private static final String COLUMNS =
        "id, tenant_id, invoice_number, gateway, gateway_payment_id, plan, period, currency, "
            + "amount_minor, gst_rate_bp, gst_amount_minor, place_of_supply, customer_gstin, "
            + "customer_name, issued_at";

    private static final RowMapper<BillingInvoice> MAPPER = new RowMapper<>() {
        @Override
        public BillingInvoice mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp issuedAt = rs.getTimestamp("issued_at");
            return new BillingInvoice(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("invoice_number"),
                rs.getString("gateway"),
                rs.getString("gateway_payment_id"),
                rs.getString("plan"),
                rs.getString("period"),
                rs.getString("currency"),
                rs.getLong("amount_minor"),
                rs.getInt("gst_rate_bp"),
                rs.getLong("gst_amount_minor"),
                rs.getString("place_of_supply"),
                rs.getString("customer_gstin"),
                rs.getString("customer_name"),
                issuedAt != null ? issuedAt.toInstant() : null
            );
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public InvoiceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Next value off {@code control_plane.billing_invoice_number_seq} - the NNNNN part of the invoice number. */
    public long nextInvoiceSequence() {
        Long next = jdbcTemplate.queryForObject(
            "SELECT nextval('control_plane.billing_invoice_number_seq')", Long.class);
        return next != null ? next : 0L;
    }

    public Optional<BillingInvoice> findByGatewayPaymentId(String gatewayPaymentId) {
        List<BillingInvoice> rows = jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM control_plane.billing_invoice WHERE gateway_payment_id = ?",
            MAPPER, gatewayPaymentId);
        return rows.stream().findFirst();
    }

    public Optional<BillingInvoice> findByIdAndTenantId(UUID id, UUID tenantId) {
        List<BillingInvoice> rows = jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM control_plane.billing_invoice WHERE id = ? AND tenant_id = ?",
            MAPPER, id, tenantId);
        return rows.stream().findFirst();
    }

    /** All invoices for a tenant, newest first. */
    public List<BillingInvoice> listByTenantId(UUID tenantId) {
        return jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM control_plane.billing_invoice WHERE tenant_id = ? "
                + "ORDER BY issued_at DESC, invoice_number DESC",
            MAPPER, tenantId);
    }

    public void insert(BillingInvoice invoice) {
        jdbcTemplate.update(
            "INSERT INTO control_plane.billing_invoice (" + COLUMNS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            invoice.id(),
            invoice.tenantId(),
            invoice.invoiceNumber(),
            invoice.gateway(),
            invoice.gatewayPaymentId(),
            invoice.plan(),
            invoice.period(),
            invoice.currency(),
            invoice.amountMinor(),
            invoice.gstRateBp(),
            invoice.gstAmountMinor(),
            invoice.placeOfSupply(),
            invoice.customerGstin(),
            invoice.customerName(),
            invoice.issuedAt() != null ? Timestamp.from(invoice.issuedAt()) : null
        );
    }
}
