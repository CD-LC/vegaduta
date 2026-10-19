package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.ChatRequest;
import com.agenticai.core.security.TenantContext;
import java.security.Principal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the HTTP entry: {@code POST /api/agents/{agentId}/chat} builds the turn
 * context itself from the tenant {@code TenantContextFilter} put on the request thread, and passes
 * it to {@code AgentChatService#chat(TurnContext, ChatTurnRequest)}. The invariant the migration
 * relies on: on the request thread, the context IS the ThreadLocal value (same instance).
 */
class AgentChatStreamControllerTurnContextTest {

    private final UUID agentId = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theControllerBuildsTheTurnContextEqualToTheRequestThreadTenant() {
        AgentChatService chatService = mock(AgentChatService.class);
        AtomicReference<TurnContext> passed = new AtomicReference<>();
        AtomicReference<ChatTurnRequest> request = new AtomicReference<>();
        AtomicReference<TenantContext> threadLocalAtEntry = new AtomicReference<>();
        AtomicReference<Thread> entryThread = new AtomicReference<>();
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        inv -> {
                            passed.set(inv.getArgument(0));
                            request.set(inv.getArgument(1));
                            threadLocalAtEntry.set(TenantContext.current().orElse(null));
                            entryThread.set(Thread.currentThread());
                            return Flux.just("ok");
                        });
        AgentChatStreamController controller =
                new AgentChatStreamController(
                        mock(AgentService.class), chatService, Duration.ofSeconds(15));
        // What TenantContextFilter does for an authenticated request.
        TenantContext filterSet = TenantContext.of("tenant_acme", "user-1", Set.of("USER"));
        TenantContext.set(filterSet);
        Principal principal = () -> "user-1";

        controller
                .chat(
                        agentId,
                        new ChatRequest(
                                "session-1", "hi", null, null, null, null, null, false, false),
                        principal,
                        null)
                .getBody()
                .collectList()
                .block(Duration.ofSeconds(5));

        TurnContext context = passed.get();
        assertNotNull(context, "the controller must call chat(TurnContext, ChatTurnRequest)");
        assertSame(
                Thread.currentThread(), entryThread.get(), "the entry runs on the request thread");
        assertSame(filterSet, context.tenant(), "the context carries the filter's tenant");
        assertSame(
                threadLocalAtEntry.get(),
                context.tenant(),
                "on the request thread the context equals the ThreadLocal value");
        assertEquals("tenant_acme", context.tenantId());
        assertEquals(agentId, context.requestedAgentId());
        assertEquals("session-1", context.sessionId());
        assertEquals(request.get().sessionId(), context.sessionId());
        assertNotNull(context.requestId());
    }
}
