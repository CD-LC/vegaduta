package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tunables.BehaviourSettings;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.settings.SettingsService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Defect D1 (scorecard Addendum E): a tenant or agent behaviour override written through the real {@link SettingsService}
 * must be seen by the reads a chat turn makes, which run on a pre-turn thread with the tenant bound and WITHOUT a
 * transaction ({@code ChatPromptAssembler.supplyPreTurn} -> {@code ChatMemoryRecall} -> {@code BehaviourSettings} ->
 * {@code SettingsResolver} -> {@code JdbcSettingsStore} through a plain pooled {@code JdbcTemplate}: the connection's
 * search_path is {@code public}, nothing selects the tenant schema). The whole {@code CoreApplication} is booted on the
 * production {@code DataSource} (no statement-counting wrapper), and every read is made twice: outside a transaction (the
 * hot path) and inside one (where Hibernate-style tenant connections carry the tenant search_path).
 *
 * <p>Needs {@code -Dvegaduta.it.pg.url=...} (see {@link ExternalPostgres}) and Redis; skipped otherwise.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SettingsTenantReadIT extends UnifiedMemoryBootBase {

    private static final String RECALL = "memory.recall.enabled";
    private static final String WRITE = "memory.write.enabled";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, "settings_tenant_read");
    }

    @BeforeAll
    static void needsPostgres() {
        requirePostgres();
    }

    @Autowired SettingsService settings;
    @Autowired SettingsResolver resolver;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static void asTenant(String schema) {
        TenantContext.set(TenantContext.of(schema, "settings-it", Set.of("ROLE_tenant-admin", "PERM_MANAGE_AGENTS")));
    }

    @Test
    void tenantAndAgentOverridesAreSeenOutsideAndInsideATransaction() {
        String schema = "t_setread_one";
        tenantSchema(schema);
        asTenant(schema);
        UUID agent = newAgent("settings-read");
        UUID untouchedAgent = newAgent("settings-read-untouched");

        // the platform values: both switches are on
        resolver.evictCurrentTenant();
        assertThat(resolver.getBool(RECALL, agent)).isTrue();
        assertThat(resolver.getBool(WRITE, agent)).isTrue();

        settings.set(SettingScope.TENANT, null, RECALL, false, "tenant switches recall off");
        settings.set(SettingScope.AGENT, agent, WRITE, false, "agent switches writes off");

        // exactly what ChatServiceParts wires for the recall and write switches, read the way the turn reads them
        BehaviourSettings behaviour = BehaviourSettings.of(resolver);
        for (boolean inTransaction : new boolean[] {false, true}) {
            resolver.evictCurrentTenant();
            String where = inTransaction ? "inside a transaction" : "outside a transaction (the chat turn)";
            boolean recall = read(inTransaction, () -> behaviour.bool(RECALL, null, agent, () -> true));
            boolean recallOtherAgent = read(inTransaction, () -> behaviour.bool(RECALL, null, untouchedAgent, () -> true));
            boolean write = read(inTransaction, () -> behaviour.bool(WRITE, null, agent, () -> true));
            boolean writeOtherAgent = read(inTransaction, () -> behaviour.bool(WRITE, null, untouchedAgent, () -> true));
            assertThat(recall).as("tenant override of %s, %s", RECALL, where).isFalse();
            assertThat(recallOtherAgent).as("tenant override reaches every agent, %s", where).isFalse();
            assertThat(write).as("agent override of %s, %s", WRITE, where).isFalse();
            assertThat(writeOtherAgent).as("agent override stays on its agent, %s", where).isTrue();
        }
    }

    @Test
    void anotherTenantNeverSeesTheOverridesOfThisOne() {
        String a = "t_setread_a";
        String b = "t_setread_b";
        tenantSchema(a);
        tenantSchema(b);
        asTenant(a);
        UUID agentA = newAgent("settings-read-a");
        settings.set(SettingScope.TENANT, null, RECALL, false, "tenant a switches recall off");
        asTenant(b);
        UUID agentB = newAgent("settings-read-b");
        resolver.evictCurrentTenant();
        assertThat(resolver.getBool(RECALL, agentB)).as("tenant b outside a transaction").isTrue();
        assertThat(read(true, () -> resolver.getBool(RECALL, agentB))).as("tenant b inside a transaction").isTrue();
        asTenant(a);
        resolver.evictCurrentTenant();
        assertThat(resolver.getBool(RECALL, agentA)).as("tenant a outside a transaction").isFalse();
    }

    private <T> T read(boolean inTransaction, java.util.function.Supplier<T> read) {
        return inTransaction ? tx.execute(status -> read.get()) : read.get();
    }
}
