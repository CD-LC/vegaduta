package com.agenticai.core.admin.localauth;

import com.agenticai.core.json.JsonMappers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link LocalAccountStore} on the control-plane tables from migration V510. Plain JDBC rather than
 * JPA entities on purpose: these rows live outside any tenant schema, are only read in local mode,
 * and every operation is a single statement (the rotating-refresh claim and the one-time
 * setup-token consume are each ONE conditional UPDATE, which is what makes them race-free without a
 * transaction).
 *
 * <p>Only present with {@code app.auth.mode=local}; in the default mode the tables are empty and
 * this bean does not exist.
 */
@Repository
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class JdbcLocalAccountStore implements LocalAccountStore {

    private static final ObjectMapper JSON = JsonMappers.plain();
    private static final TypeReference<Map<String, List<String>>> ATTRIBUTES =
            new TypeReference<>() {};

    private static final String ACCOUNT_COLUMNS =
            "id, email, first_name, last_name, password_hash, must_change_password, enabled,"
                    + " setup_pending, tenant_id, roles, attributes, created_at, last_login_at";

    private static final String REFRESH_COLUMNS =
            "id, account_id, family_id, token_hash, auth_method, auth_time, issued_at, expires_at,"
                    + " family_expires_at, used_at, revoked_at";

    private final JdbcTemplate jdbc;

    public JdbcLocalAccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ accounts

    @Override
    public long countAccounts() {
        Long n =
                jdbc.queryForObject("SELECT count(*) FROM control_plane.local_account", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public boolean anySetupPending() {
        Boolean pending =
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM control_plane.local_account WHERE setup_pending)",
                        Boolean.class);
        return Boolean.TRUE.equals(pending);
    }

    @Override
    public Optional<LocalAccount> findById(String id) {
        return jdbc
                .query(
                        "SELECT "
                                + ACCOUNT_COLUMNS
                                + " FROM control_plane.local_account WHERE id = ?",
                        ACCOUNT_MAPPER,
                        uuid(id))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<LocalAccount> findByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        "SELECT "
                                + ACCOUNT_COLUMNS
                                + " FROM control_plane.local_account WHERE email_lower = ?",
                        ACCOUNT_MAPPER,
                        email.trim().toLowerCase(Locale.ROOT))
                .stream()
                .findFirst();
    }

    @Override
    public List<LocalAccount> listAll() {
        return jdbc.query(
                "SELECT "
                        + ACCOUNT_COLUMNS
                        + " FROM control_plane.local_account ORDER BY created_at",
                ACCOUNT_MAPPER);
    }

    @Override
    public List<LocalAccount> listByTenant(String tenantId) {
        return jdbc.query(
                "SELECT "
                        + ACCOUNT_COLUMNS
                        + " FROM control_plane.local_account WHERE tenant_id = ? ORDER BY created_at",
                ACCOUNT_MAPPER,
                tenantId);
    }

    @Override
    public void insert(LocalAccount a) {
        Instant now = a.createdAt() == null ? Instant.now() : a.createdAt();
        try {
            jdbc.update(
                    "INSERT INTO control_plane.local_account (id, email, email_lower, first_name,"
                            + " last_name, password_hash, must_change_password, enabled, setup_pending,"
                            + " tenant_id, roles, attributes, created_at, updated_at, password_changed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    uuid(a.id()),
                    a.email().trim(),
                    a.email().trim().toLowerCase(Locale.ROOT),
                    a.firstName(),
                    a.lastName(),
                    a.passwordHash(),
                    a.mustChangePassword(),
                    a.enabled(),
                    a.setupPending(),
                    a.tenantId(),
                    joinRoles(a.roles()),
                    attributesJson(a),
                    ts(now),
                    ts(now),
                    a.passwordHash() == null ? null : ts(now));
        } catch (DuplicateKeyException e) {
            throw new DuplicateAccountException(
                    "An account with this e-mail address already exists", e);
        }
    }

    @Override
    public void update(LocalAccount a) {
        Instant now = Instant.now();
        try {
            jdbc.update(
                    "UPDATE control_plane.local_account SET email = ?, email_lower = ?, first_name = ?,"
                            + " last_name = ?, password_hash = ?, must_change_password = ?, enabled = ?,"
                            + " setup_pending = ?, tenant_id = ?, roles = ?, attributes = ?, updated_at = ?,"
                            + " password_changed_at = CASE WHEN password_hash IS DISTINCT FROM CAST(? AS VARCHAR)"
                            + " THEN ? ELSE password_changed_at END WHERE id = ?",
                    a.email().trim(),
                    a.email().trim().toLowerCase(Locale.ROOT),
                    a.firstName(),
                    a.lastName(),
                    a.passwordHash(),
                    a.mustChangePassword(),
                    a.enabled(),
                    a.setupPending(),
                    a.tenantId(),
                    joinRoles(a.roles()),
                    attributesJson(a),
                    ts(now),
                    a.passwordHash(),
                    ts(now),
                    uuid(a.id()));
        } catch (DuplicateKeyException e) {
            throw new DuplicateAccountException(
                    "An account with this e-mail address already exists", e);
        }
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM control_plane.local_account WHERE id = ?", uuid(id));
    }

    @Override
    public void touchLogin(String id, Instant at) {
        jdbc.update(
                "UPDATE control_plane.local_account SET last_login_at = ? WHERE id = ?",
                ts(at),
                uuid(id));
    }

    // ------------------------------------------------------------------ refresh tokens

    @Override
    public void insertRefresh(RefreshRecord r) {
        jdbc.update(
                "INSERT INTO control_plane.local_refresh_token ("
                        + REFRESH_COLUMNS
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                uuid(r.id()),
                uuid(r.accountId()),
                uuid(r.familyId()),
                r.tokenHash(),
                r.authMethod(),
                ts(r.authTime()),
                ts(r.issuedAt()),
                ts(r.expiresAt()),
                ts(r.familyExpiresAt()),
                r.usedAt() == null ? null : ts(r.usedAt()),
                r.revokedAt() == null ? null : ts(r.revokedAt()));
    }

    @Override
    public Optional<RefreshRecord> findRefreshByHash(String tokenHash) {
        return jdbc
                .query(
                        "SELECT "
                                + REFRESH_COLUMNS
                                + " FROM control_plane.local_refresh_token WHERE token_hash = ?",
                        REFRESH_MAPPER,
                        tokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public boolean claimRefresh(String id, Instant at) {
        return jdbc.update(
                        "UPDATE control_plane.local_refresh_token SET used_at = ?"
                                + " WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL",
                        ts(at),
                        uuid(id))
                == 1;
    }

    @Override
    public void revokeFamily(String familyId, Instant at) {
        jdbc.update(
                "UPDATE control_plane.local_refresh_token SET revoked_at = ?"
                        + " WHERE family_id = ? AND revoked_at IS NULL",
                ts(at),
                uuid(familyId));
    }

    @Override
    public void revokeAllForAccount(String accountId, Instant at) {
        jdbc.update(
                "UPDATE control_plane.local_refresh_token SET revoked_at = ?"
                        + " WHERE account_id = ? AND revoked_at IS NULL",
                ts(at),
                uuid(accountId));
    }

    @Override
    public void revokeAllForAccountExcept(String accountId, String keepFamilyId, Instant at) {
        jdbc.update(
                "UPDATE control_plane.local_refresh_token SET revoked_at = ?"
                        + " WHERE account_id = ? AND family_id <> ? AND revoked_at IS NULL",
                ts(at),
                uuid(accountId),
                uuid(keepFamilyId));
    }

    @Override
    public int purgeRefreshExpiredBefore(Instant cutoff) {
        return jdbc.update(
                "DELETE FROM control_plane.local_refresh_token WHERE family_expires_at < ?",
                ts(cutoff));
    }

    // ------------------------------------------------------------------ setup tokens

    @Override
    public void insertSetupToken(
            String accountId, String tokenHash, Instant createdAt, Instant expiresAt) {
        // One live setup token per account: a re-issued link replaces the printed one.
        jdbc.update(
                "UPDATE control_plane.local_setup_token SET consumed_at = ?"
                        + " WHERE account_id = ? AND consumed_at IS NULL",
                ts(createdAt),
                uuid(accountId));
        jdbc.update(
                "INSERT INTO control_plane.local_setup_token (id, account_id, token_hash, created_at,"
                        + " expires_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                uuid(accountId),
                tokenHash,
                ts(createdAt),
                ts(expiresAt));
    }

    @Override
    public Optional<String> findSetupAccount(String tokenHash, Instant now) {
        return jdbc
                .query(
                        "SELECT account_id::text FROM control_plane.local_setup_token"
                                + " WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ?",
                        (rs, i) -> rs.getString(1),
                        tokenHash,
                        ts(now))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<String> consumeSetupToken(String tokenHash, Instant now) {
        return jdbc
                .query(
                        "UPDATE control_plane.local_setup_token SET consumed_at = ?"
                                + " WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ?"
                                + " RETURNING account_id::text",
                        (rs, i) -> rs.getString(1),
                        ts(now),
                        tokenHash,
                        ts(now))
                .stream()
                .findFirst();
    }

    // ------------------------------------------------------------------ mapping

    private static final RowMapper<LocalAccount> ACCOUNT_MAPPER =
            (rs, i) ->
                    new LocalAccount(
                            rs.getString("id"),
                            rs.getString("email"),
                            rs.getString("first_name"),
                            rs.getString("last_name"),
                            rs.getString("password_hash"),
                            rs.getBoolean("must_change_password"),
                            rs.getBoolean("enabled"),
                            rs.getBoolean("setup_pending"),
                            rs.getString("tenant_id"),
                            splitRoles(rs.getString("roles")),
                            parseAttributes(rs.getString("attributes")),
                            instant(rs, "created_at"),
                            instant(rs, "last_login_at"));

    private static final RowMapper<RefreshRecord> REFRESH_MAPPER =
            (rs, i) ->
                    new RefreshRecord(
                            rs.getString("id"),
                            rs.getString("account_id"),
                            rs.getString("family_id"),
                            rs.getString("token_hash").trim(),
                            rs.getString("auth_method"),
                            instant(rs, "auth_time"),
                            instant(rs, "issued_at"),
                            instant(rs, "expires_at"),
                            instant(rs, "family_expires_at"),
                            instant(rs, "used_at"),
                            instant(rs, "revoked_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static UUID uuid(String id) {
        return UUID.fromString(id);
    }

    private static String joinRoles(Set<String> roles) {
        return String.join(",", new LinkedHashSet<>(roles));
    }

    private static Set<String> splitRoles(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(
                Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    private static String attributesJson(LocalAccount account) {
        try {
            return JSON.writeValueAsString(account.attributes());
        } catch (JacksonException e) {
            throw new IllegalStateException("could not serialise account attributes", e);
        }
    }

    private static Map<String, List<String>> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, ATTRIBUTES);
        } catch (JacksonException e) {
            return Map.of();
        }
    }
}
