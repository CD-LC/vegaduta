package com.agenticai.core.agents.resumable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.ChatRequest;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.AgentChatStreamController;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * The flag-off identity at the controller seam: without the flag, without the opt-in header, or
 * with no service wired (every slim test context), {@code POST /api/agents/{id}/chat} returns
 * exactly the Flux of plain String chunks it always did, with no turn id and no event ids. Only
 * flag + header switches to the resumable framing.
 */
class AgentControllerResumableStreamsTest {

    private final UUID agentId = UUID.randomUUID();
    private AgentService agentService;
    private AgentChatService agentChatService;
    private AgentChatStreamController controller;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        agentChatService = mock(AgentChatService.class);
        when(agentChatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("Hello", " ", "world"));
        controller =
                new AgentChatStreamController(
                        agentService, agentChatService, Duration.ofSeconds(15));
    }

    private ChatRequest request() {
        return new ChatRequest("session-1", "hi", null, null, null, null, null, false, false);
    }

    private ResumableChatStreamService service(boolean enabled) {
        return new ResumableChatStreamService(
                enabled,
                new InMemoryResumableStreamBuffer(100, 600, 900, System::currentTimeMillis),
                agentService,
                Duration.ofMinutes(10),
                System::currentTimeMillis);
    }

    private List<Object> drain(ResponseEntity<? extends Flux<?>> response) {
        return List.<Object>copyOf(response.getBody().collectList().block(Duration.ofSeconds(5)));
    }

    @Test
    void noServiceWiredReturnsThePlainStringStream() {
        ResponseEntity<Flux<String>> response = controller.chat(agentId, request(), null, "1");

        assertEquals(
                "session-1", response.getHeaders().getFirst(AgentController.SESSION_ID_HEADER));
        assertNull(response.getHeaders().getFirst(ResumableChatStreamService.TURN_ID_HEADER));
        assertEquals(List.of("Hello", " ", "world"), drain(response));
    }

    @Test
    void flagOffIgnoresTheOptInHeader() {
        controller.setResumableChatStreams(service(false));

        ResponseEntity<Flux<String>> response = controller.chat(agentId, request(), null, "1");

        assertNull(response.getHeaders().getFirst(ResumableChatStreamService.TURN_ID_HEADER));
        assertEquals(List.of("Hello", " ", "world"), drain(response));
    }

    @Test
    void flagOnWithoutTheHeaderIsStillThePlainStream() {
        controller.setResumableChatStreams(service(true));

        ResponseEntity<Flux<String>> response = controller.chat(agentId, request(), null, null);

        assertNull(response.getHeaders().getFirst(ResumableChatStreamService.TURN_ID_HEADER));
        assertEquals(List.of("Hello", " ", "world"), drain(response));
    }

    @Test
    void flagOnWithTheHeaderDetachesAndFramesEveryEventWithAnId() {
        controller.setResumableChatStreams(service(true));

        ResponseEntity<Flux<String>> response = controller.chat(agentId, request(), null, "1");

        assertNotNull(response.getHeaders().getFirst(ResumableChatStreamService.TURN_ID_HEADER));
        assertEquals(
                "session-1", response.getHeaders().getFirst(AgentController.SESSION_ID_HEADER));
        List<Object> events = drain(response);
        assertEquals(4, events.size());
        ServerSentEvent<?> first = assertInstanceOf(ServerSentEvent.class, events.get(0));
        assertEquals("1", first.id());
        assertEquals("Hello", first.data());
        ServerSentEvent<?> last = assertInstanceOf(ServerSentEvent.class, events.get(3));
        assertEquals(ResumableChatStreamService.TURN_END_EVENT, last.event());
    }
}
