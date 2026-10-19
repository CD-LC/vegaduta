package com.agenticai.core.common.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Retention periods per tenant ({@code channel = '*'}) and per channel, per {@link
 * PrivacyDataClass}. Nothing is purged unless a row exists AND is enabled: upgrading never starts
 * deleting data.
 *
 * <p><b>Defaults.</b> When a tenant enables a rule without a period, it gets {@value
 * #DEFAULT_RETENTION_DAYS} days. That figure is the one-year floor in DPDP Rules 2025, rule 8(3),
 * for personal data, traffic data and logs of processing (in force from 13 May 2027). Shorter is
 * allowed (the tenant may have a lawful reason, and the rule is the fiduciary's to apply), but the
 * response carries a warning so it is a decision rather than an accident. Whether the floor applies
 * to a given tenant is to be confirmed by counsel.
 */
@Service
public class RetentionPolicyService {

    public static final int DEFAULT_RETENTION_DAYS = 365;
    public static final int MAX_RETENTION_DAYS = 3650;

    public record Rule(
            String channel,
            PrivacyDataClass dataClass,
            boolean enabled,
            int retentionDays,
            String updatedBy,
            Instant updatedAt) {}

    public record Saved(Rule rule, List<String> warnings) {}

    private final JdbcTemplate jdbc;

    public RetentionPolicyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Rule> list() {
        return jdbc.query(
                "SELECT channel, data_class, enabled, retention_days, updated_by, updated_at"
                        + " FROM privacy_retention_policy ORDER BY data_class, channel",
                (rs, n) ->
                        new Rule(
                                rs.getString(1),
                                PrivacyDataClass.valueOf(rs.getString(2)),
                                rs.getBoolean(3),
                                rs.getInt(4),
                                rs.getString(5),
                                rs.getTimestamp(6).toInstant()));
    }

    /** The enabled rule that applies to {@code channel}: its own row, else the tenant default. */
    public Optional<Rule> effective(String channel, PrivacyDataClass dataClass) {
        return jdbc
                .query(
                        "SELECT channel, data_class, enabled, retention_days, updated_by, updated_at"
                                + " FROM privacy_retention_policy WHERE data_class = ? AND channel IN (?, ?)"
                                + " ORDER BY (channel = '*') LIMIT 1",
                        (rs, n) ->
                                new Rule(
                                        rs.getString(1),
                                        PrivacyDataClass.valueOf(rs.getString(2)),
                                        rs.getBoolean(3),
                                        rs.getInt(4),
                                        rs.getString(5),
                                        rs.getTimestamp(6).toInstant()),
                        dataClass.name(),
                        channel,
                        PrivacyDataClass.ALL_CHANNELS)
                .stream()
                .findFirst()
                .filter(Rule::enabled);
    }

    public Saved put(
            String channelName, PrivacyDataClass dataClass, boolean enabled, Integer days, String actor) {
        String channel = channelName == null ? PrivacyDataClass.ALL_CHANNELS : channelName.trim().toUpperCase(Locale.ROOT);
        if (!dataClass.allows(channel)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    dataClass + " cannot be set for channel '" + channel + "'");
        }
        int retentionDays = days == null ? DEFAULT_RETENTION_DAYS : days;
        if (retentionDays < 1 || retentionDays > MAX_RETENTION_DAYS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "retentionDays must be between 1 and " + MAX_RETENTION_DAYS);
        }
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
        List<String> warnings = new ArrayList<>();
        if (enabled && retentionDays < DEFAULT_RETENTION_DAYS) {
            warnings.add(
                    "Shorter than 365 days. DPDP Rules 2025, rule 8(3) (in force from 13 May 2027)"
                            + " requires a fiduciary to retain personal data, traffic data and logs"
                            + " of processing for at least one year unless another law requires"
                            + " otherwise. Whether this applies to you is to be confirmed by counsel.");
        }
        if (enabled) {
            warnings.add("Data older than " + retentionDays + " days will be permanently deleted by the nightly job.");
        }
        Rule rule = list().stream()
                .filter(r -> r.channel().equals(channel) && r.dataClass() == dataClass)
                .findFirst()
                .orElseThrow();
        return new Saved(rule, warnings);
    }
}
