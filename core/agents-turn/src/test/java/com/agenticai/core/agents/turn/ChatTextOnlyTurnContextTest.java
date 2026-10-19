package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at {@code chatTextOnly}, the tool-less turn that the eval-dataset replayer
 * (reflectively, from core/quality) and legacy callers use. It is the one entry that still builds
 * its context through {@code TurnContext.atEntry}; the invariant pinned here is that this carries
 * the calling thread's tenant (same instance) and suppresses the agent's tools.
 */
class ChatTextOnlyTurnContextTest {

    private final UUID agentId = UUID.randomUUID();
    private AgentChatService service;

    @BeforeEach
    void setUp() {
        // Real LegacyChatEntryPoints methods; only the pipeline entry is stubbed.
        service = mock(AgentChatService.class, CALLS_REAL_METHODS);
        doReturn(Flux.just("text"))
                .when(service)
                .chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theContextIsTheCallingThreadsTenantAndToolsAreWithheld() {
        TenantContext tenant = TenantContext.of("tenant_acme", "replayer", Set.of("USER"));
        TenantContext.set(tenant);

        service.chatTextOnly(agentId, "s-1", "subject-1", "hello").blockLast();

        ArgumentCaptor<TurnContext> ctx = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> req = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(service).chat(ctx.capture(), req.capture());
        assertSame(tenant, ctx.getValue().tenant());
        assertEquals(agentId, ctx.getValue().requestedAgentId());
        assertEquals("s-1", ctx.getValue().sessionId());
        assertTrue(req.getValue().suppressAgentTools());
    }

    @Test
    void aThreadWithoutATenantBuildsATenantlessContext() {
        service.chatTextOnly(agentId, "s-1", "subject-1", "hello").blockLast();

        ArgumentCaptor<TurnContext> ctx = ArgumentCaptor.forClass(TurnContext.class);
        verify(service).chat(ctx.capture(), any(ChatTurnRequest.class));
        assertNull(ctx.getValue().tenant());
    }
}
