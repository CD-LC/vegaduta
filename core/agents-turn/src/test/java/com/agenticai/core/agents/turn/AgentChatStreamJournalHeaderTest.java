package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.ChatRequest;
import com.agenticai.core.agents.turnjournal.InMemoryTurnJournal;
import com.agenticai.core.agents.turnjournal.TurnJournalGateway;
import java.security.Principal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

/**
 * The chat response names the journal record of the turn it carries ({@code X-Journal-Turn-Id}), and only while the
 * journal is on: the id is the one on the turn's own {@link TurnContext}, so the client can open the turn it just ran
 * without knowing anything else.
 */
class AgentChatStreamJournalHeaderTest {

    @AfterEach
    void reset() {
        TurnJournalGateway.install(null, false);
    }

    private ResponseEntity<Flux<String>> respond(AtomicReference<TurnContext> passed) {
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        inv -> {
                            passed.set(inv.getArgument(0));
                            return Flux.just("ok");
                        });
        AgentChatStreamController controller =
                new AgentChatStreamController(mock(AgentService.class), chatService, Duration.ofSeconds(15));
        Principal principal = () -> "user-1";
        return controller.chat(
                UUID.randomUUID(),
                new ChatRequest("session-1", "hi", null, null, null, null, null, false, false),
                principal,
                null);
    }

    @Test
    void theHeaderCarriesTheTurnsOwnIdWhileTheJournalIsOn() {
        TurnJournalGateway.install(new InMemoryTurnJournal(), true);
        AtomicReference<TurnContext> passed = new AtomicReference<>();

        ResponseEntity<Flux<String>> response = respond(passed);

        assertNotNull(passed.get());
        assertEquals(
                passed.get().turnId().toString(),
                response.getHeaders().getFirst(TurnJournalGateway.TURN_ID_HEADER));
    }

    @Test
    void thereIsNoHeaderWhileTheJournalIsOff() {
        TurnJournalGateway.install(new InMemoryTurnJournal(), false);

        assertNull(respond(new AtomicReference<>()).getHeaders().getFirst(TurnJournalGateway.TURN_ID_HEADER));
    }
}
