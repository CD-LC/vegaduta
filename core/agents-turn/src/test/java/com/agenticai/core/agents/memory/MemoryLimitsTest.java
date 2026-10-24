package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.agenticai.core.knowledge.SidecarClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The per-agent and per-tenant memory caps and the automatic recall top-k come from {@link
 * MemoryLimitsProperties} (the numbers that used to be constants: 50, 300, 5, 5). JdbcTemplate is mocked.
 */
class MemoryLimitsTest {

    private final SidecarClient sidecar = mock(SidecarClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(sidecar.embedQuery(anyString())).thenReturn(new float[] {0.1f, 0.2f});
    }

    @Test
    void theDefaultsAreTheOldConstants() {
        MemoryLimitsProperties p = new MemoryLimitsProperties();
        assertEquals(50, p.getAgentMaxMemories());
        assertEquals(300, p.getTenantMaxMemories());
        assertEquals(5, p.getAgentRecallTopK());
        assertEquals(5, p.getTenantRecallTopK());
        assertEquals(400, p.getSessionMaxStoredEntries());
        assertEquals(40, p.getSessionFoldBatch());
    }

    @Test
    void anAgentPrunesDownToItsConfiguredCap() {
        AgentMemoryService service = new AgentMemoryService(sidecar, jdbc);
        MemoryLimitsProperties limits = new MemoryLimitsProperties();
        limits.setAgentMaxMemories(10);
        service.setLimits(limits);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(14);

        service.remember(agentId, "alice", "likes tea");

        verify(jdbc).update(contains("DELETE FROM"), eq(agentId), eq(4));
    }

    @Test
    void anAgentUnderItsConfiguredCapPrunesNothing() {
        AgentMemoryService service = new AgentMemoryService(sidecar, jdbc);
        MemoryLimitsProperties limits = new MemoryLimitsProperties();
        limits.setAgentMaxMemories(100);
        service.setLimits(limits);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(60);

        service.remember(agentId, "alice", "likes tea");

        verify(jdbc, never()).update(contains("DELETE FROM"), any(Object[].class));
    }

    @Test
    void anAgentWithNoSettingsKeepsFiftyAsBefore() {
        AgentMemoryService service = new AgentMemoryService(sidecar, jdbc);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(53);

        service.remember(agentId, "alice", "likes tea");

        verify(jdbc).update(contains("DELETE FROM"), eq(agentId), eq(3));
    }

    @Test
    void anAgentRecallsTheConfiguredNumberOfMemories() {
        AgentMemoryService service = spy(new AgentMemoryService(sidecar, jdbc));
        MemoryLimitsProperties limits = new MemoryLimitsProperties();
        limits.setAgentRecallTopK(9);
        service.setLimits(limits);
        doReturn(List.of()).when(service).recall(eq(agentId), eq("alice"), eq("tea"), eq(9));

        service.recall(agentId, "alice", "tea");

        verify(service).recall(agentId, "alice", "tea", 9);
    }

    @Test
    void aTenantPrunesDownToItsConfiguredCap() {
        TenantMemoryService service = new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 4000, true);
        MemoryLimitsProperties limits = new MemoryLimitsProperties();
        limits.setTenantMaxMemories(100);
        service.setLimits(limits);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class))).thenReturn(130);
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class))).thenReturn(130);

        service.remember("alice", "likes tea", null);

        verify(jdbc).update(contains("DELETE FROM"), eq(30));
    }

    @Test
    void aTenantRecallsTheConfiguredNumberOfMemories() {
        TenantMemoryService service = spy(new TenantMemoryService(sidecar, jdbc, true, 1200, 20, 4000, true));
        MemoryLimitsProperties limits = new MemoryLimitsProperties();
        limits.setTenantRecallTopK(7);
        service.setLimits(limits);
        doReturn(List.of()).when(service).recall(eq("alice"), eq("tea"), eq(7));

        service.recall("alice", "tea");

        verify(service).recall("alice", "tea", 7);
    }

    @Test
    void theFoldBatchNeverReachesTheStorageBound() {
        MemoryLimitsProperties p = new MemoryLimitsProperties();
        p.setSessionMaxStoredEntries(30);
        p.setSessionFoldBatch(40);
        assertEquals(29, p.effectiveFoldBatch());
    }
}
