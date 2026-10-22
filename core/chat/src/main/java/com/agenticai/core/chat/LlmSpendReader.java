package com.agenticai.core.chat;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Component;

/**
 * Read side of the BYOK spend counter ({@code llm_spend_month}, V481), isolated from the caller's
 * transaction. A failing statement (typically: the tenant schema has no {@code llm_spend_month}
 * because V481 was not applied, a failed tenant migration or a rolling deploy) aborts a Postgres
 * transaction, and a failing Spring Data call additionally marks the SHARED Spring transaction
 * rollback-only, so the budget check's own {@code catch} could not save the turn: the commit threw
 * {@code UnexpectedRollbackException} out of {@code resolveClient}. These reads therefore run as
 * plain JDBC behind a savepoint on the caller's connection (no second connection, no pool
 * pressure): a failure rolls back to the savepoint only, leaves the transaction usable, and is
 * reported loudly (ERROR log and the {@value #COUNTER_UNAVAILABLE_METRIC} counter).
 */
@Component
public class LlmSpendReader {

    private static final Logger log = LoggerFactory.getLogger(LlmSpendReader.class);

    /** Incremented each time the counter table could not be read. */
    public static final String COUNTER_UNAVAILABLE_METRIC = "llm.spend.counter.unavailable";

    private static final String COUNTER_SQL =
            "SELECT cost_usd FROM llm_spend_month WHERE month_start = ?";
    private static final String SUM_SQL =
            "SELECT COALESCE(SUM(estimated_cost_usd), 0) FROM llm_call_log WHERE started_at >= ?";

    @PersistenceContext private EntityManager entityManager;

    private final MeterRegistry meterRegistry;

    public LlmSpendReader(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * The month's counter, or {@code null} when there is no row yet OR the counter table cannot be
     * read (the caller then falls back to {@link #costSince}). Never throws for a database error
     * and never leaves the caller's transaction rollback-only. Requires an active transaction.
     */
    public BigDecimal monthlySpend(LocalDate monthStart) {
        try {
            return read(COUNTER_SQL, ps -> ps.setDate(1, java.sql.Date.valueOf(monthStart)));
        } catch (RuntimeException e) {
            log.error(
                    "llm_spend_month cannot be read for the current tenant schema (V481 not"
                            + " applied?) - falling back to SUM(llm_call_log); run the tenant"
                            + " migrations. Cause: {}",
                    rootMessage(e));
            meterRegistry.counter(COUNTER_UNAVAILABLE_METRIC).increment();
            return null;
        }
    }

    /**
     * The SUM of the call costs since {@code since}. Throws on a database error, transaction left
     * usable.
     */
    public BigDecimal costSince(Instant since) {
        return read(SUM_SQL, ps -> ps.setTimestamp(1, Timestamp.from(since)));
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /**
     * The read's outcome, carried out of Hibernate's work callback: a value, or the SQLException
     * the statement failed with.
     */
    private record Outcome(BigDecimal value, SQLException failure) {}

    private BigDecimal read(String sql, Binder binder) {
        // The SQLException must not escape the work callback: on Hibernate 7 an exception leaving
        // doReturningWork is converted by the session, which marks the shared transaction
        // rollback-only, so the commit threw UnexpectedRollbackException even though the savepoint
        // rollback had left the transaction usable. The failure is rethrown here, outside
        // Hibernate.
        Outcome outcome =
                entityManager
                        .unwrap(Session.class)
                        .doReturningWork(connection -> readWithSavepoint(connection, sql, binder));
        if (outcome.failure() != null) {
            throw new UncategorizedSQLException("llm spend read", sql, outcome.failure());
        }
        return outcome.value();
    }

    private Outcome readWithSavepoint(Connection connection, String sql, Binder binder)
            throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            BigDecimal value = null;
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    value = rs.getBigDecimal(1);
                }
            }
            connection.releaseSavepoint(savepoint);
            return new Outcome(value, null);
        } catch (SQLException e) {
            connection.rollback(savepoint);
            return new Outcome(null, e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }
}
