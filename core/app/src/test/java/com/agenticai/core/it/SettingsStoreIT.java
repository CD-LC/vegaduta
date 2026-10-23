package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.JdbcSettingsStore;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingHistoryEntry;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingSource;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingsAgentAccess;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.SettingsService;
import com.agenticai.core.settings.Strictness;
import com.agenticai.core.settings.SettingRisk;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The settings catalog against a real Postgres: V668 creates the tables with their invariants, the history cannot be
 * changed or deleted, the JDBC store, service and resolver work together through the real SQL, and a second tenant
 * schema never sees the first tenant's rows.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettingsStoreIT {

    private static final String SCHEMA = "tenant_settings_it";
    private static final String OTHER_SCHEMA = "tenant_settings_it_two";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static DataSource base;
    private static SingleConnectionDataSource tenantA;
    private static JdbcTemplate jdbc;

    private static Flyway tenantFlyway(String schema) {
        return Flyway.configure()
            .dataSource(base)
            .locations(PlatformMigrations.TENANT)
            .schemas(schema)
            .createSchemas(true)
            .outOfOrder(true)
            .baselineOnMigrate(false)
            .load();
    }

    @BeforeAll
    static void migrate() throws Exception {
        base = IntegrationTestContainers.freshDatabase("settings_it");
        PlatformMigrations.bootFlyway(base).migrate();
        tenantFlyway(SCHEMA).migrate();
        tenantA = new SingleConnectionDataSource(base.getConnection(), true);
        jdbc = new JdbcTemplate(tenantA);
        jdbc.execute("SET search_path TO " + SCHEMA);
    }

    @AfterAll
    static void close() {
        TenantContext.clear();
        tenantA.destroy();
    }

    private static SettingsRegistry registry() {
        return new SettingsRegistry(List.of(() -> List.of(
            SettingDefinition.builder("chat.finish.maxContinuations", "chat", SettingType.INT)
                .range(0, 10).scopes(SettingScope.TENANT, SettingScope.AGENT).platform(() -> 2).build(),
            SettingDefinition.builder("guardrails.failMode", "guardrails", SettingType.ENUM)
                .enumValues("open", "closed").risk(SettingRisk.SECURITY).strictness(Strictness.ENUM_ORDER)
                .scopes(SettingScope.TENANT, SettingScope.AGENT).platform(() -> "open").build())));
    }

    private static SettingsService service(JdbcSettingsStore store, DefaultSettingsResolver resolver, SettingsProperties props, SettingsRegistry registry) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SettingsAgentAccess> access = mock(ObjectProvider.class);
        when(access.getIfAvailable()).thenReturn(new SettingsAgentAccess() {
            @Override public void requireViewer(UUID id) {}
            @Override public void requireEditor(UUID id) {}
            @Override public void requireEditable(UUID id) {}
        });
        return new SettingsService(registry, store, List.of(), resolver, mock(AuditLogService.class), access, props);
    }

    @Test
    @Order(1)
    void v668CreatesTheTablesAndTheirInvariants() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM setting_override", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO setting_override (scope, scope_id, setting_key, value, version) VALUES ('agent', '', 'a.b', '1', 1)"))
            .hasMessageContaining("setting_override_scope_id_chk");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO setting_override (scope, scope_id, setting_key, value, version) VALUES ('tenant', 'x', 'a.b', '1', 1)"))
            .hasMessageContaining("setting_override_scope_id_chk");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO setting_override (scope, scope_id, setting_key, value, version) VALUES ('platform', '', 'a.b', '1', 1)"))
            .hasMessageContaining("setting_override_scope_chk");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO setting_override (scope, scope_id, setting_key, value, version) VALUES ('tenant', '', 'a.b', '1', 0)"))
            .hasMessageContaining("setting_override_version_chk");
    }

    @Test
    @Order(2)
    void aWrittenVersionChainSurvivesInTheRealTablesAndAResetKeepsTheHistory() {
        TenantContext.set(TenantContext.of("tenant-a", "owner@a", Set.of("ROLE_tenant-admin")));
        JdbcSettingsStore store = new JdbcSettingsStore(jdbc);
        SettingsProperties props = new SettingsProperties();
        SettingsRegistry registry = registry();
        DefaultSettingsResolver resolver = new DefaultSettingsResolver(registry, store, List.of(), props, null);
        SettingsService service = service(store, resolver, props, registry);

        service.set(SettingScope.TENANT, null, "chat.finish.maxContinuations", 4, "long answers");
        service.set(SettingScope.AGENT, AGENT, "chat.finish.maxContinuations", 6, "this agent writes essays");
        service.set(SettingScope.TENANT, null, "guardrails.failMode", "closed", "regulated data");

        assertThat(resolver.get("chat.finish.maxContinuations", SettingContext.tenant()).value()).isEqualTo(4L);
        var agent = resolver.get("chat.finish.maxContinuations", SettingContext.forAgent(AGENT));
        assertThat(agent.value()).isEqualTo(6L);
        assertThat(agent.source()).isEqualTo(SettingSource.AGENT);
        assertThat(agent.inherited().value()).isEqualTo(4L);
        assertThat(resolver.get("guardrails.failMode", SettingContext.tenant()).value()).isEqualTo("closed");

        service.reset(SettingScope.AGENT, AGENT, "chat.finish.maxContinuations", "back");
        assertThat(store.find(SettingScope.AGENT, AGENT.toString(), "chat.finish.maxContinuations")).isEmpty();
        List<SettingHistoryEntry> history = service.history(SettingScope.AGENT, AGENT, "chat.finish.maxContinuations", null);
        assertThat(history).extracting(SettingHistoryEntry::version).containsExactly(2, 1);
        assertThat(history).extracting(SettingHistoryEntry::action).containsExactly("RESET", "SET");
        assertThat(history.get(0).previousValue()).isEqualTo(6L);
        assertThat(history.get(1).value()).isEqualTo(6L);
        assertThat(history.get(1).actor()).isEqualTo("owner@a");
        assertThat(history.get(1).reason()).isEqualTo("this agent writes essays");

        // the version counter continues after a reset: the history, not the current row, counts
        assertThat(service.set(SettingScope.AGENT, AGENT, "chat.finish.maxContinuations", 7, null).version()).isEqualTo(3);
    }

    @Test
    @Order(3)
    void theHistoryCannotBeUpdatedOrDeleted() {
        assertThatThrownBy(() -> jdbc.update("UPDATE setting_override_history SET reason = 'tampered'"))
            .hasMessageContaining("setting_override_history is append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM setting_override_history"))
            .hasMessageContaining("setting_override_history is append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM setting_override_history", Integer.class)).isGreaterThanOrEqualTo(4);
    }

    @Test
    @Order(4)
    void anotherTenantSchemaNeverSeesTheRowsOfTenantA() throws Exception {
        tenantFlyway(OTHER_SCHEMA).migrate();
        try (SingleConnectionDataSource b = new SingleConnectionDataSource(base.getConnection(), true)) {
            JdbcTemplate jdbcB = new JdbcTemplate(b);
            jdbcB.execute("SET search_path TO " + OTHER_SCHEMA);
            JdbcSettingsStore storeB = new JdbcSettingsStore(jdbcB);
            assertThat(storeB.tenantValues()).isEmpty();
            assertThat(storeB.agentValues(AGENT.toString())).isEmpty();
            assertThat(storeB.history(SettingScope.TENANT, "", "chat.finish.maxContinuations", 10)).isEmpty();
        }
        JdbcSettingsStore storeA = new JdbcSettingsStore(jdbc);
        assertThat(storeA.tenantValues()).containsKeys("chat.finish.maxContinuations", "guardrails.failMode");
    }

    @Test
    @Order(5)
    void jsonValuesRoundTripWithTheirTypes() {
        JdbcSettingsStore store = new JdbcSettingsStore(jdbc);
        store.saveCurrent(new com.agenticai.core.settings.SettingOverride(SettingScope.TENANT, "", "t.bool", true, 1, "x", java.time.Instant.now()));
        store.saveCurrent(new com.agenticai.core.settings.SettingOverride(SettingScope.TENANT, "", "t.dec", 0.25, 1, "x", java.time.Instant.now()));
        store.saveCurrent(new com.agenticai.core.settings.SettingOverride(SettingScope.TENANT, "", "t.text", "quoted \"text\"", 1, "x", java.time.Instant.now()));
        assertThat(store.tenantValues()).containsEntry("t.bool", true).containsEntry("t.dec", 0.25).containsEntry("t.text", "quoted \"text\"");
    }

    @Test
    @Order(6)
    void twoConcurrentWritersOfTheSameKeyGetConsecutiveVersionsInsteadOfAUniqueViolation() throws Exception {
        String key = "chat.finish.maxContinuations";
        String scopeId = "concurrent-agent";
        java.util.concurrent.CountDownLatch firstHoldsLock = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger[] versions = {
            new java.util.concurrent.atomic.AtomicInteger(), new java.util.concurrent.atomic.AtomicInteger()
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> first = pool.submit(() -> writeInOwnTransaction(key, scopeId, versions[0], firstHoldsLock, true));
            assertThat(firstHoldsLock.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // the second writer starts while the first still holds its transaction open
            java.util.concurrent.Future<?> second = pool.submit(() -> writeInOwnTransaction(key, scopeId, versions[1], null, false));
            first.get(60, java.util.concurrent.TimeUnit.SECONDS);
            second.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(java.util.List.of(versions[0].get(), versions[1].get())).containsExactlyInAnyOrder(1, 2);
        assertThat(jdbc.queryForList(
                "SELECT version FROM setting_override_history WHERE scope_id = ? AND setting_key = ? ORDER BY version",
                Integer.class, scopeId, key)).containsExactly(1, 2);
    }

    private static void writeInOwnTransaction(
        String key, String scopeId, java.util.concurrent.atomic.AtomicInteger out,
        java.util.concurrent.CountDownLatch signalAfterLock, boolean holdOpen) {
        try (SingleConnectionDataSource ds = new SingleConnectionDataSource(base.getConnection(), true)) {
            JdbcTemplate template = new JdbcTemplate(ds);
            template.execute("SET search_path TO " + SCHEMA);
            JdbcSettingsStore store = new JdbcSettingsStore(template);
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds))
                .executeWithoutResult(status -> {
                    int version = store.latestVersion(SettingScope.AGENT, scopeId, key) + 1;
                    if (signalAfterLock != null) {
                        signalAfterLock.countDown();
                    }
                    if (holdOpen) {
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    store.appendHistory(new SettingHistoryEntry(
                        0, SettingScope.AGENT, scopeId, key, version, SettingHistoryEntry.ACTION_SET,
                        version, null, null, "writer", java.time.Instant.now()));
                    out.set(version);
                });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @Order(7)
    void theAuditRowOfARefusedWeakeningSurvivesTheRollbackOfTheRefusedRequest() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS it_audit_probe (action text, target_id text, outcome text)");
        jdbc.execute("DELETE FROM it_audit_probe");
        TenantContext.set(TenantContext.of("tenant-a", "owner@a", Set.of("ROLE_tenant-admin")));
        JdbcSettingsStore store = new JdbcSettingsStore(jdbc);
        SettingsProperties props = new SettingsProperties();
        SettingsRegistry registry = registry();
        DefaultSettingsResolver resolver = new DefaultSettingsResolver(registry, store, List.of(), props, null);
        AuditLogService audit = mock(AuditLogService.class);
        when(audit.record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> {
                jdbc.update("INSERT INTO it_audit_probe VALUES (?, ?, ?)", inv.getArgument(1), inv.getArgument(3), String.valueOf((Object) inv.getArgument(4)));
                return null;
            });
        @SuppressWarnings("unchecked")
        ObjectProvider<SettingsAgentAccess> access = mock(ObjectProvider.class);
        when(access.getIfAvailable()).thenReturn(new SettingsAgentAccess() {
            @Override public void requireViewer(UUID id) {}
            @Override public void requireEditor(UUID id) {}
            @Override public void requireEditable(UUID id) {}
        });
        SettingsService target = new SettingsService(registry, store, List.of(), resolver, audit, access, props);
        org.springframework.aop.framework.ProxyFactory factory = new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(tenantA),
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        SettingsService transactional = (SettingsService) factory.getProxy();

        String agentKey = "guardrails.failMode";
        UUID agent = UUID.fromString("00000000-0000-0000-0000-0000000000b7");
        transactional.set(SettingScope.TENANT, null, agentKey, "closed", "regulated data");
        jdbc.execute("DELETE FROM it_audit_probe");
        assertThatThrownBy(() -> transactional.set(SettingScope.AGENT, agent, agentKey, "open", "weaken it"))
            .isInstanceOf(com.agenticai.core.settings.SettingInvalidException.class)
            .hasMessageContaining("only be made stricter");

        assertThat(jdbc.queryForList("SELECT action FROM it_audit_probe WHERE target_id = ?", String.class, agentKey))
            .containsExactly(SettingsService.ACTION_REJECTED);
        // and the refused write stored nothing
        assertThat(store.find(SettingScope.AGENT, agent.toString(), agentKey)).isEmpty();
    }

    @Test
    @Order(8)
    void deletingAnAgentRemovesItsOverridesAndLeavesAnAuditedResetInTheHistory() {
        TenantContext.set(TenantContext.of("tenant-a", "owner@a", Set.of("ROLE_tenant-admin")));
        UUID gone = UUID.randomUUID();
        UUID stays = UUID.randomUUID();
        for (UUID id : new UUID[] {gone, stays}) {
            jdbc.update("INSERT INTO agent (id, name, system_prompt, provider_name, model, max_tokens, temperature,"
                + " knowledge_base_enabled, created_at, updated_at) VALUES (?, 'it', 'x', 'OPENAI', 'gpt-4o-mini', 256, 0.2, false, now(), now())", id);
        }
        JdbcSettingsStore store = new JdbcSettingsStore(jdbc);
        SettingsProperties props = new SettingsProperties();
        SettingsRegistry registry = registry();
        DefaultSettingsResolver resolver = new DefaultSettingsResolver(registry, store, List.of(), props, null);
        SettingsService service = service(store, resolver, props, registry);
        service.set(SettingScope.AGENT, gone, "chat.finish.maxContinuations", 5, null);
        service.set(SettingScope.AGENT, gone, "chat.finish.maxContinuations", 6, null);
        service.set(SettingScope.AGENT, gone, "guardrails.failMode", "closed", "regulated data");
        service.set(SettingScope.AGENT, stays, "chat.finish.maxContinuations", 3, null);
        int tenantRowsBefore = store.tenantValues().size();

        jdbc.update("DELETE FROM agent WHERE id = ?", gone);

        assertThat(store.agentValues(gone.toString())).isEmpty();
        assertThat(store.find(SettingScope.AGENT, gone.toString(), "guardrails.failMode")).isEmpty();
        assertThat(store.agentValues(stays.toString())).containsEntry("chat.finish.maxContinuations", 3L);
        assertThat(store.tenantValues()).hasSize(tenantRowsBefore);

        // the history stays, and says what was removed, when and why
        List<SettingHistoryEntry> history = store.history(SettingScope.AGENT, gone.toString(), "chat.finish.maxContinuations", 10);
        assertThat(history).extracting(SettingHistoryEntry::version).containsExactly(3, 2, 1);
        SettingHistoryEntry removal = history.get(0);
        assertThat(removal.action()).isEqualTo(SettingHistoryEntry.ACTION_RESET);
        assertThat(removal.value()).isNull();
        assertThat(removal.previousValue()).isEqualTo(6L);
        assertThat(removal.reason()).isEqualTo("agent deleted");
        assertThat(removal.actor()).isEqualTo("system:agent-deleted");
        assertThat(store.history(SettingScope.AGENT, gone.toString(), "guardrails.failMode", 10)).hasSize(2);
        assertThat(store.history(SettingScope.AGENT, stays.toString(), "chat.finish.maxContinuations", 10)).hasSize(1);

        // an agent with no overrides deletes without a trace, and the bulk history sees the removal rows
        UUID plain = UUID.randomUUID();
        jdbc.update("INSERT INTO agent (id, name, system_prompt, provider_name, model, max_tokens, temperature,"
            + " knowledge_base_enabled, created_at, updated_at) VALUES (?, 'it', 'x', 'OPENAI', 'gpt-4o-mini', 256, 0.2, false, now(), now())", plain);
        jdbc.update("DELETE FROM agent WHERE id = ?", plain);
        assertThat(store.history(SettingScope.AGENT, plain.toString(), "chat.finish.maxContinuations", 10)).isEmpty();
        assertThat(store.historyPage(SettingScope.AGENT, gone.toString(), null, 50, 0)).hasSize(5);
        assertThat(store.historyCount(SettingScope.AGENT, gone.toString(), null)).isEqualTo(5);
    }

    @Test
    @Order(9)
    void theBulkHistoryQueriesFilterAndPageInTheRealTable() {
        JdbcSettingsStore store = new JdbcSettingsStore(jdbc);
        long all = store.historyCount(null, null, null);
        long tenant = store.historyCount(SettingScope.TENANT, "", null);
        long agents = store.historyCount(SettingScope.AGENT, null, null);
        assertThat(all).isEqualTo(tenant + agents).isGreaterThan(5);
        List<SettingHistoryEntry> firstPage = store.historyPage(null, null, null, 3, 0);
        List<SettingHistoryEntry> secondPage = store.historyPage(null, null, null, 3, 3);
        assertThat(firstPage).hasSize(3);
        assertThat(secondPage).hasSize(3);
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
        assertThat(firstPage).extracting(SettingHistoryEntry::at).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(store.historyPage(null, null, java.time.Instant.now().plusSeconds(3600), 10, 0)).isEmpty();
        assertThat(store.historyCount(null, null, java.time.Instant.now().plusSeconds(3600))).isZero();
        assertThat(store.historyPage(SettingScope.TENANT, "", null, 500, 0)).allMatch(e -> e.scope() == SettingScope.TENANT);
        assertThat(store.historyPage(null, null, null, 10, 1_000_000)).isEmpty();
    }
}
