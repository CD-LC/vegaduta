package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.VerifiedOutcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Raw JDBC over {@code memory_competence} (V682), schema-qualified from the calling thread's tenant (a thread without
 * one fails closed, as {@link JdbcMemoryItemStorage}).
 *
 * <p>{@link #add} is one {@code INSERT ... ON CONFLICT DO UPDATE} whose increments are computed in SQL from the stored
 * row, so concurrent writers add up instead of overwriting each other. The per-agent cap is enforced in the same
 * statement; a transaction-scoped advisory lock keyed on (tenant schema, agent) serialises writers of one agent so two
 * brand-new classes cannot both slip in under the cap. The lock is held for one short statement.
 */
public class JdbcCompetenceStorage implements CompetenceStorage {

    private final ObjectProvider<JdbcTemplate> jdbcProvider;

    public JdbcCompetenceStorage(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException("no JDBC access for the competence counters");
        }
        return jdbc;
    }

    @Override
    public void add(VerifiedOutcome outcome, int maxClasses, Instant now) {
        JdbcTemplate jdbc = jdbc();
        String schema = JdbcMemoryItemStorage.schema();
        String table = schema + ".memory_competence";
        int[] d = deltas(outcome.signal());
        Timestamp ts = Timestamp.from(now);
        String sql = "INSERT INTO " + table + " AS c (agent_id, task_class, attempts, verified_success, verified_failure,"
                + " human_accepted, human_rejected, unknown_outcome, last_outcome_at, updated_at)"
                + " SELECT ?, t.cls, 1, ?, ?, ?, ?, ?, ?, ?"
                + " FROM (SELECT CASE WHEN EXISTS (SELECT 1 FROM " + table + " WHERE agent_id = ? AND task_class = ?)"
                + " OR (SELECT count(*) FROM " + table + " WHERE agent_id = ? AND task_class <> ?) < ?"
                + " THEN ? ELSE ? END AS cls) t WHERE true"
                + " ON CONFLICT (agent_id, task_class) DO UPDATE SET"
                + " attempts = c.attempts + 1,"
                + " verified_success = c.verified_success + EXCLUDED.verified_success,"
                + " verified_failure = c.verified_failure + EXCLUDED.verified_failure,"
                + " human_accepted = c.human_accepted + EXCLUDED.human_accepted,"
                + " human_rejected = c.human_rejected + EXCLUDED.human_rejected,"
                + " unknown_outcome = c.unknown_outcome + EXCLUDED.unknown_outcome,"
                + " last_outcome_at = EXCLUDED.last_outcome_at, updated_at = EXCLUDED.updated_at";
        UUID agent = outcome.agentId();
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> {
                    jdbc.queryForObject(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                            Object.class,
                            "memory_competence:" + schema + ":" + agent);
                    jdbc.update(
                            sql,
                            agent,
                            d[0],
                            d[1],
                            d[2],
                            d[3],
                            d[4],
                            ts,
                            ts,
                            agent,
                            outcome.taskClass(),
                            agent,
                            OVERFLOW_CLASS,
                            maxClasses,
                            outcome.taskClass(),
                            OVERFLOW_CLASS);
                });
    }

    @Override
    public List<CompetenceRow> rows(UUID agentId) {
        return jdbc().query(
                        "SELECT task_class, attempts, verified_success, verified_failure, human_accepted,"
                                + " human_rejected, unknown_outcome, last_outcome_at FROM "
                                + JdbcMemoryItemStorage.schema()
                                + ".memory_competence WHERE agent_id = ? ORDER BY attempts DESC, task_class LIMIT 1000",
                        (rs, i) -> {
                            Timestamp last = rs.getTimestamp("last_outcome_at");
                            return new CompetenceRow(
                                    rs.getString("task_class"),
                                    rs.getInt("attempts"),
                                    rs.getInt("verified_success"),
                                    rs.getInt("verified_failure"),
                                    rs.getInt("human_accepted"),
                                    rs.getInt("human_rejected"),
                                    rs.getInt("unknown_outcome"),
                                    last == null ? null : last.toInstant());
                        },
                        agentId);
    }

    /** Column increments: verified_success, verified_failure, human_accepted, human_rejected, unknown_outcome. */
    static int[] deltas(VerifiedOutcome.Signal signal) {
        return switch (signal) {
            case TOOL_SUCCESS, APPROVAL_GRANTED -> new int[] {1, 0, 0, 0, 0};
            case TOOL_ERROR -> new int[] {0, 1, 0, 0, 0};
            case HUMAN_ACCEPTED -> new int[] {0, 0, 1, 0, 0};
            case APPROVAL_DENIED, HUMAN_REJECTED -> new int[] {0, 0, 0, 1, 0};
            case UNKNOWN -> new int[] {0, 0, 0, 0, 1};
        };
    }
}
