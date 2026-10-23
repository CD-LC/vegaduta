package com.agenticai.core.agents.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryLimitsProperties;
import com.agenticai.core.agents.settings.ProductControlsFixture;
import com.agenticai.core.agents.settings.SettingsRig;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code memory.edge.maxFacts}: the facts an edge session is given follow the agent's own or its tenant's setting,
 * {@code app.memory.limits.edge-memory-max-facts} when there is none (the tenant is the one the request authenticated).
 */
@SuppressWarnings("unchecked")
class EdgeMemorySettingsTest extends EdgeToolExecutionControllerTestBase {

    private final ProductControlsFixture f = new ProductControlsFixture();
    private AgentMemoryService memory;

    @AfterEach
    void clear() {
        SettingsRig.clear();
    }

    private void withMemory(int facts, MemoryLimitsProperties limits, boolean withCatalog) {
        memory = mock(AgentMemoryService.class);
        List<AgentMemoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < facts; i++) {
            AgentMemoryEntry entry = mock(AgentMemoryEntry.class);
            when(entry.content()).thenReturn("fact " + i);
            entries.add(entry);
        }
        when(memory.listAll(eq(AGENT_ID), any())).thenReturn(entries);
        when(memory.recall(eq(AGENT_ID), any(), any(), anyInt())).thenReturn(entries);
        ObjectProvider<AgentMemoryService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(memory);
        controller = new EdgeToolExecutionController(
                agentService, agentToolProvider, new com.fasterxml.jackson.databind.ObjectMapper(), deviceAuthResolver, provider);
        controller.useMemoryLimits(limits);
        if (withCatalog) {
            controller.useSettings(f.rig().resolver());
        }
    }

    @Test
    void aTenantsAgentGetsTheNumberOfFactsTheTenantOrTheAgentSet() {
        f.memory.setEdgeMemoryMaxFacts(24);
        f.rig().tenant("tenant-a", "memory.edge.maxFacts", 10).agent("tenant-a", AGENT_ID, "memory.edge.maxFacts", 4);
        withMemory(40, f.memory, true);

        TenantContext.set(TenantContext.of("tenant-a", "u", Set.of()));
        assertEquals(4, controller.listMemory(AGENT_ID, null, null, PRINCIPAL).getBody().facts().size());
        TenantContext.set(TenantContext.of("tenant-b", "u", Set.of()));
        assertEquals(24, controller.listMemory(AGENT_ID, null, null, PRINCIPAL).getBody().facts().size());
    }

    @Test
    void theRankedRecallAsksForTheTenantsNumber() {
        f.memory.setEdgeMemoryMaxFacts(24);
        f.rig().tenant("tenant-a", "memory.edge.maxFacts", 9);
        withMemory(40, f.memory, true);

        TenantContext.set(TenantContext.of("tenant-a", "u", Set.of()));
        controller.listMemory(AGENT_ID, "tea", null, PRINCIPAL);

        verify(memory).recall(eq(AGENT_ID), any(), eq("tea"), eq(9));
    }

    @Test
    void aControllerBuiltWithoutACatalogKeepsTheProperty() {
        f.memory.setEdgeMemoryMaxFacts(6);
        f.rig().tenant("tenant-a", "memory.edge.maxFacts", 12);
        withMemory(40, f.memory, false);

        TenantContext.set(TenantContext.of("tenant-a", "u", Set.of()));
        assertEquals(6, controller.listMemory(AGENT_ID, null, null, PRINCIPAL).getBody().facts().size());
    }
}
