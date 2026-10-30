package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link InMemorySettingsStore} keeps the same two contracts the JDBC store does: rows are
 * partitioned by the calling thread's tenant (one tenant never sees another's, and a thread with no
 * context gets its own {@code "<none>"} partition), and history is append-only with a real unique
 * constraint on {@code (scope, scope_id, setting_key, version)}.
 */
class InMemorySettingsStoreTest {

    private InMemorySettingsStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySettingsStore();
        as("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static void as(String tenantId) {
        TenantContext.set(TenantContext.of(tenantId, "alice", Set.of()));
    }

    private static SettingOverride override(
            SettingScope scope, String scopeId, String key, Object value) {
        return new SettingOverride(scope, scopeId, key, value, 1, "alice", Instant.now());
    }

    private static SettingHistoryEntry entry(
            SettingScope scope, String scopeId, String key, int version, Instant at) {
        return new SettingHistoryEntry(
                0L, scope, scopeId, key, version, "SET", "v" + version, null, null, "alice", at);
    }

    @Test
    void currentValuesRoundTripPerScope() {
        store.saveCurrent(override(SettingScope.TENANT, "", "k.one", 7));
        store.saveCurrent(override(SettingScope.AGENT, "agent-1", "k.two", "x"));

        assertThat(store.tenantValues()).containsEntry("k.one", 7);
        assertThat(store.agentValues("agent-1")).containsEntry("k.two", "x");
        assertThat(store.agentValues("agent-2")).isEmpty();
        assertThat(store.find(SettingScope.TENANT, "", "k.one")).isPresent();
        assertThat(store.find(SettingScope.TENANT, "", "k.missing")).isEmpty();
    }

    @Test
    void deleteCurrentRemovesOnlyTheNamedKey() {
        store.saveCurrent(override(SettingScope.TENANT, "", "k.one", 1));
        store.saveCurrent(override(SettingScope.TENANT, "", "k.two", 2));

        store.deleteCurrent(SettingScope.TENANT, "", "k.one");

        assertThat(store.find(SettingScope.TENANT, "", "k.one")).isEmpty();
        assertThat(store.tenantValues()).hasSize(1).containsEntry("k.two", 2);
    }

    @Test
    void oneTenantNeverSeesAnothersOverridesOrHistory() {
        store.saveCurrent(override(SettingScope.TENANT, "", "k.one", 1));
        store.appendHistory(entry(SettingScope.TENANT, "", "k.one", 1, Instant.now()));

        as("tenant-b");
        assertThat(store.tenantValues()).isEmpty();
        assertThat(store.history(SettingScope.TENANT, "", "k.one", 10)).isEmpty();
        assertThat(store.latestVersion(SettingScope.TENANT, "", "k.one")).isZero();
    }

    @Test
    void noTenantContextGetsItsOwnPartition() {
        store.saveCurrent(override(SettingScope.TENANT, "", "k.one", 1));

        TenantContext.clear();
        assertThat(store.tenantValues()).isEmpty();
    }

    @Test
    void appendHistoryRejectsADuplicateVersionLikeTheRealUniqueIndex() {
        Instant at = Instant.now();
        store.appendHistory(entry(SettingScope.TENANT, "", "k.one", 1, at));

        assertThatThrownBy(
                        () -> store.appendHistory(entry(SettingScope.TENANT, "", "k.one", 1, at)))
                .isInstanceOf(DuplicateKeyException.class);
        // A different version of the same key is fine.
        store.appendHistory(entry(SettingScope.TENANT, "", "k.one", 2, at.plusSeconds(1)));
        assertThat(store.latestVersion(SettingScope.TENANT, "", "k.one")).isEqualTo(2);
    }

    @Test
    void historyReturnsNewestVersionFirstAndHonoursTheLimit() {
        Instant base = Instant.now();
        store.appendHistory(entry(SettingScope.TENANT, "", "k.one", 1, base));
        store.appendHistory(entry(SettingScope.TENANT, "", "k.one", 2, base.plusSeconds(1)));
        store.appendHistory(entry(SettingScope.TENANT, "", "k.other", 1, base.plusSeconds(2)));

        assertThat(store.history(SettingScope.TENANT, "", "k.one", 10))
                .extracting(SettingHistoryEntry::version)
                .containsExactly(2, 1);
        assertThat(store.history(SettingScope.TENANT, "", "k.one", 1)).hasSize(1);
    }

    @Test
    void historyPagePaginatesNewestFirstAndSinceFilters() {
        Instant base = Instant.now();
        store.appendHistory(entry(SettingScope.TENANT, "", "a", 1, base));
        store.appendHistory(entry(SettingScope.AGENT, "g", "b", 1, base.plusSeconds(1)));
        store.appendHistory(entry(SettingScope.TENANT, "", "c", 1, base.plusSeconds(2)));

        // All scopes, newest first.
        assertThat(store.historyPage(null, null, null, 10, 0))
                .extracting(SettingHistoryEntry::key)
                .containsExactly("c", "b", "a");
        // Offset/limit page into the middle.
        assertThat(store.historyPage(null, null, null, 1, 1))
                .extracting(SettingHistoryEntry::key)
                .containsExactly("b");
        // The since filter drops the oldest row.
        assertThat(store.historyCount(null, null, base.plusSeconds(1))).isEqualTo(2);
        assertThat(store.historyCount(null, null, base.plusSeconds(100))).isZero();
        // Scope filter.
        assertThat(store.historyPage(SettingScope.TENANT, "", null, 10, 0)).hasSize(2);
        assertThat(store.historyPage(SettingScope.AGENT, "g", null, 10, 0)).hasSize(1);
    }

    @Test
    void latestVersionIsPerScopeAndKey() {
        Instant at = Instant.now();
        store.appendHistory(entry(SettingScope.AGENT, "g1", "k", 3, at));
        store.appendHistory(entry(SettingScope.TENANT, "", "k", 1, at));

        assertThat(store.latestVersion(SettingScope.AGENT, "g1", "k")).isEqualTo(3);
        assertThat(store.latestVersion(SettingScope.TENANT, "", "k")).isEqualTo(1);
        assertThat(store.latestVersion(SettingScope.AGENT, "g2", "k")).isZero();
    }
}
