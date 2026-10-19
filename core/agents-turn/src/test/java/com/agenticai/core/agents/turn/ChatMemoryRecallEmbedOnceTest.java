package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One chat turn recalls from the agent's private memory and, when the agent shares memory with its
 * tenant, from the tenant-shared memory, both with the same user message. Each used to embed that
 * message through the sidecar; the sidecar is now asked once per turn. These tests use the real
 * services over a counting sidecar, so they count the real embedding calls.
 */
@SuppressWarnings("unchecked")
class ChatMemoryRecallEmbedOnceTest {

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentMemoryService agentMemory = new AgentMemoryService(sidecar, jdbc);
    private final TenantMemoryService tenantMemory =
            new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 4000, true);
    private final AgentService agentService = mock(AgentService.class);
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("acme", "u", Set.of()));
        when(sidecar.embedQuery(anyString())).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        when(agentService.isShareMemoryWithTenant(agentId)).thenReturn(true);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChatMemoryRecall recall(AgentMemoryService agent, TenantMemoryService tenant) {
        ObjectProvider<AgentMemoryService> agentProvider = mock(ObjectProvider.class);
        ObjectProvider<TenantMemoryService> tenantProvider = mock(ObjectProvider.class);
        when(agentProvider.getIfAvailable()).thenReturn(agent);
        when(tenantProvider.getIfAvailable()).thenReturn(tenant);
        return new ChatMemoryRecall(agentProvider, tenantProvider, agentService, () -> false);
    }

    @Test
    void bothStoresShareOneEmbeddingOfTheMessage() {
        recall(agentMemory, tenantMemory)
                .recallMemoryFactsBlock(agentId, "alice", "what do I like");

        verify(sidecar, times(1)).embedQuery("what do I like");
    }

    @Test
    void anAgentThatDoesNotShareMemoryEmbedsOnceToo() {
        when(agentService.isShareMemoryWithTenant(agentId)).thenReturn(false);

        recall(agentMemory, tenantMemory)
                .recallMemoryFactsBlock(agentId, "alice", "what do I like");

        verify(sidecar, times(1)).embedQuery("what do I like");
    }

    @Test
    void theTenantStoreAloneEmbedsOnce() {
        recall(null, tenantMemory).recallMemoryFactsBlock(agentId, "alice", "what do I like");

        verify(sidecar, times(1)).embedQuery("what do I like");
    }

    @Test
    void aFailedEmbeddingIsTriedAgainByTheNextStoreJustAsTwoSeparateEmbedsWould() {
        when(sidecar.embedQuery(anyString()))
                .thenThrow(new IllegalStateException("sidecar down"))
                .thenReturn(new float[] {0.1f});

        String block = recall(agentMemory, tenantMemory).recallMemoryFactsBlock(agentId, "a", "q");

        // Recall never fails the turn; the second store got its own try, as before.
        assertEquals(null, block);
        verify(sidecar, times(2)).embedQuery("q");
    }
}
