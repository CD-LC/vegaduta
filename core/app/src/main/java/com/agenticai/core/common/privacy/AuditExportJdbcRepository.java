package com.agenticai.core.common.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Raw SQL for {@link AuditExportService}: streams audit rows and seals row by row. */
@Repository
public class AuditExportJdbcRepository {

    private static final String ENTRIES =
            "SELECT id, created_at, actor_id, action_type, target_type, target_id, outcome, detail"
                    + " FROM audit_log_entry WHERE created_at >= ? AND created_at < ?"
                    + " ORDER BY created_at, id";

    private static final String SEALS =
            "SELECT seq, first_row_at, last_row_at, row_count, seal_hash, prev_seal_hash,"
                    + " algorithm, key_id, signature FROM audit_seal"
                    + " WHERE last_row_at >= ? AND first_row_at < ? ORDER BY seq";

    private final JdbcTemplate jdbc;

    public AuditExportJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void streamEntries(Instant from, Instant to, RowCallbackHandler handler) {
        jdbc.query(ENTRIES, handler, Timestamp.from(from), Timestamp.from(to));
    }

    void streamSeals(Instant from, Instant to, RowCallbackHandler handler) {
        jdbc.query(SEALS, handler, Timestamp.from(from), Timestamp.from(to));
    }
}
