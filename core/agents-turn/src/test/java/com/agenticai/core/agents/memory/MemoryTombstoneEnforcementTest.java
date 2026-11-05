package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportFact;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportRequest;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos.ImportResponse;
import com.agenticai.core.agents.memory.importer.MemoryImportService;
import com.agenticai.core.agents.memorystore.MemoryTombstoneGuard;
import com.agenticai.core.agents.usermemory.UserMemoryFactService;
import com.agenticai.core.knowledge.SidecarClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tombstones make "forgotten" mean forgotten: with the guard active every recall statement carries the indexed
 * NOT EXISTS (no extra statement), and the automated write paths and the importer refuse a forgotten fact unless a
 * person re-confirms; with no guard (all memory flags off) not one statement changes. JdbcTemplate is mocked.
 */
class MemoryTombstoneEnforcementTest {

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UUID agentId = UUID.randomUUID();
    private AgentMemoryService agentMemory;
    private TenantMemoryService tenantMemory;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JdbcTemplate> provider(JdbcTemplate j) {
        ObjectProvider<JdbcTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(j);
        return p;
    }

    @BeforeEach
    void setUp() {
        when(sidecar.embedQuery(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        agentMemory = new AgentMemoryService(sidecar, jdbc);
        tenantMemory = new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 4000, true);
    }

    private void guardOn(boolean forgotten) {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class), any(Object[].class)))
                .thenReturn(forgotten);
        MemoryTombstoneGuard guard = new MemoryTombstoneGuard(true, provider(jdbc));
        agentMemory.setTombstoneGuard(guard);
        tenantMemory.setTombstoneGuard(guard);
    }

    private List<String> statements(String method) {
        List<String> out = new ArrayList<>();
        for (Invocation i : mockingDetails(jdbc).getInvocations()) {
            if (i.getMethod().getName().equals(method) && i.getArguments().length > 0) {
                out.add(String.valueOf(i.getArguments()[0]));
            }
        }
        return out;
    }

    private List<Object> insertArgs() {
        return mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("update") && String.valueOf(i.getArguments()[0]).contains("INSERT INTO"))
                .findFirst()
                .map(i -> java.util.Arrays.asList(i.getArguments()).subList(1, i.getArguments().length))
                .orElseThrow(() -> new AssertionError("no INSERT issued"));
    }

    // ------------------------------------------------------------------ recall

    @Test
    void withTheGuardActiveEveryLegacyRecallStatementExcludesTombstonedHashesInline() {
        guardOn(false);
        agentMemory.recall(agentId, "alice", "tea", 5);
        tenantMemory.recall("alice", "tea", 5);

        List<String> queries = statements("query");
        assertThat(queries).hasSize(2);
        assertThat(queries.get(0)).contains("FROM ").contains("agent_memory").contains("AND NOT EXISTS")
                .contains("memory_tombstone t").contains("t.memory_kind = 'agent'")
                .contains("t.content_sha256 = encode(sha256(convert_to(");
        assertThat(queries.get(1)).contains("tenant_memory").contains("AND NOT EXISTS").contains("t.memory_kind = 'shared'");
        // Inline, so no extra statement: the only other JDBC call a recall makes is the existing usage touch (not here: no rows).
        assertThat(mockingDetails(jdbc).getInvocations()).hasSize(2);
    }

    @Test
    void withNoGuardOrAnInactiveOneRecallStatementsAreUnchangedAndNothingExtraRuns() {
        agentMemory.recall(agentId, "alice", "tea", 5);
        tenantMemory.recall("alice", "tea", 5);
        agentMemory.setTombstoneGuard(new MemoryTombstoneGuard(false, provider(jdbc)));
        tenantMemory.setTombstoneGuard(MemoryTombstoneGuard.inactive());
        agentMemory.recall(agentId, "alice", "tea", 5);
        tenantMemory.recall("alice", "tea", 5);

        assertThat(statements("query")).hasSize(4).noneMatch(q -> q.contains("memory_tombstone"));
        assertThat(mockingDetails(jdbc).getInvocations()).as("the flags-off budget: zero extra statements").hasSize(4);
    }

    // ------------------------------------------------------------------ resurrection guard

    @Test
    void anAutomatedRememberOfAForgottenFactIsRefusedAndWritesNothing() {
        guardOn(true);
        assertThatThrownBy(() -> agentMemory.remember(agentId, "alice", "Prefers tea", MemoryProvenance.TRUSTED))
                .isInstanceOf(MemoryForgottenException.class);
        assertThatThrownBy(() -> tenantMemory.remember("alice", "Prefers tea", agentId, MemoryProvenance.TRUSTED))
                .isInstanceOf(MemoryForgottenException.class);
        assertThat(statements("update")).noneMatch(s -> s.contains("INSERT INTO"));
    }

    @Test
    void theRememberToolsTellTheModelTheFactWasForgottenAndSaveNothing() {
        guardOn(true);
        String agentOut = new RememberFactToolCallback(agentMemory, agentId, "alice", null, null, null, null, null)
                .call("{\"fact\":\"Prefers tea\"}");
        assertThat(agentOut).contains("\"remembered\": false").contains("\"forgotten\": true");
        String sharedOut = new RememberSharedFactToolCallback(tenantMemory, agentId, "alice", null, null, null, null)
                .call("{\"fact\":\"Prefers tea\"}");
        assertThat(sharedOut).contains("\"remembered\": false").contains("\"forgotten\": true");
        assertThat(statements("update")).noneMatch(s -> s.contains("INSERT INTO"));
    }

    @Test
    void aNotForgottenFactIsRememberedWithTheAgentImportancePrior() {
        guardOn(false);
        agentMemory.remember(agentId, "alice", "Prefers coffee", MemoryProvenance.TRUSTED);
        assertThat(insertArgs()).contains(0.6, false);
    }

    @Test
    void aPersonTypingTheFactAgainClearsTheTombstoneAndStoresItProtectedWithHighImportance() {
        guardOn(true);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        agentMemory.rememberOwn(agentId, "alice", "Prefers tea", 25);

        assertThat(statements("update")).anyMatch(s -> s.startsWith("DELETE FROM") && s.contains("memory_tombstone"));
        assertThat(insertArgs()).contains(0.8, true);
    }

    @Test
    void sharedFactsGetTheSameImportanceAndAuthorshipSignals() {
        guardOn(false);
        tenantMemory.remember("alice", "Prefers coffee", agentId, MemoryProvenance.TRUSTED);
        assertThat(insertArgs()).contains(0.6, false);
    }

    // ------------------------------------------------------------------ importer

    @Test
    @SuppressWarnings("unchecked")
    void theImporterSkipsAForgottenFactUnlessThePersonReconfirmsIt() {
        guardOn(true);
        AgentMemoryService agent = mock(AgentMemoryService.class);
        TenantMemoryService tenant = mock(TenantMemoryService.class);
        UserMemoryFactService facts = mock(UserMemoryFactService.class);
        ObjectProvider<AgentMemoryService> ap = mock(ObjectProvider.class);
        ObjectProvider<TenantMemoryService> tp = mock(ObjectProvider.class);
        ObjectProvider<com.agenticai.core.audit.AuditLogService> auditP = mock(ObjectProvider.class);
        when(ap.getIfAvailable()).thenReturn(agent);
        when(tp.getIfAvailable()).thenReturn(tenant);
        when(tenant.listFactsForSubject("alice")).thenReturn(List.of());
        when(agent.listAllForSubject("alice")).thenReturn(List.of());
        MemoryImportService importer = new MemoryImportService(ap, tp, facts, auditP, true, true, 200, 20000, 25, 50);
        importer.setTombstoneGuard(new MemoryTombstoneGuard(true, provider(jdbc)));

        ImportResponse blocked = importer.importFacts("alice",
                new ImportRequest(List.of(new ImportFact("Likes green tea", "shared", null)), "chatgpt"));
        assertThat(blocked.saved()).isZero();
        assertThat(blocked.skipped()).extracting(s -> s.reason()).containsExactly("forgotten");

        ImportResponse confirmed = importer.importFacts("alice",
                new ImportRequest(List.of(new ImportFact("Likes green tea", "shared", null, true)), "chatgpt"));
        assertThat(confirmed.saved()).isEqualTo(1);
    }
}
