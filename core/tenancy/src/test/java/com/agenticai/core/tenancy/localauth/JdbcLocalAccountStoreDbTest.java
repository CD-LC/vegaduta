package com.agenticai.core.tenancy.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.admin.localauth.JdbcLocalAccountStore;
import com.agenticai.core.admin.localauth.LocalAccount;
import com.agenticai.core.admin.localauth.LocalAccountStore;
import com.agenticai.core.admin.localauth.LocalAccountStore.RefreshRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs the REAL migration (V510) and {@link JdbcLocalAccountStore} against a live Postgres, because
 * every other local-auth test uses an in-memory fake and SQL is exactly what a fake cannot prove.
 * Skipped unless {@code LOCALAUTH_TEST_JDBC_URL} (plus {@code _USER} / {@code _PASSWORD}) is set, so
 * a plain build needs no database; it lives in tenancy because that module carries the Postgres
 * driver.
 *
 * <p>Example: {@code LOCALAUTH_TEST_JDBC_URL=jdbc:postgresql://localhost:55432/verify
 * LOCALAUTH_TEST_USER=verify LOCALAUTH_TEST_PASSWORD=... mvn -pl tenancy test
 * -Dtest=JdbcLocalAccountStoreDbTest}.
 */
@EnabledIfEnvironmentVariable(named = "LOCALAUTH_TEST_JDBC_URL", matches = ".+")
class JdbcLocalAccountStoreDbTest {

    private static final Path MIGRATION =
            Path.of("..", "app", "src", "main", "resources", "db", "migration", "controlplane", "V510__local_auth.sql");

    private JdbcTemplate jdbc;
    private LocalAccountStore store;

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
        jdbc.execute(Files.readString(MIGRATION));
        store = new JdbcLocalAccountStore(jdbc);
    }

    private static LocalAccount account(String email) {
        return new LocalAccount(
                UUID.randomUUID().toString(), email, "Ada", "Lovelace", "{bcrypt}hash", false, true, false, "t_abc",
                Set.of("tenant-admin", "agent-builder"), Map.of("organization_name", List.of("Acme")),
                Instant.now().truncatedTo(ChronoUnit.MILLIS), null);
    }

    @Test
    void accountsRoundTripWithRolesAttributesAndCaseInsensitiveEmail() {
        LocalAccount a = account("Owner@Example.Test");
        store.insert(a);

        LocalAccount read = store.findByEmail("owner@EXAMPLE.test").orElseThrow();
        assertThat(read.id()).isEqualTo(a.id());
        assertThat(read.email()).isEqualTo("Owner@Example.Test");
        assertThat(read.roles()).containsExactlyInAnyOrder("tenant-admin", "agent-builder");
        assertThat(read.attributes()).containsEntry("organization_name", List.of("Acme"));
        assertThat(read.tenantId()).isEqualTo("t_abc");
        assertThat(store.findById(a.id())).isPresent();
        assertThat(store.countAccounts()).isEqualTo(1);
        assertThat(store.listByTenant("t_abc")).hasSize(1);
        assertThat(store.listByTenant("t_other")).isEmpty();
    }

    @Test
    void aDuplicateEmailIgnoringCaseIsRefused() {
        store.insert(account("a@example.test"));
        assertThatThrownBy(() -> store.insert(account("A@Example.test")))
                .isInstanceOf(LocalAccountStore.DuplicateAccountException.class);
    }

    @Test
    void updateChangesTheMutableColumnsAndTracksPasswordChanges() {
        LocalAccount a = account("a@example.test");
        store.insert(a);

        store.update(a.withPassword("{bcrypt}new", true, true).withEnabled(false).withRoles(Set.of("end-customer")).withTenant("t_two"));

        LocalAccount read = store.findById(a.id()).orElseThrow();
        assertThat(read.passwordHash()).isEqualTo("{bcrypt}new");
        assertThat(read.mustChangePassword()).isTrue();
        assertThat(read.setupPending()).isTrue();
        assertThat(read.enabled()).isFalse();
        assertThat(read.roles()).containsExactly("end-customer");
        assertThat(read.tenantId()).isEqualTo("t_two");
        assertThat(store.anySetupPending()).isTrue();

        // a null password (an invite) must also be updatable
        store.update(read.withPassword(null, false, true));
        assertThat(store.findById(a.id()).orElseThrow().passwordHash()).isNull();
    }

    @Test
    void refreshTokensAreClaimedExactlyOnceAndRevokedByFamilyOrAccount() {
        LocalAccount a = account("a@example.test");
        store.insert(a);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String family = UUID.randomUUID().toString();
        RefreshRecord r1 = refresh(a, family, "h1", now);
        RefreshRecord r2 = refresh(a, family, "h2", now);
        store.insertRefresh(r1);
        store.insertRefresh(r2);

        assertThat(store.findRefreshByHash("h1")).isPresent();
        assertThat(store.claimRefresh(r1.id(), now)).isTrue();
        assertThat(store.claimRefresh(r1.id(), now)).as("second claimant loses").isFalse();
        assertThat(store.findRefreshByHash("h1").orElseThrow().usedAt()).isNotNull();

        store.revokeFamily(family, now);
        assertThat(store.findRefreshByHash("h2").orElseThrow().revokedAt()).isNotNull();
        assertThat(store.claimRefresh(r2.id(), now)).as("revoked tokens cannot be claimed").isFalse();

        RefreshRecord r3 = refresh(a, UUID.randomUUID().toString(), "h3", now);
        store.insertRefresh(r3);
        store.revokeAllForAccount(a.id(), now);
        assertThat(store.findRefreshByHash("h3").orElseThrow().revokedAt()).isNotNull();

        assertThat(store.purgeRefreshExpiredBefore(now.plus(Duration.ofDays(400)))).isEqualTo(3);
    }

    @Test
    void setupTokensAreSingleUseExpiringAndReplaceEachOther() {
        LocalAccount a = account("a@example.test");
        store.insert(a);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        store.insertSetupToken(a.id(), "s1", now, now.plusSeconds(60));
        assertThat(store.findSetupAccount("s1", now)).contains(a.id());
        store.insertSetupToken(a.id(), "s2", now, now.plusSeconds(60));
        assertThat(store.consumeSetupToken("s1", now)).as("replaced").isEmpty();

        assertThat(store.consumeSetupToken("s2", now)).contains(a.id());
        assertThat(store.consumeSetupToken("s2", now)).as("single use").isEmpty();

        store.insertSetupToken(a.id(), "s3", now, now.plusSeconds(1));
        assertThat(store.consumeSetupToken("s3", now.plusSeconds(5))).as("expired").isEmpty();
    }

    @Test
    void deletingAnAccountCascadesToItsTokens() {
        LocalAccount a = account("a@example.test");
        store.insert(a);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.insertRefresh(refresh(a, UUID.randomUUID().toString(), "h1", now));
        store.insertSetupToken(a.id(), "s1", now, now.plusSeconds(60));

        store.delete(a.id());

        assertThat(store.findById(a.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM control_plane.local_refresh_token", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM control_plane.local_setup_token", Long.class)).isZero();
    }

    private static RefreshRecord refresh(LocalAccount a, String family, String hash, Instant now) {
        return new RefreshRecord(
                UUID.randomUUID().toString(), a.id(), family, hash, "pwd", now, now, now.plus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(90)), null, null);
    }
}
