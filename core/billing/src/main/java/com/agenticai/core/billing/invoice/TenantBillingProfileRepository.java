package com.agenticai.core.billing.invoice;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads/writes the billing-profile columns migration V160 added to {@code control_plane.tenant}
 * ({@code gstin}, {@code billing_name}, {@code billing_address}) plus the pre-existing {@code name}
 * - via raw JDBC rather than the {@code Tenant} JPA entity, deliberately: the entity lives in
 * core/tenancy which already depends on this module, so mapping these columns there would either
 * create a dependency cycle or force the invoice feature out of core/billing. Same raw-column-read
 * pattern (and rationale) as {@code NewsletterService}'s {@code news_opt_in}.
 */
@Repository
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class TenantBillingProfileRepository {

    /**
     * A tenant's billing identity as stored on its {@code control_plane.tenant} row. {@code
     * schemaName} is what {@code TenantContext#getTenantId()} carries; {@code tenantId} is the
     * row's UUID PK (what {@code billing_invoice.tenant_id} references).
     */
    public record TenantBillingProfile(
            UUID tenantId,
            String schemaName,
            String tenantName,
            String gstin,
            String billingName,
            String billingAddress) {
        /** Preferred legal name for the invoice's customer block. */
        public String effectiveCustomerName() {
            return billingName != null && !billingName.isBlank() ? billingName : tenantName;
        }
    }

    private static final RowMapper<TenantBillingProfile> MAPPER =
            new RowMapper<>() {
                @Override
                public TenantBillingProfile mapRow(ResultSet rs, int rowNum) throws SQLException {
                    return new TenantBillingProfile(
                            rs.getObject("id", UUID.class),
                            rs.getString("schema_name"),
                            rs.getString("name"),
                            rs.getString("gstin"),
                            rs.getString("billing_name"),
                            rs.getString("billing_address"));
                }
            };

    private static final String SELECT =
            "SELECT id, schema_name, name, gstin, billing_name, billing_address FROM control_plane.tenant";

    private final JdbcTemplate jdbcTemplate;

    public TenantBillingProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TenantBillingProfile> findBySchemaName(String schemaName) {
        List<TenantBillingProfile> rows =
                jdbcTemplate.query(SELECT + " WHERE schema_name = ?", MAPPER, schemaName);
        return rows.stream().findFirst();
    }

    public Optional<TenantBillingProfile> findByTenantId(UUID tenantId) {
        List<TenantBillingProfile> rows =
                jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, tenantId);
        return rows.stream().findFirst();
    }

    /** Blank strings are normalized to NULL so "cleared" and "never set" look the same. */
    public void updateProfile(
            UUID tenantId, String gstin, String billingName, String billingAddress) {
        jdbcTemplate.update(
                "UPDATE control_plane.tenant SET gstin = ?, billing_name = ?, billing_address = ? WHERE id = ?",
                nullIfBlank(gstin),
                nullIfBlank(billingName),
                nullIfBlank(billingAddress),
                tenantId);
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
