package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.QueryEmbedding;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Memory recall into the prompt: unchanged by default, skipped when switched off, and traced by ids and counts only. */
@SuppressWarnings("unchecked")
class ChatMemoryRecallSwitchTest {

    private final ObjectProvider<AgentMemoryService> agentProvider = mock(ObjectProvider.class);
    private final ObjectProvider<TenantMemoryService> tenantProvider = mock(ObjectProvider.class);
    private final AgentMemoryService agentMemory = mock(AgentMemoryService.class);
    private final TenantMemoryService tenant = mock(TenantMemoryService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final UUID agentId = UUID.randomUUID();
    private final UUID privateId = UUID.randomUUID();
    private final UUID sharedId = UUID.randomUUID();
    private ChatMemoryRecall recall;

    @BeforeEach
    void setUp() {
        when(agentProvider.getIfAvailable()).thenReturn(agentMemory);
        when(tenantProvider.getIfAvailable()).thenReturn(tenant);
        when(agentService.isShareMemoryWithTenant(agentId)).thenReturn(true);
        when(tenant.renderPromptBlocks("alice", "hi")).thenReturn("BLOCKS");
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(
                        List.of(
                                new AgentMemoryEntry(
                                        privateId,
                                        agentId,
                                        "alice",
                                        "likes tea secret-text",
                                        0.0,
                                        Instant.now(),
                                        Instant.now(),
                                        0)));
        when(tenant.recall(anyString(), anyString(), nullable(QueryEmbedding.class), nullable(UUID.class)))
                .thenReturn(
                        List.of(
                                new TenantMemoryEntry(
                                        sharedId,
                                        "alice",
                                        "team fact",
                                        0.0,
                                        null,
                                        Instant.now(),
                                        Instant.now(),
                                        0)));
        recall = new ChatMemoryRecall(agentProvider, tenantProvider, agentService, () -> false);
    }

    @Test
    void byDefaultRecallIsUnchangedAndTraced() {
        ChatMemoryRecall.MemoryRecall out = recall.recallMemoryFacts(agentId, "alice", "hi", null);

        assertNotNull(out.block());
        assertTrue(out.block().startsWith("BLOCKS"));
        assertTrue(out.block().contains("likes tea secret-text"));
        assertTrue(out.block().contains("team fact"));
        assertEquals(2, out.recalledFacts());
        TurnMemoryRecall.Trace trace = out.trace();
        assertTrue(trace.enabled());
        assertEquals(List.of(privateId), trace.privateFactIds());
        assertEquals(List.of(sharedId), trace.sharedFactIds());
        assertEquals(1, trace.blocks());
        assertTrue(trace.approxTokens() > 0);
        assertFalse(trace.toString().contains("secret-text"), "the trace carries ids and counts, never text");
    }

    @Test
    void anExplicitlyOnSwitchIsTheSameAsNoSwitch() {
        String without = recall.recallMemoryFactsBlock(agentId, "alice", "hi");
        recall.useRecallSwitch(id -> true);
        assertEquals(without, recall.recallMemoryFactsBlock(agentId, "alice", "hi"));
    }

    @Test
    void switchedOffNothingIsReadOrInjectedAndTheTraceSaysSo() {
        recall.useRecallSwitch(id -> !id.equals(agentId));

        ChatMemoryRecall.MemoryRecall out = recall.recallMemoryFacts(agentId, "alice", "hi", null);

        assertNull(out.block());
        assertEquals(0, out.recalledFacts());
        assertFalse(out.trace().enabled());
        assertEquals(0, out.trace().blocks());
        assertNull(recall.recallMemoryFactsBlock(agentId, "alice", "hi"));
        verifyNoInteractions(agentMemory);
        verifyNoInteractions(tenant);
    }

    @Test
    void theSwitchIsPerAgent() {
        UUID other = UUID.randomUUID();
        recall.useRecallSwitch(id -> !id.equals(other));

        assertNotNull(recall.recallMemoryFactsBlock(agentId, "alice", "hi"));
        assertNull(recall.recallMemoryFactsBlock(other, "alice", "hi"));
    }

    @Test
    void aFailingSwitchLeavesRecallOn() {
        recall.useRecallSwitch(
                id -> {
                    throw new IllegalStateException("settings down");
                });

        assertNotNull(recall.recallMemoryFactsBlock(agentId, "alice", "hi"));
    }

    @Test
    void nothingFoundIsTracedAsEnabledAndEmpty() {
        when(tenant.renderPromptBlocks(anyString(), anyString())).thenReturn(null);
        when(agentMemory.recall(any(), anyString(), anyString(), nullable(QueryEmbedding.class))).thenReturn(List.of());
        when(tenant.recall(anyString(), anyString(), nullable(QueryEmbedding.class), nullable(UUID.class)))
                .thenReturn(List.of());

        ChatMemoryRecall.MemoryRecall out = recall.recallMemoryFacts(agentId, "alice", "hi", null);

        assertNull(out.block());
        assertTrue(out.trace().enabled());
        assertEquals(0, out.trace().factIds().size());
    }
}
