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
 * Plain-JDBC access to {@code control_plane.checkout_session} (migration
 * V221). Fully-qualified table name throughout, same rationale as {@code
 * InvoiceRepository}/{@code NewsletterService}: control-plane data is
 * schema-fixed, so no JPA entity and no coupling to the schema-per-tenant
 * connection provider's current-schema state. That also means every
 * method here works from a thread WITHOUT a {@code TenantContext} - which
 * the webhook handler thread is.
 *
 * <p>Tenant scoping is by explicit {@code tenant_id} predicate on every
 * tenant-facing read ({@link #findByIdAndTenantId}, {@link
 * #listByTenantId}); the one lookup that has no tenant in hand ({@link
 * #findByGatewayRefId}, the webhook path) is keyed on a globally-unique
 * gateway id and is only ever called by {@code UpiPaymentConfirmedHandler}.
 */
@Repository
public class CheckoutSessionRepository {

    private static final String TABLE = "control_plane.checkout_session";

    private static final String COLUMNS =
        "id, tenant_id, agent_id, session_id, channel, customer_phone, customer_name, currency, amount_paise, "
            + "description, line_items, status, payment_provider, gateway_ref_id, short_url, paid_at, payment_id, "
            + "review_note, created_at, updated_at, expires_at";

    private static final RowMapper<CheckoutSession> MAPPER = new RowMapper<>() {
        @Override
        public CheckoutSession mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CheckoutSession(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getObject("agent_id", UUID.class),
                rs.getString("session_id"),
                rs.getString("channel"),
                rs.getString("customer_phone"),
                rs.getString("customer_name"),
                rs.getString("currency"),
                rs.getLong("amount_paise"),
                rs.getString("description"),
                rs.getString("line_items"),
                CheckoutSessionStatus.valueOf(rs.getString("status")),
                rs.getString("payment_provider"),
                rs.getString("gateway_ref_id"),
                rs.getString("short_url"),
                toInstant(rs.getTimestamp("paid_at")),
                rs.getString("payment_id"),
                rs.getString("review_note"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                toInstant(rs.getTimestamp("expires_at"))
            );
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public CheckoutSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(CheckoutSession session) {
        jdbcTemplate.update(
            "INSERT INTO " + TABLE + " (" + COLUMNS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            session.id(),
            session.tenantId(),
            session.agentId(),
            session.sessionId(),
            session.channel(),
            session.customerPhone(),
            session.customerName(),
            session.currency(),
            session.amountPaise(),
            session.description(),
            session.lineItemsJson(),
            session.status().name(),
            session.paymentProvider(),
            session.gatewayRefId(),
            session.shortUrl(),
            toTimestamp(session.paidAt()),
            session.paymentId(),
            session.reviewNote(),
            toTimestamp(session.createdAt()),
            toTimestamp(session.updatedAt()),
            toTimestamp(session.expiresAt())
        );
    }

    public Optional<CheckoutSession> findById(UUID id) {
        return first(jdbcTemplate.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ?", MAPPER, id));
    }

    public Optional<CheckoutSession> findByIdAndTenantId(UUID id, String tenantId) {
        return first(jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? AND tenant_id = ?", MAPPER, id, tenantId));
    }

    /** The webhook path's lookup - {@code gateway_ref_id} is unique across all tenants (partial unique index in V221). */
    public Optional<CheckoutSession> findByGatewayRefId(String gatewayRefId) {
        return first(jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE gateway_ref_id = ?", MAPPER, gatewayRefId));
    }

    /** Newest first, bounded - the console list; {@code limit} is clamped by the caller. */
    public List<CheckoutSession> listByTenantId(String tenantId, int limit) {
        return jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
            MAPPER, tenantId, limit);
    }

    /**
     * NOT_READY_FOR_PAYMENT -> READY_FOR_PAYMENT once the gateway link
     * exists. State-guarded so a stray double call can never overwrite a
     * later COMPLETED row's reference with a second link.
     */
    public int markReadyForPayment(UUID id, String gatewayRefId, String shortUrl, Instant expiresAt, Instant now) {
        return jdbcTemplate.update(
            "UPDATE " + TABLE + " SET status = ?, gateway_ref_id = ?, short_url = ?, expires_at = ?, updated_at = ? "
                + "WHERE id = ? AND status = ?",
            CheckoutSessionStatus.READY_FOR_PAYMENT.name(), gatewayRefId, shortUrl, toTimestamp(expiresAt),
            toTimestamp(now), id, CheckoutSessionStatus.NOT_READY_FOR_PAYMENT.name());
    }

    /**
     * The webhook's transition. The {@code status <> COMPLETED} guard IS the
     * idempotency mechanism (same posture as {@code SubscriptionChargedHandler}'s
     * UPDATE): a redelivered {@code payment_link.paid} finds zero rows to
     * update, and the caller uses that zero to NOT re-fire the completed
     * event. Deliberately does not require READY: a session the app already
     * marked EXPIRED (or even CANCELED) whose customer paid anyway still
     * becomes COMPLETED, because the money really arrived and hiding that
     * would be worse than an unexpected state.
     *
     * @return rows updated - 1 on the actual transition, 0 when already COMPLETED
     */
    public int markCompleted(UUID id, String paymentId, Instant paidAt) {
        return jdbcTemplate.update(
            "UPDATE " + TABLE + " SET status = ?, paid_at = ?, payment_id = COALESCE(?, payment_id), updated_at = ? "
                + "WHERE id = ? AND status <> ?",
            CheckoutSessionStatus.COMPLETED.name(), toTimestamp(paidAt), paymentId, toTimestamp(paidAt),
            id, CheckoutSessionStatus.COMPLETED.name());
    }

    /** Lazy READY -> EXPIRED (see {@link CheckoutSessionStatus}); state-guarded so a late webhook's COMPLETED is never clobbered. */
    public int markExpired(UUID id, Instant now) {
        return jdbcTemplate.update(
            "UPDATE " + TABLE + " SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
            CheckoutSessionStatus.EXPIRED.name(), toTimestamp(now), id, CheckoutSessionStatus.READY_FOR_PAYMENT.name());
    }

    /** NOT_READY -> CANCELED when the gateway refused to create the link; the note carries the gateway's reason. */
    public int markCanceled(UUID id, String reviewNote, Instant now) {
        return jdbcTemplate.update(
            "UPDATE " + TABLE + " SET status = ?, review_note = ?, updated_at = ? WHERE id = ? AND status <> ?",
            CheckoutSessionStatus.CANCELED.name(), reviewNote, toTimestamp(now), id,
            CheckoutSessionStatus.COMPLETED.name());
    }

    /**
     * Sessions created by one tenant on one channel since a point in time -
     * backs the public feed-checkout abuse cap ({@code channel = 'acp'}) in
     * {@code ProductFeedService}.
     */
    public long countByTenantIdAndChannelAndCreatedAtAfter(String tenantId, String channel, Instant after) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + TABLE + " WHERE tenant_id = ? AND channel = ? AND created_at > ?",
            Long.class, tenantId, channel, toTimestamp(after));
        return count == null ? 0 : count;
    }

    /** Flags a row for a human without changing its status (amount mismatch on the webhook path). */
    public int setReviewNote(UUID id, String reviewNote, Instant now) {
        return jdbcTemplate.update(
            "UPDATE " + TABLE + " SET review_note = ?, updated_at = ? WHERE id = ?",
            reviewNote, toTimestamp(now), id);
    }

    private static Optional<CheckoutSession> first(List<CheckoutSession> rows) {
        return rows.stream().findFirst();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }
}
