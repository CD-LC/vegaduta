package com.agenticai.core.common.privacy;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Raw SQL for {@link ConsentGateService}: resolves a channel session to its contact. */
@Repository
public class ConsentGateJdbcRepository {

    private final JdbcTemplate jdbc;

    public ConsentGateJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ConsentGateService.Contact> contactForSession(String sessionId) {
        return jdbc
                .query(
                        "SELECT channel, external_id FROM channel_session WHERE session_id = ?",
                        (rs, n) -> new ConsentGateService.Contact(rs.getString(1), rs.getString(2)),
                        sessionId)
                .stream()
                .findFirst();
    }
}
