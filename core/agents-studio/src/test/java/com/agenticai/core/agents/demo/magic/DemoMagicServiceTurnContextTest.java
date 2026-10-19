package com.agenticai.core.agents.demo.magic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.agentspi.HostedImageModel;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.agenticai.core.tenancy.demo.DemoImageService;
import com.agenticai.core.tenancy.demo.DemoMagicWorkflow;
import com.agenticai.core.tenancy.demo.DemoMagicWorkflowRepository;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * ADR-0022 stage 4 at the demo "magic" run: the turn context is built inside {@code
 * DemoSessionService#inDemoTenantContext} and must carry the sandbox tenant that scope bound (the
 * public caller has none of its own).
 */
class DemoMagicServiceTurnContextTest {

    private final UUID agentId = UUID.randomUUID();
    private final UUID sessionUuid = UUID.randomUUID();
    private AgentChatService chat;
    private DemoMagicService service;
    private DemoSession session;
    private DemoMagicWorkflow workflow;
    private TenantContext sandboxTenant;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chat = mock(AgentChatService.class);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("hello"));
        sandboxTenant =
                TenantContext.of(
                        "demo_schema", DemoSessionService.actorSubject(sessionUuid), Set.of());
        DemoSessionService sessions = mock(DemoSessionService.class);
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
        service =
                new DemoMagicService(
                        mock(DemoMagicWorkflowRepository.class),
                        mock(DemoMagicPlanner.class),
                        sessions,
                        mock(DemoImageService.class),
                        mock(HostedImageModel.class),
                        chat,
                        mock(AgentService.class),
                        mock(PlatformSettingsService.class),
                        mock(SafePublicUrlFetcher.class),
                        mock(ObjectProvider.class),
                        new ObjectMapper(),
                        "");
        session = mock(DemoSession.class);
        when(session.getId()).thenReturn(sessionUuid);
        workflow = mock(DemoMagicWorkflow.class);
        when(workflow.getId()).thenReturn(UUID.randomUUID());
        when(workflow.getIntent()).thenReturn("TEXT");
        when(workflow.getAgentId()).thenReturn(agentId);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theTurnContextCarriesTheSandboxTenantBoundByTheScope() {
        // The public runner's own thread holds no tenant.
        TenantContext.clear();

        DemoMagicService.RunResult result =
                service.execute(session, workflow, "write a tagline", null, "203.0.113.9");

        assertFalse(result.simulated(), "the turn must run, not degrade to the fallback");
        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> request = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), request.capture());
        assertSame(sandboxTenant, context.getValue().tenant());
        assertEquals("demo_schema", context.getValue().tenantId());
        assertEquals(agentId, context.getValue().requestedAgentId());
        assertEquals(request.getValue().sessionId(), context.getValue().sessionId());
        assertEquals("demo:" + sessionUuid, request.getValue().subjectKey());
        assertNotNull(context.getValue().requestId());
    }
}
