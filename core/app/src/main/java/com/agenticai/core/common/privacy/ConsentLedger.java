package com.agenticai.core.common.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Append-only record of consent decisions per contact (Act s.6(10): the fiduciary must be able to
 * prove a notice was given and consent recorded). The current state of a contact is their latest
 * row for the purpose.
 */
@Component
public class ConsentLedger {

    public enum Decision {
        PROMPTED,
        GRANTED,
        DECLINED,
        WITHDRAWN
    }

    public record Entry(
            UUID id,
            String channel,
            String externalId,
            UUID agentId,
            String purpose,
            int noticeVersion,
            String noticeLanguage,
            String noticeSha256,
            Decision decision,
            Instant decidedAt) {}

    private final JdbcTemplate jdbc;

    public ConsentLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Entry record(
            String channel,
            String externalId,
            UUID agentId,
            String purpose,
            int noticeVersion,
            String language,
            String noticeSha256,
            Decision decision) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO privacy_consent_record (id, channel, external_id, agent_id, purpose,"
                        + " notice_version, notice_language, notice_sha256, decision, decided_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                channel,
                externalId,
                agentId,
                purpose,
                noticeVersion,
                language,
                noticeSha256,
                decision.name(),
                Timestamp.from(now));
        return new Entry(
                id, channel, externalId, agentId, purpose, noticeVersion, language, noticeSha256,
                decision, now);
    }

    public Optional<Entry> latest(String channel, String externalId, String purpose) {
        return jdbc
                .query(
                        SELECT
                                + " WHERE channel = ? AND external_id = ? AND purpose = ?"
                                + " ORDER BY seq DESC LIMIT 1",
                        ConsentLedger::map,
                        channel,
                        externalId,
                        purpose)
                .stream()
                .findFirst();
    }

    /** Every decision for the contact, oldest first. */
    public List<Entry> history(String channel, String externalId) {
        return jdbc.query(
                SELECT
                        + " WHERE channel = ? AND external_id = ? ORDER BY seq",
                ConsentLedger::map,
                channel,
                externalId);
    }

    public int deleteFor(String channel, String externalId) {
        return jdbc.update(
                "DELETE FROM privacy_consent_record WHERE channel = ? AND external_id = ?",
                channel,
                externalId);
    }

    private static final String SELECT =
            "SELECT id, channel, external_id, agent_id, purpose, notice_version, notice_language,"
                    + " notice_sha256, decision, decided_at FROM privacy_consent_record";

    private static Entry map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Entry(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getObject(4, UUID.class),
                rs.getString(5),
                rs.getInt(6),
                rs.getString(7),
                rs.getString(8),
                Decision.valueOf(rs.getString(9)),
                rs.getTimestamp(10).toInstant());
    }
}
