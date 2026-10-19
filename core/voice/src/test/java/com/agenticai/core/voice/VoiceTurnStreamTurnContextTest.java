package com.agenticai.core.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.voice.VoiceCallController.TranscriptionOutcome;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ADR-0022 stage 4 at the push-to-talk voice entry: {@code VoiceTurnStream#stream} builds the turn
 * context from the request thread's tenant, and keeps the push-to-talk fast-depth flag.
 */
class VoiceTurnStreamTurnContextTest {

    private final UUID agentId = UUID.randomUUID();
    private AgentChatService chat;
    private VoiceTurnStream stream;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("Hello there."));
        VoiceTurnSpeech speech = mock(VoiceTurnSpeech.class);
        when(speech.synthesizeChunk(any(), any(), any(TenantScope.class))).thenReturn(Mono.empty());
        stream = new VoiceTurnStream(chat, speech);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void drive() {
        var events =
                stream.stream(agentId, "voice-1", "caller-1", new TranscriptionOutcome("hi", "stt"))
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertTrue(events != null && !events.isEmpty());
    }

    private TurnContext capturedContext(ArgumentCaptor<ChatTurnRequest> request) {
        ArgumentCaptor<TurnContext> ctx = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(ctx.capture(), request.capture());
        return ctx.getValue();
    }

    @Test
    void theContextIsTheRequestThreadsTenant() {
        TenantContext tenant = TenantContext.of("tenant_acme", "user-1", Set.of("USER"));
        TenantContext.set(tenant);

        drive();

        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        TurnContext context = capturedContext(request);
        assertSame(tenant, context.tenant());
        assertEquals(agentId, context.requestedAgentId());
        assertEquals("voice-1", context.sessionId());
        assertTrue(request.getValue().forceFastDepth(), "push-to-talk stays a FAST turn");
    }

    @Test
    void aThreadWithoutATenantBuildsATenantlessContext() {
        drive();

        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        assertNull(capturedContext(request).tenant());
    }
}
