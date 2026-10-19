package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Per-number agent routing at Conversation Relay {@code setup} time.
 *
 * <p>What these pin down is the precedence the feature is entirely about:
 * the {@link ChannelSession} is keyed on the CALLER, so its stamped agent is
 * "whoever this person last spoke to", while the dialled number is "which of
 * our lines they rang". The dialled number has to win, and it has to win
 * without rebinding the session row - otherwise a caller who rings the sales
 * line after the support line silently drags their whole conversation
 * history onto a different agent. The assertion is therefore always on the
 * in-memory {@code voiceRelay.agentId} attribute, never on a persisted one.
 *
 * <p>This class is also the first test of any kind against {@link
 * VoiceRelayWebSocketHandler} - it previously had none, alongside {@link
 * TwilioVoiceWebhookController} and {@link VoiceDtmfMappingService}.
 *
 * <p>{@code handleTextMessage} is {@code protected}; this test lives in the
 * same package deliberately, so the real Conversation Relay wire message is
 * what drives the handler rather than a private method being reached
 * reflectively.
 */
class VoiceRelayWebSocketHandlerNumberRoutingTest {

    private static final String ACCOUNT_SID = "AC12345678901234567890123456789012";
    private static final String TENANT_SCHEMA = "tenant_test";
    private static final String CALLER = "+919876543210";
    private static final String SALES_LINE = "+15551110000";
    private static final String SUPPORT_LINE = "+15552220000";

    private static final UUID CHANNEL_DEFAULT_AGENT = UUID.randomUUID();
    private static final UUID SALES_AGENT = UUID.randomUUID();

    private VoiceNumberAgentService voiceNumberAgentService;
    private VoiceRelayWebSocketHandler handler;
    private WebSocketSession session;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, ACCOUNT_SID))
            .thenReturn(Optional.of(TENANT_SCHEMA));

        ChannelSession channelSession = mock(ChannelSession.class);
        when(channelSession.isPaired()).thenReturn(true);
        when(channelSession.getAgentId()).thenReturn(CHANNEL_DEFAULT_AGENT);
        when(channelSession.getSessionId()).thenReturn("memory-session-1");
        when(channelSession.getId()).thenReturn(UUID.randomUUID());

        ChannelSessionService channelSessionService = mock(ChannelSessionService.class);
        when(channelSessionService.resolveOrCreate(eq(ChannelType.VOICE), anyString(), anyString()))
            .thenReturn(channelSession);

        voiceNumberAgentService = mock(VoiceNumberAgentService.class);
        when(voiceNumberAgentService.findAgentIdForNumber(any())).thenReturn(Optional.empty());
        when(voiceNumberAgentService.findAgentIdForNumber(SALES_LINE)).thenReturn(Optional.of(SALES_AGENT));

        // Routing is what this class tests; admission has its own test
        // (VoiceRelayWebSocketHandlerAdmissionTest), so every setup here is
        // simply admitted as the call the fixtures describe.
        VoiceRelayTicketService voiceRelayTicketService = mock(VoiceRelayTicketService.class);
        when(voiceRelayTicketService.verify(any()))
            .thenReturn(Optional.of(new VoiceRelayTicketService.Admission(ACCOUNT_SID, "CA1", CALLER)));

        handler = new VoiceRelayWebSocketHandler(
            tenantResolutionService,
            channelSessionService,
            mock(AgentChatService.class),
            mock(VoiceDtmfMappingService.class),
            voiceNumberAgentService,
            voiceRelayTicketService
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
    void aCallToAMappedNumberIsAnsweredByThatNumbersAgent() throws Exception {
        handler.handleTextMessage(session, setup(SALES_LINE));

        assertEquals(SALES_AGENT, attributes.get("voiceRelay.agentId"));
    }

    @Test
    void aCallToAnUnmappedNumberStillFallsBackToTheChannelDefaultAgent() throws Exception {
        handler.handleTextMessage(session, setup(SUPPORT_LINE));

        assertEquals(CHANNEL_DEFAULT_AGENT, attributes.get("voiceRelay.agentId"));
    }

    /**
     * Routing must not disturb the caller-keyed session identity - the memory
     * session id is what gives a caller continuity across calls, and the
     * whole point of overriding only the attribute is that it survives.
     */
    @Test
    void routingByNumberLeavesTheCallersMemorySessionIntact() throws Exception {
        handler.handleTextMessage(session, setup(SALES_LINE));

        assertEquals("memory-session-1", attributes.get("voiceRelay.memorySessionId"));
        assertEquals(TENANT_SCHEMA, attributes.get("voiceRelay.tenantSchema"));
    }

    /**
     * The {@code ?to=} query parameter is the fallback for a setup message
     * that carries no {@code to} field. It is URL-encoded by {@link
     * TwilioVoiceWebhookController#resolveRelayUrl} precisely because a raw
     * {@code +} would arrive decoded as a space and match nothing.
     */
    @Test
    void aSetupWithoutAToFieldFallsBackToTheRelayUrlQueryParameter() throws Exception {
        when(session.getUri()).thenReturn(
            URI.create("wss://api.example.com/webhook/twilio/voice-relay?to=%2B15551110000")
        );

        handler.handleTextMessage(session, setupWithoutTo());

        assertEquals(SALES_AGENT, attributes.get("voiceRelay.agentId"));
    }

    @Test
    void theSetupFieldWinsOverTheQueryParameterWhenBothArePresent() throws Exception {
        when(session.getUri()).thenReturn(
            URI.create("wss://api.example.com/webhook/twilio/voice-relay?to=%2B15551110000")
        );

        handler.handleTextMessage(session, setup(SUPPORT_LINE));

        assertEquals(CHANNEL_DEFAULT_AGENT, attributes.get("voiceRelay.agentId"));
    }

    private static TextMessage setup(String dialedNumber) {
        return new TextMessage(
            "{\"type\":\"setup\",\"accountSid\":\"" + ACCOUNT_SID + "\",\"callSid\":\"CA1\","
                + "\"from\":\"" + CALLER + "\",\"to\":\"" + dialedNumber + "\"}"
        );
    }

    private static TextMessage setupWithoutTo() {
        return new TextMessage(
            "{\"type\":\"setup\",\"accountSid\":\"" + ACCOUNT_SID + "\",\"callSid\":\"CA1\","
                + "\"from\":\"" + CALLER + "\"}"
        );
    }
}
