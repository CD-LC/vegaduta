package com.agenticai.core.common.resolution;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Raw SQL for {@link ChannelSessionResolutionSweep}: reads {@code channel_session} and {@code
 * turn_feedback} without their entities (see the sweep's javadoc). Runs on the transaction the
 * sweep opens per tenant schema.
 */
@Repository
public class ChannelSessionResolutionJdbcRepository {

    static final int BATCH_LIMIT = 200;

    private static final String IDLE_SESSIONS_SQL =
            "SELECT cs.id, cs.channel, cs.session_id, cs.agent_id, cs.handoff_state, cs.last_message_at"
                    + " FROM channel_session cs"
                    + " WHERE cs.paired AND cs.last_message_at < ?"
                    + " AND NOT EXISTS (SELECT 1 FROM resolution_event re"
                    + "   WHERE re.dedupe_key = 'cs:' || cs.id::text || ':' ||"
                    + "     (extract(epoch FROM cs.last_message_at) * 1000)::bigint::text)"
                    + " ORDER BY cs.last_message_at ASC LIMIT "
                    + BATCH_LIMIT;

    private static final String LATEST_FEEDBACK_SQL =
            "SELECT signal FROM turn_feedback WHERE session_id = ? ORDER BY created_at DESC LIMIT 1";

    private final JdbcTemplate jdbcTemplate;

    public ChannelSessionResolutionJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    <T> List<T> idleSessions(Instant cutoff, RowMapper<T> mapper) {
        return jdbcTemplate.query(IDLE_SESSIONS_SQL, mapper, Timestamp.from(cutoff));
    }

    Optional<String> latestFeedbackSignal(String sessionId) {
        return jdbcTemplate
                .query(LATEST_FEEDBACK_SQL, (rs, n) -> rs.getString(1), sessionId)
                .stream()
                .findFirst();
    }
}
