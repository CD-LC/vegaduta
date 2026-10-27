package com.agenticai.core.agents.turn;

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
import com.agenticai.core.agents.memory.QueryEmbedding;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.guardrails.taint.TurnTaintSeeder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The auto-injected memory recall must TAINT the turn when recalled facts (private or shared) enter
 * the prompt - recall bypasses the tool guard entirely, so the taint is the only gate before a
 * poisoned memory reaches a sink. User-authored blocks alone (persona/profile) must NOT taint.
 */
@SuppressWarnings("unchecked")
class ChatMemoryRecallTaintTest {

    private final ObjectProvider<AgentMemoryService> agentProvider = mock(ObjectProvider.class);
    private final ObjectProvider<TenantMemoryService> tenantProvider = mock(ObjectProvider.class);
    private final AgentMemoryService agentMemory = mock(AgentMemoryService.class);
    private final TenantMemoryService tenant = mock(TenantMemoryService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final TurnTaintSeeder seeder = mock(TurnTaintSeeder.class);
    private final AtomicInteger stepCounter = new AtomicInteger();
    private final UUID agentId = UUID.randomUUID();
    private ChatMemoryRecall recall;

    @BeforeEach
    void setUp() {
        when(agentProvider.getIfAvailable()).thenReturn(agentMemory);
        when(tenantProvider.getIfAvailable()).thenReturn(tenant);
        when(agentService.isShareMemoryWithTenant(agentId)).thenReturn(true);
        when(agentMemory.queryEmbedding(anyString())).thenReturn(mock(QueryEmbedding.class));
        when(tenant.recall(
                        anyString(),
                        anyString(),
                        nullable(QueryEmbedding.class),
                        nullable(UUID.class)))
                .thenReturn(List.of());
        recall = new ChatMemoryRecall(agentProvider, tenantProvider, agentService, () -> true);
    }

    private AgentMemoryEntry privateFact(String content) {
        return new AgentMemoryEntry(
                UUID.randomUUID(), agentId, "alice", content, 0.0, Instant.now(), Instant.now(), 0);
    }

    private void run(String userMessage) {
        recall.recallMemoryFactsBlock(
                agentId,
                "alice",
                userMessage,
                AgentStepEventEmitter.disabled(),
                stepCounter,
                seeder);
    }

    @Test
    void recalledPrivateFactsSeedTheTurn() {
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of(privateFact("likes tea")));

        run("hi");

        verify(seeder).seed(stepCounter, "memory_recall");
    }

    @Test
    void recalledSharedFactsSeedTheTurn() {
        TenantMemoryEntry shared =
                new TenantMemoryEntry(
                        UUID.randomUUID(),
                        "alice",
                        "shared fact",
                        0.0,
                        agentId,
                        Instant.now(),
                        Instant.now(),
                        0);
        when(tenant.recall(eq("alice"), eq("hi"), nullable(QueryEmbedding.class), eq(agentId)))
                .thenReturn(List.of(shared));

        run("hi");

        verify(seeder).seed(stepCounter, "memory_recall");
    }

    @Test
    void userBlocksAloneDoNotSeedTheTurn() {
        when(tenant.renderPromptBlocks("alice", "hi")).thenReturn("PERSONA TEXT");

        run("hi");

        verify(seeder, never()).seed(stepCounter, "memory_recall");
    }

    @Test
    void noRecallAndNoBlocksSeedNothing() {
        run("hi");

        verify(seeder, never()).seed(stepCounter, "memory_recall");
    }

    @Test
    void nullSeederKeepsTheOldBehaviour() {
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("hi"), nullable(QueryEmbedding.class)))
                .thenReturn(List.of(privateFact("likes tea")));

        String block =
                recall.recallMemoryFactsBlock(
                        agentId,
                        "alice",
                        "hi",
                        AgentStepEventEmitter.disabled(),
                        stepCounter,
                        null);

        verify(seeder, never()).seed(stepCounter, "memory_recall");
        org.junit.jupiter.api.Assertions.assertNotNull(
                block, "the block itself is unchanged by the taint overload");
    }
}
