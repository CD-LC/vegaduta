package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.graphmemory.EntityExtractionService;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.graphmemory.GraphTriple;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/** G6: an approved fact is offered to the graph, best-effort and fail-open, carrying its memory id. */
class ApprovedFactGraphSyncTest {

    private final UUID agentId = UUID.randomUUID();
    private final UUID memoryId = UUID.randomUUID();
    private final GraphMemoryService graph = mock(GraphMemoryService.class);
    private final EntityExtractionService extraction = mock(EntityExtractionService.class);
    private final MemoryExtractionChatClients clients = mock(MemoryExtractionChatClients.class);
    private final ChatClient chat = mock(ChatClient.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final GraphTriple triple = new GraphTriple("Priya", "MANAGER_OF_BY", "Raj");

    @BeforeEach
    void setUp() {
        when(clients.forBackgroundExtraction()).thenReturn(chat);
        when(extraction.extractTriples(any(), anyString(), any())).thenReturn(List.of(triple));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private ApprovedFactGraphSync sync(GraphMemoryService g, EntityExtractionService e, MemoryExtractionChatClients c) {
        return new ApprovedFactGraphSync(provider(g), provider(e), provider(c), provider(registry));
    }

    private double count(String scope, String outcome) {
        var counter = registry.find(ApprovedFactGraphSync.METRIC).tag("scope", scope).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void anApprovedAgentFactIsWrittenToTheAgentTierWithItsMemoryId() {
        var outcome = sync(graph, extraction, clients).agentFactApproved(agentId, "alice", memoryId, "Priya's manager is Raj");

        assertThat(outcome).isEqualTo(ApprovedFactGraphSync.Outcome.WRITTEN);
        verify(extraction).extractTriples(chat, "Priya's manager is Raj", agentId);
        verify(graph).remember(agentId, "alice", List.of(triple), memoryId);
        assertThat(count("agent", "written")).isEqualTo(1);
    }

    @Test
    void anApprovedSharedFactIsWrittenToTheSharedTierWithItsMemoryId() {
        var outcome = sync(graph, extraction, clients).sharedFactApproved(agentId, "alice", memoryId, "Priya's manager is Raj");

        assertThat(outcome).isEqualTo(ApprovedFactGraphSync.Outcome.WRITTEN);
        verify(graph).rememberShared("alice", List.of(triple), memoryId);
        verify(graph, never()).remember(any(), anyString(), anyList(), any());
        assertThat(count("shared", "written")).isEqualTo(1);
    }

    @Test
    void withoutGraphMemoryOrAClientNothingHappensAndNothingThrows() {
        assertThat(sync(null, extraction, clients).agentFactApproved(agentId, "a", memoryId, "x"))
                .isEqualTo(ApprovedFactGraphSync.Outcome.SKIPPED);
        assertThat(sync(graph, null, clients).agentFactApproved(agentId, "a", memoryId, "x"))
                .isEqualTo(ApprovedFactGraphSync.Outcome.SKIPPED);
        assertThat(sync(graph, extraction, null).agentFactApproved(agentId, "a", memoryId, "x"))
                .isEqualTo(ApprovedFactGraphSync.Outcome.SKIPPED);
        assertThat(sync(graph, extraction, clients).agentFactApproved(agentId, "a", memoryId, "  "))
                .isEqualTo(ApprovedFactGraphSync.Outcome.SKIPPED);
        verify(graph, never()).remember(any(), anyString(), anyList(), any());
        assertThat(count("agent", "skipped")).isEqualTo(4);
    }

    @Test
    void noTriplesMeansNoGraphWrite() {
        when(extraction.extractTriples(any(), anyString(), any())).thenReturn(List.of());
        assertThat(sync(graph, extraction, clients).agentFactApproved(agentId, "a", memoryId, "hello"))
                .isEqualTo(ApprovedFactGraphSync.Outcome.NO_TRIPLES);
        verify(graph, never()).remember(any(), anyString(), anyList(), any());
        assertThat(count("agent", "no_triples")).isEqualTo(1);
    }

    @Test
    void noUsableProviderIsAFailedSyncNotAnException() {
        when(clients.forBackgroundExtraction()).thenThrow(new IllegalStateException("no provider"));
        assertThat(sync(graph, extraction, clients).sharedFactApproved(agentId, "a", memoryId, "fact"))
                .isEqualTo(ApprovedFactGraphSync.Outcome.FAILED);
        assertThat(count("shared", "failed")).isEqualTo(1);
    }

    @Test
    void aGraphWriteErrorIsSwallowedAndCounted() {
        doThrow(new IllegalStateException("age down")).when(graph).remember(eq(agentId), anyString(), anyList(), eq(memoryId));
        assertThat(sync(graph, extraction, clients).agentFactApproved(agentId, "a", memoryId, "fact"))
                .isEqualTo(ApprovedFactGraphSync.Outcome.FAILED);
        assertThat(count("agent", "failed")).isEqualTo(1);
    }

    @Test
    void aMissingMeterRegistryIsFine() {
        var s = new ApprovedFactGraphSync(provider(graph), provider(extraction), provider(clients), provider((MeterRegistry) null));
        assertThat(s.agentFactApproved(agentId, "a", memoryId, "fact")).isEqualTo(ApprovedFactGraphSync.Outcome.WRITTEN);
    }
}
