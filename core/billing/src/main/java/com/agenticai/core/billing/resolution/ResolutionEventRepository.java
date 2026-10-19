package com.agenticai.core.billing.resolution;

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
 * JdbcTemplate access for {@code resolution_event} (tenant schema V280) -
 * the same raw-JDBC shape every repository in {@code core/billing} takes
 * (see {@code InvoiceRepository}). The table lives in the TENANT schema:
 * every statement here runs unqualified and resolves through the
 * schema-per-tenant connection provider under {@code TenantContext}, but ONLY
 * when the call joins the JPA-bound connection, i.e. runs inside a Spring
 * transaction. A bare JdbcTemplate call outside one borrows a pooled
 * connection still on the default schema and silently reads or writes
 * there. Every caller (controller, {@link ResolutionMeteringService}, the
 * sweep) therefore opens a transaction first; {@code ResolutionTenantScopeIT}
 * pins it.
 */
@Repository
public class ResolutionEventRepository {

    private static final RowMapper<ResolutionEvent> MAPPER = new RowMapper<>() {
        @Override
        public ResolutionEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long price = rs.getObject("unit_price_minor", Long.class);
            return new ResolutionEvent(
                rs.getObject("id", UUID.class),
                rs.getString("conversation_key"),
                rs.getObject("agent_id", UUID.class),
                rs.getString("channel"),
                ResolutionOutcome.valueOf(rs.getString("outcome")),
                ResolutionEvidence.valueOf(rs.getString("evidence")),
                rs.getBigDecimal("confidence"),
                rs.getBoolean("billable"),
                rs.getString("billable_reason"),
                price,
                rs.getString("currency"),
                rs.getTimestamp("resolved_at").toInstant(),
                rs.getString("dedupe_key"),
                rs.getString("created_by"),
                optionalInstant(rs, "disputed_at"),
                rs.getString("disputed_by"),
                rs.getString("dispute_reason"),
                rs.getTimestamp("created_at").toInstant()
            );
        }
    };

    private static Instant optionalInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static final String SELECT =
        "SELECT id, conversation_key, agent_id, channel, outcome, evidence, confidence, billable,"
            + " billable_reason, unit_price_minor, currency, resolved_at, dedupe_key, created_by,"
            + " disputed_at, disputed_by, dispute_reason, created_at FROM resolution_event";

    private final JdbcTemplate jdbcTemplate;

    public ResolutionEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Exactly-once insert on the UNIQUE dedupe_key. Returns the inserted
     * row's id, or empty when the dedupe key already exists - the caller's
     * "already recorded" signal, no exception needed (a sweep re-encountering
     * a still-idle session is the normal case, not an error).
     */
    public Optional<UUID> insertIfAbsent(ResolutionEvent event) {
        List<UUID> rows = jdbcTemplate.query(
            "INSERT INTO resolution_event (id, conversation_key, agent_id, channel, outcome, evidence,"
                + " confidence, billable, billable_reason, unit_price_minor, currency, resolved_at,"
                + " dedupe_key, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (dedupe_key) DO NOTHING RETURNING id",
            (rs, rowNum) -> rs.getObject(1, UUID.class),
            event.id(), event.conversationKey(), event.agentId(), event.channel(),
            event.outcome().name(), event.evidence().name(), event.confidence(), event.billable(),
            event.billableReason(), event.unitPriceMinor(), event.currency(),
            Timestamp.from(event.resolvedAt()), event.dedupeKey(), event.createdBy());
        return rows.stream().findFirst();
    }

    public boolean existsByDedupeKey(String dedupeKey) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM resolution_event WHERE dedupe_key = ?", Integer.class, dedupeKey);
        return count != null && count > 0;
    }

    public Optional<ResolutionEvent> findById(UUID id) {
        List<ResolutionEvent> rows = jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** Newest-first page for the tenant-facing list endpoint. */
    public List<ResolutionEvent> listPage(Instant from, Instant to, int limit, int offset) {
        return jdbcTemplate.query(
            SELECT + " WHERE resolved_at >= ? AND resolved_at < ? ORDER BY resolved_at DESC LIMIT ? OFFSET ?",
            MAPPER, Timestamp.from(from), Timestamp.from(to), limit, offset);
    }

    /** Outcome counts over a period - the summary endpoint's per-outcome breakdown. */
    public List<OutcomeCount> countByOutcome(Instant from, Instant to) {
        return jdbcTemplate.query(
            "SELECT outcome, count(*) AS n FROM resolution_event WHERE resolved_at >= ? AND resolved_at < ?"
                + " GROUP BY outcome",
            (rs, rowNum) -> new OutcomeCount(ResolutionOutcome.valueOf(rs.getString("outcome")), rs.getLong("n")),
            Timestamp.from(from), Timestamp.from(to));
    }

    /** Billable count + summed price over a period - the meter's "what would this have cost" answer. */
    public BillableTotals billableTotals(Instant from, Instant to) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) AS n, coalesce(sum(unit_price_minor), 0) AS total_minor,"
                + " min(currency) AS currency FROM resolution_event"
                + " WHERE billable AND disputed_at IS NULL AND resolved_at >= ? AND resolved_at < ?",
            (rs, rowNum) -> new BillableTotals(rs.getLong("n"), rs.getLong("total_minor"), rs.getString("currency")),
            Timestamp.from(from), Timestamp.from(to));
    }

    /** Dispute a billable event: flips billable off and records the who/when/why. Returns false when the row is gone. */
    public boolean dispute(UUID id, String disputedBy, String reason) {
        return jdbcTemplate.update(
            "UPDATE resolution_event SET billable = FALSE, disputed_at = now(), disputed_by = ?,"
                + " dispute_reason = ?, billable_reason = ? WHERE id = ?",
            disputedBy, reason, "disputed: " + reason, id) > 0;
    }

    public record OutcomeCount(ResolutionOutcome outcome, long count) {}

    public record BillableTotals(long billableCount, long totalMinor, String currency) {}
}
