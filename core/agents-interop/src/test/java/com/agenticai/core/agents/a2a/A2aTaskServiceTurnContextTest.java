package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the A2A entry: {@link A2aTaskService#run} builds the turn context from the
 * tenant bound on the calling thread and hands it to {@code chat(TurnContext, ChatTurnRequest)}.
 */
class A2aTaskServiceTurnContextTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private AgentChatService chat;
    private A2aTaskService service;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("ok"));
        A2aProperties properties = new A2aProperties();
        properties.setBlockingTimeout(Duration.ofSeconds(5));
        service =
                new A2aTaskService(
                        chat,
                        new A2aTaskStore(Duration.ofHours(1), 100),
                        mock(AuditLogService.class),
                        properties);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theTurnContextCarriesTheCallingThreadsTenantInstance() throws Exception {
        TenantContext bound = TenantContext.of("tenant-a", "alice", Set.of("USER"));
        TenantContext.set(bound);

        A2aTask task = service.create(AGENT_ID, "ctx-1", "msg-1", "hello", () -> "alice");
        service.run(task, null).get(5, TimeUnit.SECONDS);

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(bound, context.getValue().tenant());
        assertEquals("tenant-a", context.getValue().tenantId());
        assertEquals(AGENT_ID, context.getValue().requestedAgentId());
        assertEquals("ctx-1", context.getValue().sessionId());
        assertEquals(request.getValue().sessionId(), context.getValue().sessionId());
        assertNotNull(context.getValue().requestId());
    }

    @Test
    void aThreadWithNoTenantYieldsATenantLessContextAndTheTurnStillRuns() throws Exception {
        TenantContext.clear();

        A2aTask task = service.create(AGENT_ID, "ctx-2", "msg-2", "hello", () -> "bob");
        service.run(task, null).get(5, TimeUnit.SECONDS);

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(context.capture(), any(ChatTurnRequest.class));
        assertNull(context.getValue().tenant());
        assertNull(context.getValue().tenantId());
    }
}
