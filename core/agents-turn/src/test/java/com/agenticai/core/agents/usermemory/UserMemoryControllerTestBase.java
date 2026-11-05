package com.agenticai.core.agents.usermemory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryTrust;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.graphmemory.GraphMemoryService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

@SuppressWarnings("unchecked")
abstract class UserMemoryControllerTestBase {

    final AgentMemoryService agent = mock(AgentMemoryService.class);
    final TenantMemoryService tenant = mock(TenantMemoryService.class);
    final ObjectProvider<AgentMemoryService> agentProvider = mock(ObjectProvider.class);
    final ObjectProvider<TenantMemoryService> tenantProvider = mock(ObjectProvider.class);
    final GraphMemoryService graph = mock(GraphMemoryService.class);
    final ObjectProvider<GraphMemoryService> graphProvider = mock(ObjectProvider.class);
    final ObjectProvider<com.agenticai.core.agents.memory.SubjectDataEraser> otherStoreProvider =
            mock(ObjectProvider.class);
    final com.agenticai.core.agents.memory.SubjectDataEraser scratchpad =
            mock(com.agenticai.core.agents.memory.SubjectDataEraser.class);
    final AgentService agentService = mock(AgentService.class);
    final Principal alice = () -> "alice";

    UserMemoryController controller() {
        return controller(true);
    }

    UserMemoryController controller(boolean selfService) {
        return controller(selfService, false);
    }

    UserMemoryController controller(boolean selfService, boolean graphForgetSync) {
        when(agentProvider.getIfAvailable()).thenReturn(agent);
        when(tenantProvider.getIfAvailable()).thenReturn(tenant);
        when(graphProvider.getIfAvailable()).thenReturn(graph);
        when(scratchpad.store()).thenReturn("scratchpad");
        when(otherStoreProvider.orderedStream())
                .thenAnswer(inv -> java.util.stream.Stream.of(scratchpad));
        when(tenant.blocksEnabled()).thenReturn(true);
        return new UserMemoryController(
                agentProvider,
                tenantProvider,
                graphProvider,
                otherStoreProvider,
                agentService,
                new UserMemoryFactService(
                        agentProvider, tenantProvider, agentService, selfService, 25, 50),
                selfService,
                graphForgetSync);
    }

    TenantMemoryEntry quarantinedShared(UUID id, UUID createdBy) {
        Instant now = Instant.now();
        return new TenantMemoryEntry(
                id,
                "alice",
                "shared fact",
                0,
                createdBy,
                now,
                now,
                0,
                MemoryTrust.QUARANTINED,
                List.of("tool:fetch"));
    }
}
