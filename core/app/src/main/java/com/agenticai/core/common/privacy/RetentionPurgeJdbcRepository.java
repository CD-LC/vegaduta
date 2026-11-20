package com.agenticai.core.common.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Raw SQL for {@link RetentionPurger}; every statement is unqualified (current tenant schema). */
@Repository
public class RetentionPurgeJdbcRepository {

    private final JdbcTemplate jdbc;

    public RetentionPurgeJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> transcriptChannels() {
        return jdbc.queryForList("SELECT DISTINCT channel FROM channel_message_log", String.class);
    }

    int deleteTranscriptBatch(String channel, Instant cutoff, int limit) {
        return jdbc.update(
                "DELETE FROM channel_message_log WHERE id IN (SELECT id FROM"
                        + " channel_message_log WHERE channel = ? AND created_at < ?"
                        + " ORDER BY created_at LIMIT ?)",
                channel,
                Timestamp.from(cutoff),
                limit);
    }

    void clearSessionPreviews(String channel, Instant cutoff) {
        jdbc.update(
                "UPDATE channel_session SET last_message_preview = NULL WHERE channel = ?"
                        + " AND last_message_preview IS NOT NULL AND last_message_at < ?",
                channel,
                Timestamp.from(cutoff));
    }

    List<Map<String, Object>> recordingBatch(Instant cutoff, int limit) {
        return jdbc.queryForList(
                "SELECT id, object_key FROM voice_call_recording WHERE created_at < ?"
                        + " ORDER BY created_at LIMIT ?",
                Timestamp.from(cutoff),
                limit);
    }

    void deleteRecording(UUID id) {
        jdbc.update("DELETE FROM voice_call_recording WHERE id = ?", id);
    }
}
