package com.agenticai.core.tenancy.demo;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Set-based control-plane reads/deletes behind {@link DemoSandboxDataPurger}: everything keyed by
 * the demo sessions of one sandbox tenant. Table and column names come from the purger's constant
 * lists, never from caller input.
 */
@Repository
public class DemoSandboxDataJdbcRepository {

    private static final String SESSIONS_OF_TENANT =
            "(SELECT id FROM control_plane.demo_session WHERE tenant_id = ?)";

    private final JdbcTemplate jdbcTemplate;

    public DemoSandboxDataJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Object-storage keys of the media rows belonging to the tenant's demo sessions. */
    public List<String> findObjectKeys(String table, String sessionColumn, UUID tenantId) {
        return jdbcTemplate.queryForList(
                "SELECT object_key FROM control_plane."
                        + table
                        + " WHERE "
                        + sessionColumn
                        + " IN "
                        + SESSIONS_OF_TENANT
                        + " AND object_key IS NOT NULL",
                String.class,
                tenantId);
    }

    /** Deletes the rows of {@code table} keyed by the tenant's demo sessions. */
    public int deleteBySessions(String table, String sessionColumn, UUID tenantId) {
        return jdbcTemplate.update(
                "DELETE FROM control_plane."
                        + table
                        + " WHERE "
                        + sessionColumn
                        + " IN "
                        + SESSIONS_OF_TENANT,
                tenantId);
    }

    /** Deletes the tenant's demo sessions (cascades the magic/studio workflow tables). */
    public int deleteSessions(UUID tenantId) {
        return jdbcTemplate.update(
                "DELETE FROM control_plane.demo_session WHERE tenant_id = ?", tenantId);
    }
}
