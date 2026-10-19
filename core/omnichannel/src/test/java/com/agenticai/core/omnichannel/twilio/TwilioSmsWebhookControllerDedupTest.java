package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Inbound-dedup wiring for the SMS trigger webhook - the gap this closes:
 * {@link InboundMessageDedupService} was wired into {@link
 * TwilioWebhookController} (WhatsApp) but never into this SMS sibling, even
 * though both share Twilio's retry-on-anything-but-a-fast-200 delivery
 * contract - so a retried SMS delivery re-fired the bound workflow ({@link
 * com.agenticai.core.omnichannel.trigger.WorkflowTriggerRateLimiter} only
 * throttles per-sender, it can't recognize a retry as the same message).
 *
 * <p>Since SMS became a conversation channel (2026-10-14) the claim is scoped
 * on {@link ChannelType#SMS} like every other channel. Same manual {@code mock(X.class)} +
 * constructor-injection shape as {@link
 * com.agenticai.core.omnichannel.session.InboundMessageDedupServiceTest}.
 * The processed path is pinned at {@link
 * WorkflowTriggerNodeService#bindingFor}, the first side effect past the
 * dedup gate - everything on this controller is synchronous, so no async
 * hedging is needed here.
 */
class TwilioSmsWebhookControllerDedupTest {

    private static final String EMPTY_TWIML = "<Response></Response>";

    private WorkflowTriggerNodeService workflowTriggerNodeService;
    private InboundMessageDedupService dedupService;
    private TwilioSmsWebhookController controller;
    private HttpServletRequest servletRequest;
    private Map<String, String> formParams;

    @BeforeEach
    void setUp() {
        TwilioSignatureValidator signatureValidator = mock(TwilioSignatureValidator.class);
        TwilioCredentialService credentialService = mock(TwilioCredentialService.class);
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        workflowTriggerNodeService = mock(WorkflowTriggerNodeService.class);
        dedupService = mock(InboundMessageDedupService.class);
        ChannelSessionService sessionService = mock(ChannelSessionService.class);
        ChannelSession paired = mock(ChannelSession.class);
        when(paired.isPaired()).thenReturn(true);
        when(sessionService.resolveOrCreate(any(ChannelType.class), any(), any())).thenReturn(paired);
        controller = new TwilioSmsWebhookController(
            signatureValidator,
            credentialService,
            tenantResolutionService,
            workflowTriggerNodeService,
            mock(WorkflowTriggerPort.class),
            dedupService,
            sessionService,
            mock(AgentChatService.class),
            mock(HumanApprovalGateService.class),
            mock(WorkflowEventPublisher.class),
            mock(AuditLogService.class),
            InboundChannelExecutor.synchronous()
        );

        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, "AC123"))
            .thenReturn(Optional.of("tenant_test"));
        when(credentialService.resolveActive())
            .thenReturn(new TwilioCredentialService.DecryptedCredential("AC123", "auth-token", null, null, "+15559990000"));
        when(workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.SMS)).thenReturn(Optional.empty());

        servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getHeader("X-Twilio-Signature")).thenReturn("sig");

        formParams = new LinkedHashMap<>();
        formParams.put("AccountSid", "AC123");
        formParams.put("MessageSid", "SM123");
        formParams.put("From", "+15550001111");
        formParams.put("Body", "hello");
    }

    @AfterEach
    void tearDown() {
        // The controller clears its own TenantContext in a finally block;
        // this is belt-and-braces against a failed assertion mid-request.
        TenantContext.clear();
    }

    @Test
    void duplicateDeliveryOfTheSameMessageSidIsProcessedExactlyOnce() {
        when(dedupService.tryClaim(ChannelType.SMS, "SM123")).thenReturn(true, false);

        controller.inboundSms(formParams, servletRequest);
        controller.inboundSms(formParams, servletRequest);

        verify(workflowTriggerNodeService, times(1)).bindingFor(WorkflowTriggerChannel.SMS);
    }

    @Test
    void duplicateDeliveryStillAcksTwilioWithEmptyTwimlWithoutReachingTheTriggerCheck() {
        when(dedupService.tryClaim(ChannelType.SMS, "SM123")).thenReturn(false);

        ResponseEntity<String> response = controller.inboundSms(formParams, servletRequest);

        // Twilio keeps retrying on anything but a fast 200 - a duplicate
        // must ack exactly like a success.
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(EMPTY_TWIML, response.getBody());
        verify(workflowTriggerNodeService, never()).bindingFor(any());
    }

    @Test
    void firstDeliveryClaimsTheMessageSidUnderTheSmsScopeBeforeProcessing() {
        when(dedupService.tryClaim(ChannelType.SMS, "SM123")).thenReturn(true);

        ResponseEntity<String> response = controller.inboundSms(formParams, servletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(EMPTY_TWIML, response.getBody());
        verify(dedupService).tryClaim(ChannelType.SMS, "SM123");
        verify(workflowTriggerNodeService).bindingFor(WorkflowTriggerChannel.SMS);
    }
}
