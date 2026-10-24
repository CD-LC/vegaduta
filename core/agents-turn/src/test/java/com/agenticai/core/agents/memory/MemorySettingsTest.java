package com.agenticai.core.agents.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.settings.BehaviourCatalogFixture;
import com.agenticai.core.knowledge.SidecarClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code memory.recall.agentTopK}, {@code memory.recall.tenantTopK}, {@code memory.agent.maxMemories}: how many memories an
 * agent recalls and keeps follows the agent's own or its tenant's setting, the {@code app.memory.limits.*} value when
 * there is none. JdbcTemplate is mocked, as in {@link MemoryLimitsTest}.
 */
class MemorySettingsTest {

    private static final String TENANT = "tenant-a";
    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UUID agentId = UUID.randomUUID();
    private final UUID sibling = UUID.randomUUID();
    private final BehaviourCatalogFixture f = new BehaviourCatalogFixture();

    @BeforeEach
    void setUp() {
        when(sidecar.embedQuery(anyString())).thenReturn(new float[] {0.1f, 0.2f});
    }

    @AfterEach
    void clear() {
        com.agenticai.core.security.TenantContext.clear();
    }

    private AgentMemoryService agentService() {
        AgentMemoryService service = spy(new AgentMemoryService(sidecar, jdbc));
        service.setLimits(f.memory);
        service.setSettingsResolver(f.resolver());
        return service;
    }

    private TenantMemoryService tenantService() {
        TenantMemoryService service = spy(new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 4000, true));
        service.setLimits(f.memory);
        service.setSettingsResolver(f.resolver());
        return service;
    }

    @Test
    void anAgentRecallsTheNumberOfMemoriesItsTenantOrItselfSetAndTheOthersTheDefault() {
        f.memory.setAgentRecallTopK(5);
        f.tenant(TENANT, "memory.recall.agentTopK", 8).agent(TENANT, agentId, "memory.recall.agentTopK", 12);
        AgentMemoryService service = agentService();
        doReturn(List.of()).when(service).recall(any(), anyString(), anyString(), anyInt());

        BehaviourCatalogFixture.inTenant(
                TENANT,
                () -> {
                    service.recall(agentId, "alice", "tea");
                    service.recall(sibling, "alice", "tea");
                });
        BehaviourCatalogFixture.inTenant("tenant-b", () -> service.recall(sibling, "alice", "tea"));

        verify(service).recall(agentId, "alice", "tea", 12);
        verify(service).recall(sibling, "alice", "tea", 8);
        verify(service).recall(sibling, "alice", "tea", 5);
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    @Test
    void anAgentPrunesDownToTheCapItsTenantSet() {
        f.memory.setAgentMaxMemories(50);
        f.tenant(TENANT, "memory.agent.maxMemories", 10);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(14);

        BehaviourCatalogFixture.inTenant(TENANT, () -> agentService().remember(agentId, "alice", "likes tea"));

        verify(jdbc).update(contains("DELETE FROM"), eq(agentId), eq(4));
    }

    @Test
    void anAgentUnderItsOwnLargerCapPrunesNothingAndAnotherTenantKeepsTheDefault() {
        f.memory.setAgentMaxMemories(10);
        f.agent(TENANT, agentId, "memory.agent.maxMemories", 100);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(60);
        AgentMemoryService service = agentService();

        BehaviourCatalogFixture.inTenant(TENANT, () -> service.remember(agentId, "alice", "likes tea"));
        verify(jdbc, never()).update(contains("DELETE FROM"), any(Object[].class));

        BehaviourCatalogFixture.inTenant("tenant-b", () -> service.remember(agentId, "alice", "likes tea"));
        verify(jdbc).update(contains("DELETE FROM"), eq(agentId), eq(50));
    }

    @Test
    void theSharedRecallMadeForAnAgentUsesThatAgentsTopKAndTheTenantLevelFormUsesTheTenants() {
        f.memory.setTenantRecallTopK(5);
        f.tenant(TENANT, "memory.recall.tenantTopK", 7).agent(TENANT, agentId, "memory.recall.tenantTopK", 3);
        TenantMemoryService service = tenantService();
        doReturn(List.of()).when(service).recall(anyString(), anyString(), anyInt(), any(QueryEmbedding.class));
        doReturn(List.of()).when(service).recall(anyString(), anyString(), anyInt());
        QueryEmbedding embedding = mock(QueryEmbedding.class);

        BehaviourCatalogFixture.inTenant(
                TENANT,
                () -> {
                    service.recall("alice", "tea", embedding, agentId);
                    service.recall("alice", "tea", embedding, sibling);
                    service.recall("alice", "tea", embedding);
                    service.recall("alice", "tea");
                });

        verify(service).recall("alice", "tea", 3, embedding);
        verify(service, org.mockito.Mockito.times(2)).recall("alice", "tea", 7, embedding);
        verify(service).recall("alice", "tea", 7);
    }

    @Test
    void servicesBuiltWithoutACatalogKeepTheLimitsProperties() {
        f.memory.setAgentRecallTopK(9);
        f.tenant(TENANT, "memory.recall.agentTopK", 2);
        AgentMemoryService service = spy(new AgentMemoryService(sidecar, jdbc));
        service.setLimits(f.memory);
        doReturn(List.of()).when(service).recall(any(), anyString(), anyString(), anyInt());

        BehaviourCatalogFixture.inTenant(TENANT, () -> service.recall(agentId, "alice", "tea"));

        verify(service).recall(agentId, "alice", "tea", 9);
    }
}
