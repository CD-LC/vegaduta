package com.agenticai.core.agents.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionRateLimiter;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.agenticai.core.tenancy.demo.DemoShareService;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the anonymous demo chat surfaces. Neither {@code /api/demo/chat} nor {@code
 * /api/demo/share/{id}/chat} has a caller tenant: {@code DemoSessionService#inDemoTenantContext}
 * binds the sandbox tenant, and the turn context built INSIDE that scope must carry exactly it. The
 * stubbed scope below mirrors the real one (bind {@code demo:<sessionId>} on the sandbox schema,
 * clear afterwards).
 */
class DemoChatShareTurnContextTest {

    private final UUID agentId = UUID.randomUUID();
    private final UUID sessionUuid = UUID.randomUUID();
    private AgentChatService chat;
    private DemoSessionService sessions;
    private DemoSession session;
    private TenantContext sandboxTenant;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("hello"));
        session = mock(DemoSession.class);
        when(session.getId()).thenReturn(sessionUuid);
        when(session.isExpired(any(Instant.class))).thenReturn(false);
        when(session.isShareRevoked()).thenReturn(false);
        sandboxTenant =
                TenantContext.of(
                        "demo_schema", DemoSessionService.actorSubject(sessionUuid), Set.of());
        sessions = mock(DemoSessionService.class);
        when(sessions.messageLimit()).thenReturn(10);
        when(sessions.resolve(anyString())).thenReturn(Optional.of(session));
        when(sessions.isReadOnly(any(), any())).thenReturn(false);
        when(sessions.isMessageCapReached(session)).thenReturn(false);
        when(sessions.ensureSeededAgent(any(), any())).thenReturn(Optional.of(agentId));
        when(sessions.inDemoTenantContext(any(), any()))
                .thenAnswer(
                        inv -> {
                            TenantContext.set(sandboxTenant);
                            try {
                                return inv.getArgument(1, Supplier.class).get();
                            } finally {
                                TenantContext.clear();
                            }
                        });
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void demoChatTurnCarriesTheSandboxTenantBoundByTheScope() {
        TenantContext.clear();
        DemoChatController controller = new DemoChatController(sessions, chat);

        controller.chat("token", new DemoChatController.DemoChatRequest("hi", "s-1", null));

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(sandboxTenant, context.getValue().tenant());
        assertEquals("demo_schema", context.getValue().tenantId());
        assertEquals(agentId, context.getValue().requestedAgentId());
        assertEquals("s-1", context.getValue().sessionId());
        assertEquals("demo:" + sessionUuid, request.getValue().subjectKey());
        assertNotNull(context.getValue().requestId());
    }

    @Test
    void demoShareTurnCarriesTheSandboxTenantNotTheViewersThread() {
        // A stale tenant on the viewer's thread must not leak into the context.
        TenantContext.set(TenantContext.of("stale_tenant", "someone", Set.of()));
        DemoShareService shares = mock(DemoShareService.class);
        when(shares.resolveByShareId("share-1")).thenReturn(Optional.of(session));
        when(shares.admitViewer(any(), any())).thenReturn(DemoShareService.ViewerAdmission.NEW);
        DemoSessionRateLimiter limiter = mock(DemoSessionRateLimiter.class);
        when(limiter.tryAcquireShareChat(any())).thenReturn(true);
        ObjectProvider<ModerationService> noModeration = mock(ObjectProvider.class);
        DemoShareController controller =
                new DemoShareController(
                        shares, sessions, limiter, chat, mock(AgentRepository.class), noModeration);

        controller.chat(
                "share-1",
                new DemoShareController.ShareChatRequest("hi"),
                new MockHttpServletRequest());

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(sandboxTenant, context.getValue().tenant());
        assertEquals("demo_schema", context.getValue().tenantId());
        assertEquals(agentId, context.getValue().requestedAgentId());
        assertEquals(request.getValue().sessionId(), context.getValue().sessionId());
        assertEquals("demo:" + sessionUuid, request.getValue().subjectKey());
    }
}
