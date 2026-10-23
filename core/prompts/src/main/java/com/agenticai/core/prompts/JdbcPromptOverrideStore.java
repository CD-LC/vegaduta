package com.agenticai.core.prompts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * {@link PromptOverrideStore} over {@link JdbcTemplate}. TENANT and AGENT rows are in {@code
 * prompt_override} / {@code prompt_override_history} of the current tenant's schema (unqualified:
 * the connection's search path is the tenant, exactly as for every other tenant table); PLATFORM
 * rows are in {@code control_plane.platform_prompt_override} / {@code
 * control_plane.platform_prompt_override_history}. Table names are constants here, never built from
 * input.
 */
@Component
public class JdbcPromptOverrideStore implements PromptOverrideStore {

    private static final String TENANT_TABLE = "prompt_override";
    private static final String TENANT_HISTORY = "prompt_override_history";
    private static final String PLATFORM_TABLE = "control_plane.platform_prompt_override";
    private static final String PLATFORM_HISTORY = "control_plane.platform_prompt_override_history";

    private static final String COLUMNS =
            "scope, scope_id, prompt_key, locale, prompt_text, enabled, version, created_by, created_at";

    private static final String HISTORY_COLUMNS =
            "id, scope, scope_id, prompt_key, locale, version, action, prompt_text, enabled, previous_text,"
                    + " previous_enabled, reason, created_by, created_at";

    private static final RowMapper<PromptOverride> ROW =
            (rs, i) ->
                    new PromptOverride(
                            PromptScope.parse(rs.getString("scope")).orElseThrow(),
                            rs.getString("scope_id"),
                            rs.getString("prompt_key"),
                            rs.getString("locale"),
                            rs.getString("prompt_text"),
                            rs.getBoolean("enabled"),
                            rs.getInt("version"),
                            rs.getString("created_by"),
                            instant(rs, "created_at"));

    private static final RowMapper<PromptHistoryEntry> HISTORY_ROW =
            (rs, i) ->
                    new PromptHistoryEntry(
                            rs.getLong("id"),
                            PromptScope.parse(rs.getString("scope")).orElseThrow(),
                            rs.getString("scope_id"),
                            rs.getString("prompt_key"),
                            rs.getString("locale"),
                            rs.getInt("version"),
                            rs.getString("action"),
                            rs.getString("prompt_text"),
                            rs.getBoolean("enabled"),
                            rs.getString("previous_text"),
                            rs.getBoolean("previous_enabled"),
                            rs.getString("reason"),
                            rs.getString("created_by"),
                            instant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public JdbcPromptOverrideStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static String table(PromptScope scope) {
        return scope == PromptScope.PLATFORM ? PLATFORM_TABLE : TENANT_TABLE;
    }

    private static String historyTable(PromptScope scope) {
        return scope == PromptScope.PLATFORM ? PLATFORM_HISTORY : TENANT_HISTORY;
    }

    @Override
    public List<PromptOverride> loadPlatform() {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + PLATFORM_TABLE, ROW);
    }

    @Override
    public List<PromptOverride> loadCurrentTenant() {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TENANT_TABLE, ROW);
    }

    @Override
    public Optional<PromptOverride> find(PromptScope scope, String scopeId, String key, String locale) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM " + table(scope)
                                + " WHERE scope = ? AND scope_id = ? AND prompt_key = ? AND locale = ?",
                        ROW,
                        scope.dbValue(),
                        scopeId,
                        key,
                        locale)
                .stream()
                .findFirst();
    }

    @Override
    public List<PromptOverride> list(PromptScope scope, String scopeId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM " + table(scope)
                        + " WHERE scope = ? AND scope_id = ? ORDER BY prompt_key, locale",
                ROW,
                scope.dbValue(),
                scopeId);
    }

    @Override
    public int latestVersion(PromptScope scope, String scopeId, String key, String locale) {
        Integer v =
                jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version), 0) FROM " + historyTable(scope)
                                + " WHERE scope = ? AND scope_id = ? AND prompt_key = ? AND locale = ?",
                        Integer.class,
                        scope.dbValue(),
                        scopeId,
                        key,
                        locale);
        return v == null ? 0 : v;
    }

    @Override
    public void saveCurrent(PromptOverride row) {
        jdbc.update(
                "INSERT INTO " + table(row.scope()) + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (scope, scope_id, prompt_key, locale) DO UPDATE SET prompt_text = EXCLUDED.prompt_text,"
                        + " enabled = EXCLUDED.enabled, version = EXCLUDED.version, created_by = EXCLUDED.created_by,"
                        + " created_at = EXCLUDED.created_at",
                row.scope().dbValue(),
                row.scopeId(),
                row.key(),
                row.locale(),
                row.text(),
                row.enabled(),
                row.version(),
                row.createdBy(),
                Timestamp.from(row.createdAt()));
    }

    @Override
    public void deleteCurrent(PromptScope scope, String scopeId, String key, String locale) {
        jdbc.update(
                "DELETE FROM " + table(scope) + " WHERE scope = ? AND scope_id = ? AND prompt_key = ? AND locale = ?",
                scope.dbValue(),
                scopeId,
                key,
                locale);
    }

    @Override
    public void appendHistory(PromptHistoryEntry e) {
        jdbc.update(
                "INSERT INTO " + historyTable(e.scope()) + " (scope, scope_id, prompt_key, locale, version, action,"
                        + " prompt_text, enabled, previous_text, previous_enabled, reason, created_by, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.scope().dbValue(),
                e.scopeId(),
                e.key(),
                e.locale(),
                e.version(),
                e.action(),
                e.text(),
                e.enabled(),
                e.previousText(),
                e.previousEnabled(),
                e.reason(),
                e.createdBy(),
                Timestamp.from(e.createdAt()));
    }

    @Override
    public List<PromptHistoryEntry> history(PromptScope scope, String scopeId, String key, String locale, int limit) {
        StringBuilder sql =
                new StringBuilder("SELECT " + HISTORY_COLUMNS + " FROM " + historyTable(scope) + " WHERE scope = ? AND scope_id = ?");
        List<Object> args = new ArrayList<>(List.of(scope.dbValue(), scopeId));
        if (key != null) {
            sql.append(" AND prompt_key = ?");
            args.add(key);
        }
        if (locale != null) {
            sql.append(" AND locale = ?");
            args.add(locale);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbc.query(sql.toString(), HISTORY_ROW, args.toArray());
    }
}
