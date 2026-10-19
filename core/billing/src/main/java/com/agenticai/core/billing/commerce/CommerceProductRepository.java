package com.agenticai.core.billing.commerce;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain-JDBC access to {@code control_plane.commerce_product} (migration
 * V252) - same fully-qualified, no-JPA posture as {@link
 * CheckoutSessionRepository}. Every tenant-facing read/write carries an
 * explicit {@code tenant_id} predicate; the public feed read is the one path
 * that intentionally bypasses owner scoping, and it reads {@code published}
 * rows only.
 */
@Repository
public class CommerceProductRepository {

    private static final String TABLE = "control_plane.commerce_product";

    private static final String COLUMNS =
        "id, tenant_id, sku, name, description, category, price_paise, currency, availability, "
            + "image_url, product_url, published, created_at, updated_at";

    private static final RowMapper<CommerceProduct> MAPPER = new RowMapper<>() {
        @Override
        public CommerceProduct mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CommerceProduct(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("category"),
                rs.getLong("price_paise"),
                rs.getString("currency"),
                CommerceProduct.ProductAvailability.valueOf(rs.getString("availability")),
                rs.getString("image_url"),
                rs.getString("product_url"),
                rs.getBoolean("published"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
            );
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public CommerceProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(CommerceProduct product) {
        jdbcTemplate.update(
            "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            product.id(),
            product.tenantId(),
            product.sku(),
            product.name(),
            product.description(),
            product.category(),
            product.pricePaise(),
            product.currency(),
            product.availability().name(),
            product.imageUrl(),
            product.productUrl(),
            product.published(),
            toTimestamp(product.createdAt()),
            toTimestamp(product.updatedAt())
        );
    }

    public int update(CommerceProduct product) {
        return jdbcTemplate.update(
            "UPDATE " + TABLE + " SET sku = ?, name = ?, description = ?, category = ?, price_paise = ?, "
                + "currency = ?, availability = ?, image_url = ?, product_url = ?, published = ?, updated_at = ? "
                + "WHERE id = ? AND tenant_id = ?",
            product.sku(),
            product.name(),
            product.description(),
            product.category(),
            product.pricePaise(),
            product.currency(),
            product.availability().name(),
            product.imageUrl(),
            product.productUrl(),
            product.published(),
            toTimestamp(product.updatedAt()),
            product.id(),
            product.tenantId()
        );
    }

    /** Tenant-scoped read - empty for another tenant's id, indistinguishable from nonexistent. */
    public Optional<CommerceProduct> findByIdAndTenantId(UUID id, String tenantId) {
        return first(jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? AND tenant_id = ?", MAPPER, id, tenantId));
    }

    /** The tenant's whole catalog, newest first (console list; catalogues are small). */
    public List<CommerceProduct> listByTenantId(String tenantId, int limit) {
        return jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE tenant_id = ? ORDER BY updated_at DESC LIMIT ?",
            MAPPER, tenantId, limit);
    }

    /** The PUBLIC feed: published rows of one tenant. The only read that deliberately skips owner scoping. */
    public List<CommerceProduct> listPublishedByTenantId(String tenantId) {
        return jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE tenant_id = ? AND published = TRUE "
                + "ORDER BY updated_at DESC",
            MAPPER, tenantId);
    }

    /** Tenant-scoped delete. */
    public int deleteByIdAndTenantId(UUID id, String tenantId) {
        return jdbcTemplate.update(
            "DELETE FROM " + TABLE + " WHERE id = ? AND tenant_id = ?", id, tenantId);
    }

    private static Optional<CommerceProduct> first(List<CommerceProduct> rows) {
        return rows.stream().findFirst();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}
