package com.agenticai.core.admin.localauth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link LocalCredentialStore} on the control-plane tables from migration V655. Plain JDBC, one
 * statement per operation, like {@link JdbcLocalAccountStore}: spending a recovery code, counting a
 * wrong PIN and replacing a code set are each ONE statement, which is what makes them race-free
 * without a transaction.
 *
 * <p>Only present with {@code app.auth.mode=local}.
 */
@Repository
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
public class JdbcLocalCredentialStore implements LocalCredentialStore {

    private static final String PASSKEY_COLUMNS =
            "id, account_id, credential_id, attested_credential_data, sign_count, uv_initialized,"
                    + " backup_eligible, backup_state, transports, rp_id, label, created_at, last_used_at";

    private static final String DEVICE_COLUMNS =
            "id, account_id, label, pin_hash, failed_attempts, created_at, last_used_at, revoked_at";

    private final JdbcTemplate jdbc;

    public JdbcLocalCredentialStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ passkeys

    @Override
    public void insertPasskey(PasskeyRecord p) {
        try {
            jdbc.update(
                    "INSERT INTO control_plane.local_passkey (" + PASSKEY_COLUMNS
                            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    uuid(p.id()),
                    uuid(p.accountId()),
                    p.credentialId(),
                    p.attestedCredentialData(),
                    p.signCount(),
                    p.uvInitialized(),
                    p.backupEligible(),
                    p.backupState(),
                    p.transports(),
                    p.rpId(),
                    p.label(),
                    ts(p.createdAt()),
                    p.lastUsedAt() == null ? null : ts(p.lastUsedAt()));
        } catch (DuplicateKeyException e) {
            throw new DuplicateCredentialException("This passkey is already registered", e);
        }
    }

    @Override
    public Optional<PasskeyRecord> findPasskeyByCredentialId(String credentialId) {
        return jdbc.query(
                        "SELECT " + PASSKEY_COLUMNS + " FROM control_plane.local_passkey WHERE credential_id = ?",
                        PASSKEY_MAPPER,
                        credentialId)
                .stream()
                .findFirst();
    }

    @Override
    public List<PasskeyRecord> listPasskeys(String accountId) {
        UUID account = uuidOrNull(accountId);
        if (account == null) {
            return List.of();
        }
        return jdbc.query(
                "SELECT " + PASSKEY_COLUMNS
                        + " FROM control_plane.local_passkey WHERE account_id = ? ORDER BY created_at",
                PASSKEY_MAPPER,
                account);
    }

    @Override
    public boolean recordPasskeyUse(String id, long signCount, boolean backupState, Instant at) {
        return jdbc.update(
                        "UPDATE control_plane.local_passkey SET sign_count = ?, backup_state = ?,"
                                + " last_used_at = ? WHERE id = ? AND sign_count <= ?",
                        signCount,
                        backupState,
                        ts(at),
                        uuid(id),
                        signCount)
                == 1;
    }

    @Override
    public boolean deletePasskey(String accountId, String id) {
        UUID account = uuidOrNull(accountId);
        UUID passkey = uuidOrNull(id);
        if (account == null || passkey == null) {
            return false;
        }
        return jdbc.update(
                        "DELETE FROM control_plane.local_passkey WHERE id = ? AND account_id = ?", passkey, account)
                == 1;
    }

    // ------------------------------------------------------------------ devices

    @Override
    public void insertDevice(DeviceRecord d) {
        jdbc.update(
                "INSERT INTO control_plane.local_device (" + DEVICE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                uuid(d.id()),
                uuid(d.accountId()),
                d.label(),
                d.pinHash(),
                d.failedAttempts(),
                ts(d.createdAt()),
                d.lastUsedAt() == null ? null : ts(d.lastUsedAt()),
                d.revokedAt() == null ? null : ts(d.revokedAt()));
    }

    @Override
    public Optional<DeviceRecord> findDevice(String id) {
        UUID device = uuidOrNull(id);
        if (device == null) {
            return Optional.empty();
        }
        return jdbc.query(
                        "SELECT " + DEVICE_COLUMNS + " FROM control_plane.local_device WHERE id = ?",
                        DEVICE_MAPPER,
                        device)
                .stream()
                .findFirst();
    }

    @Override
    public List<DeviceRecord> listActiveDevices(String accountId) {
        UUID account = uuidOrNull(accountId);
        if (account == null) {
            return List.of();
        }
        return jdbc.query(
                "SELECT " + DEVICE_COLUMNS
                        + " FROM control_plane.local_device WHERE account_id = ? AND revoked_at IS NULL"
                        + " ORDER BY created_at",
                DEVICE_MAPPER,
                account);
    }

    @Override
    public boolean revokeDevice(String accountId, String id, Instant at) {
        UUID account = uuidOrNull(accountId);
        UUID device = uuidOrNull(id);
        if (account == null || device == null) {
            return false;
        }
        return jdbc.update(
                        "UPDATE control_plane.local_device SET revoked_at = ?"
                                + " WHERE id = ? AND account_id = ? AND revoked_at IS NULL",
                        ts(at),
                        device,
                        account)
                == 1;
    }

    @Override
    public int recordDeviceFailure(String id, int maxFailures, Instant at) {
        UUID device = uuidOrNull(id);
        if (device == null) {
            return 0;
        }
        List<Integer> counts =
                jdbc.query(
                        "UPDATE control_plane.local_device SET failed_attempts = failed_attempts + 1,"
                                + " revoked_at = CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE revoked_at END"
                                + " WHERE id = ? AND revoked_at IS NULL RETURNING failed_attempts",
                        (rs, i) -> rs.getInt(1),
                        maxFailures,
                        ts(at),
                        device);
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    @Override
    public boolean recordDeviceSuccess(String id, Instant at) {
        return jdbc.update(
                        "UPDATE control_plane.local_device SET failed_attempts = 0, last_used_at = ?"
                                + " WHERE id = ? AND revoked_at IS NULL",
                        ts(at),
                        uuid(id))
                > 0;
    }

    // ------------------------------------------------------------------ recovery codes

    @Override
    public void replaceRecoveryCodes(String accountId, List<String> codeHashes, Instant at) {
        // One statement: the data-modifying CTE deletes the old set in the same snapshot that
        // inserts the new one, so a crash or a concurrent regeneration can never leave zero or two sets.
        StringBuilder sql =
                new StringBuilder(
                        "WITH purged AS (DELETE FROM control_plane.local_recovery_code WHERE account_id = ?)"
                                + " INSERT INTO control_plane.local_recovery_code (id, account_id, code_hash,"
                                + " created_at) VALUES ");
        List<Object> args = new ArrayList<>();
        args.add(uuid(accountId));
        for (int i = 0; i < codeHashes.size(); i++) {
            sql.append(i == 0 ? "(?, ?, ?, ?)" : ", (?, ?, ?, ?)");
            args.add(UUID.randomUUID());
            args.add(uuid(accountId));
            args.add(codeHashes.get(i));
            args.add(ts(at));
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    @Override
    public Optional<String> consumeRecoveryCode(String codeHash, Instant at) {
        return jdbc.query(
                        "UPDATE control_plane.local_recovery_code SET used_at = ?"
                                + " WHERE code_hash = ? AND used_at IS NULL RETURNING account_id",
                        (rs, i) -> rs.getString(1),
                        ts(at),
                        codeHash)
                .stream()
                .findFirst();
    }

    @Override
    public int countUnusedRecoveryCodes(String accountId) {
        UUID account = uuidOrNull(accountId);
        if (account == null) {
            return 0;
        }
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM control_plane.local_recovery_code"
                                + " WHERE account_id = ? AND used_at IS NULL",
                        Integer.class,
                        account);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ mapping

    private static final RowMapper<PasskeyRecord> PASSKEY_MAPPER =
            (rs, i) ->
                    new PasskeyRecord(
                            rs.getString("id"),
                            rs.getString("account_id"),
                            rs.getString("credential_id"),
                            rs.getBytes("attested_credential_data"),
                            rs.getLong("sign_count"),
                            rs.getBoolean("uv_initialized"),
                            rs.getBoolean("backup_eligible"),
                            rs.getBoolean("backup_state"),
                            rs.getString("transports"),
                            rs.getString("rp_id"),
                            rs.getString("label"),
                            rs.getTimestamp("created_at").toInstant(),
                            instant(rs.getTimestamp("last_used_at")));

    private static final RowMapper<DeviceRecord> DEVICE_MAPPER =
            (rs, i) ->
                    new DeviceRecord(
                            rs.getString("id"),
                            rs.getString("account_id"),
                            rs.getString("label"),
                            rs.getString("pin_hash"),
                            rs.getInt("failed_attempts"),
                            rs.getTimestamp("created_at").toInstant(),
                            instant(rs.getTimestamp("last_used_at")),
                            instant(rs.getTimestamp("revoked_at")));

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static UUID uuid(String id) {
        return UUID.fromString(id);
    }

    /** Ids that arrive from a URL or a cookie may be junk; junk simply matches nothing. */
    private static UUID uuidOrNull(String id) {
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
