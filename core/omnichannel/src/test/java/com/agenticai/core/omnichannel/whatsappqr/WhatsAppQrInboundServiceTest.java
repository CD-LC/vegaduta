package com.agenticai.core.omnichannel.whatsappqr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.security.TenantContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Default deny, pairing and the reply path of an inbound WhatsApp QR message. */
class WhatsAppQrInboundServiceTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String SENDER = "4917600000001";

    private ChannelSessionService sessions;
    private AgentChatService agentChat;
    private HumanApprovalGateService gate;
    private AuditLogService audit;
    private InboundMessageDedupService dedup;
    private WhatsAppQrInboundService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant_test", "tester", Set.of()));
        sessions = mock(ChannelSessionService.class);
        agentChat = mock(AgentChatService.class);
        gate = mock(HumanApprovalGateService.class);
        audit = mock(AuditLogService.class);
        dedup = mock(InboundMessageDedupService.class);
        when(dedup.tryClaim(eq(ChannelType.WHATSAPP_QR), any())).thenReturn(true);
        when(sessions.consumeAiDisclosurePrefix(any())).thenReturn("");
        service =
                new WhatsAppQrInboundService(
                        sessions,
                        agentChat,
                        gate,
                        audit,
                        dedup,
                        InboundChannelExecutor.synchronous());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static JsonNode event(String chatJid, String senderNumber, String text)
            throws Exception {
        return MAPPER.readTree(
                "{\"id\":\"MSG1\",\"chatJid\":\""
                        + chatJid
                        + "\",\"senderNumber\":"
                        + (senderNumber == null ? "null" : "\"" + senderNumber + "\"")
                        + ",\"pushName\":\"Ann\",\"text\":\""
                        + text
                        + "\",\"timestampMs\":11}");
    }

    private ChannelSession session(boolean paired, boolean noticeSent, String externalId) {
        ChannelSession s = mock(ChannelSession.class);
        when(s.isPaired()).thenReturn(paired);
        when(s.isPairingNoticeSent()).thenReturn(noticeSent);
        when(s.getPairingCode()).thenReturn("123456");
        when(s.getId()).thenReturn(UUID.randomUUID());
        when(s.getAgentId()).thenReturn(UUID.randomUUID());
        when(s.getSessionId()).thenReturn("session-1");
        when(s.getExternalId()).thenReturn(externalId);
        return s;
    }

    private void agentReplies(String reply) {
        when(agentChat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just(reply));
    }

    private String lastGatePayload() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gate)
                .evaluate(
                        eq(WhatsAppQrChannelAdapter.ACTION_TYPE),
                        anyString(),
                        payload.capture(),
                        anyString());
        return payload.getValue();
    }

    @Test
    void anUnknownSenderGetsOnePairingCodeAndTheAgentStaysSilent() throws Exception {
        ChannelSession unpaired = session(false, false, SENDER);
        when(sessions.resolveOrCreate(
                        eq(ChannelType.WHATSAPP_QR), eq(SENDER), eq("hi"), eq(SENDER)))
                .thenReturn(unpaired);

        assertThat(service.accept(event(SENDER + "@s.whatsapp.net", SENDER, "hi"))).isTrue();

        assertThat(lastGatePayload()).contains("pairing code is 123456").contains(SENDER);
        verify(sessions).markPairingNoticeSent(unpaired.getId());
        verify(agentChat, never()).chat(any(), any());
    }

    @Test
    void aStillUnpairedSenderIsNotNotifiedAgain() throws Exception {
        ChannelSession unpaired = session(false, true, SENDER);
        when(sessions.peek(ChannelType.WHATSAPP_QR, SENDER)).thenReturn(Optional.of(unpaired));
        when(sessions.resolveOrCreate(eq(ChannelType.WHATSAPP_QR), eq(SENDER), anyString(), any()))
                .thenReturn(unpaired);

        service.accept(event(SENDER + "@s.whatsapp.net", SENDER, "hello??"));

        verify(gate, never()).evaluate(anyString(), anyString(), anyString(), anyString());
        verify(agentChat, never()).chat(any(), any());
    }

    @Test
    void aPairedSenderGetsTheAgentsReplyThroughTheApprovalGate() throws Exception {
        ChannelSession paired = session(true, false, SENDER);
        when(sessions.resolveOrCreate(
                        eq(ChannelType.WHATSAPP_QR), eq(SENDER), eq("what time is it"), eq(SENDER)))
                .thenReturn(paired);
        agentReplies("half past nine");

        assertThat(service.accept(event(SENDER + "@s.whatsapp.net", SENDER, "what time is it")))
                .isTrue();

        assertThat(lastGatePayload())
                .contains("half past nine")
                .contains("\"to\":\"" + SENDER + "\"");
    }

    @Test
    void aLidSenderIsPairedOnItsOpaqueId() throws Exception {
        ChannelSession paired = session(true, false, "9988776655@lid");
        when(sessions.resolveOrCreate(
                        eq(ChannelType.WHATSAPP_QR), eq("9988776655@lid"), eq("yo"), isNull()))
                .thenReturn(paired);
        agentReplies("hey");

        assertThat(service.accept(event("9988776655@lid", null, "yo"))).isTrue();

        assertThat(lastGatePayload()).contains("\"to\":\"9988776655@lid\"");
    }

    @Test
    void aDuplicateIsDroppedBeforeAnyWork() throws Exception {
        when(dedup.tryClaim(eq(ChannelType.WHATSAPP_QR), any())).thenReturn(false);

        assertThat(service.accept(event(SENDER + "@s.whatsapp.net", SENDER, "hi"))).isTrue();

        verify(sessions, never()).resolveOrCreate(any(), any(), any(), any());
        verify(agentChat, never()).chat(any(), any());
    }

    @Test
    void nonMessageEventsAreNoOps() throws Exception {
        assertThat(service.accept(MAPPER.readTree("{\"status\":\"CONNECTED\"}"))).isTrue();
        verify(sessions, never()).resolveOrCreate(any(), any(), any(), any());
    }
}
