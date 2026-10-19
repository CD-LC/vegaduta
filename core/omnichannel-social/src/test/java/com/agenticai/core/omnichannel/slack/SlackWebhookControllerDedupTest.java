package com.agenticai.core.omnichannel.slack;

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
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.AsyncInboundReply;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Inbound-dedup wiring for the Slack Events API webhook - Wave-2. Slack is arguably the channel
 * that needs this MOST: it retries a delivery (tagged {@code X-Slack-Retry-Num}) not only on
 * non-2xx but whenever the endpoint takes longer than ~3 seconds to answer, so a slow downstream
 * turns "processing succeeded, just slowly" into a real double delivery. The claim is on the
 * envelope's {@code event_id} - stable across retries of the same event - not {@code
 * client_msg_id}, which only exists on user-typed message events (see the controller's inline
 * comment).
 *
 * <p>Same manual {@code mock(X.class)} + constructor-injection shape as {@link
 * com.agenticai.core.omnichannel.meta.MetaWhatsAppWebhookControllerDedupTest}, including the
 * unpaired-notice-already-sent session so the flow ends synchronously at {@link
 * ChannelSessionService#resolveOrCreate}, the first side effect past the dedup gate - never
 * reaching the blocking agent-chat leg.
 */
class SlackWebhookControllerDedupTest {

    private static final String TEAM_ID = "T12345678";
    private static final String EVENT_BODY =
            """
            {"team_id":"T12345678","type":"event_callback","event_id":"Ev12345678",
             "event":{"type":"message","user":"U11111111","channel":"C22222222","text":"hello"}}
            """;
    private static final String URL_VERIFICATION_BODY =
            """
            {"team_id":"T12345678","type":"url_verification","challenge":"challenge-token"}
            """;

    private ChannelSessionService channelSessionService;
    private InboundMessageDedupService dedupService;
    private SlackWebhookController controller;

    @BeforeEach
    void setUp() {
        SlackSignatureValidator signatureValidator = mock(SlackSignatureValidator.class);
        SlackCredentialService credentialService = mock(SlackCredentialService.class);
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        channelSessionService = mock(ChannelSessionService.class);
        dedupService = mock(InboundMessageDedupService.class);
        controller =
                new SlackWebhookController(
                        signatureValidator,
                        credentialService,
                        channelSessionService,
                        mock(AgentChatService.class),
                        mock(HumanApprovalGateService.class),
                        tenantResolutionService,
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        dedupService,
                        new AsyncInboundReply(Runnable::run));

        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.SLACK, TEAM_ID))
                .thenReturn(Optional.of("tenant_test"));
        when(credentialService.resolveActive())
                .thenReturn(
                        new SlackCredentialService.DecryptedCredential(
                                TEAM_ID, "UBOT00000", "xoxb-token", "signing-secret"));

        // Unpaired sender whose pairing notice already went out - the
        // earliest fully-synchronous exit past the dedup gate, so "was this
        // message processed" reduces to "was resolveOrCreate called".
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(true);
        when(channelSessionService.resolveOrCreate(eq(ChannelType.SLACK), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        // The controller clears its own TenantContext in a finally block;
        // this is belt-and-braces against a failed assertion mid-request.
        TenantContext.clear();
    }

    @Test
    void slackRetryOfTheSameEventIdIsProcessedExactlyOnce() {
        when(dedupService.tryClaim(ChannelType.SLACK, "Ev12345678")).thenReturn(true, false);

        controller.inbound(EVENT_BODY, "1717171717", "v0=sig");
        controller.inbound(EVENT_BODY, "1717171717", "v0=sig");

        verify(channelSessionService, times(1))
                .resolveOrCreate(ChannelType.SLACK, "C22222222", "hello");
    }

    @Test
    void slackRetryStillGetsTheSameFast200WithoutTouchingTheSessionLayer() {
        when(dedupService.tryClaim(ChannelType.SLACK, "Ev12345678")).thenReturn(false);

        ResponseEntity<String> response = controller.inbound(EVENT_BODY, "1717171717", "v0=sig");

        // The 200 is what stops Slack retrying (it retries up to 3 times on
        // non-2xx or slow answers) - a duplicate must ack exactly like a
        // success.
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(channelSessionService, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void urlVerificationHandshakeNeverTouchesTheDedupService() {
        // url_verification has no event_id and must always be answered with
        // the challenge - claiming there would be meaningless (a null claim)
        // at best and a dropped handshake at worst.
        ResponseEntity<String> response =
                controller.inbound(URL_VERIFICATION_BODY, "1717171717", "v0=sig");

        assertEquals("challenge-token", response.getBody());
        verify(dedupService, never()).tryClaim(any(ChannelType.class), any());
    }
}
