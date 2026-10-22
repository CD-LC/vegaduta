package com.agenticai.core.omnichannel.imessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.ConversationHeldByHumanException;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

class ImessageUpdateServiceTest {

    private static final String SENDER = "+15550100100";
    private static final String CHAT = "iMessage;-;+15550100100";

    private ChannelSessionService sessions;
    private AgentChatService chat;
    private HumanApprovalGateService gate;
    private InboundMessageDedupService dedup;
    private ImessageCredentialService credentials;
    private ImessageRateLimiter limiter;
    private ImessageUpdateService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant_a", "test", Set.of()));
        sessions = mock(ChannelSessionService.class);
        chat = mock(AgentChatService.class);
        gate = mock(HumanApprovalGateService.class);
        dedup = mock(InboundMessageDedupService.class);
        credentials = mock(ImessageCredentialService.class);
        limiter = new ImessageRateLimiter(20, 30, System::currentTimeMillis);
        when(dedup.tryClaim(any(ChannelType.class), anyString())).thenReturn(true);
        when(sessions.consumeAiDisclosurePrefix(any())).thenReturn("");
        when(chat.chat(any(), any())).thenReturn(Flux.just("Hi ", "there"));
        service =
                new ImessageUpdateService(
                        sessions,
                        chat,
                        gate,
                        mock(AuditLogService.class),
                        dedup,
                        InboundChannelExecutor.synchronous(),
                        credentials,
                        limiter);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void settings(Set<String> allowed, boolean groups, UnknownSenderPolicy policy) {
        when(credentials.inboundSettings())
                .thenReturn(
                        Optional.of(
                                new ImessageCredentialService.InboundSettings(
                                        allowed, groups, policy)));
    }

    private static JsonNode event(
            String guid, String chatGuid, int style, String sender, String text) throws Exception {
        return JsonMappers.plain()
                .readTree(
                        "{\"type\":\"new-message\",\"data\":{\"guid\":\""
                                + guid
                                + "\",\"text\":\""
                                + text
                                + "\",\"isFromMe\":false,\"handle\":{\"address\":\""
                                + sender
                                + "\"},\"chats\":[{\"guid\":\""
                                + chatGuid
                                + "\",\"style\":"
                                + style
                                + "}]}}");
    }

    private ChannelSession session(boolean paired, boolean noticeSent) {
        ChannelSession s = mock(ChannelSession.class);
        when(s.getId()).thenReturn(UUID.randomUUID());
        when(s.getAgentId()).thenReturn(UUID.randomUUID());
        when(s.getSessionId()).thenReturn("sess");
        when(s.getExternalId()).thenReturn(CHAT);
        when(s.isPaired()).thenReturn(paired);
        when(s.isPairingNoticeSent()).thenReturn(noticeSent);
        when(s.getPairingCode()).thenReturn("ABC123");
        return s;
    }

    private String sentPayload() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gate)
                .evaluate(
                        eq(ImessageChannelAdapter.ACTION_TYPE),
                        anyString(),
                        payload.capture(),
                        anyString());
        return payload.getValue();
    }

    @Test
    void anAllowListedSenderIsPairedOnTheSpotAndGetsAnAgentReply() throws Exception {
        settings(Set.of(SENDER), false, UnknownSenderPolicy.PAIR);
        ChannelSession unpaired = session(false, false);
        ChannelSession paired = session(true, false);
        when(sessions.resolveOrCreate(ChannelType.IMESSAGE, CHAT, "hello")).thenReturn(unpaired);
        when(sessions.approvePairing(unpaired.getId())).thenReturn(paired);

        assertThat(service.acceptVerified(event("m1", CHAT, 45, SENDER, "hello"))).isTrue();

        verify(sessions).approvePairing(unpaired.getId());
        String payload = sentPayload();
        assertThat(payload).contains("Hi there").contains(CHAT);
    }

    @Test
    void anUnlistedSenderGetsAPairingCodeAndNoAgentTurn() throws Exception {
        settings(Set.of(), false, UnknownSenderPolicy.PAIR);
        ChannelSession unpaired = session(false, false);
        when(sessions.resolveOrCreate(ChannelType.IMESSAGE, CHAT, "hello")).thenReturn(unpaired);

        assertThat(service.acceptVerified(event("m1", CHAT, 45, SENDER, "hello"))).isTrue();

        assertThat(sentPayload()).contains("ABC123");
        verify(sessions).markPairingNoticeSent(unpaired.getId());
        verify(sessions, never()).approvePairing(any());
        verifyNoInteractions(chat);
    }

    @Test
    void aStillUnpairedSenderIsNotNotifiedTwice() throws Exception {
        settings(Set.of(), false, UnknownSenderPolicy.PAIR);
        ChannelSession notified = session(false, true);
        when(sessions.resolveOrCreate(ChannelType.IMESSAGE, CHAT, "hello")).thenReturn(notified);

        service.acceptVerified(event("m1", CHAT, 45, SENDER, "hello"));

        verifyNoInteractions(gate, chat);
    }

    @Test
    void ignorePolicyDropsAnUnlistedSenderBeforeAnythingIsWritten() throws Exception {
        settings(Set.of("+15550999999"), false, UnknownSenderPolicy.IGNORE);

        assertThat(service.acceptVerified(event("m1", CHAT, 45, SENDER, "hello"))).isTrue();

        verifyNoInteractions(sessions, gate, chat, dedup);
    }

    @Test
    void groupsAreOffByDefault() throws Exception {
        settings(Set.of(SENDER), false, UnknownSenderPolicy.PAIR);

        service.acceptVerified(event("m1", "iMessage;+;chat1", 43, SENDER, "hello"));

        verifyNoInteractions(sessions, gate, chat, dedup);
    }

    @Test
    void inAnEnabledGroupAStrangerIsIgnoredAndNeverSentAPairingCode() throws Exception {
        settings(Set.of("+15550999999"), true, UnknownSenderPolicy.PAIR);

        service.acceptVerified(event("m1", "iMessage;+;chat1", 43, SENDER, "hello"));

        verifyNoInteractions(sessions, gate, chat);
    }

    @Test
    void inAnEnabledGroupAListedSpeakerIsAnswered() throws Exception {
        settings(Set.of(SENDER), true, UnknownSenderPolicy.PAIR);
        ChannelSession paired = session(true, false);
        when(sessions.resolveOrCreate(ChannelType.IMESSAGE, "iMessage;+;chat1", "hello"))
                .thenReturn(paired);

        service.acceptVerified(event("m1", "iMessage;+;chat1", 43, SENDER, "hello"));

        assertThat(sentPayload()).contains("iMessage;+;chat1").contains("Hi there");
    }

    @Test
    void spellingOfTheSenderDoesNotMatterForTheAllowList() throws Exception {
        settings(Set.of(SENDER), false, UnknownSenderPolicy.IGNORE);
        ChannelSession paired = session(true, false);
        when(sessions.resolveOrCreate(any(), anyString(), anyString())).thenReturn(paired);

        service.acceptVerified(event("m1", CHAT, 45, "+1 (555) 010-0100", "hello"));

        verify(gate)
                .evaluate(
                        eq(ImessageChannelAdapter.ACTION_TYPE),
                        anyString(),
                        anyString(),
                        anyString());
    }

    @Test
    void aDuplicateDeliveryIsANoOp() throws Exception {
        settings(Set.of(SENDER), false, UnknownSenderPolicy.PAIR);
        when(dedup.tryClaim(ChannelType.IMESSAGE, "m1")).thenReturn(false);

        assertThat(service.acceptVerified(event("m1", CHAT, 45, SENDER, "hello"))).isTrue();

        verifyNoInteractions(sessions, gate, chat);
    }

    @Test
    void aChatOverTheRateLimitIsDroppedBeforeTheDedupClaim() throws Exception {
        settings(Set.of(SENDER), false, UnknownSenderPolicy.PAIR);
        limiter = new ImessageRateLimiter(1, 30, System::currentTimeMillis);
        service =
                new ImessageUpdateService(
                        sessions,
                        chat,
                        gate,
                        mock(AuditLogService.class),
                        dedup,
                        InboundChannelExecutor.synchronous(),
                        credentials,
                        limiter);
        ChannelSession paired = session(true, false);
        when(sessions.resolveOrCreate(any(), anyString(), anyString())).thenReturn(paired);

        service.acceptVerified(event("m1", CHAT, 45, SENDER, "one"));
        service.acceptVerified(event("m2", CHAT, 45, SENDER, "two"));

        verify(dedup).tryClaim(ChannelType.IMESSAGE, "m1");
        verify(dedup, never()).tryClaim(ChannelType.IMESSAGE, "m2");
    }

    @Test
    void ownMessagesNeverReachAnything() throws Exception {
        settings(Set.of(SENDER), false, UnknownSenderPolicy.PAIR);
        JsonNode own =
                JsonMappers.plain()
                        .readTree(
                                "{\"type\":\"new-message\",\"data\":{\"guid\":\"o1\",\"text\":\"my reply\",\"isFromMe\":true,"
                                        + "\"chats\":[{\"guid\":\""
                                        + CHAT
                                        + "\",\"style\":45}]}}");

        assertThat(service.acceptVerified(own)).isTrue();

        verifyNoInteractions(sessions, gate, chat, dedup);
    }

    @Test
    void nothingHappensWhenNoCredentialIsActive() throws Exception {
        when(credentials.inboundSettings()).thenReturn(Optional.empty());

        assertThat(service.acceptVerified(event("m1", CHAT, 45, SENDER, "hello"))).isTrue();

        verifyNoInteractions(sessions, gate, chat, dedup);
    }

    @Test
    void aConversationHeldByAHumanDoesNotInvokeTheAgent() throws Exception {
        settings(Set.of(SENDER), false, UnknownSenderPolicy.PAIR);
        ConversationHeldByHumanException held =
                new ConversationHeldByHumanException(session(true, false));
        when(sessions.resolveOrCreate(ChannelType.IMESSAGE, CHAT, "hello")).thenThrow(held);

        assertThat(service.acceptVerified(event("m1", CHAT, 45, SENDER, "hello"))).isTrue();

        verifyNoInteractions(gate, chat);
    }

    @Test
    void whenTheInboundExecutorIsFullTheClaimIsReleasedAndFalseIsReturned() throws Exception {
        settings(Set.of(SENDER), false, UnknownSenderPolicy.PAIR);
        InboundChannelExecutor full = mock(InboundChannelExecutor.class);
        when(full.acquire(anyString())).thenReturn(null);
        service =
                new ImessageUpdateService(
                        sessions,
                        chat,
                        gate,
                        mock(AuditLogService.class),
                        dedup,
                        full,
                        credentials,
                        limiter);

        assertThat(service.acceptVerified(event("m1", CHAT, 45, SENDER, "hello"))).isFalse();

        verify(dedup).release(ChannelType.IMESSAGE, "m1");
        verifyNoInteractions(sessions);
    }
}
