package com.agenticai.core.tenancy.demo;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Raw control-plane statements behind {@link DemoSessionRecorder} that JPA cannot express safely.
 */
@Repository
public class DemoSessionJdbcRepository {

    private static final String MARK_DEMO_SQL =
            "UPDATE control_plane.tenant SET is_demo = TRUE WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    public DemoSessionJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Flags the tenant row {@code is_demo}. */
    public void markTenantDemo(UUID tenantId) {
        jdbcTemplate.update(MARK_DEMO_SQL, tenantId);
    }

    /**
     * Assigns a share id exactly once per session, atomically (conditional UPDATE guarded by {@code
     * share_id IS NULL}).
     *
     * @return the share id actually on the row afterwards, or null if the session vanished.
     */
    public String assignShareIdIfAbsent(UUID sessionId, String candidateShareId, Instant at) {
        jdbcTemplate.update(
                "UPDATE control_plane.demo_session SET share_id = ?, share_created_at = ? "
                        + "WHERE id = ? AND share_id IS NULL",
                candidateShareId,
                at.atOffset(ZoneOffset.UTC),
                sessionId);
        return jdbcTemplate.query(
                "SELECT share_id FROM control_plane.demo_session WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                sessionId);
    }
}
