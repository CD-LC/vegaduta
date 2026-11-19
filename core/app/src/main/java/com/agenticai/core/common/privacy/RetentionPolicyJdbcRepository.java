package com.agenticai.core.common.privacy;

import com.agenticai.core.common.privacy.RetentionPolicyService.Rule;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Raw SQL for {@link RetentionPolicyService} ({@code privacy_retention_policy}). */
@Repository
public class RetentionPolicyJdbcRepository {

    private final JdbcTemplate jdbc;

    public RetentionPolicyJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Rule> list() {
        return jdbc.query(
                "SELECT channel, data_class, enabled, retention_days, updated_by, updated_at"
                        + " FROM privacy_retention_policy ORDER BY data_class, channel",
                (rs, n) -> map(rs));
    }

    /** The row that applies to {@code channel}: its own row, else the tenant default (any enabled state). */
    Optional<Rule> effective(String channel, PrivacyDataClass dataClass) {
        return jdbc
                .query(
                        "SELECT channel, data_class, enabled, retention_days, updated_by, updated_at"
                                + " FROM privacy_retention_policy WHERE data_class = ? AND channel IN (?, ?)"
                                + " ORDER BY (channel = '*') LIMIT 1",
                        (rs, n) -> map(rs),
                        dataClass.name(),
                        channel,
                        PrivacyDataClass.ALL_CHANNELS)
                .stream()
                .findFirst();
    }

    void upsert(
            String channel, PrivacyDataClass dataClass, boolean enabled, int retentionDays, String actor) {
        jdbc.update(
                "INSERT INTO privacy_retention_policy (channel, data_class, enabled, retention_days,"
                        + " updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (channel, data_class) DO UPDATE SET enabled = EXCLUDED.enabled,"
                        + " retention_days = EXCLUDED.retention_days, updated_by = EXCLUDED.updated_by,"
                        + " updated_at = EXCLUDED.updated_at",
                channel,
                dataClass.name(),
                enabled,
                retentionDays,
                actor,
                Timestamp.from(Instant.now()));
    }

    private static Rule map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Rule(
                rs.getString(1),
                PrivacyDataClass.valueOf(rs.getString(2)),
                rs.getBoolean(3),
                rs.getInt(4),
                rs.getString(5),
                rs.getTimestamp(6).toInstant());
    }
}
