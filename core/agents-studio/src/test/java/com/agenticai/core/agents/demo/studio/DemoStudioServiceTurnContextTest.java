package com.agenticai.core.agents.demo.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the /try Agent Studio: both mission entries (streaming {@code runMission} and
 * the blocking workflow-step variant) build the turn context inside {@code inDemoTenantContextAs},
 * so it must carry the sandbox tenant that scope bound, not whatever the public caller's thread
 * held.
 */
class DemoStudioServiceTurnContextTest {

    private final UUID sessionUuid = UUID.randomUUID();
    private AgentChatService chat;
    private DemoStudioService studio;
    private DemoSession me;
    private Agent agent;
    private TenantContext sandboxTenant;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("done"));
        sandboxTenant =
                TenantContext.of(
                        "demo_schema", DemoSessionService.actorSubject(sessionUuid), Set.of());
        DemoSessionService sessions = mock(DemoSessionService.class);
        when(sessions.inDemoTenantContext(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1, Supplier.class).get());
        when(sessions.inDemoTenantContextAs(any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            TenantContext.set(sandboxTenant);
                            try {
                                return inv.getArgument(2, Supplier.class).get();
                            } finally {
                                TenantContext.clear();
                            }
                        });
        AgentRepository agents = mock(AgentRepository.class);
        ObjectProvider<DemoStudioDocumentService> documents = mock(ObjectProvider.class);
        ObjectProvider<DemoStudioConnectorService> connectors = mock(ObjectProvider.class);
        studio =
                new DemoStudioService(
                        sessions,
                        mock(AgentService.class),
                        agents,
                        mock(AgentGuardrailService.class),
                        mock(AgentToolPort.class),
                        chat,
                        2,
                        3,
                        documents,
                        connectors,
                        false,
                        120,
                        mock(DemoStudioModerationService.class));
        me = mock(DemoSession.class);
        when(me.getId()).thenReturn(sessionUuid);
        agent = mock(Agent.class);
        when(agent.getId()).thenReturn(UUID.randomUUID());
        when(agent.getCreatedBy()).thenReturn(DemoSessionService.actorSubject(sessionUuid));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void assertCarriesTheSandboxTenant(String chatSessionId) {
        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(sandboxTenant, context.getValue().tenant());
        assertEquals("demo_schema", context.getValue().tenantId());
        assertEquals(agent.getId(), context.getValue().requestedAgentId());
        assertEquals(chatSessionId, context.getValue().sessionId());
        assertEquals(DemoSessionService.actorSubject(sessionUuid), request.getValue().subjectKey());
        assertNotNull(context.getValue().requestId());
    }

    @Test
    void runMissionCarriesTheSandboxTenantBoundByTheScope() {
        TenantContext.clear();

        studio.runMission(me, agent.getId(), "plan my week", "chat-1");

        assertCarriesTheSandboxTenant("chat-1");
    }

    @Test
    void runMissionBlockingCarriesTheSandboxTenantBoundByTheScope() {
        TenantContext.set(TenantContext.of("stale_tenant", "someone", Set.of()));

        String reply = studio.runMissionBlocking(me, agent.getId(), "step one", "chat-2", null);

        assertEquals("done", reply);
        assertCarriesTheSandboxTenant("chat-2");
    }
}
