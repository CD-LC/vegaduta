package com.agenticai.core.omnichannel.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.agenticai.core.tenancy.demo.DemoChannelLink;
import com.agenticai.core.tenancy.demo.DemoChannelLinkService;
import com.agenticai.core.tenancy.demo.DemoChannelRateLimiter;
import com.agenticai.core.tenancy.demo.DemoChannelType;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.agenticai.core.tenancy.demo.DemoWhatsAppPhoneGuard;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

class DemoWhatsAppWebhookControllerTest {

    private static final String FROM = "919812345678";

    private DemoWhatsAppCloudApi meta;
    private DemoWhatsAppBot bot;
    private DemoSessionService sessions;
    private DemoChannelLinkService links;
    private DemoChannelRateLimiter limiter;
    private DemoWhatsAppPhoneGuard phoneGuard;
    private AgentChatService chat;
    private InboundMessageDedupService dedup;
    private DemoWhatsAppWebhookController controller;

    private DemoSession session;
    private DemoChannelLink link;
    private UUID sessionId;
    private String hash;

    @BeforeEach
    void setUp() {
        meta = mock(DemoWhatsAppCloudApi.class);
        PlatformSettingsService settings = DemoWhatsAppBotTest.settings(true);
        bot =
                DemoWhatsAppBotTest.bot(
                        meta,
                        settings,
                        "1234",
                        "tok",
                        DemoWhatsAppBotTest.SECRET,
                        "+919876543210",
                        "vt");
        hash = bot.hashPhone(FROM);
        sessions = mock(DemoSessionService.class);
        links = mock(DemoChannelLinkService.class);
        limiter = mock(DemoChannelRateLimiter.class);
        when(limiter.tryAcquire(anyString())).thenReturn(true);
        phoneGuard = mock(DemoWhatsAppPhoneGuard.class);
        when(phoneGuard.tryConsumeTurn(anyString(), anyInt())).thenReturn(true);
        when(phoneGuard.tryConsumePair(anyString(), anyInt())).thenReturn(true);
        chat = mock(AgentChatService.class);
        dedup = mock(InboundMessageDedupService.class);
        when(dedup.tryClaim(any(ChannelType.class), anyString())).thenReturn(true);

        sessionId = UUID.randomUUID();
        session = mock(DemoSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isExpired(any(Instant.class))).thenReturn(false);
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessions.isMessageCapReached(session)).thenReturn(false);
        when(sessions.messageLimit()).thenReturn(20);
        UUID agentId = UUID.randomUUID();
        when(sessions.ensureSeededAgent(any(), any())).thenReturn(Optional.of(agentId));
        when(sessions.inDemoTenantContext(any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null)))
                .thenReturn(Flux.just("Hello ", "from agent"));

        link = mock(DemoChannelLink.class);
        when(link.getId()).thenReturn(UUID.randomUUID());
        when(link.getDemoSessionId()).thenReturn(sessionId);

        controller = build(bot);
    }

    private DemoWhatsAppWebhookController build(DemoWhatsAppBot b) {
        return new DemoWhatsAppWebhookController(
                b,
                sessions,
                links,
                limiter,
                phoneGuard,
                chat,
                mock(AgentRepository.class),
                dedup,
                60,
                3,
                Runnable::run);
    }

    private static String payload(String pnid, String id, String from, String type, String text) {
        return "{\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\""
                + pnid
                + "\"},"
                + "\"messages\":[{\"id\":\""
                + id
                + "\",\"from\":\""
                + from
                + "\",\"type\":\""
                + type
                + "\","
                + "\"text\":{\"body\":\""
                + text
                + "\"}}]}}]}]}";
    }

    private ResponseEntity<Void> post(String json, boolean signed) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return controller.inbound(
                body, signed ? DemoWhatsAppBotTest.sign(body, DemoWhatsAppBotTest.SECRET) : null);
    }

    // ---------------------------------------------------------------- gates

    @Test
    void notConfiguredIsNoOp200() throws Exception {
        DemoWhatsAppBot blank =
                DemoWhatsAppBotTest.bot(
                        meta, DemoWhatsAppBotTest.settings(true), "", "", "", "", "");
        controller = build(blank);
        ResponseEntity<Void> r = post(payload("1234", "w1", FROM, "text", "hi"), false);
        assertEquals(200, r.getStatusCode().value());
        verifyNoInteractions(dedup, links, chat, meta);
    }

    @Test
    void badOrMissingSignatureIs403AndTouchesNothing() throws Exception {
        assertEquals(
                403,
                post(payload("1234", "w1", FROM, "text", "hi"), false).getStatusCode().value());
        byte[] body = payload("1234", "w1", FROM, "text", "hi").getBytes(StandardCharsets.UTF_8);
        assertEquals(
                403,
                controller
                        .inbound(body, DemoWhatsAppBotTest.sign(body, "wrong"))
                        .getStatusCode()
                        .value());
        verifyNoInteractions(dedup, links, chat, meta);
    }

    @Test
    void getVerifyHandshake() {
        assertEquals(200, controller.verify("subscribe", "vt", "CHAL").getStatusCode().value());
        assertEquals("CHAL", controller.verify("subscribe", "vt", "CHAL").getBody());
        assertEquals(403, controller.verify("subscribe", "bad", "CHAL").getStatusCode().value());
        assertEquals(403, controller.verify("subscribe", null, "CHAL").getStatusCode().value());
        assertEquals(403, controller.verify("unsubscribe", "vt", "CHAL").getStatusCode().value());
    }

    @Test
    void otherPhoneNumberIdIsIgnored() throws Exception {
        assertEquals(
                200,
                post(payload("9999", "w1", FROM, "text", "VD-7F3K"), true).getStatusCode().value());
        verifyNoInteractions(dedup, links, chat, meta);
    }

    @Test
    void duplicateWamidIsProcessedOnce() throws Exception {
        when(dedup.tryClaim(ChannelType.WHATSAPP, "demo:w1")).thenReturn(false);
        assertEquals(
                200,
                post(payload("1234", "w1", FROM, "text", "hello"), true).getStatusCode().value());
        verify(dedup).tryClaim(ChannelType.WHATSAPP, "demo:w1");
        verifyNoInteractions(links, chat, meta);
    }

    @Test
    void nonTextIsIgnored() throws Exception {
        post(payload("1234", "w1", FROM, "image", ""), true);
        verifyNoInteractions(links, chat, meta);
    }

    // ---------------------------------------------------------------- pairing

    @Test
    void codeRedeemsBindsAndWelcomesWithHonestLimits() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.empty());
        when(links.redeem(eq(DemoChannelType.WHATSAPP), eq("VD-7F3K"), eq(hash), any(), any()))
                .thenReturn(
                        new DemoChannelLinkService.RedeemResult(
                                DemoChannelLinkService.RedeemOutcome.LINKED, link, session));
        post(payload("1234", "w1", FROM, "text", "VD-7F3K"), true);

        // the stored external id is the HASH, never the raw number
        ArgumentCaptor<String> ext = ArgumentCaptor.forClass(String.class);
        verify(links)
                .redeem(eq(DemoChannelType.WHATSAPP), eq("VD-7F3K"), ext.capture(), any(), any());
        assertEquals(hash, ext.getValue());
        assertTrue(!ext.getValue().contains("9812345678"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(meta).sendTextMessage(eq("1234"), eq("tok"), eq(FROM), sent.capture());
        assertTrue(sent.getValue().contains("cannot message you first"));
        assertTrue(sent.getValue().contains("STOP"));
        verify(phoneGuard).tryConsumePair(eq(hash), eq(3));
        verifyNoInteractions(chat); // pairing costs no model turn
    }

    @Test
    void pairCapPerPhoneRevokesAndRefuses() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.empty());
        when(links.redeem(any(), anyString(), anyString(), any(), any()))
                .thenReturn(
                        new DemoChannelLinkService.RedeemResult(
                                DemoChannelLinkService.RedeemOutcome.LINKED, link, session));
        when(phoneGuard.tryConsumePair(anyString(), anyInt())).thenReturn(false);
        post(payload("1234", "w1", FROM, "text", "VD-7F3K"), true);
        verify(links).revokeById(link.getId());
        verify(meta).sendTextMessage(eq("1234"), eq("tok"), eq(FROM), contains("maximum number"));
    }

    @Test
    void unknownCodeGetsGuidance() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.empty());
        when(links.redeem(any(), anyString(), anyString(), any(), any()))
                .thenReturn(
                        new DemoChannelLinkService.RedeemResult(
                                DemoChannelLinkService.RedeemOutcome.UNKNOWN_CODE, null, null));
        post(payload("1234", "w1", FROM, "text", "VD-ZZZZ"), true);
        verify(meta).sendTextMessage(eq("1234"), eq("tok"), eq(FROM), contains("couldn't match"));
    }

    @Test
    void pairAttemptRateLimitIsSilent() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.empty());
        when(limiter.tryAcquire("wa-pair:" + hash)).thenReturn(false);
        post(payload("1234", "w1", FROM, "text", "VD-7F3K"), true);
        verify(links, never()).redeem(any(), any(), any(), any(), any());
        verifyNoInteractions(meta);
    }

    @Test
    void unpairedSenderGetsHelpNotAnAgentTurn() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.empty());
        post(payload("1234", "w1", FROM, "text", "hello, what is this?"), true);
        verify(meta).sendTextMessage(eq("1234"), eq("tok"), eq(FROM), contains("To connect"));
        verifyNoInteractions(chat);
        verify(sessions, never()).recordMessage(any());
    }

    @Test
    void unpairedHelpIsRateLimited() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.empty());
        when(limiter.tryAcquire("wa:" + hash)).thenReturn(false);
        post(payload("1234", "w1", FROM, "text", "hello"), true);
        verifyNoInteractions(meta, chat);
    }

    // ---------------------------------------------------------------- turns

    @Test
    void boundTurnRecordsBeforeModelCallAndRepliesInWindow() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.of(link));
        post(payload("1234", "w1", FROM, "text", "write a polite reply"), true);
        var order = org.mockito.Mockito.inOrder(sessions, chat, meta);
        order.verify(sessions).recordMessage(session);
        order.verify(chat)
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(
                                                        r.sessionId(), "demo-whatsapp:" + sessionId)
                                                && java.util.Objects.equals(
                                                        r.subjectKey(), "demo:" + sessionId)
                                                && java.util.Objects.equals(
                                                        r.userMessage(), "write a polite reply")));
        order.verify(meta).sendTextMessage(eq("1234"), eq("tok"), eq(FROM), eq("Hello from agent"));
        verify(phoneGuard).tryConsumeTurn(eq(hash), eq(60));
    }

    @Test
    void messageCapStopsTheTurn() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.of(link));
        when(sessions.isMessageCapReached(session)).thenReturn(true);
        post(payload("1234", "w1", FROM, "text", "one more"), true);
        verify(sessions, never()).recordMessage(any());
        verifyNoInteractions(chat);
        verify(meta)
                .sendTextMessage(eq("1234"), eq("tok"), eq(FROM), contains("used all 20 messages"));
    }

    @Test
    void perPhoneLifetimeCapStopsTheTurn() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.of(link));
        when(phoneGuard.tryConsumeTurn(anyString(), anyInt())).thenReturn(false);
        post(payload("1234", "w1", FROM, "text", "one more"), true);
        verify(sessions, never()).recordMessage(any());
        verifyNoInteractions(chat);
        verify(meta)
                .sendTextMessage(
                        eq("1234"), eq("tok"), eq(FROM), contains("total message allowance"));
    }

    @Test
    void expiredSessionRevokesAndSays() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.of(link));
        when(session.isExpired(any(Instant.class))).thenReturn(true);
        post(payload("1234", "w1", FROM, "text", "hi"), true);
        verify(links).revokeById(link.getId());
        verifyNoInteractions(chat);
    }

    @Test
    void modelFailureBecomesLabelledSampleAndStill200() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.of(link));
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null)))
                .thenReturn(Flux.error(new RuntimeException("model down")));
        ResponseEntity<Void> r = post(payload("1234", "w1", FROM, "text", "hi"), true);
        assertEquals(200, r.getStatusCode().value());
        verify(meta).sendTextMessage(eq("1234"), eq("tok"), eq(FROM), contains("Sample reply"));
    }

    @Test
    void stopDisconnects() throws Exception {
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, hash)).thenReturn(Optional.of(link));
        post(payload("1234", "w1", FROM, "text", "stop"), true);
        verify(links).revokeById(link.getId());
        verifyNoInteractions(chat);
    }

    @Test
    void killSwitchOffAnswersPausedAndSpendsNothing() throws Exception {
        DemoWhatsAppBot paused =
                DemoWhatsAppBotTest.bot(
                        meta,
                        DemoWhatsAppBotTest.settings(false),
                        "1234",
                        "tok",
                        DemoWhatsAppBotTest.SECRET,
                        "+919876543210",
                        "vt");
        controller = build(paused);
        post(payload("1234", "w1", FROM, "text", "hi"), true);
        verify(meta).sendTextMessage(eq("1234"), eq("tok"), eq(FROM), contains("paused"));
        verifyNoInteractions(chat);
        verify(sessions, never()).recordMessage(any());
    }
}
