package com.agenticai.core.common.privacy;

import com.agenticai.core.common.privacy.ConsentLedger.Decision;
import com.agenticai.core.common.privacy.ConsentLedger.Entry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Raw SQL for {@link ConsentLedger} (append-only {@code privacy_consent_record}). */
@Repository
public class ConsentLedgerJdbcRepository {

    private static final String SELECT =
            "SELECT id, channel, external_id, agent_id, purpose, notice_version, notice_language,"
                    + " notice_sha256, decision, decided_at FROM privacy_consent_record";

    private final JdbcTemplate jdbc;

    public ConsentLedgerJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(
            UUID id,
            String channel,
            String externalId,
            UUID agentId,
            String purpose,
            int noticeVersion,
            String language,
            String noticeSha256,
            Decision decision,
            Instant decidedAt) {
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
                Timestamp.from(decidedAt));
    }

    Optional<Entry> latest(String channel, String externalId, String purpose) {
        return jdbc
                .query(
                        SELECT
                                + " WHERE channel = ? AND external_id = ? AND purpose = ?"
                                + " ORDER BY seq DESC LIMIT 1",
                        ConsentLedgerJdbcRepository::map,
                        channel,
                        externalId,
                        purpose)
                .stream()
                .findFirst();
    }

    List<Entry> history(String channel, String externalId) {
        return jdbc.query(
                SELECT + " WHERE channel = ? AND external_id = ? ORDER BY seq",
                ConsentLedgerJdbcRepository::map,
                channel,
                externalId);
    }

    int deleteFor(String channel, String externalId) {
        return jdbc.update(
                "DELETE FROM privacy_consent_record WHERE channel = ? AND external_id = ?",
                channel,
                externalId);
    }

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
