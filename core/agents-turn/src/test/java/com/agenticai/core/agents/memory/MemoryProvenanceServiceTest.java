package com.agenticai.core.agents.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@SuppressWarnings("unchecked")
class MemoryProvenanceServiceTest {

    private final AgentMemoryService agentMemory = mock(AgentMemoryService.class);
    private final TenantMemoryService tenantMemory = mock(TenantMemoryService.class);
    private final ObjectProvider<AgentMemoryService> agentProvider = mock(ObjectProvider.class);
    private final ObjectProvider<TenantMemoryService> tenantProvider = mock(ObjectProvider.class);
    private final ToolCallLogRepository repository = mock(ToolCallLogRepository.class);
    private final UUID agentId = UUID.randomUUID();
    private final UUID factId = UUID.randomUUID();
    private final Instant createdAt = Instant.parse("2026-10-01T10:00:00Z");
    private MemoryProvenanceService service;

    @BeforeEach
    void setUp() {
        when(agentProvider.getIfAvailable()).thenReturn(agentMemory);
        when(tenantProvider.getIfAvailable()).thenReturn(tenantMemory);
        service = new MemoryProvenanceService(agentProvider, tenantProvider, repository);
    }

    private ToolCallLog call(
            String tool,
            String session,
            String input,
            String output,
            String error,
            Instant startedAt) {
        return new ToolCallLog(
                agentId, session, tool, input, output, error, startedAt, startedAt.plusMillis(50));
    }

    private void ownsAgentFact(String content) {
        when(agentMemory.listAllForSubject("alice"))
                .thenReturn(
                        List.of(
                                new AgentMemoryEntry(
                                        factId, agentId, "alice", content, 0.0, createdAt,
                                        createdAt, 0)));
    }

    @Test
    void matchesTheCallWhoseOutputNamesTheFactId() {
        ownsAgentFact("Prefers evening calls");
        ToolCallLog other =
                call(
                        "remember_fact",
                        "s-other",
                        "{\"fact\":\"Prefers evening calls\"}",
                        "{\"remembered\": true, \"memoryId\": \"" + UUID.randomUUID() + "\"}",
                        null,
                        createdAt.minusSeconds(1));
        ToolCallLog writer =
                call(
                        "remember_fact",
                        "s-1",
                        "{\"fact\":\"the original wording\"}",
                        "{\"remembered\": true, \"memoryId\": \"" + factId + "\"}",
                        null,
                        createdAt.minusSeconds(40));
        when(repository.findByAgentIdAndToolNameInAndStartedAtBetweenOrderByStartedAtAsc(
                        eq(agentId),
                        eq(List.of("remember_fact")),
                        eq(createdAt.minusSeconds(120)),
                        eq(createdAt.plusSeconds(120))))
                .thenReturn(List.of(writer, other));

        MemoryProvenanceService.FactSource source =
                service.sourceOf("agent", factId, "alice").orElseThrow();
        assertTrue(source.matched());
        assertEquals("s-1", source.sessionId());
        assertEquals(agentId, source.agentId());
        assertEquals(createdAt.minusSeconds(40), source.learnedAt());
    }

    @Test
    void aTextOnlyMatchIsNeverReportedBecauseItCouldBeAnotherUsersSession() {
        ownsAgentFact("Prefers evening calls");
        // Same agent, same text, inside the window - but the output names a different fact (someone
        // else's).
        ToolCallLog someoneElses =
                call(
                        "remember_fact",
                        "s-other-user",
                        "{\"fact\":\"Prefers evening calls\"}",
                        "{\"remembered\": true, \"memoryId\": \"" + UUID.randomUUID() + "\"}",
                        null,
                        createdAt.minusSeconds(2));
        ToolCallLog noOutput =
                call(
                        "remember_fact",
                        "s-no-output",
                        "{\"fact\":\"Prefers evening calls\"}",
                        null,
                        null,
                        createdAt);
        when(repository.findByAgentIdAndToolNameInAndStartedAtBetweenOrderByStartedAtAsc(
                        any(), any(), any(), any()))
                .thenReturn(List.of(someoneElses, noOutput));

        MemoryProvenanceService.FactSource source =
                service.sourceOf("agent", factId, "alice").orElseThrow();
        assertFalse(source.matched());
        assertNull(source.sessionId());
    }

    @Test
    void noMatchingCallIsUnmatchedNotAnError() {
        ownsAgentFact("Prefers evening calls");
        ToolCallLog differentFact =
                call("remember_fact", "s-1", "{\"fact\":\"Has two dogs\"}", "{}", null, createdAt);
        ToolCallLog failed =
                call(
                        "remember_fact",
                        "s-2",
                        "{\"fact\":\"Prefers evening calls\"}",
                        "{\"memoryId\": \"" + factId + "\"}",
                        "boom",
                        createdAt);
        when(repository.findByAgentIdAndToolNameInAndStartedAtBetweenOrderByStartedAtAsc(
                        any(), any(), any(), any()))
                .thenReturn(List.of(differentFact, failed));

        MemoryProvenanceService.FactSource source =
                service.sourceOf("agent", factId, "alice").orElseThrow();
        assertFalse(source.matched());
        assertNull(source.sessionId());
        assertEquals(agentId, source.agentId());
        assertEquals(createdAt, source.learnedAt());
    }

    @Test
    void repositoryFailureIsUnmatchedNotAnError() {
        ownsAgentFact("Prefers evening calls");
        when(repository.findByAgentIdAndToolNameInAndStartedAtBetweenOrderByStartedAtAsc(
                        any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        assertFalse(service.sourceOf("agent", factId, "alice").orElseThrow().matched());
    }

    @Test
    void aFactThatIsNotTheCallersIsEmptyAndNeverQueriesTheLog() {
        when(agentMemory.listAllForSubject("mallory")).thenReturn(List.of());
        Optional<MemoryProvenanceService.FactSource> source =
                service.sourceOf("agent", factId, "mallory");
        assertTrue(source.isEmpty());
        verify(repository, never())
                .findByAgentIdAndToolNameInAndStartedAtBetweenOrderByStartedAtAsc(
                        any(), any(), any(), any());
    }

    @Test
    void sharedFactUsesTheSharedToolAndAHandAddedOneIsUnmatchedWithoutAQuery() {
        UUID handAdded = UUID.randomUUID();
        when(tenantMemory.listFactsForSubject("alice"))
                .thenReturn(
                        List.of(
                                new TenantMemoryEntry(
                                        factId,
                                        "alice",
                                        "Works at Acme",
                                        0.0,
                                        agentId,
                                        createdAt,
                                        createdAt,
                                        0),
                                new TenantMemoryEntry(
                                        handAdded,
                                        "alice",
                                        "Typed in by hand",
                                        0.0,
                                        null,
                                        createdAt,
                                        createdAt,
                                        0)));
        ToolCallLog writer =
                call(
                        "remember_shared_fact",
                        "s-9",
                        "{\"fact\":\"Works at Acme\"}",
                        "{\"remembered\": true, \"shared\": true, \"memoryId\": \""
                                + factId
                                + "\"}",
                        null,
                        createdAt);
        when(repository.findByAgentIdAndToolNameInAndStartedAtBetweenOrderByStartedAtAsc(
                        eq(agentId), eq(List.of("remember_shared_fact")), any(), any()))
                .thenReturn(List.of(writer));

        assertEquals("s-9", service.sourceOf("shared", factId, "alice").orElseThrow().sessionId());

        MemoryProvenanceService.FactSource manual =
                service.sourceOf("shared", handAdded, "alice").orElseThrow();
        assertFalse(manual.matched());
        assertNull(manual.agentId());
        verify(repository, never())
                .findByAgentIdAndToolNameInAndStartedAtBetweenOrderByStartedAtAsc(
                        eq(null), any(), any(), any());
    }

    @Test
    void unknownScopeIsRejectedAndMissingMemoryServiceIsEmpty() {
        assertThrows(
                IllegalArgumentException.class, () -> service.sourceOf("bogus", factId, "alice"));
        when(agentProvider.getIfAvailable()).thenReturn(null);
        assertTrue(service.sourceOf("agent", factId, "alice").isEmpty());
    }

    @Test
    void bestMatchHandlesEmptyInputAndMatchesOnTheIdOnly() {
        assertNull(MemoryProvenanceService.bestMatch(List.of(), factId));
        assertNull(MemoryProvenanceService.bestMatch(null, factId));
        ToolCallLog textOnly =
                call("remember_fact", "s-raw", "fact=Prefers evening calls", "ok", null, createdAt);
        ToolCallLog byId =
                call("remember_fact", "s-id", "{}", "memoryId=" + factId, null, createdAt);
        assertNull(MemoryProvenanceService.bestMatch(List.of(textOnly), factId));
        assertNull(MemoryProvenanceService.bestMatch(List.of(byId), null));
        assertSame(byId, MemoryProvenanceService.bestMatch(List.of(textOnly, byId), factId));
    }
}
