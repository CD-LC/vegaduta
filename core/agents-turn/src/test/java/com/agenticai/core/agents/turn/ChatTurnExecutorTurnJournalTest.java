package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turnjournal.InMemoryTurnJournal;
import com.agenticai.core.agents.turnjournal.TurnEvent;
import com.agenticai.core.agents.turnjournal.TurnEventType;
import com.agenticai.core.agents.turnjournal.TurnHashChain;
import com.agenticai.core.agents.turnjournal.TurnJournalGateway;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * Characterisation of {@link ChatTurnExecutor#streamFast} around the turn journal (ADR-0020): the
 * streamed reply is identical with the journal off and on; only when on are stage events written.
 */
class ChatTurnExecutorTurnJournalTest {

    private static final String PROMPT = "please summarise my confidential contract";

    @AfterEach
    void reset() {
        TurnJournalGateway.install(null, false);
    }

    private List<String> run(UUID agentId) {
        return run(
                agentId,
                TurnContext.fromCurrentThread(agentId, "s1", "req-1", java.time.Instant.now(), null));
    }

    @SuppressWarnings("unchecked")
    private List<String> run(UUID agentId, TurnContext context) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt()
                        .system(anyString())
                        .messages(anyList())
                        .user(any(Consumer.class))
                        .toolCallbacks(anyList())
                        .toolContext(org.mockito.ArgumentMatchers.anyMap())
                        .advisors(any(Consumer.class))
                        .stream()
                        .content())
                .thenReturn(Flux.just("Hello", " world"));
        ChatTurnExecutor executor =
                new ChatTurnExecutor(
                        null,
                        null,
                        null,
                        mock(com.agenticai.core.agents.memory.SessionMemoryService.class),
                        null,
                        mock(LlmQualityJudgeService.class),
                        mock(ChatGuardrailGate.class),
                        mock(ChatIssueRecorder.class),
                        mock(AgentMessageEvents.class),
                        Duration.ofSeconds(30));
        Agent agent = mock(Agent.class);
        String previous =
                com.agenticai.core.agents.memory.SessionMemoryService.bindMemorySessionId("c1:s1");
        try {
            return executor.streamFast(
                            context, agent, agentId, "s1", client, "sys", List.of(), PROMPT,
                            List.of(), List.of(), null)
                    .collectList()
                    .block();
        } finally {
            com.agenticai.core.agents.memory.SessionMemoryService.restoreMemorySessionId(previous);
        }
    }

    @Test
    void theReplyIsIdenticalWithTheJournalOffAndOn() {
        UUID agentId = UUID.randomUUID();
        List<String> off = run(agentId);

        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        TurnJournalGateway.install(journal, true);
        List<String> on = run(agentId);

        assertEquals(List.of("Hello", " world"), off);
        assertEquals(off, on);
    }

    @Test
    void nothingIsWrittenWhileDisabled() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        TurnJournalGateway.install(journal, false);
        UUID agentId = UUID.randomUUID();
        run(agentId);
        assertFalse(TurnJournalGateway.enabled());
        org.junit.jupiter.api.Assertions.assertThrows(
                java.util.NoSuchElementException.class, () -> turnIdOf(journal, agentId));
    }

    @Test
    void anEnabledTurnJournalsItsStagesInOrderWithAnIntactChainAndNoRawPrompt() {
        UUID agentId = UUID.randomUUID();
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        TurnJournalGateway.install(journal, true);
        run(agentId);

        // the only turn this journal holds
        UUID turnId = turnIdOf(journal, agentId);
        List<TurnEvent> events = journal.read(agentId, turnId);
        assertEquals(
                List.of(
                        TurnEventType.INTAKE,
                        TurnEventType.MODEL_CALL,
                        TurnEventType.GUARD,
                        TurnEventType.VERIFY,
                        TurnEventType.COMMIT),
                events.stream().map(TurnEvent::type).toList());
        assertTrue(TurnHashChain.verify(events).intact());
        assertEquals(TurnHashChain.sha256(PROMPT), events.get(0).payloadHash());
        assertFalse(events.toString().contains("confidential"));
        assertFalse(events.toString().contains("Hello"));
    }

    @Test
    void theRecordIsKeyedByTheTurnContextsOwnIdAndFoundBySession() {
        UUID agentId = UUID.randomUUID();
        InMemoryTurnJournal journal = new InMemoryTurnJournal();
        TurnJournalGateway.install(journal, true);
        TurnContext context =
                TurnContext.fromCurrentThread(agentId, "s1", "req-1", java.time.Instant.now(), null);

        run(agentId, context);

        // The id the response header names is the id of the journal record, not one made up inside the stream.
        List<TurnEvent> events = journal.read(agentId, context.turnId());
        assertEquals(TurnEventType.INTAKE, events.get(0).type());
        assertTrue(TurnHashChain.verify(events).intact());
        assertEquals(
                List.of(context.turnId()),
                journal.listBySession(agentId, "s1", 10).stream()
                        .map(com.agenticai.core.agents.turnjournal.TurnSummary::turnId)
                        .toList());
    }

    private static UUID turnIdOf(InMemoryTurnJournal journal, UUID agentId) {
        try {
            var f = InMemoryTurnJournal.class.getDeclaredField("byTurn");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<UUID, ?>) f.get(journal);
            return map.keySet().iterator().next();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
