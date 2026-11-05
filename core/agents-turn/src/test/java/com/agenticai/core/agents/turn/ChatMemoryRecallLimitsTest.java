package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.QueryEmbedding;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.memory.spi.MemoryItemView;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.agents.memory.spi.UnifiedMemoryRecall;
import com.agenticai.core.agents.memorystore.MemoryRecallProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code app.memory.recall.min-similarity} and {@code max-items} in the chat turn's recall: unrelated lines are not injected,
 * one turn injects at most the cap across the private, shared and unified lines (closest first), the always-in-context
 * blocks are never touched, and with no limits (or both off) the block is exactly what it was.
 */
@SuppressWarnings("unchecked")
class ChatMemoryRecallLimitsTest {

    private final ObjectProvider<AgentMemoryService> agentProvider = mock(ObjectProvider.class);
    private final ObjectProvider<TenantMemoryService> tenantProvider = mock(ObjectProvider.class);
    private final AgentMemoryService agentMemory = mock(AgentMemoryService.class);
    private final TenantMemoryService tenant = mock(TenantMemoryService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final UUID agentId = UUID.randomUUID();
    private ChatMemoryRecall recall;

    @BeforeEach
    void setUp() {
        when(agentProvider.getIfAvailable()).thenReturn(agentMemory);
        when(tenantProvider.getIfAvailable()).thenReturn(tenant);
        when(agentService.isShareMemoryWithTenant(agentId)).thenReturn(true);
        when(tenant.renderPromptBlocks("alice", "q")).thenReturn("PERSONA BLOCK");
        recall = new ChatMemoryRecall(agentProvider, tenantProvider, agentService, () -> false);
    }

    private static AgentMemoryEntry fact(String text, double similarity) {
        return new AgentMemoryEntry(UUID.randomUUID(), UUID.randomUUID(), "alice", text, 1.0 - similarity, Instant.now(), Instant.now(), 0);
    }

    private static TenantMemoryEntry shared(String text, double similarity) {
        return new TenantMemoryEntry(UUID.randomUUID(), "alice", text, 1.0 - similarity, UUID.randomUUID(), Instant.now(), Instant.now(), 0);
    }

    private void stores(List<AgentMemoryEntry> mine, List<TenantMemoryEntry> theirs) {
        when(agentMemory.recall(eq(agentId), eq("alice"), eq("q"), nullable(QueryEmbedding.class))).thenReturn(mine);
        when(tenant.recall(anyString(), anyString(), nullable(QueryEmbedding.class), nullable(UUID.class))).thenReturn(theirs);
    }

    private void limits(double minSimilarity, int maxItems) {
        MemoryRecallProperties p = new MemoryRecallProperties();
        p.setMinSimilarity(minSimilarity);
        p.setMaxItems(maxItems);
        when(agentMemory.recallLimits()).thenReturn(p);
    }

    private String block() {
        ChatMemoryRecall.MemoryRecall r = recall.recallMemoryFacts(agentId, "alice", "q", null);
        return r.block() == null ? "" : r.block();
    }

    @Test
    void aLineBelowTheFloorIsNotInjected() {
        stores(List.of(fact("likes tea", 0.80), fact("shop closes at 9", 0.05)), List.of(shared("on the gold plan", 0.10)));
        limits(0.20, 0);

        String block = block();

        assertTrue(block.contains("likes tea"));
        assertFalse(block.contains("shop closes at 9"));
        assertFalse(block.contains("gold plan"));
    }

    @Test
    void theFloorLeavesTheAlwaysInContextBlocksAlone() {
        stores(List.of(fact("unrelated", 0.01)), List.of());
        limits(0.50, 1);

        assertTrue(block().contains("PERSONA BLOCK"));
    }

    @Test
    void withNoLimitsTheBlockIsWhatItWas() {
        stores(List.of(fact("likes tea", 0.80), fact("shop closes at 9", 0.05)), List.of(shared("on the gold plan", 0.10)));
        String without = block(); // recallLimits() on a mock is null: no floor, no cap

        limits(0.0, 0); // the shipped "off" values

        assertEquals(without, block());
        assertTrue(without.contains("likes tea") && without.contains("shop closes at 9") && without.contains("gold plan"));
    }

    @Test
    void aTurnInjectsAtMostTheCapAcrossPrivateAndSharedClosestFirst() {
        stores(List.of(fact("mine-high", 0.90), fact("mine-low", 0.50)),
                List.of(shared("shared-high", 0.80), shared("shared-mid", 0.60)));
        limits(0.0, 3);

        ChatMemoryRecall.MemoryRecall r = recall.recallMemoryFacts(agentId, "alice", "q", null);

        assertEquals(3, r.recalledFacts());
        assertTrue(r.block().contains("mine-high") && r.block().contains("shared-high") && r.block().contains("shared-mid"));
        assertFalse(r.block().contains("mine-low"));
        assertEquals(1, r.trace().privateFactIds().size());
        assertEquals(2, r.trace().sharedFactIds().size());
    }

    @Test
    void aTieBetweenAPrivateAndASharedFactKeepsThePrivateOne() {
        stores(List.of(fact("mine", 0.70)), List.of(shared("shared", 0.70)));
        limits(0.0, 1);

        String block = block();

        assertTrue(block.contains("mine"));
        assertFalse(block.contains("shared"));
    }

    private static final class Fake implements UnifiedMemoryRecall {
        MemoryRecallRequest last;
        int calls;
        final List<MemoryItemView> items = new ArrayList<>();

        @Override
        public int totalTokens() {
            return 800;
        }

        @Override
        public MemoryRecallResult compose(MemoryRecallRequest request) {
            calls++;
            last = request;
            return new MemoryRecallResult(items, 0, 0, null);
        }
    }

    private static MemoryItemView episode(String text) {
        return new MemoryItemView(UUID.randomUUID(), MemoryKind.EPISODIC, text, 0.9, MemoryProvenanceRecord.TRUSTED);
    }

    private Fake unified() {
        Fake fake = new Fake();
        ObjectProvider<UnifiedMemoryRecall> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fake);
        recall.useUnifiedRecall(provider);
        return fake;
    }

    @Test
    void theUnifiedBlockOnlyGetsWhatIsLeftOfTheCap() {
        stores(List.of(fact("a", 0.9), fact("b", 0.8)), List.of());
        limits(0.0, 3);
        Fake fake = unified();
        fake.items.add(episode("episode one"));
        fake.items.add(episode("episode two"));

        ChatMemoryRecall.MemoryRecall r = recall.recallMemoryFacts(agentId, "alice", "q", null);

        assertEquals(1 + 2, fake.last.maxItems()); // room for one new item, plus the two legacy ids it may repeat
        assertEquals(3, r.recalledFacts());
        assertTrue(r.block().contains("episode one"));
        assertFalse(r.block().contains("episode two"));
    }

    @Test
    void aFullCapMeansTheUnifiedRecallIsNotAskedAtAll() {
        stores(List.of(fact("a", 0.9), fact("b", 0.8), fact("c", 0.7)), List.of());
        limits(0.0, 3);
        Fake fake = unified();
        fake.items.add(episode("episode one"));

        ChatMemoryRecall.MemoryRecall r = recall.recallMemoryFacts(agentId, "alice", "q", null);

        assertEquals(0, fake.calls);
        assertNotNull(r.block());
        assertFalse(r.block().contains("episode one"));
    }

    @Test
    void withoutACapTheUnifiedRecallIsAskedExactlyAsBefore() {
        stores(List.of(fact("a", 0.9)), List.of());
        limits(0.2, 0);
        Fake fake = unified();

        recall.recallMemoryFacts(agentId, "alice", "q", null);

        assertEquals(1, fake.calls);
        assertEquals(0, fake.last.maxItems());
    }
}
