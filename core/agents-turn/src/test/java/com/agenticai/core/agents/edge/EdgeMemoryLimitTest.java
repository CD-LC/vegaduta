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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The number of facts the edge memory endpoint returns is {@code app.memory.limits.edge-memory-max-facts}. */
@SuppressWarnings("unchecked")
class EdgeMemoryLimitTest extends EdgeToolExecutionControllerTestBase {

    private AgentMemoryService memory;

    private void withMemory(int facts, MemoryLimitsProperties limits) {
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
        if (limits != null) {
            controller.useMemoryLimits(limits);
        }
    }

    @Test
    void theDefaultIsTwentyFourFacts() {
        withMemory(40, null);

        assertEquals(24, controller.listMemory(AGENT_ID, null, null, PRINCIPAL).getBody().facts().size());
    }

    @Test
    void theNumberOfFactsIsConfigurable() {
        MemoryLimitsProperties limits = new MemoryLimitsProperties();
        limits.setEdgeMemoryMaxFacts(5);
        withMemory(40, limits);

        assertEquals(5, controller.listMemory(AGENT_ID, null, null, PRINCIPAL).getBody().facts().size());
    }

    @Test
    void theRankedRecallAsksForTheConfiguredNumber() {
        MemoryLimitsProperties limits = new MemoryLimitsProperties();
        limits.setEdgeMemoryMaxFacts(7);
        withMemory(10, limits);

        controller.listMemory(AGENT_ID, "tea", null, PRINCIPAL);

        verify(memory).recall(eq(AGENT_ID), any(), eq("tea"), eq(7));
    }
}
