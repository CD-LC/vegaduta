package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * The relay socket is reachable by anyone ({@code /webhook/**} is
 * {@code permitAll()}), so until the 2026-09-18 security audit a client that
 * knew a tenant's Twilio AccountSid and one paired caller number could open it
 * and drive that tenant's agent. These tests pin the replacement rule: no
 * tenant is resolved, and no session is created, for a socket that cannot
 * present a ticket minted by the signature-verified inbound-call webhook - and
 * what the frame CLAIMS about account and caller is ignored in favour of what
 * the ticket vouches for.
 *
 * <p>Uses the real {@link VoiceRelayTicketService} over a real
 * {@link EncryptionService}, so a forged or tampered ticket is rejected by the
 * actual AES-GCM check rather than by a mock told to say no.
 */
class VoiceRelayWebSocketHandlerAdmissionTest {

    private static final String ACCOUNT_SID = "AC12345678901234567890123456789012";
    private static final String VICTIM_ACCOUNT_SID = "AC99999999999999999999999999999999";
    private static final String CALLER = "+919876543210";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private TenantResolutionService tenantResolutionService;
    private ChannelSessionService channelSessionService;
    private VoiceRelayTicketService tickets;
    private VoiceRelayWebSocketHandler handler;
    private WebSocketSession session;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        tenantResolutionService = mock(TenantResolutionService.class);
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, ACCOUNT_SID))
            .thenReturn(Optional.of("tenant_test"));
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, VICTIM_ACCOUNT_SID))
            .thenReturn(Optional.of("tenant_victim"));

        ChannelSession channelSession = mock(ChannelSession.class);
        when(channelSession.isPaired()).thenReturn(true);
        when(channelSession.getAgentId()).thenReturn(UUID.randomUUID());
        when(channelSession.getSessionId()).thenReturn("memory-session-1");
        when(channelSession.getId()).thenReturn(UUID.randomUUID());
        channelSessionService = mock(ChannelSessionService.class);
        when(channelSessionService.resolveOrCreate(eq(ChannelType.VOICE), anyString(), anyString()))
            .thenReturn(channelSession);

        VoiceNumberAgentService voiceNumberAgentService = mock(VoiceNumberAgentService.class);
        when(voiceNumberAgentService.findAgentIdForNumber(any())).thenReturn(Optional.empty());

        tickets = new VoiceRelayTicketService(encryption(KEY));
        handler = new VoiceRelayWebSocketHandler(
            tenantResolutionService, channelSessionService, mock(AgentChatService.class),
            mock(VoiceDtmfMappingService.class), voiceNumberAgentService, tickets
        );

        attributes = new HashMap<>();
        session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(URI.create("wss://api.example.com/webhook/twilio/voice-relay"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aSetupWithNoTicketIsClosedBeforeAnyTenantIsResolved() throws Exception {
        handler.handleTextMessage(session, setup(ACCOUNT_SID, "CA1", CALLER, null));

        assertRejected();
    }

    @Test
    void aForgedTicketIsRejected() throws Exception {
        handler.handleTextMessage(session, setup(ACCOUNT_SID, "CA1", CALLER, "bm90LWEtcmVhbC10aWNrZXQtanVzdC1iYXNlNjQ="));

        assertRejected();
    }

    @Test
    void aTicketFromAnotherDeploymentsKeyIsRejected() throws Exception {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        String foreign = new VoiceRelayTicketService(
            encryption(Base64.getEncoder().encodeToString(otherKey))
        ).mint(ACCOUNT_SID, "CA1", CALLER);

        handler.handleTextMessage(session, setup(ACCOUNT_SID, "CA1", CALLER, foreign));

        assertRejected();
    }

    @Test
    void aTicketCannotBeReplayedOnADifferentCall() throws Exception {
        String ticket = tickets.mint(ACCOUNT_SID, "CA1", CALLER);

        handler.handleTextMessage(session, setup(ACCOUNT_SID, "CA-some-other-call", CALLER, ticket));

        assertRejected();
    }

    @Test
    void anExpiredTicketIsRejected() {
        Instant minted = Instant.parse("2026-09-18T10:00:00Z");
        EncryptionService encryption = encryption(KEY);
        String ticket = new VoiceRelayTicketService(encryption, Clock.fixed(minted, ZoneOffset.UTC))
            .mint(ACCOUNT_SID, "CA1", CALLER);

        Clock justInside = Clock.fixed(minted.plus(VoiceRelayTicketService.TTL).minusSeconds(1), ZoneOffset.UTC);
        Clock justAfter = Clock.fixed(minted.plus(VoiceRelayTicketService.TTL).plusSeconds(1), ZoneOffset.UTC);
        assertTrue(new VoiceRelayTicketService(encryption, justInside).verify(ticket).isPresent());
        assertFalse(new VoiceRelayTicketService(encryption, justAfter).verify(ticket).isPresent());
    }

    /**
     * The attack this whole change exists for: a genuine ticket for the
     * attacker's OWN call, presented with a frame naming the victim's account
     * and a caller number the victim has paired. The ticket wins on both.
     */
    @Test
    void theTenantAndCallerComeFromTheTicketNotFromWhatTheFrameClaims() throws Exception {
        String ticket = tickets.mint(ACCOUNT_SID, "CA1", CALLER);

        handler.handleTextMessage(session, setup(VICTIM_ACCOUNT_SID, "CA1", "+10000000000", ticket));

        assertEquals("tenant_test", attributes.get("voiceRelay.tenantSchema"));
        verify(tenantResolutionService, never()).resolveTenantSchema(ExternalAccountProvider.TWILIO, VICTIM_ACCOUNT_SID);
        verify(channelSessionService).resolveOrCreate(eq(ChannelType.VOICE), eq(CALLER), anyString());
    }

    @Test
    void aTicketInTheRelayUrlQueryStringIsAcceptedToo() throws Exception {
        String ticket = tickets.mint(ACCOUNT_SID, "CA1", CALLER);
        when(session.getUri()).thenReturn(URI.create(
            "wss://api.example.com/webhook/twilio/voice-relay?to=%2B15551110000&ticket="
                + URLEncoder.encode(ticket, StandardCharsets.UTF_8)
        ));

        handler.handleTextMessage(session, setup(ACCOUNT_SID, "CA1", CALLER, null));

        assertEquals("tenant_test", attributes.get("voiceRelay.tenantSchema"));
    }

    /** An account linked to no tenant used to fall through to the default schema; now it is nobody's call. */
    @Test
    void aTicketForAnAccountLinkedToNoTenantIsNotRoutedToTheDefaultSchema() throws Exception {
        String unlinked = "AC00000000000000000000000000000000";
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, unlinked))
            .thenReturn(Optional.empty());

        handler.handleTextMessage(session, setup(unlinked, "CA1", CALLER, tickets.mint(unlinked, "CA1", CALLER)));

        assertNull(attributes.get("voiceRelay.tenantSchema"));
        verify(channelSessionService, never()).resolveOrCreate(any(), anyString(), anyString());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    /** EncryptionService parses its key in a package-private @PostConstruct, which only Spring would otherwise run. */
    private static EncryptionService encryption(String base64Key) {
        EncryptionService service = new EncryptionService(base64Key, "");
        ReflectionTestUtils.invokeMethod(service, "init");
        return service;
    }

    private void assertRejected() throws Exception {
        assertNull(attributes.get("voiceRelay.tenantSchema"));
        verify(tenantResolutionService, never()).resolveTenantSchema(any(), anyString());
        verify(channelSessionService, never()).resolveOrCreate(any(), anyString(), anyString());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    private static TextMessage setup(String accountSid, String callSid, String from, String ticket) {
        String custom = ticket == null ? "" : ",\"customParameters\":{\"ticket\":\"" + ticket + "\"}";
        return new TextMessage(
            "{\"type\":\"setup\",\"accountSid\":\"" + accountSid + "\",\"callSid\":\"" + callSid + "\","
                + "\"from\":\"" + from + "\",\"to\":\"+15551110000\"" + custom + "}"
        );
    }
}
