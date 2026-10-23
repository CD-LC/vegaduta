package com.agenticai.core.settings;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * {@link SettingsStore} over {@link JdbcTemplate}: {@code setting_override} and {@code setting_override_history} of the
 * current tenant's schema (unqualified: the connection's search path is the tenant, exactly as for every other
 * tenant table). Table names are constants, never built from input.
 */
@Component
public class JdbcSettingsStore implements SettingsStore {

    private static final ObjectMapper JSON =
            JsonMappers.plain().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS);

    private static final RowMapper<SettingOverride> ROW =
            (rs, i) ->
                    new SettingOverride(
                            SettingScope.parse(rs.getString("scope")).orElseThrow(),
                            rs.getString("scope_id"),
                            rs.getString("setting_key"),
                            parse(rs.getString("value")),
                            rs.getInt("version"),
                            rs.getString("updated_by"),
                            instant(rs, "updated_at"));

    private static final RowMapper<SettingHistoryEntry> HISTORY_ROW =
            (rs, i) ->
                    new SettingHistoryEntry(
                            rs.getLong("id"),
                            SettingScope.parse(rs.getString("scope")).orElseThrow(),
                            rs.getString("scope_id"),
                            rs.getString("setting_key"),
                            rs.getInt("version"),
                            rs.getString("action"),
                            parse(rs.getString("value")),
                            parse(rs.getString("previous_value")),
                            rs.getString("reason"),
                            rs.getString("actor"),
                            instant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public JdbcSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static Object parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored setting value is not JSON: " + json, e);
        }
    }

    static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Setting value is not serialisable", e);
        }
    }

    private Map<String, Object> values(SettingScope scope, String scopeId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (SettingOverride o :
                jdbc.query(
                        "SELECT scope, scope_id, setting_key, value::text AS value, version, updated_by, updated_at"
                                + " FROM setting_override WHERE scope = ? AND scope_id = ?",
                        ROW,
                        scope.json(),
                        scopeId)) {
            out.put(o.key(), o.value());
        }
        return out;
    }

    @Override
    public Map<String, Object> tenantValues() {
        return values(SettingScope.TENANT, "");
    }

    @Override
    public Map<String, Object> agentValues(String agentId) {
        return values(SettingScope.AGENT, agentId);
    }

    @Override
    public Optional<SettingOverride> find(SettingScope scope, String scopeId, String key) {
        return jdbc.query(
                        "SELECT scope, scope_id, setting_key, value::text AS value, version, updated_by, updated_at"
                                + " FROM setting_override WHERE scope = ? AND scope_id = ? AND setting_key = ?",
                        ROW,
                        scope.json(),
                        scopeId,
                        key)
                .stream()
                .findFirst();
    }

    @Override
    public int latestVersion(SettingScope scope, String scopeId, String key) {
        // Two concurrent writers of the same key would both compute MAX+1 and the second would hit the unique
        // (scope, scope_id, key, version) constraint (a 500). A transaction-scoped advisory lock on the key
        // serialises them: the second one waits for the first to commit, then reads the new maximum. Callers run
        // inside the service's transaction, so the lock is held until the write is committed.
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text",
                String.class,
                "setting:" + scope.json() + ':' + scopeId + ':' + key);
        Integer v =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version), 0) FROM setting_override_history"
                                + " WHERE scope = ? AND scope_id = ? AND setting_key = ?",
                        Integer.class,
                        scope.json(),
                        scopeId,
                        key);
        return v == null ? 0 : v;
    }

    @Override
    public void saveCurrent(SettingOverride row) {
        jdbc.update(
                "INSERT INTO setting_override (scope, scope_id, setting_key, value, version, updated_by, updated_at)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)"
                        + " ON CONFLICT (scope, scope_id, setting_key) DO UPDATE SET value = EXCLUDED.value,"
                        + " version = EXCLUDED.version, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
                row.scope().json(),
                row.scopeId(),
                row.key(),
                write(row.value()),
                row.version(),
                row.updatedBy(),
                Timestamp.from(row.updatedAt()));
    }

    @Override
    public void deleteCurrent(SettingScope scope, String scopeId, String key) {
        jdbc.update(
                "DELETE FROM setting_override WHERE scope = ? AND scope_id = ? AND setting_key = ?",
                scope.json(),
                scopeId,
                key);
    }

    @Override
    public void appendHistory(SettingHistoryEntry e) {
        jdbc.update(
                "INSERT INTO setting_override_history (scope, scope_id, setting_key, version, action, value,"
                        + " previous_value, reason, actor, created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)",
                e.scope().json(),
                e.scopeId(),
                e.key(),
                e.version(),
                e.action(),
                write(e.value()),
                write(e.previousValue()),
                e.reason(),
                e.actor(),
                Timestamp.from(e.at()));
    }

    @Override
    public List<SettingHistoryEntry> history(SettingScope scope, String scopeId, String key, int limit) {
        return jdbc.query(
                "SELECT id, scope, scope_id, setting_key, version, action, value::text AS value,"
                        + " previous_value::text AS previous_value, reason, actor, created_at"
                        + " FROM setting_override_history WHERE scope = ? AND scope_id = ? AND setting_key = ?"
                        + " ORDER BY version DESC, id DESC LIMIT ?",
                HISTORY_ROW,
                scope.json(),
                scopeId,
                key,
                Math.max(1, limit));
    }

    @Override
    public List<SettingHistoryEntry> historyPage(SettingScope scope, String scopeId, java.time.Instant since, int limit, int offset) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        String where = historyFilter(scope, scopeId, since, args);
        args.add(Math.max(1, limit));
        args.add(Math.max(0, offset));
        return jdbc.query(
                "SELECT id, scope, scope_id, setting_key, version, action, value::text AS value,"
                        + " previous_value::text AS previous_value, reason, actor, created_at"
                        + " FROM setting_override_history" + where
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                HISTORY_ROW,
                args.toArray());
    }

    @Override
    public long historyCount(SettingScope scope, String scopeId, java.time.Instant since) {
        java.util.List<Object> args = new java.util.ArrayList<>();
        String where = historyFilter(scope, scopeId, since, args);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM setting_override_history" + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /** The WHERE clause for the bulk history queries: fixed fragments only, every value is a bind parameter. */
    private static String historyFilter(SettingScope scope, String scopeId, java.time.Instant since, java.util.List<Object> args) {
        StringBuilder sb = new StringBuilder();
        String glue = " WHERE ";
        if (scope != null) {
            sb.append(glue).append("scope = ?");
            args.add(scope.json());
            glue = " AND ";
        }
        if (scopeId != null) {
            sb.append(glue).append("scope_id = ?");
            args.add(scopeId);
            glue = " AND ";
        }
        if (since != null) {
            sb.append(glue).append("created_at >= ?");
            args.add(Timestamp.from(since));
        }
        return sb.toString();
    }
}
