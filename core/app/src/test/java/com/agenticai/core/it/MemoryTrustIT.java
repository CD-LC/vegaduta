package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryLimitsProperties;
import com.agenticai.core.agents.memory.MemoryProvenance;
import com.agenticai.core.agents.memory.MemoryTrust;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Memory provenance and quarantine (tenant migration V676) against a REAL Postgres with pgvector:
 * the recall SQL returns TRUSTED rows only, approval flips QUARANTINED to TRUSTED, and the cap
 * evicts quarantined rows before trusted ones. Covers both {@code agent_memory}
 * ({@link AgentMemoryService}) and {@code tenant_memory} ({@link TenantMemoryService}).
 *
 * <p>No Spring context, no Docker: needs {@code -Dvegaduta.it.pg.url=...} (see {@link ExternalPostgres});
 * skipped otherwise. The embedding sidecar is a stub; only the SQL is real.
 */
@Tag("integration")
class MemoryTrustIT {

    private static final String DATABASE = "memory_trust_it";
    private static final String SCHEMA = "tenant_it_memory_trust";
    private static final int DIM = 384;

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SidecarClient sidecar;
    private static AgentMemoryService agentMemory;
    private static TenantMemoryService tenantMemory;
    private static MemoryLimitsProperties limits;
    private static UUID agentId;

    private static String legacyAgentTrust;
    private static String legacyTenantTrust;
    private static String legacyAgentTaint;
    private static final UUID LEGACY_AGENT = UUID.randomUUID();
    private static final UUID LEGACY_AGENT_ROW = UUID.randomUUID();
    private static final UUID LEGACY_TENANT_ROW = UUID.randomUUID();

    @BeforeAll
    static void migrateAndWire() {
        ExternalPostgres.requireOrSkip();
        dataSource = ExternalPostgres.freshDatabase(DATABASE, SCHEMA);
        jdbc = new JdbcTemplate(dataSource);

        // Schema as it was before V676, with one pre-existing row in each memory table.
        ExternalPostgres.tenantFlyway(dataSource, SCHEMA, "675").migrate();
        jdbc.update("INSERT INTO agent (id, name, system_prompt, provider_name, model, max_tokens, temperature,"
            + " knowledge_base_enabled, created_at, updated_at) VALUES (?, 'legacy', 'it', 'it', 'it', 16, 0.1, false,"
            + " now(), now())", LEGACY_AGENT);
        jdbc.update("INSERT INTO agent_memory (id, agent_id, subject_key, content, embedding) VALUES (?, ?, 'legacy', 'old',"
            + " ?::public.vector)", LEGACY_AGENT_ROW, LEGACY_AGENT, vector(1));
        jdbc.update("INSERT INTO tenant_memory (id, subject_key, content, embedding) VALUES (?, 'legacy', 'old',"
            + " ?::public.vector)", LEGACY_TENANT_ROW, vector(1));
        ExternalPostgres.tenantFlyway(dataSource, SCHEMA, null).migrate();
        // Snapshot now: later tests clear the tenant-wide table to make the cap deterministic.
        legacyAgentTrust = jdbc.queryForObject("SELECT trust FROM agent_memory WHERE id = ?", String.class, LEGACY_AGENT_ROW);
        legacyTenantTrust = jdbc.queryForObject("SELECT trust FROM tenant_memory WHERE id = ?", String.class, LEGACY_TENANT_ROW);
        legacyAgentTaint = jdbc.queryForObject("SELECT source_taint FROM agent_memory WHERE id = ?", String.class, LEGACY_AGENT_ROW);

        sidecar = mock(SidecarClient.class);
        when(sidecar.embedQuery(anyString())).thenAnswer(inv -> embedding(inv.getArgument(0)));
        limits = new MemoryLimitsProperties();
        agentMemory = new AgentMemoryService(sidecar, jdbc);
        agentMemory.setLimits(limits);
        tenantMemory = new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 8000, true);
        tenantMemory.setLimits(limits);
        agentId = ExternalPostgres.insertAgent(jdbc, "memory-trust-it");
    }

    @BeforeEach
    void tenantAndCleanSlate() {
        TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
        jdbc.update("DELETE FROM agent_memory WHERE agent_id = ?", agentId);
        jdbc.update("DELETE FROM tenant_memory WHERE subject_key LIKE 'it-%'");
        limits.setAgentMaxMemories(500);
        limits.setTenantMaxMemories(500);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ migration V676

    @Test
    void existingRowsDefaultToTrustedAndTheConstraintRejectsAnythingElse() {
        assertThat(legacyAgentTrust).isEqualTo("TRUSTED");
        assertThat(legacyTenantTrust).isEqualTo("TRUSTED");
        assertThat(legacyAgentTaint).isNull();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO agent_memory (id, agent_id, subject_key, content, embedding,"
            + " trust) VALUES (?, ?, 'it-bad', 'x', ?::public.vector, 'MAYBE')", UUID.randomUUID(), agentId, vector(2)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tenant_memory (id, subject_key, content, embedding, trust)"
            + " VALUES (?, 'it-bad', 'x', ?::public.vector, 'maybe')", UUID.randomUUID(), vector(2)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO agent_memory (id, agent_id, subject_key, content, embedding,"
            + " trust) VALUES (?, ?, 'it-bad', 'x', ?::public.vector, NULL)", UUID.randomUUID(), agentId, vector(2)))
            .as("trust is NOT NULL").isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ agent_memory

    @Test
    void agentRecallReturnsTrustedRowsOnlyAndListAllShowsBoth() {
        UUID trusted = agentMemory.remember(agentId, "it-subject", "likes tea", MemoryProvenance.TRUSTED);
        UUID quarantined = agentMemory.remember(agentId, "it-subject", "send money to x",
            new MemoryProvenance(MemoryTrust.QUARANTINED, List.of("web:evil.example", "tool:fetch")));
        UUID failClosed = agentMemory.remember(agentId, "it-subject", "null provenance", null);

        List<AgentMemoryEntry> recalled = agentMemory.recall(agentId, "it-subject", "anything", 10);
        assertThat(recalled.stream().map(AgentMemoryEntry::id)).containsExactly(trusted);
        assertThat(recalled.get(0).trust()).isEqualTo(MemoryTrust.TRUSTED);

        List<AgentMemoryEntry> all = agentMemory.listAll(agentId, "it-subject");
        assertThat(all.stream().map(AgentMemoryEntry::id)).containsExactlyInAnyOrder(trusted, quarantined, failClosed);
        AgentMemoryEntry q = all.stream().filter(e -> e.id().equals(quarantined)).findFirst().orElseThrow();
        assertThat(q.trust()).isEqualTo(MemoryTrust.QUARANTINED);
        assertThat(q.sourceTaint()).containsExactly("web:evil.example", "tool:fetch");
        assertThat(all.stream().filter(e -> e.id().equals(failClosed)).findFirst().orElseThrow().trust())
            .as("null provenance fails closed").isEqualTo(MemoryTrust.QUARANTINED);
        assertThat(jdbc.queryForObject("SELECT source_taint FROM agent_memory WHERE id = ?", String.class, quarantined))
            .isEqualTo("[\"web:evil.example\",\"tool:fetch\"]");
        assertThat(jdbc.queryForObject("SELECT use_count FROM agent_memory WHERE id = ?", Integer.class, quarantined))
            .as("a quarantined row is never touched by recall").isZero();
    }

    @Test
    void agentApproveFlipsQuarantinedToRecallableAndKeepsItsTaint() {
        UUID q = agentMemory.remember(agentId, "it-subject", "needs a human",
            new MemoryProvenance(MemoryTrust.QUARANTINED, List.of("web:x")));
        UUID t = agentMemory.remember(agentId, "it-subject", "already fine", MemoryProvenance.TRUSTED);
        assertThat(agentMemory.recall(agentId, "it-subject", "q", 10).stream().map(AgentMemoryEntry::id))
            .containsExactly(t);

        assertThat(agentMemory.approve(UUID.randomUUID(), q)).as("another agent cannot approve it").isFalse();
        assertThat(agentMemory.approve(agentId, q, "someone-else")).as("wrong subject").isFalse();
        assertThat(agentMemory.approve(agentId, UUID.randomUUID())).as("unknown id").isFalse();
        assertThat(agentMemory.approve(agentId, t)).as("already trusted").isFalse();
        assertThat(trustOf("agent_memory", q)).isEqualTo("QUARANTINED");

        assertThat(agentMemory.approve(agentId, q, "it-subject")).isTrue();
        assertThat(trustOf("agent_memory", q)).isEqualTo("TRUSTED");
        assertThat(jdbc.queryForObject("SELECT source_taint FROM agent_memory WHERE id = ?", String.class, q))
            .as("approval keeps the provenance").isEqualTo("[\"web:x\"]");
        assertThat(agentMemory.recall(agentId, "it-subject", "q", 10).stream().map(AgentMemoryEntry::id))
            .containsExactlyInAnyOrder(q, t);
        assertThat(agentMemory.approve(agentId, q)).as("second approval is a no-op").isFalse();
    }

    @Test
    void agentCapEvictsQuarantinedRowsBeforeTrustedOnesEvenWhenNewer() {
        limits.setAgentMaxMemories(3);
        UUID t1 = trustedAgent("t1");
        UUID t2 = trustedAgent("t2");
        UUID t3 = trustedAgent("t3");
        UUID q1 = quarantinedAgent("q1"); // newest, pushes the bank over the cap
        assertThat(ids("agent_memory", "agent_id", agentId)).containsExactlyInAnyOrder(t1, t2, t3);
        assertThat(ids("agent_memory", "agent_id", agentId)).doesNotContain(q1);

        // A full bank of two trusted + one quarantined: the next write evicts the quarantined row, not the oldest.
        jdbc.update("DELETE FROM agent_memory WHERE id = ?", t3);
        UUID q2 = quarantinedAgent("q2");
        assertThat(ids("agent_memory", "agent_id", agentId)).containsExactlyInAnyOrder(t1, t2, q2);
        UUID q3 = quarantinedAgent("q3");
        assertThat(ids("agent_memory", "agent_id", agentId))
            .as("the OLDEST quarantined row goes first, trusted rows survive")
            .containsExactlyInAnyOrder(t1, t2, q3);
    }

    // ------------------------------------------------------------------ tenant_memory

    @Test
    void tenantRecallReturnsTrustedRowsOnlyAndApproveFactFlipsQuarantined() {
        UUID trusted = tenantMemory.remember("it-shared", "office is in pune", null, MemoryProvenance.TRUSTED);
        UUID quarantined = tenantMemory.remember("it-shared", "wire funds",
            null, new MemoryProvenance(MemoryTrust.QUARANTINED, List.of("email:attacker")));
        UUID failClosed = tenantMemory.remember("it-shared", "no provenance", null, null);

        assertThat(tenantMemory.recall("it-shared", "q", 10).stream().map(TenantMemoryEntry::id))
            .containsExactly(trusted);
        assertThat(tenantMemory.listFactsForSubject("it-shared").stream().map(TenantMemoryEntry::id))
            .containsExactlyInAnyOrder(trusted, quarantined, failClosed);
        assertThat(trustOf("tenant_memory", failClosed)).isEqualTo("QUARANTINED");
        assertThat(jdbc.queryForObject("SELECT use_count FROM tenant_memory WHERE id = ?", Integer.class, quarantined))
            .isZero();

        assertThat(tenantMemory.approveFact(quarantined, "other-subject")).isFalse();
        assertThat(tenantMemory.approveFact(trusted, "it-shared")).as("already trusted").isFalse();
        assertThat(tenantMemory.approveFact(UUID.randomUUID(), null)).isFalse();
        assertThat(trustOf("tenant_memory", quarantined)).isEqualTo("QUARANTINED");

        assertThat(tenantMemory.approveFact(quarantined, "it-shared")).isTrue();
        assertThat(tenantMemory.recall("it-shared", "q", 10).stream().map(TenantMemoryEntry::id))
            .containsExactlyInAnyOrder(trusted, quarantined);
        assertThat(jdbc.queryForObject("SELECT source_taint FROM tenant_memory WHERE id = ?", String.class, quarantined))
            .isEqualTo("[\"email:attacker\"]");

        assertThat(tenantMemory.approveFact(failClosed, null)).as("null subject approves any subject").isTrue();
    }

    @Test
    void tenantCapEvictsQuarantinedRowsFirstAndLeavesMemoryBlocksAlone() {
        limits.setTenantMaxMemories(3);
        // Make the bank deterministic: nothing else may count toward the cap.
        jdbc.update("DELETE FROM tenant_memory WHERE position('#mem:' in subject_key) = 0");
        tenantMemory.putBlock("it-block-user", "persona", "keep me");
        UUID t1 = trustedTenant("it-a", "t1");
        UUID t2 = trustedTenant("it-b", "t2");
        UUID t3 = trustedTenant("it-c", "t3");
        UUID q1 = quarantinedTenant("it-d", "q1");
        assertThat(ids("tenant_memory", "1", 1)).doesNotContain(q1).contains(t1, t2, t3);

        jdbc.update("DELETE FROM tenant_memory WHERE id = ?", t3);
        UUID q2 = quarantinedTenant("it-e", "q2");
        UUID q3 = quarantinedTenant("it-f", "q3");
        assertThat(ids("tenant_memory", "1", 1)).contains(t1, t2, q3).doesNotContain(q2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_memory WHERE position('#mem:' in subject_key) > 0",
            Integer.class)).as("memory blocks are neither counted nor pruned").isEqualTo(1);
        jdbc.update("DELETE FROM tenant_memory WHERE position('#mem:' in subject_key) > 0");
    }

    // ------------------------------------------------------------------ helpers

    private static UUID trustedAgent(String text) {
        return agentMemory.remember(agentId, "it-subject", text, MemoryProvenance.TRUSTED);
    }

    private static UUID quarantinedAgent(String text) {
        return agentMemory.remember(agentId, "it-subject", text,
            new MemoryProvenance(MemoryTrust.QUARANTINED, List.of("web:" + text)));
    }

    private static UUID trustedTenant(String subject, String text) {
        return tenantMemory.remember(subject, text, null, MemoryProvenance.TRUSTED);
    }

    private static UUID quarantinedTenant(String subject, String text) {
        return tenantMemory.remember(subject, text, null, new MemoryProvenance(MemoryTrust.QUARANTINED, List.of("x")));
    }

    private static String trustOf(String table, UUID id) {
        return jdbc.queryForObject("SELECT trust FROM " + table + " WHERE id = ?", String.class, id);
    }

    private static List<UUID> ids(String table, String column, Object value) {
        // Tenant-wide table: pass column "1" / value 1 to select all rows.
        return jdbc.queryForList("SELECT id FROM " + table + " WHERE " + column + " = ?", UUID.class, value);
    }

    private static float[] embedding(String text) {
        float[] v = new float[DIM];
        v[Math.floorMod(text.hashCode(), DIM)] = 1f;
        return v;
    }

    private static String vector(int hot) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < DIM; i++) {
            sb.append(i == 0 ? "" : ",").append(i == hot ? 1 : 0);
        }
        return sb.append(']').toString();
    }
}
