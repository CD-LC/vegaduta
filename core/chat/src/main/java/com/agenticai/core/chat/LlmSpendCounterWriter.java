package com.agenticai.core.chat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Writes the {@code llm_spend_month} counter (V481) inside the caller's transaction (the one that
 * inserted the {@code llm_call_log} row, so the two commit or roll back together and the counter
 * stays exact), but behind a JDBC savepoint. A Postgres error aborts the whole transaction, so
 * without the savepoint a failing counter write (for example a tenant whose V481 is not applied
 * yet) would also lose the log row. With it, the failure rolls back to the savepoint, is logged,
 * and the transaction carries on. Plain JDBC rather than a JPA query because a failed JPA native
 * query marks the persistence transaction rollback-only.
 */
@Component
public class LlmSpendCounterWriter {

    private static final Logger log = LoggerFactory.getLogger(LlmSpendCounterWriter.class);

    private static final String ADD_SQL =
            "UPDATE llm_spend_month SET cost_usd = cost_usd + ?, updated_at = now() WHERE"
                    + " month_start = ?";

    /**
     * Creates the month row from the real SUM (which already includes the row this transaction just
     * inserted), or, if a concurrent transaction created it first, adds this call's cost to it.
     */
    private static final String SEED_SQL =
            "INSERT INTO llm_spend_month (month_start, cost_usd, updated_at) SELECT ?,"
                    + " COALESCE(SUM(estimated_cost_usd), 0), now() FROM llm_call_log WHERE"
                    + " started_at >= ? AND started_at < ? ON CONFLICT (month_start) DO UPDATE SET"
                    + " cost_usd = llm_spend_month.cost_usd + ?, updated_at = now()";

    @PersistenceContext private EntityManager entityManager;

    /**
     * Adds {@code costUsd} to the UTC month of {@code startedAt}, in the current transaction. Never
     * throws for a database error: that rolls back to a savepoint and logs. Requires an active
     * transaction.
     */
    public void add(Instant startedAt, BigDecimal costUsd) {
        LocalDate month =
                (startedAt == null ? Instant.now() : startedAt)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .withDayOfMonth(1);
        // The seed SUM must see the log row inserted earlier in this transaction.
        entityManager.flush();
        entityManager
                .unwrap(Session.class)
                .doWork(connection -> addWithSavepoint(connection, month, costUsd));
    }

    private void addWithSavepoint(Connection connection, LocalDate month, BigDecimal costUsd)
            throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            int updated;
            try (PreparedStatement ps = connection.prepareStatement(ADD_SQL)) {
                ps.setBigDecimal(1, costUsd);
                ps.setDate(2, java.sql.Date.valueOf(month));
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = connection.prepareStatement(SEED_SQL)) {
                    ps.setDate(1, java.sql.Date.valueOf(month));
                    ps.setTimestamp(
                            2, Timestamp.from(month.atStartOfDay(ZoneOffset.UTC).toInstant()));
                    ps.setTimestamp(
                            3,
                            Timestamp.from(
                                    month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
                    ps.setBigDecimal(4, costUsd);
                    ps.executeUpdate();
                }
            }
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            log.warn(
                    "Failed to update the monthly spend counter (call log row kept) - {}",
                    e.getMessage());
            connection.rollback(savepoint);
        }
    }
}
