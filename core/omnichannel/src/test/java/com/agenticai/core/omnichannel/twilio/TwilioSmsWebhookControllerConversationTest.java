package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.omnichannel.relay.EdgeRelayHandoff;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

/**
 * SMS as a conversation channel (2026-10-14): a paired sender's text reaches the default agent and
 * the reply leaves through the approval gate as {@code channel.sms.send}; an unpaired sender gets
 * one pairing notice; a bound workflow still wins. Same manual-mock shape as the dedup test, with
 * {@link InboundChannelExecutor#synchronous()} so the "async" reply runs inline here.
 */
class TwilioSmsWebhookControllerConversationTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    private WorkflowTriggerNodeService workflowTriggerNodeService;
    private WorkflowTriggerPort workflowTriggerPort;
    private ChannelSessionService sessionService;
    private AgentChatService agentChatService;
    private HumanApprovalGateService gateService;
    private WorkflowEventPublisher eventPublisher;
    private ChannelSession session;
    private TwilioSmsWebhookController controller;
    private HttpServletRequest servletRequest;
    private Map<String, String> formParams;

    @BeforeEach
    void setUp() {
        TwilioSignatureValidator signatureValidator = mock(TwilioSignatureValidator.class);
        TwilioCredentialService credentialService = mock(TwilioCredentialService.class);
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        InboundMessageDedupService dedupService = mock(InboundMessageDedupService.class);
        workflowTriggerNodeService = mock(WorkflowTriggerNodeService.class);
        workflowTriggerPort = mock(WorkflowTriggerPort.class);
        sessionService = mock(ChannelSessionService.class);
        agentChatService = mock(AgentChatService.class);
        gateService = mock(HumanApprovalGateService.class);
        eventPublisher = mock(WorkflowEventPublisher.class);
        session = mock(ChannelSession.class);

        controller =
                new TwilioSmsWebhookController(
                        signatureValidator,
                        credentialService,
                        tenantResolutionService,
                        workflowTriggerNodeService,
                        workflowTriggerPort,
                        dedupService,
                        sessionService,
                        agentChatService,
                        gateService,
                        eventPublisher,
                        mock(AuditLogService.class),
                        InboundChannelExecutor.synchronous());

        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, "AC123"))
                .thenReturn(Optional.of("tenant_test"));
        when(credentialService.resolveActive())
                .thenReturn(
                        new TwilioCredentialService.DecryptedCredential(
                                "AC123", "auth-token", null, null, "+15559990000"));
        when(dedupService.tryClaim(ChannelType.SMS, "SM123")).thenReturn(true);
        when(workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.SMS))
                .thenReturn(Optional.empty());

        when(session.getId()).thenReturn(SESSION_ID);
        when(session.getAgentId()).thenReturn(AGENT_ID);
        when(session.getSessionId()).thenReturn("redis-session");
        when(session.getExternalId()).thenReturn("+15550001111");
        when(session.isPaired()).thenReturn(true);
        when(sessionService.resolveOrCreate(ChannelType.SMS, "+15550001111", "what is my balance"))
                .thenReturn(session);
        when(sessionService.consumeAiDisclosurePrefix(SESSION_ID)).thenReturn("");

        servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getHeader("X-Twilio-Signature")).thenReturn("sig");

        formParams = new LinkedHashMap<>();
        formParams.put("AccountSid", "AC123");
        formParams.put("MessageSid", "SM123");
        formParams.put("From", "+15550001111");
        formParams.put("Body", "what is my balance");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void pairedSenderGetsAnAgentReplyThroughTheSmsApprovalGate() {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && java.util.Objects.equals(
                                                        r.sessionId(), "redis-session")
                                                && java.util.Objects.equals(
                                                        r.subjectKey(), "+15550001111")
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just("Your balance ", "is 42."));

        ResponseEntity<String> response = controller.inboundSms(formParams, servletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(agentChatService).chat(any(TurnContext.class), turns.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && java.util.Objects.equals(
                                                        r.sessionId(), "redis-session")
                                                && java.util.Objects.equals(
                                                        r.subjectKey(), "+15550001111")
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
        assertTrue(
                turns.getValue().userMessage().startsWith("what is my balance"),
                turns.getValue().userMessage());
        assertTrue(
                turns.getValue().userMessage().contains("arrived by SMS"),
                "the agent is told it is on SMS");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateService)
                .evaluate(
                        eq(TwilioSmsChannelAdapter.ACTION_TYPE),
                        anyString(),
                        payload.capture(),
                        eq("system:sms-inbound:+15550001111"));
        assertTrue(payload.getValue().contains("\"to\":\"+15550001111\""), payload.getValue());
        assertTrue(payload.getValue().contains("Your balance is 42."), payload.getValue());
        verify(eventPublisher)
                .publishNewRun(eq(WorkflowEventTypes.CHANNEL_SMS_MESSAGE_RECEIVED), any());
    }

    @Test
    void unpairedSenderGetsOnePairingNoticeAndNeverReachesTheAgent() {
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(false);
        when(session.getPairingCode()).thenReturn("K7Q2");

        controller.inboundSms(formParams, servletRequest);

        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateService)
                .evaluate(
                        eq(TwilioSmsChannelAdapter.ACTION_TYPE),
                        anyString(),
                        payload.capture(),
                        anyString());
        assertTrue(payload.getValue().contains("K7Q2"));
        verify(sessionService).markPairingNoticeSent(SESSION_ID);
        verify(eventPublisher, never()).publishNewRun(any(), any());
    }

    @Test
    void stillUnpairedSenderIsSilentlyIgnoredAfterTheNotice() {
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(true);

        controller.inboundSms(formParams, servletRequest);

        verify(gateService, never()).evaluate(any(), any(), any(), any());
        verify(sessionService, never()).markPairingNoticeSent(any());
    }

    @Test
    void boundWorkflowStillTakesOverFromTheChat() {
        when(workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.SMS))
                .thenReturn(
                        Optional.of(
                                new WorkflowTriggerNodeService.TriggerBinding(
                                        UUID.randomUUID(), "node-1")));
        when(workflowTriggerNodeService.tryAcquireTriggerSlot(
                        WorkflowTriggerChannel.SMS, "+15550001111"))
                .thenReturn(true);

        controller.inboundSms(formParams, servletRequest);

        verify(workflowTriggerPort)
                .triggerAsync(any(), eq("node-1"), eq("what is my balance"), anyString(), any());
        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        verify(gateService, never()).evaluate(any(), any(), any(), any());
    }

    @Test
    void anOnlineEdgeRelayAnswersInsteadOfTheHostedModel() {
        EdgeRelayHandoff relay = mock(EdgeRelayHandoff.class);
        when(relay.answer(eq(ChannelType.SMS), eq(session), anyString()))
                .thenReturn(Optional.of("Answered on a laptop."));
        controller.setEdgeRelay(relay);

        controller.inboundSms(formParams, servletRequest);

        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gateService)
                .evaluate(
                        eq(TwilioSmsChannelAdapter.ACTION_TYPE),
                        anyString(),
                        payload.capture(),
                        anyString());
        assertTrue(payload.getValue().contains("Answered on a laptop."), payload.getValue());
    }
}
