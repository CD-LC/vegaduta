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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.MemoryBlocks;
import com.agenticai.core.agents.memory.QueryEmbedding;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@SuppressWarnings("unchecked")
class ChatMemoryRecallBlocksTest {

    private final ObjectProvider<AgentMemoryService> agentProvider = mock(ObjectProvider.class);
    private final ObjectProvider<TenantMemoryService> tenantProvider = mock(ObjectProvider.class);
    private final AgentMemoryService agentMemory = mock(AgentMemoryService.class);
    private final TenantMemoryService tenant = mock(TenantMemoryService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final UUID agentId = UUID.randomUUID();
    private boolean memoryEvents = true;
    private ChatMemoryRecall recall;

    @BeforeEach
    void setUp() {
        when(agentProvider.getIfAvailable()).thenReturn(agentMemory);
        when(tenantProvider.getIfAvailable()).thenReturn(tenant);
        when(agentService.isShareMemoryWithTenant(agentId)).thenReturn(true);
        when(tenant.recall(
                        anyString(),
                        anyString(),
                        nullable(QueryEmbedding.class),
                        nullable(UUID.class)))
                .thenReturn(List.of());
        recall =
                new ChatMemoryRecall(
                        agentProvider, tenantProvider, agentService, () -> memoryEvents);
    }

    private AgentMemoryEntry privateFact(String content) {
        return new AgentMemoryEntry(
                UUID.randomUUID(), agentId, "alice", content, 0.0, Instant.now(), Instant.now(), 0);
    }

    @Test
    void blocksComeBeforeRecalledFacts() {
        when(tenant.renderPromptBlocks("alice", "hi")).thenReturn("BLOCKS");
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of(privateFact("likes tea")));
        String out = recall.recallMemoryFactsBlock(agentId, "alice", "hi");
        assertNotNull(out);
        assertTrue(out.startsWith("BLOCKS"));
        assertTrue(out.indexOf("BLOCKS") < out.indexOf("likes tea"));
    }

    @Test
    void renderFailureIsTolerated() {
        when(tenant.renderPromptBlocks("alice", "hi")).thenThrow(new RuntimeException("db down"));
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of(privateFact("likes tea")));
        String out = recall.recallMemoryFactsBlock(agentId, "alice", "hi");
        assertNotNull(out);
        assertTrue(out.contains("likes tea"));
    }

    @Test
    void blocksNotRenderedForAgentsThatDidNotOptIn() {
        when(agentService.isShareMemoryWithTenant(agentId)).thenReturn(false);
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of());
        assertNull(recall.recallMemoryFactsBlock(agentId, "alice", "hi"));
        verify(tenant, never()).renderPromptBlocks(any(), any());
    }

    // ---- "memory used" step event (app.chat.sse.memory-events.enabled) ----

    /**
     * Starts the run, closes the side channel and returns every step event that reached the wire.
     */
    private List<JsonNode> drain(AgentStepEventEmitter emitter) throws Exception {
        emitter.runStarted("s-1", agentId);
        emitter.complete();
        List<String> chunks = emitter.asFlux().collectList().block();
        List<JsonNode> events = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String chunk : chunks) {
            assertTrue(chunk.startsWith(AgentStepEventEmitter.STEP_EVENT_MARKER));
            assertFalse(chunk.contains("\n"));
            events.add(
                    mapper.readTree(
                            chunk.substring(AgentStepEventEmitter.STEP_EVENT_MARKER.length())));
        }
        return events;
    }

    @Test
    void memoryEventCarriesLabelsCountsAndIdsButNoTextAndFollowsRunStarted() throws Exception {
        when(tenant.renderPromptBlocksWithLabels("alice", "hi"))
                .thenReturn(
                        new MemoryBlocks.Rendered("BLOCKS", List.of("persona", "project:acme")));
        AgentMemoryEntry mine = privateFact("likes tea");
        TenantMemoryEntry shared =
                new TenantMemoryEntry(
                        UUID.randomUUID(),
                        "alice",
                        "works at Acme",
                        0.0,
                        null,
                        Instant.now(),
                        Instant.now(),
                        0);
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of(mine));
        when(tenant.recall(
                        eq("alice"),
                        eq("hi"),
                        nullable(QueryEmbedding.class),
                        nullable(UUID.class)))
                .thenReturn(List.of(shared));
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();

        String out = recall.recallMemoryFactsBlock(agentId, "alice", "hi", emitter);

        assertTrue(out.startsWith("BLOCKS"));
        assertTrue(out.contains("likes tea"));
        // Recalled before the run started: held back, so nothing counts as "on the wire" yet.
        assertFalse(emitter.hasEmitted());
        List<JsonNode> events = drain(emitter);
        assertEquals(2, events.size());
        assertEquals("run_started", events.get(0).get("type").asText());
        JsonNode memory = events.get(1);
        assertEquals("memory", memory.get("type").asText());
        assertEquals(2, memory.get("blocks").asInt());
        assertEquals("persona", memory.get("blockLabels").get(0).asText());
        assertEquals("project:acme", memory.get("blockLabels").get(1).asText());
        assertEquals(1, memory.get("privateFacts").asInt());
        assertEquals(1, memory.get("sharedFacts").asInt());
        assertEquals(2, memory.get("factIds").size());
        assertEquals(mine.id().toString(), memory.get("factIds").get(0).asText());
        assertEquals(shared.id().toString(), memory.get("factIds").get(1).asText());
        assertFalse(memory.toString().contains("likes tea"));
        assertFalse(memory.toString().contains("works at Acme"));
        assertFalse(memory.toString().contains("BLOCKS"));
        verify(tenant, never()).renderPromptBlocks(any(), any());
    }

    @Test
    void memoryEventIsSentDirectlyOnceTheRunHasStarted() throws Exception {
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of(privateFact("likes tea")));
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        emitter.runStarted("s-1", agentId);
        recall.recallMemoryFactsBlock(agentId, "alice", "hi", emitter);
        emitter.complete();
        List<String> chunks = emitter.asFlux().collectList().block();
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(1).contains("\"type\":\"memory\""));
        assertTrue(chunks.get(1).contains("\"blocks\":0"));
        assertTrue(chunks.get(1).contains("\"privateFacts\":1"));
    }

    @Test
    void noMemoryEventWhenNothingWasRecalled() throws Exception {
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of());
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        assertNull(recall.recallMemoryFactsBlock(agentId, "alice", "hi", emitter));
        List<JsonNode> events = drain(emitter);
        assertEquals(1, events.size());
        assertEquals("run_started", events.get(0).get("type").asText());
    }

    @Test
    void flagOffEmitsNothingAndTakesTheOldRenderPath() throws Exception {
        memoryEvents = false;
        when(tenant.renderPromptBlocks("alice", "hi")).thenReturn("BLOCKS");
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of(privateFact("likes tea")));
        AgentStepEventEmitter emitter = AgentStepEventEmitter.active();
        String out = recall.recallMemoryFactsBlock(agentId, "alice", "hi", emitter);
        assertTrue(out.startsWith("BLOCKS"));
        assertEquals(1, drain(emitter).size());
        verify(tenant, never()).renderPromptBlocksWithLabels(any(), any());
    }

    @Test
    void disabledOrMissingEmitterIsANoOpWithIdenticalText() {
        when(tenant.renderPromptBlocks("alice", "hi")).thenReturn("BLOCKS");
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of(privateFact("likes tea")));
        String plain = recall.recallMemoryFactsBlock(agentId, "alice", "hi");
        String withNull = recall.recallMemoryFactsBlock(agentId, "alice", "hi", null);
        String withDisabled =
                recall.recallMemoryFactsBlock(
                        agentId, "alice", "hi", AgentStepEventEmitter.disabled());
        assertEquals(plain, withNull);
        assertEquals(plain, withDisabled);
        assertFalse(AgentStepEventEmitter.disabled().hasEmitted());
        verify(tenant, never()).renderPromptBlocksWithLabels(any(), any());
    }

    @Test
    void sameTextWithAndWithoutALiveEmitter() {
        when(tenant.renderPromptBlocks("alice", "hi")).thenReturn("BLOCKS");
        when(tenant.renderPromptBlocksWithLabels("alice", "hi"))
                .thenReturn(new MemoryBlocks.Rendered("BLOCKS", List.of("persona")));
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of(privateFact("likes tea")));
        assertEquals(
                recall.recallMemoryFactsBlock(agentId, "alice", "hi"),
                recall.recallMemoryFactsBlock(
                        agentId, "alice", "hi", AgentStepEventEmitter.active()));
    }

    @Test
    void recallMemoryFactsDistinguishesRecalledFactsFromOwnerBlocks() {
        // Owner blocks only: not counted as recalled facts
        when(tenant.renderPromptBlocks("alice", "hi")).thenReturn("MY NOTES");
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of());
        ChatMemoryRecall.MemoryRecall ownerOnly =
                recall.recallMemoryFacts(agentId, "alice", "hi", null);
        assertNotNull(ownerOnly.block());
        assertEquals(0, ownerOnly.recalledFacts());
        assertFalse(
                ownerOnly.hasRecalledFacts(),
                "owner-authored blocks must not count as recalled facts");

        // Stored facts: counted for taint marking
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of(privateFact("fact 1"), privateFact("fact 2")));
        ChatMemoryRecall.MemoryRecall withFacts =
                recall.recallMemoryFacts(agentId, "alice", "hi", null);
        assertNotNull(withFacts.block());
        assertEquals(2, withFacts.recalledFacts());
        assertTrue(withFacts.hasRecalledFacts());

        // Empty recall: null block and zero count
        when(tenant.renderPromptBlocks("alice", "empty")).thenReturn(null);
        when(agentMemory.recall(
                        eq(agentId), eq("alice"), eq("empty"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of());
        ChatMemoryRecall.MemoryRecall empty =
                recall.recallMemoryFacts(agentId, "alice", "empty", null);
        assertNull(empty.block());
        assertEquals(0, empty.recalledFacts());
        assertFalse(empty.hasRecalledFacts());
    }
}
