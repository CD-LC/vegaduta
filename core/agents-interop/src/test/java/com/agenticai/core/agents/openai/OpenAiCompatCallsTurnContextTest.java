package com.agenticai.core.agents.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 for the OpenAI-compatible surface: {@code /v1/chat/completions} and {@code
 * /v1/responses} both run their turn through {@link OpenAiCompatCalls#runAgent}.
 */
class OpenAiCompatCallsTurnContextTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private AgentChatService chat;
    private OpenAiCompatCalls calls;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("ok"));
        calls =
                new OpenAiCompatCalls(
                        mock(AgentService.class),
                        chat,
                        mock(OpenAiCompatRateLimiter.class),
                        16000,
                        5,
                        5,
                        mock(ObjectProvider.class));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theTurnContextCarriesTheRequestThreadsTenantInstance() {
        TenantContext bound = TenantContext.of("tenant-a", "user-1", Set.of("USER"));
        TenantContext.set(bound);

        calls.runAgent(
                AGENT_ID,
                "oai-1",
                new OpenAiCompatCalls.Caller("user-1", "tenant-a", Optional.empty()),
                "hi");

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(bound, context.getValue().tenant());
        assertEquals(AGENT_ID, context.getValue().requestedAgentId());
        assertEquals("oai-1", context.getValue().sessionId());
        assertEquals("user-1", request.getValue().subjectKey());
        assertNotNull(context.getValue().requestId());
    }

    @Test
    void aThreadWithNoTenantYieldsATenantLessContext() {
        TenantContext.clear();

        calls.runAgent(
                AGENT_ID,
                "oai-2",
                new OpenAiCompatCalls.Caller("user-1", "none", Optional.empty()),
                "hi");

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(context.capture(), any(ChatTurnRequest.class));
        assertNull(context.getValue().tenant());
    }
}
