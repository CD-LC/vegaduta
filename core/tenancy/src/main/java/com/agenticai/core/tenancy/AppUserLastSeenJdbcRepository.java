package com.agenticai.core.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The raw conditional UPDATE behind {@link AppUserLastSeenRecorder}: a single set-based statement
 * (no entity load) that only writes when the stamp is older than the throttle window.
 */
@Repository
public class AppUserLastSeenJdbcRepository {

    static final String UPDATE_SQL =
            "UPDATE control_plane.app_user SET last_seen_at = now() WHERE keycloak_user_id = ? AND"
                    + " (last_seen_at IS NULL OR last_seen_at < now() - interval '5 minutes')";

    private final JdbcTemplate jdbcTemplate;

    public AppUserLastSeenJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Stamps {@code last_seen_at} for the Keycloak user unless it was stamped within the last five minutes. */
    public void stampLastSeen(String keycloakUserId) {
        jdbcTemplate.update(UPDATE_SQL, keycloakUserId);
    }
}
