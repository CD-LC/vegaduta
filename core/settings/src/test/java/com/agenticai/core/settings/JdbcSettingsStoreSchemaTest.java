package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantSchemaSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Defect D1 (scorecard Addendum E): the store gets its connection from the plain pool, whose search_path is {@code public}
 * outside a transaction, so every statement must name the tenant's schema. Pinned on the SQL text, for every statement.
 */
class JdbcSettingsStoreSchemaTest {

    /** Records the SQL of every statement instead of running it. */
    private static final class Recording extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql.add(sql);
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            this.sql.add(sql);
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            this.sql.add(sql);
            return null;
        }
    }

    private final Recording jdbc = new Recording();

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TenantSchemaSource> provider(TenantSchemaSource source) {
        ObjectProvider<TenantSchemaSource> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(source);
        return p;
    }

    @Test
    void readsOfTheTenantAndAgentLayersAreQualifiedWithTheTenantSchema() {
        JdbcSettingsStore store = new JdbcSettingsStore(jdbc, provider(() -> "t_acme"));
        store.tenantValues();
        store.agentValues("agent-1");
        assertThat(jdbc.sql).hasSize(2).allSatisfy(s -> assertThat(s)
                .contains(" FROM \"t_acme\".setting_override WHERE")
                .doesNotContain(" FROM setting_override"));
    }

    @Test
    void findIsQualified() {
        new JdbcSettingsStore(jdbc, provider(() -> "t_acme")).find(SettingScope.TENANT, "", "memory.recall.enabled");
        assertThat(jdbc.sql).singleElement().asString().contains("\"t_acme\".setting_override WHERE");
    }

    @Test
    void writesHistoryAndTheVersionCounterAreQualified() {
        JdbcSettingsStore store = new JdbcSettingsStore(jdbc, provider(() -> "t_acme"));
        store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", "a.b", true, 1, "u", Instant.now()));
        store.deleteCurrent(SettingScope.TENANT, "", "a.b");
        store.appendHistory(new SettingHistoryEntry(0, SettingScope.TENANT, "", "a.b", 1, "SET", true, null, null, "u", Instant.now()));
        store.latestVersion(SettingScope.TENANT, "", "a.b");
        assertThat(jdbc.sql).hasSize(5);
        assertThat(jdbc.sql.get(0)).startsWith("INSERT INTO \"t_acme\".setting_override (");
        assertThat(jdbc.sql.get(1)).startsWith("DELETE FROM \"t_acme\".setting_override WHERE");
        assertThat(jdbc.sql.get(2)).startsWith("INSERT INTO \"t_acme\".setting_override_history (");
        // [3] is the advisory lock (names no table); [4] the counter, which reads the tenant's history
        assertThat(jdbc.sql.get(4)).contains("FROM \"t_acme\".setting_override_history WHERE");
    }

    @Test
    void historyReadsAreQualified() {
        JdbcSettingsStore store = new JdbcSettingsStore(jdbc, provider(() -> "t_acme"));
        store.history(SettingScope.TENANT, "", "a.b", 5);
        store.historyPage(null, null, null, 10, 0);
        store.historyCount(null, null, null);
        assertThat(jdbc.sql).hasSize(3).allSatisfy(s -> assertThat(s)
                .contains("FROM \"t_acme\".setting_override_history")
                .doesNotContain("FROM setting_override_history"));
    }

    @Test
    void theSchemaIsReadPerCallSoTheCallingThreadsTenantAlwaysWins() {
        String[] current = {"t_one"};
        JdbcSettingsStore store = new JdbcSettingsStore(jdbc, provider(() -> current[0]));
        assertThat(store.table("setting_override")).isEqualTo("\"t_one\".setting_override");
        current[0] = "t_two";
        assertThat(store.table("setting_override")).isEqualTo("\"t_two\".setting_override");
    }

    @Test
    void aQuoteInTheSchemaNameCannotBreakOutOfTheIdentifier() {
        JdbcSettingsStore store = new JdbcSettingsStore(jdbc, provider(() -> "x\"; DROP TABLE y; --"));
        assertThat(store.table("setting_override")).isEqualTo("\"x\"\"; DROP TABLE y; --\".setting_override");
    }

    @Test
    void withoutASchemaSourceTheNamesStayBareForAConnectionThatAlreadyPointsAtTheTenant() {
        assertThat(new JdbcSettingsStore(jdbc).table("setting_override")).isEqualTo("setting_override");
        assertThat(new JdbcSettingsStore(jdbc, provider(null)).table("setting_override_history"))
                .isEqualTo("setting_override_history");
        new JdbcSettingsStore(jdbc).tenantValues();
        assertThat(jdbc.sql).singleElement().asString().contains(" FROM setting_override WHERE");
    }
}
