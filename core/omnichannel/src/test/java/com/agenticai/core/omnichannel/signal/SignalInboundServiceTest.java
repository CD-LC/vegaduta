package com.agenticai.core.omnichannel.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.omnichannel.contact.ChannelContact;
import com.agenticai.core.omnichannel.contact.ChannelContactService;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.ConversationHeldByHumanException;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
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

/** Default deny, pairing, group policy and the reply path of an inbound Signal message. */
class SignalInboundServiceTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String ACCOUNT = "+4915112345678";
    private static final String SENDER = "+4917600000001";
    private static final String GROUP = "Z3JvdXBpZGdyb3VwaWQ=";

    private ChannelSessionService sessions;
    private ChannelContactService contacts;
    private AgentChatService agentChat;
    private HumanApprovalGateService gate;
    private AuditLogService audit;
    private InboundMessageDedupService dedup;
    private SignalApiClient api;
    private SignalInboundService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant_test", "tester", Set.of()));
        sessions = mock(ChannelSessionService.class);
        contacts = mock(ChannelContactService.class);
        agentChat = mock(AgentChatService.class);
        gate = mock(HumanApprovalGateService.class);
        audit = mock(AuditLogService.class);
        dedup = mock(InboundMessageDedupService.class);
        api = mock(SignalApiClient.class);
        when(dedup.tryClaim(eq(ChannelType.SIGNAL), any())).thenReturn(true);
        when(sessions.consumeAiDisclosurePrefix(any())).thenReturn("");
        when(contacts.findForInbound(any(), any(), any())).thenReturn(Optional.empty());
        service = newService(InboundChannelExecutor.synchronous());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private SignalInboundService newService(InboundChannelExecutor executor) {
        return new SignalInboundService(
                sessions, contacts, agentChat, gate, audit, dedup, api, executor);
    }

    private static SignalConnection connection(
            UnknownSenderPolicy senders, SignalGroupMode groups) {
        return new SignalConnection("http://signal-cli:8080", ACCOUNT, null, senders, groups);
    }

    private static JsonNode direct(String text) throws Exception {
        return MAPPER.readTree(
                """
                {"envelope":{"sourceNumber":"%s","timestamp":11,
                 "dataMessage":{"timestamp":11,"message":"%s"}}}"""
                        .formatted(SENDER, text));
    }

    private static JsonNode group(String text, boolean mention) throws Exception {
        return MAPPER.readTree(
                """
                {"envelope":{"sourceNumber":"%s","timestamp":12,
                 "dataMessage":{"timestamp":12,"message":"%s","groupInfo":{"groupId":"%s"},
                 "mentions":[%s]}}}"""
                        .formatted(
                                SENDER,
                                text,
                                GROUP,
                                mention ? "{\"number\":\"" + ACCOUNT + "\"}" : ""));
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
                        eq(SignalChannelAdapter.ACTION_TYPE),
                        anyString(),
                        payload.capture(),
                        anyString());
        return payload.getValue();
    }

    @Test
    void anUnknownSenderGetsOnePairingCodeAndTheAgentStaysSilent() throws Exception {
        ChannelSession unpaired = session(false, false, SENDER);
        when(sessions.resolveOrCreate(eq(ChannelType.SIGNAL), eq(SENDER), eq("hi"), eq(SENDER)))
                .thenReturn(unpaired);

        assertThat(
                        service.accept(
                                connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE),
                                direct("hi")))
                .isTrue();

        assertThat(lastGatePayload()).contains("pairing code is 123456").contains(SENDER);
        verify(sessions).markPairingNoticeSent(unpaired.getId());
        verify(agentChat, never()).chat(any(), any());
    }

    @Test
    void aStillUnpairedSenderIsNotNotifiedAgain() throws Exception {
        ChannelSession unpaired = session(false, true, SENDER);
        when(sessions.peek(ChannelType.SIGNAL, SENDER)).thenReturn(Optional.of(unpaired));
        when(sessions.resolveOrCreate(eq(ChannelType.SIGNAL), eq(SENDER), anyString(), any()))
                .thenReturn(unpaired);

        service.accept(
                connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE), direct("hello??"));

        verify(gate, never()).evaluate(anyString(), anyString(), anyString(), anyString());
        verify(agentChat, never()).chat(any(), any());
    }

    @Test
    void aPairedSenderGetsTheAgentsReplyThroughTheApprovalGate() throws Exception {
        ChannelSession paired = session(true, false, SENDER);
        when(sessions.resolveOrCreate(
                        eq(ChannelType.SIGNAL), eq(SENDER), eq("what time is it"), eq(SENDER)))
                .thenReturn(paired);
        agentReplies("half past nine");

        assertThat(
                        service.accept(
                                connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE),
                                direct("what time is it")))
                .isTrue();

        assertThat(lastGatePayload())
                .contains("half past nine")
                .contains("\"to\":\"" + SENDER + "\"");
        verify(api).sendTypingIndicator(any(), eq(SENDER));
    }

    @Test
    void theAiDisclosureRidesOnTheFirstReply() throws Exception {
        ChannelSession paired = session(true, false, SENDER);
        when(sessions.resolveOrCreate(any(), any(), any(), any())).thenReturn(paired);
        when(sessions.consumeAiDisclosurePrefix(paired.getId())).thenReturn("[AI] ");
        agentReplies("ok");

        service.accept(connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE), direct("hi"));

        assertThat(lastGatePayload()).contains("[AI] ok");
    }

    @Test
    void groupsAreIgnoredByDefaultBeforeAnythingIsStored() throws Exception {
        assertThat(
                        service.accept(
                                connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE),
                                group("hi all", true)))
                .isTrue();

        verify(dedup, never()).tryClaim(any(ChannelType.class), anyString());
        verify(sessions, never()).resolveOrCreate(any(), any(), any(), any());
        verify(gate, never()).evaluate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void mentionModeAnswersOnlyWhenTheAgentIsMentioned() throws Exception {
        ChannelSession paired = session(true, false, "group:" + GROUP);
        when(sessions.resolveOrCreate(
                        eq(ChannelType.SIGNAL), eq("group:" + GROUP), anyString(), isNull()))
                .thenReturn(paired);
        agentReplies("here");
        SignalConnection mention = connection(UnknownSenderPolicy.PAIR, SignalGroupMode.MENTION);

        service.accept(mention, group("chatter", false));
        verify(sessions, never()).resolveOrCreate(any(), any(), any(), any());

        service.accept(mention, group("@bot status", true));
        assertThat(lastGatePayload()).contains("\"to\":\"group:" + GROUP + "\"").contains("here");
    }

    @Test
    void allModeRoutesEveryGroupMessageButTheGroupMustStillBePaired() throws Exception {
        ChannelSession unpaired = session(false, false, "group:" + GROUP);
        when(sessions.resolveOrCreate(
                        eq(ChannelType.SIGNAL), eq("group:" + GROUP), anyString(), isNull()))
                .thenReturn(unpaired);

        service.accept(
                connection(UnknownSenderPolicy.PAIR, SignalGroupMode.ALL), group("hello", false));

        assertThat(lastGatePayload()).contains("pairing code").contains("group:" + GROUP);
        verify(agentChat, never()).chat(any(), any());
    }

    @Test
    void ignoreModeSaysNothingToAStrangerAndStoresNothing() throws Exception {
        when(sessions.peek(any(), any())).thenReturn(Optional.empty());
        when(sessions.isAutoApproveNewSenders(ChannelType.SIGNAL)).thenReturn(false);

        assertThat(
                        service.accept(
                                connection(UnknownSenderPolicy.IGNORE, SignalGroupMode.IGNORE),
                                direct("hello")))
                .isTrue();

        verify(sessions, never()).resolveOrCreate(any(), any(), any(), any());
        verify(gate, never()).evaluate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ignoreModeStillServesAKnownContactAndAnExistingSession() throws Exception {
        ChannelSession paired = session(true, false, SENDER);
        when(sessions.resolveOrCreate(any(), any(), any(), any())).thenReturn(paired);
        agentReplies("hi back");
        when(contacts.findForInbound(ChannelType.SIGNAL, SENDER, SENDER))
                .thenReturn(Optional.of(mock(ChannelContact.class)));

        service.accept(
                connection(UnknownSenderPolicy.IGNORE, SignalGroupMode.IGNORE), direct("hello"));

        assertThat(lastGatePayload()).contains("hi back");
    }

    @Test
    void ignoreModeServesAnAlreadyKnownSession() throws Exception {
        ChannelSession paired = session(true, false, SENDER);
        when(sessions.peek(ChannelType.SIGNAL, SENDER)).thenReturn(Optional.of(paired));
        when(sessions.resolveOrCreate(any(), any(), any(), any())).thenReturn(paired);
        agentReplies("known");

        service.accept(
                connection(UnknownSenderPolicy.IGNORE, SignalGroupMode.IGNORE), direct("hello"));

        assertThat(lastGatePayload()).contains("known");
    }

    @Test
    void aRedeliveredMessageIsANoOp() throws Exception {
        when(dedup.tryClaim(eq(ChannelType.SIGNAL), any())).thenReturn(false);

        assertThat(
                        service.accept(
                                connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE),
                                direct("again")))
                .isTrue();

        verify(sessions, never()).resolveOrCreate(any(), any(), any(), any());
    }

    @Test
    void receiptsAndTypingEventsNeverTouchThePipeline() throws Exception {
        JsonNode receipt =
                MAPPER.readTree(
                        "{\"envelope\":{\"sourceNumber\":\""
                                + SENDER
                                + "\",\"receiptMessage\":{}}}");

        assertThat(
                        service.accept(
                                connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE),
                                receipt))
                .isTrue();

        verify(dedup, never()).tryClaim(any(ChannelType.class), anyString());
    }

    @Test
    void aConversationHeldByAHumanSuppressesTheAgent() throws Exception {
        ChannelSession held = session(true, false, SENDER);
        ConversationHeldByHumanException heldException = new ConversationHeldByHumanException(held);
        when(sessions.resolveOrCreate(any(), any(), any(), any())).thenThrow(heldException);

        assertThat(
                        service.accept(
                                connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE),
                                direct("hi")))
                .isTrue();

        verify(agentChat, never()).chat(any(), any());
        verify(gate, never()).evaluate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void whenNoInboundSlotIsFreeTheClaimIsReleasedAndTheCallerIsTold() throws Exception {
        InboundChannelExecutor full = mock(InboundChannelExecutor.class);
        when(full.acquire("signal")).thenReturn(null);
        SignalInboundService shedding = newService(full);

        assertThat(
                        shedding.accept(
                                connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE),
                                direct("hi")))
                .isFalse();

        verify(dedup).release(eq(ChannelType.SIGNAL), anyString());
        verify(sessions, never()).resolveOrCreate(any(), any(), any(), any());
    }

    @Test
    void aSessionBoundToADeletedAgentGetsANoticeAndAnAuditRecord() throws Exception {
        ChannelSession paired = session(true, false, SENDER);
        when(sessions.resolveOrCreate(any(), any(), any(), any())).thenReturn(paired);
        AgentNotFoundException gone = new AgentNotFoundException(paired.getAgentId());
        when(agentChat.chat(any(), any())).thenThrow(gone);

        service.accept(connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE), direct("hi"));

        assertThat(lastGatePayload()).contains("no longer available");
        verify(audit)
                .record(
                        anyString(),
                        eq("channel.signal.agent_not_found"),
                        anyString(),
                        anyString(),
                        any(),
                        anyString());
    }

    @Test
    void aFailureInTheTurnIsContainedAndKeepsTheClaim() throws Exception {
        ChannelSession paired = session(true, false, SENDER);
        when(sessions.resolveOrCreate(any(), any(), any(), any())).thenReturn(paired);
        when(agentChat.chat(any(), any())).thenThrow(new IllegalStateException("model down"));

        assertThat(
                        service.accept(
                                connection(UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE),
                                direct("hi")))
                .isTrue();

        verify(dedup, never()).release(any(), any());
    }
}
