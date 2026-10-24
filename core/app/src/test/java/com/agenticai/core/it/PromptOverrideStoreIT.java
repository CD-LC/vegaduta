package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.JdbcPromptOverrideStore;
import com.agenticai.core.prompts.PromptAgentDirectory;
import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptHistoryEntry;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptOverrideService;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.prompts.PromptSource;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The prompt registry against a real Postgres: the V664 data migration copies a tenant's directive
 * settings (V654) byte for byte, the tables enforce their invariants, the history cannot be changed
 * or deleted, the PLATFORM scope lives in the control plane, and the JDBC store, service and resolver
 * work together through the real SQL.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PromptOverrideStoreIT {

    private static final String SCHEMA = "tenant_prompt_it";
    private static final String OTHER_SCHEMA = "tenant_prompt_it_two";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static DataSource base;
    private static JdbcTemplate admin;
    private static SingleConnectionDataSource tenantA;
    private static JdbcTemplate jdbc;

    private static Flyway tenantFlyway(String schema, String target) {
        return Flyway.configure()
            .dataSource(base)
            .locations(PlatformMigrations.TENANT)
            .schemas(schema)
            .createSchemas(true)
            .outOfOrder(true)
            .baselineOnMigrate(false)
            .target(target)
            .load();
    }

    @BeforeAll
    static void migrate() throws Exception {
        base = IntegrationTestContainers.freshDatabase("prompt_override_it");
        admin = new JdbcTemplate(base);
        // The control plane first (platform_prompt_override lives there), then a tenant schema up to the
        // version BEFORE the prompt registry, with directive settings the way a live tenant has them.
        PlatformMigrations.bootFlyway(base).migrate();
        tenantFlyway(SCHEMA, "662").migrate();
        tenantA = new SingleConnectionDataSource(base.getConnection(), true);
        new JdbcTemplate(tenantA).execute("SET search_path TO " + SCHEMA);
        jdbc = new JdbcTemplate(tenantA);
        jdbc.update("INSERT INTO prompt_directive_setting (directive_key, enabled, text_override, updated_by) VALUES"
            + " ('tool_trust', TRUE, 'House rules.', 'owner@a'),"
            + " ('context_compaction', TRUE, 'Compaction rules.', 'owner@a'),"
            + " ('memory_usage', FALSE, NULL, 'owner@a'),"
            + " ('custom_instructions', TRUE, 'Answer in French.', 'owner@a'),"
            + " ('media_honesty', FALSE, NULL, 'owner@a'),"
            + " ('tool_search', TRUE, NULL, 'owner@a')");
    }

    @AfterAll
    static void close() {
        TenantContext.clear();
        tenantA.destroy();
    }

    @Test
    @Order(1)
    void beforeV664TheRegistryTablesDoNotExistAndTheOldSettingsAreThere() {
        assertThat(count("prompt_override")).isNull();
        assertThat(count("prompt_directive_setting")).isEqualTo(6);
    }

    private static Integer count(String table) {
        try {
            return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Test
    @Order(2)
    void v664CopiesEveryDirectiveSettingToTheTenantScope() {
        tenantFlyway(SCHEMA, "latest").migrate();
        assertThat(tenantFlyway(SCHEMA, "latest").info().pending()).isEmpty();

        Map<String, PromptOverride> rows = new JdbcPromptOverrideStore(jdbc).list(PromptScope.TENANT, "").stream()
            .collect(Collectors.toMap(PromptOverride::key, r -> r));
        assertThat(rows.keySet()).containsExactlyInAnyOrder(
            "directive.tool_trust", "directive.tool_trust.compact",
            "directive.context_compaction", "directive.context_compaction.no_restore",
            "directive.memory_usage", "directive.custom_instructions", "directive.media_honesty");
        assertThat(rows.get("directive.tool_trust").text()).isEqualTo("House rules.");
        assertThat(rows.get("directive.tool_trust.compact").text()).isEqualTo("House rules.");
        assertThat(rows.get("directive.context_compaction.no_restore").text()).isEqualTo("Compaction rules.");
        assertThat(rows.get("directive.memory_usage").enabled()).isFalse();
        assertThat(rows.get("directive.memory_usage").text()).isNull();
        assertThat(rows.get("directive.custom_instructions").text()).isEqualTo("Answer in French.");
        assertThat(rows.get("directive.media_honesty").enabled()).isFalse();
        assertThat(rows.values()).allSatisfy(r -> {
            assertThat(r.scopeId()).isEmpty();
            assertThat(r.locale()).isEqualTo("en");
            assertThat(r.version()).isEqualTo(1);
            assertThat(r.createdBy()).isEqualTo("owner@a");
        });
        // An enabled row with the built-in wording ("no override") is not copied.
        assertThat(rows).doesNotContainKey("directive.tool_search");

        List<PromptHistoryEntry> history =
            new JdbcPromptOverrideStore(jdbc).history(PromptScope.TENANT, "", null, null, 100);
        assertThat(history).hasSize(7).allSatisfy(h -> {
            assertThat(h.action()).isEqualTo("MIGRATE");
            assertThat(h.version()).isEqualTo(1);
            assertThat(h.reason()).contains("prompt_directive_setting");
        });
        // The old table is left in place, unread.
        assertThat(count("prompt_directive_setting")).isEqualTo(6);
    }

    @Test
    @Order(3)
    void theResolverServesTheMigratedWordingThroughTheRealStore() {
        TenantContext.set(TenantContext.of("tenant-a", "owner@a", Set.of("ROLE_tenant-admin")));
        DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), new JdbcPromptOverrideStore(jdbc), new PromptProperties());

        assertThat(resolver.text("directive.tool_trust")).isEqualTo("House rules.");
        assertThat(resolver.resolve("directive.tool_trust").source()).isEqualTo(PromptSource.TENANT);
        assertThat(resolver.resolve("directive.memory_usage").active()).isFalse();
        assertThat(resolver.text("directive.custom_instructions")).isEqualTo("Answer in French.");
        assertThat(resolver.resolve("directive.tool_search").source()).isEqualTo(PromptSource.DEFAULT);
    }

    @Test
    @Order(4)
    void theTablesEnforceTheirInvariants() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO prompt_override (scope, scope_id, prompt_key, version, prompt_text) VALUES ('agent', '', 'a.b', 1, 'x')"))
            .hasMessageContaining("prompt_override_scope_id_chk");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO prompt_override (scope, scope_id, prompt_key, version, prompt_text) VALUES ('tenant', 'x', 'a.b', 1, 'x')"))
            .hasMessageContaining("prompt_override_scope_id_chk");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO prompt_override (scope, scope_id, prompt_key, version, enabled) VALUES ('tenant', '', 'a.b', 1, TRUE)"))
            .hasMessageContaining("prompt_override_text_chk");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO prompt_override (scope, scope_id, prompt_key, version, prompt_text) VALUES ('platform', '', 'a.b', 1, 'x')"))
            .hasMessageContaining("prompt_override_scope_chk");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO prompt_override (scope, scope_id, prompt_key, version, prompt_text) VALUES ('tenant', '', 'a.b', 0, 'x')"))
            .hasMessageContaining("prompt_override_version_chk");
    }

    @Test
    @Order(5)
    void theHistoryCannotBeUpdatedOrDeleted() {
        assertThatThrownBy(() -> jdbc.update("UPDATE prompt_override_history SET prompt_text = 'tampered'"))
            .hasMessageContaining("prompt_override_history is append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM prompt_override_history"))
            .hasMessageContaining("prompt_override_history is append-only");
        assertThat(count("prompt_override_history")).isEqualTo(7);
    }

    @Test
    @Order(6)
    void aWrittenVersionChainSurvivesInTheRealTablesAndRevertKeepsTheHistory() {
        TenantContext.set(TenantContext.of("tenant-a", "owner@a", Set.of("ROLE_tenant-admin")));
        JdbcPromptOverrideStore store = new JdbcPromptOverrideStore(jdbc);
        DefaultPromptResolver resolver = new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
        @SuppressWarnings("unchecked")
        ObjectProvider<PromptAgentDirectory> directory = mock(ObjectProvider.class);
        PromptAgentDirectory agents = id -> id.equals(AGENT);
        org.mockito.Mockito.when(directory.getIfAvailable()).thenReturn(agents);
        PromptOverrideService service =
            new PromptOverrideService(store, resolver, resolver, mock(AuditLogService.class), directory, new PromptProperties());

        service.put(new PromptOverrideService.Write(PromptScope.AGENT, AGENT, "directive.tool_search", null, "Agent search rule.", true, false, "first", null));
        service.put(new PromptOverrideService.Write(PromptScope.AGENT, AGENT, "directive.tool_search", null, "Agent search rule v2.", true, false, "second", 1));
        assertThat(resolver.resolve("directive.tool_search", PromptContext.forAgent(AGENT)).version()).isEqualTo(2);
        assertThat(resolver.text("directive.tool_search")).as("another agent / no agent").isNotEqualTo("Agent search rule v2.");

        service.revert(PromptScope.AGENT, AGENT, "directive.tool_search", null, "back", 2);
        assertThat(store.find(PromptScope.AGENT, AGENT.toString(), "directive.tool_search", "en")).isEmpty();
        List<PromptHistoryEntry> history = service.history(PromptScope.AGENT, AGENT, "directive.tool_search", null, null);
        assertThat(history).extracting(PromptHistoryEntry::version).containsExactly(3, 2, 1);
        assertThat(history).extracting(PromptHistoryEntry::action).containsExactly("REVERT", "UPDATE", "CREATE");
        assertThat(history.get(1).previousText()).isEqualTo("Agent search rule.");
        assertThat(history.get(0).createdBy()).isEqualTo("owner@a");

        // The version counter continues after a revert: the history, not the current row, counts.
        PromptOverrideService.Applied again = service.put(new PromptOverrideService.Write(PromptScope.AGENT, AGENT, "directive.tool_search", null, "Again.", true, false, null, null));
        assertThat(again.override().version()).isEqualTo(4);
    }

    @Test
    @Order(7)
    void thePlatformScopeLivesInTheControlPlaneAndAnotherTenantNeverSeesATenantRow() throws Exception {
        TenantContext.set(TenantContext.of("tenant-a", "root", Set.of("ROLE_platform-admin")));
        JdbcPromptOverrideStore store = new JdbcPromptOverrideStore(jdbc);
        DefaultPromptResolver resolver = new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
        @SuppressWarnings("unchecked")
        ObjectProvider<PromptAgentDirectory> none = mock(ObjectProvider.class);
        PromptOverrideService service =
            new PromptOverrideService(store, resolver, resolver, mock(AuditLogService.class), none, new PromptProperties());
        service.put(new PromptOverrideService.Write(PromptScope.PLATFORM, null, "directive.browser_v2", null, "Platform browser rule.", true, false, null, null));

        assertThat(admin.queryForObject("SELECT count(*) FROM control_plane.platform_prompt_override", Integer.class)).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM control_plane.platform_prompt_override_history", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> admin.update("UPDATE control_plane.platform_prompt_override_history SET prompt_text = 'tampered'"))
            .hasMessageContaining("platform_prompt_override_history is append-only");
        assertThatThrownBy(() -> admin.update("DELETE FROM control_plane.platform_prompt_override_history"))
            .hasMessageContaining("platform_prompt_override_history is append-only");

        // A second tenant schema: it sees the platform text, and none of tenant A's rows.
        tenantFlyway(OTHER_SCHEMA, "latest").migrate();
        try (SingleConnectionDataSource b = new SingleConnectionDataSource(base.getConnection(), true)) {
            JdbcTemplate jdbcB = new JdbcTemplate(b);
            jdbcB.execute("SET search_path TO " + OTHER_SCHEMA);
            JdbcPromptOverrideStore storeB = new JdbcPromptOverrideStore(jdbcB);
            assertThat(storeB.loadCurrentTenant()).isEmpty();
            assertThat(storeB.loadPlatform()).extracting(PromptOverride::key).containsExactly("directive.browser_v2");
            assertThat(storeB.history(PromptScope.TENANT, "", null, null, 10)).isEmpty();
        }
        assertThat(store.loadCurrentTenant()).isNotEmpty();
    }

    @Test
    @Order(8)
    void tenantsCreatedAfterV664GetEmptyTables() {
        Integer rows = admin.queryForObject(
            "SELECT count(*) FROM " + OTHER_SCHEMA + ".prompt_override", Integer.class);
        assertThat(rows).isZero();
        assertThat(Instant.now()).isNotNull();
    }
}
