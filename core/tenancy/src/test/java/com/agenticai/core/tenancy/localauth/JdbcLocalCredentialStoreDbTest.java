package com.agenticai.core.tenancy.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.admin.localauth.JdbcLocalCredentialStore;
import com.agenticai.core.admin.localauth.LocalCredentialStore;
import com.agenticai.core.admin.localauth.LocalCredentialStore.DeviceRecord;
import com.agenticai.core.admin.localauth.LocalCredentialStore.DuplicateCredentialException;
import com.agenticai.core.admin.localauth.LocalCredentialStore.PasskeyRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs the REAL migrations (V510, V655) and {@link JdbcLocalCredentialStore} against a live
 * Postgres: the one-statement operations (counting a wrong PIN, spending a recovery code, replacing a
 * code set, advancing a passkey counter) are SQL, which the in-memory fake cannot prove. Skipped
 * unless {@code LOCALAUTH_TEST_JDBC_URL} (plus {@code _USER} / {@code _PASSWORD}) is set, exactly like
 * {@link JdbcLocalAccountStoreDbTest}, which this follows.
 */
@EnabledIfEnvironmentVariable(named = "LOCALAUTH_TEST_JDBC_URL", matches = ".+")
class JdbcLocalCredentialStoreDbTest {

    private static final Path MIGRATIONS = Path.of("..", "app", "src", "main", "resources", "db", "migration", "controlplane");

    private JdbcTemplate jdbc;
    private LocalCredentialStore store;
    private String account;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds =
                new DriverManagerDataSource(
                        System.getenv("LOCALAUTH_TEST_JDBC_URL"),
                        System.getenv().getOrDefault("LOCALAUTH_TEST_USER", ""),
                        System.getenv().getOrDefault("LOCALAUTH_TEST_PASSWORD", ""));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS control_plane CASCADE");
        jdbc.execute("CREATE SCHEMA control_plane");
        jdbc.execute(Files.readString(MIGRATIONS.resolve("V510__local_auth.sql")));
        jdbc.execute(Files.readString(MIGRATIONS.resolve("V655__local_passkeys_devices.sql")));
        store = new JdbcLocalCredentialStore(jdbc);
        account = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO control_plane.local_account (id, email, email_lower, created_at, updated_at)"
                        + " VALUES (?::uuid, 'a@x.test', 'a@x.test', now(), now())",
                account);
    }

    private PasskeyRecord passkey(String credentialId) {
        return new PasskeyRecord(UUID.randomUUID().toString(), account, credentialId, new byte[] {1, 2, 3}, 5, true, true, false, "internal,hybrid", "x.test", "Laptop", now, null);
    }

    @Test
    void passkeysRoundTripAdvanceTheCounterAndRefuseDuplicatesAndRegressions() {
        PasskeyRecord p = passkey("cred-1");
        store.insertPasskey(p);

        PasskeyRecord read = store.findPasskeyByCredentialId("cred-1").orElseThrow();
        assertThat(read.attestedCredentialData()).containsExactly(1, 2, 3);
        assertThat(read.transports()).isEqualTo("internal,hybrid");
        assertThat(read.uvInitialized()).isTrue();
        assertThat(read.createdAt()).isEqualTo(now);
        assertThatThrownBy(() -> store.insertPasskey(passkey("cred-1"))).isInstanceOf(DuplicateCredentialException.class);

        assertThat(store.recordPasskeyUse(p.id(), 7, true, now)).isTrue();
        assertThat(store.recordPasskeyUse(p.id(), 6, false, now)).as("a counter behind the stored one").isFalse();
        PasskeyRecord after = store.findPasskeyByCredentialId("cred-1").orElseThrow();
        assertThat(after.signCount()).isEqualTo(7);
        assertThat(after.backupState()).isTrue();
        assertThat(after.lastUsedAt()).isNotNull();

        assertThat(store.listPasskeys("not-a-uuid")).isEmpty();
        assertThat(store.deletePasskey(UUID.randomUUID().toString(), p.id())).as("another account's id").isFalse();
        assertThat(store.deletePasskey(account, p.id())).isTrue();
        assertThat(store.listPasskeys(account)).isEmpty();
    }

    @Test
    void aDeviceIsRevokedByTheConfiguredNumberOfConsecutiveFailures() {
        String id = UUID.randomUUID().toString();
        store.insertDevice(new DeviceRecord(id, account, "Laptop", "{bcrypt}x", 0, now, null, null));

        assertThat(store.recordDeviceFailure(id, 3, now)).isEqualTo(1);
        assertThat(store.recordDeviceFailure(id, 3, now)).isEqualTo(2);
        store.recordDeviceSuccess(id, now);
        assertThat(store.findDevice(id).orElseThrow().failedAttempts()).isZero();
        store.recordDeviceFailure(id, 3, now);
        store.recordDeviceFailure(id, 3, now);
        assertThat(store.recordDeviceFailure(id, 3, now)).isEqualTo(3);

        assertThat(store.findDevice(id).orElseThrow().revokedAt()).isNotNull();
        assertThat(store.recordDeviceFailure(id, 3, now)).as("a revoked device stops counting").isZero();
        assertThat(store.listActiveDevices(account)).isEmpty();
        assertThat(store.findDevice("junk")).isEmpty();
    }

    @Test
    void aDeviceCanBeRevokedOnlyByItsOwnerAndOnlyOnce() {
        String id = UUID.randomUUID().toString();
        store.insertDevice(new DeviceRecord(id, account, "Phone", "{bcrypt}y", 0, now, null, null));

        assertThat(store.revokeDevice(UUID.randomUUID().toString(), id, now)).isFalse();
        assertThat(store.revokeDevice(account, id, now)).isTrue();
        assertThat(store.revokeDevice(account, id, now)).isFalse();
    }

    @Test
    void recoveryCodesAreSpentOnceAndReplacedAsASet() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            first.add(String.format("%064d", i));
            second.add(String.format("%064d", 100 + i));
        }
        store.replaceRecoveryCodes(account, first, now);

        assertThat(store.countUnusedRecoveryCodes(account)).isEqualTo(8);
        assertThat(store.consumeRecoveryCode(first.get(0), now)).contains(account);
        assertThat(store.consumeRecoveryCode(first.get(0), now)).as("second use").isEmpty();
        assertThat(store.countUnusedRecoveryCodes(account)).isEqualTo(7);

        store.replaceRecoveryCodes(account, second, now);
        assertThat(store.countUnusedRecoveryCodes(account)).isEqualTo(8);
        assertThat(store.consumeRecoveryCode(first.get(1), now)).as("an old code").isEmpty();
        assertThat(store.consumeRecoveryCode(second.get(1), now)).contains(account);
    }

    @Test
    void deletingTheAccountRemovesEverythingItOwned() {
        store.insertPasskey(passkey("cred-9"));
        store.insertDevice(new DeviceRecord(UUID.randomUUID().toString(), account, "d", "{bcrypt}x", 0, now, null, null));
        store.replaceRecoveryCodes(account, List.of(String.format("%064d", 1)), now);

        jdbc.update("DELETE FROM control_plane.local_account WHERE id = ?::uuid", account);

        Integer left =
                jdbc.queryForObject(
                        "SELECT (SELECT count(*) FROM control_plane.local_passkey)"
                                + " + (SELECT count(*) FROM control_plane.local_device)"
                                + " + (SELECT count(*) FROM control_plane.local_recovery_code)",
                        Integer.class);
        assertThat(left).isZero();
    }
}
