package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Post-blackout-audit fix verification (see {@code AgentEventTriggerDispatcher}'s javadoc). A
 * workflow bound to WHATSAPP must NOT silently suppress this Twilio-connected channel's
 * agent-event-trigger publish, and vice versa - both must fire for the same paired-sender inbound
 * message when a workflow binding exists.
 */
class TwilioWebhookControllerTriggerAndAgentEventBothFireTest {

    private static final String ACCOUNT_SID = "AC12345678901234567890123456789012";

    private WorkflowEventPublisher workflowEventPublisher;
    private WorkflowTriggerPort workflowTriggerPort;
    private AgentChatService agentChatService;
    private TwilioWebhookController controller;

    @BeforeEach
    void setUp() {
        TwilioSignatureValidator signatureValidator = mock(TwilioSignatureValidator.class);
        TwilioCredentialService credentialService = mock(TwilioCredentialService.class);
        ChannelSessionService channelSessionService = mock(ChannelSessionService.class);
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        InboundMessageDedupService dedupService = mock(InboundMessageDedupService.class);
        WorkflowTriggerNodeService workflowTriggerNodeService =
                mock(WorkflowTriggerNodeService.class);
        workflowEventPublisher = mock(WorkflowEventPublisher.class);
        workflowTriggerPort = mock(WorkflowTriggerPort.class);
        agentChatService = mock(AgentChatService.class);

        controller =
                new TwilioWebhookController(
                        signatureValidator,
                        credentialService,
                        channelSessionService,
                        agentChatService,
                        mock(HumanApprovalGateService.class),
                        tenantResolutionService,
                        workflowEventPublisher,
                        workflowTriggerNodeService,
                        workflowTriggerPort,
                        mock(AuditLogService.class),
                        dedupService,
                        com.agenticai.core.omnichannel.session.InboundChannelExecutor
                                .synchronous());

        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.TWILIO, ACCOUNT_SID))
                .thenReturn(Optional.of("tenant_test"));
        when(credentialService.resolveActive())
                .thenReturn(
                        new TwilioCredentialService.DecryptedCredential(
                                ACCOUNT_SID,
                                "auth-token",
                                "api-key-sid",
                                "api-key-secret",
                                "+15550009999"));
        when(dedupService.tryClaim(eq(ChannelType.WHATSAPP), any())).thenReturn(true);

        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn("+15550001111");
        when(channelSessionService.resolveOrCreate(
                        eq(ChannelType.WHATSAPP), anyString(), anyString()))
                .thenReturn(session);

        when(workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.WHATSAPP))
                .thenReturn(
                        Optional.of(
                                new WorkflowTriggerNodeService.TriggerBinding(
                                        UUID.randomUUID(), "node-1")));
        when(workflowTriggerNodeService.tryAcquireTriggerSlot(
                        eq(WorkflowTriggerChannel.WHATSAPP), anyString()))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void bothTheWorkflowTriggerAndTheAgentEventPublishFireForTheSameMessage() {
        Map<String, String> formParams =
                Map.of(
                        "AccountSid", ACCOUNT_SID,
                        "MessageSid", "SM12345678901234567890123456789012",
                        "From", "whatsapp:+15550001111",
                        "Body", "hello");
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);

        ResponseEntity<String> response = controller.inboundWhatsApp(formParams, servletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workflowEventPublisher, times(1))
                .publishNewRun(
                        eq(WorkflowEventTypes.CHANNEL_WHATSAPP_MESSAGE_RECEIVED), any(Map.class));
        verify(workflowTriggerPort, times(1))
                .triggerAsync(
                        any(UUID.class),
                        eq("node-1"),
                        eq("hello"),
                        anyString(),
                        any(InboundTriggerContext.class));
        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }
}
