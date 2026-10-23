package com.agenticai.core.omnichannel.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The Slack webhook with the REAL {@link SlackSignatureValidator} (the dedup test mocks it): the
 * one-time Request URL handshake as Slack really sends it (no {@code team_id}), and the rejections
 * every other delivery must get - bad signature, stale timestamp, missing signing secret, replayed
 * event - none of which may reach the session layer.
 */
class SlackWebhookControllerSecurityTest {

    private static final String TEAM_ID = "T12345678";
    private static final String SECRET = "test-signing-secret";
    private static final String EVENT_BODY =
            """
            {"team_id":"T12345678","type":"event_callback","event_id":"Ev12345678",
             "event":{"type":"message","user":"U11111111","channel":"C22222222","text":"hello"}}
            """;

    /** Slack's real handshake body: token, challenge, type - and no team_id. */
    private static final String HANDSHAKE =
            "{\"token\":\"legacy-token\",\"challenge\":\"3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P\","
                    + "\"type\":\"url_verification\"}";

    private TenantResolutionService tenants;
    private SlackCredentialService credentials;
    private ChannelSessionService sessions;
    private InboundMessageDedupService dedup;
    private SlackWebhookController controller;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantResolutionService.class);
        credentials = mock(SlackCredentialService.class);
        sessions = mock(ChannelSessionService.class);
        dedup = mock(InboundMessageDedupService.class);
        controller =
                new SlackWebhookController(
                        new SlackSignatureValidator(),
                        credentials,
                        sessions,
                        mock(AgentChatService.class),
                        mock(HumanApprovalGateService.class),
                        tenants,
                        mock(WorkflowEventPublisher.class),
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        dedup,
                        new AsyncInboundReply(Runnable::run));
        when(tenants.resolveTenantSchema(ExternalAccountProvider.SLACK, TEAM_ID))
                .thenReturn(Optional.of("tenant_test"));
        when(credentials.resolveActive())
                .thenReturn(
                        new SlackCredentialService.DecryptedCredential(
                                TEAM_ID, "UBOT00000", "xoxb-token", SECRET));
        when(dedup.tryClaim(ChannelType.SLACK, "Ev12345678")).thenReturn(true, false);
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(true);
        when(sessions.resolveOrCreate(eq(ChannelType.SLACK), anyString(), anyString()))
                .thenReturn(session);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static String now() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    // ---- Request URL handshake -------------------------------------------------------------

    @Test
    void slacksRealHandshakeWithNoTeamIdIsAnsweredWithTheChallenge() {
        ResponseEntity<String> response = controller.inbound(HANDSHAKE, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P", response.getBody());
        assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType());
        // It touches no tenant, no credential and no dedup key.
        verifyNoInteractions(tenants, credentials, dedup, sessions);
    }

    @Test
    void aHandshakeChallengeThatIsNotAShortTokenIsNotReflected() {
        String[] challenges = {
            "\"<script>alert(1)</script>\"",
            "\"has space\"",
            "\"" + "a".repeat(257) + "\"",
            "\"\"",
            "{\"a\":1}",
            "12345"
        };
        for (String challenge : challenges) {
            String body = "{\"type\":\"url_verification\",\"challenge\":" + challenge + "}";
            ResponseEntity<String> response = controller.inbound(body, null, null);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNull(response.getBody(), "reflected: " + challenge);
        }
    }

    @Test
    void anEventWithNoTeamIdIsStillIgnoredAndNeverAnswered() {
        ResponseEntity<String> response =
                controller.inbound(
                        "{\"type\":\"event_callback\",\"event_id\":\"Ev1\"}", now(), "v0=abc");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getBody());
        verifyNoInteractions(tenants, credentials, dedup, sessions);
    }

    // ---- Signature enforcement on events ---------------------------------------------------

    @Test
    void aValidlySignedEventIsProcessed() {
        String ts = now();
        controller.inbound(
                EVENT_BODY, ts, SlackSignatureValidatorTest.sign(SECRET, ts, EVENT_BODY));

        verify(sessions, times(1)).resolveOrCreate(ChannelType.SLACK, "C22222222", "hello");
    }

    @Test
    void aBadSignatureNeverReachesTheDedupOrSessionLayer() {
        String ts = now();
        ResponseEntity<String> response =
                controller.inbound(
                        EVENT_BODY, ts, SlackSignatureValidatorTest.sign("wrong", ts, EVENT_BODY));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(dedup, never()).tryClaim(any(ChannelType.class), any());
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void aStaleTimestampIsRefusedEvenWhenTheSignatureMatchesIt() {
        String ts = Long.toString(Instant.now().getEpochSecond() - 3600);
        controller.inbound(
                EVENT_BODY, ts, SlackSignatureValidatorTest.sign(SECRET, ts, EVENT_BODY));

        verify(dedup, never()).tryClaim(any(ChannelType.class), any());
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void missingSignatureHeadersAreRefused() {
        controller.inbound(EVENT_BODY, null, null);
        controller.inbound(EVENT_BODY, now(), null);

        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void aTenantWithNoSigningSecretConfiguredProcessesNothing() {
        when(credentials.resolveActive()).thenThrow(new SlackNotConfiguredException());
        String ts = now();
        controller.inbound(
                EVENT_BODY, ts, SlackSignatureValidatorTest.sign(SECRET, ts, EVENT_BODY));

        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void aReplayOfAValidDeliveryIsProcessedOnlyOnce() {
        String ts = now();
        String signature = SlackSignatureValidatorTest.sign(SECRET, ts, EVENT_BODY);

        controller.inbound(EVENT_BODY, ts, signature);
        controller.inbound(EVENT_BODY, ts, signature);

        verify(sessions, times(1)).resolveOrCreate(ChannelType.SLACK, "C22222222", "hello");
    }
}
