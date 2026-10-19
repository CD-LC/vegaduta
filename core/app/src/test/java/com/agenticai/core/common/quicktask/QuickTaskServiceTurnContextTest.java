package com.agenticai.core.common.quicktask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.decision.AgentRelevanceSelector;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.omnichannel.SmsDispatchService;
import com.agenticai.core.omnichannel.VoiceCallService;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.AppUser;
import com.agenticai.core.tenancy.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

/**
 * ADR-0022 stage 4 at the Quick Task entry. {@code run} builds the turn context on the request
 * thread; {@code runStream} hops to a bounded-elastic thread and re-applies the captured tenant
 * there before building it, so the context must still carry the SAME tenant instance the request
 * thread held.
 */
class QuickTaskServiceTurnContextTest {

    private AgentChatService chat;
    private QuickTaskService service;
    private AgentResponse agent;

    @BeforeEach
    void setUp() {
        chat = mock(AgentChatService.class);
        AgentService agentService = mock(AgentService.class);
        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findByKeycloakUserId(anyString())).thenReturn(Optional.of(mock(AppUser.class)));
        Agent entity =
                new Agent(
                        "Helper",
                        "You help.",
                        ProviderName.ANTHROPIC,
                        "claude-sonnet-5",
                        1024,
                        0.7,
                        1.0,
                        false,
                        ExperienceMode.ADVANCED);
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        agent = AgentResponse.from(entity);
        when(agentService.get(any(UUID.class))).thenReturn(agent);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("done"));
        service =
                new QuickTaskService(
                        users,
                        agentService,
                        chat,
                        mock(AgentToolProvider.class),
                        mock(AgentRelevanceSelector.class),
                        mock(WhatsAppDispatchService.class),
                        mock(SmsDispatchService.class),
                        mock(VoiceCallService.class),
                        mock(HumanApprovalGateService.class),
                        mock(ToolCallLogService.class),
                        mock(QuickTaskRunService.class),
                        new ObjectMapper(),
                        mock(AuditLogService.class),
                        mock(ChannelTextSends.class));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private QuickTaskRequest request() {
        return new QuickTaskRequest("summarise this", QuickTaskChannel.LOG, UUID.randomUUID());
    }

    @Test
    void runCarriesTheRequestThreadsTenantInstance() {
        TenantContext bound = TenantContext.of("tenant_acme", "user-1", Set.of("USER"));
        TenantContext.set(bound);

        service.run("user-1", request());

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> turn = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(context.capture(), turn.capture());
        assertSame(bound, context.getValue().tenant());
        assertEquals("tenant_acme", context.getValue().tenantId());
        assertEquals(turn.getValue().sessionId(), context.getValue().sessionId());
        assertEquals("user-1", turn.getValue().subjectKey());
        assertNotNull(context.getValue().requestId());
    }

    @Test
    void runStreamReappliesTheCapturedTenantOnTheHoppedThreadBeforeBuildingTheContext() {
        TenantContext bound = TenantContext.of("tenant_acme", "user-1", Set.of("USER"));
        TenantContext.set(bound);
        AtomicReference<TenantContext> threadLocalAtChat = new AtomicReference<>();
        AtomicReference<Thread> chatThread = new AtomicReference<>();
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        inv -> {
                            threadLocalAtChat.set(TenantContext.current().orElse(null));
                            chatThread.set(Thread.currentThread());
                            return Flux.just("done");
                        });

        service.runStream("user-1", request()).blockLast(Duration.ofSeconds(10));

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(context.capture(), any(ChatTurnRequest.class));
        assertSame(bound, context.getValue().tenant());
        assertSame(threadLocalAtChat.get(), context.getValue().tenant());
        assertNotNull(chatThread.get());
        assertNotNull(context.getValue().tenantId(), "the hopped thread must not lose the tenant");
    }

    @Test
    void aThreadWithNoTenantStillRunsWithATenantLessContext() {
        TenantContext.clear();

        service.run("user-1", request());

        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        verify(chat).chat(context.capture(), any(ChatTurnRequest.class));
        assertNull(context.getValue().tenant());
    }
}
