package com.agenticai.core.omnichannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.meta.MetaWhatsAppCredentialService;
import com.agenticai.core.omnichannel.meta.MetaWhatsAppSignatureValidator;
import com.agenticai.core.omnichannel.meta.MetaWhatsAppWebhookController;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.telegram.TelegramApiClient;
import com.agenticai.core.omnichannel.telegram.TelegramCredential;
import com.agenticai.core.omnichannel.telegram.TelegramCredentialRepository;
import com.agenticai.core.omnichannel.telegram.TelegramUpdateService;
import com.agenticai.core.omnichannel.telegram.TelegramWebhookController;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.omnichannel.twilio.TwilioCredentialService;
import com.agenticai.core.omnichannel.twilio.TwilioSignatureValidator;
import com.agenticai.core.omnichannel.twilio.TwilioWebhookController;
import com.agenticai.core.omnichannel.twilio.TwilioWhatsAppTextSend;
import com.agenticai.core.omnichannel.whatsappvoice.VoiceNoteOutcome;
import com.agenticai.core.omnichannel.whatsappvoice.WhatsAppAudioRef;
import com.agenticai.core.omnichannel.whatsappvoice.WhatsAppVoiceNoteService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

/**
 * Overload behaviour of every inbound channel webhook that uses {@link InboundChannelExecutor}.
 * Meta and Telegram (providers that redeliver on 503) shed with the dedup claim released and a 503;
 * Twilio (no redelivery) is NEVER dropped, even with every cap and overflow exhausted; cheap paths
 * (pairing notice, workflow trigger) take no LLM slot even at the cap; and the Meta voice-note
 * worker runs its chat step inline in its own slot instead of re-submitting. Providers and the LLM
 * are mocks.
 */
class InboundWebhookOverloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FROM = "15550001111";

    private final InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
    private final ChannelSessionService sessions = mock(ChannelSessionService.class);
    private final TenantResolutionService tenants = mock(TenantResolutionService.class);
    private final AgentChatService chat = mock(AgentChatService.class);
    private final HumanApprovalGateService gate = mock(HumanApprovalGateService.class);

    /** Sheds everything, like a full executor. */
    private static InboundChannelExecutor shedding() {
        return new InboundChannelExecutor(
                r -> {
                    throw new RejectedExecutionException("full");
                },
                1,
                1,
                null);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ChannelSession pairedSession(ChannelSessionService svc, boolean qr) {
        ChannelSession session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("s1");
        when(session.getExternalId()).thenReturn(FROM);
        when(svc.resolveOrCreate(any(), anyString(), anyString())).thenReturn(session);
        when(svc.resolveOrCreate(any(), anyString(), anyString(), any())).thenReturn(session);
        when(svc.consumeAiDisclosurePrefix(any(UUID.class))).thenReturn("");
        return session;
    }

    // ---- Meta ----

    private MetaWhatsAppWebhookController meta(InboundChannelExecutor ex) {
        MetaWhatsAppCredentialService cred = mock(MetaWhatsAppCredentialService.class);
        when(tenants.resolveTenantSchema(ExternalAccountProvider.META_WHATSAPP, "1234567890"))
                .thenReturn(Optional.of("tenant_test"));
        when(cred.resolveActive())
                .thenReturn(
                        new MetaWhatsAppCredentialService.DecryptedCredential(
                                "1234567890", "tok", "secret", "waba"));
        when(dedup.tryClaim(eq(ChannelType.WHATSAPP), anyString())).thenReturn(true);
        pairedSession(sessions, false);
        when(gate.evaluate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.autoApproved());
        return new MetaWhatsAppWebhookController(
                "v",
                mock(MetaWhatsAppSignatureValidator.class),
                cred,
                sessions,
                chat,
                gate,
                new ChannelTextSends(List.of(new TwilioWhatsAppTextSend())),
                tenants,
                mock(WorkflowEventPublisher.class),
                mock(WorkflowTriggerNodeService.class),
                mock(WorkflowTriggerPort.class),
                mock(AuditLogService.class),
                dedup,
                ex);
    }

    private static final String META_TEXT =
            """
{"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"1234567890"},
  "messages":[{"type":"text","id":"wamid.T1","from":"15550001111","text":{"body":"hi"}}]}}]}]}
""";
    private static final String META_AUDIO =
            """
{"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"1234567890"},
  "messages":[{"type":"audio","id":"wamid.A1","from":"15550001111","audio":{"mime_type":"audio/ogg","sha256":"x=","id":"9001","voice":true}}]}}]}]}
""";

    @Test
    void metaTextShedUnderLoadReleasesTheClaimAndAsksMetaToRetry() {
        ResponseEntity<Void> r = meta(shedding()).inbound(META_TEXT, "sha256=sig");

        assertEquals(503, r.getStatusCode().value());
        verify(dedup).release(ChannelType.WHATSAPP, "wamid.T1");
    }

    @Test
    void metaVoiceNoteShedUnderLoadReleasesTheClaimAndAsksMetaToRetry() {
        MetaWhatsAppWebhookController c = meta(shedding());
        WhatsAppVoiceNoteService voice = mock(WhatsAppVoiceNoteService.class);
        when(voice.isEnabled()).thenReturn(true);
        c.setVoiceNotes(voice);

        ResponseEntity<Void> r = c.inbound(META_AUDIO, "sha256=sig");

        assertEquals(503, r.getStatusCode().value());
        verify(dedup).release(ChannelType.WHATSAPP, "wamid.A1");
        verify(voice, never()).transcribe(any(), any(), anyString());
    }

    @Test
    void metaVoiceNoteRunsItsChatStepInlineInItsOwnSlotEvenAtTheCapOfOne() {
        // Cap of 1 global / 1 tenant: the voice worker holds the only slot, so a nested submit for
        // the
        // chat step would be rejected and the already-transcribed (paid) note lost.
        InboundChannelExecutor real =
                new InboundChannelExecutor(Executors.newVirtualThreadPerTaskExecutor(), 1, 1, null);
        MetaWhatsAppWebhookController c = meta(real);
        WhatsAppVoiceNoteService voice = mock(WhatsAppVoiceNoteService.class);
        when(voice.isEnabled()).thenReturn(true);
        when(voice.languageHint("hi-IN")).thenReturn("[HINT]");
        when(voice.transcribe(
                        any(WhatsAppAudioRef.class),
                        any(WhatsAppVoiceNoteService.Credentials.class),
                        anyString()))
                .thenReturn(
                        new WhatsAppVoiceNoteService.Transcription(
                                VoiceNoteOutcome.TRANSCRIBED, "namaste", "hi-IN", true));
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("reply"));
        c.setVoiceNotes(voice);

        ResponseEntity<Void> r = c.inbound(META_AUDIO, "sha256=sig");

        assertEquals(200, r.getStatusCode().value());
        verify(chat, timeout(5_000))
                .chat(
                        any(TurnContext.class),
                        argThat(
                                turn ->
                                        java.util.Objects.equals(turn.sessionId(), "s1")
                                                && java.util.Objects.equals(turn.subjectKey(), FROM)
                                                && java.util.Objects.equals(
                                                        turn.userMessage(), "namaste\n\n[HINT]")));
        verify(gate, timeout(5_000))
                .evaluate(
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.contains("reply"),
                        anyString());
        verify(dedup, never()).release(any(), anyString());
        real.destroy();
    }

    // ---- Twilio: never dropped ----

    private TwilioWebhookController twilio(
            InboundChannelExecutor ex, WorkflowEventPublisher events) {
        TwilioCredentialService cred = mock(TwilioCredentialService.class);
        when(tenants.resolveTenantSchema(ExternalAccountProvider.TWILIO, "AC1"))
                .thenReturn(Optional.of("tenant_test"));
        when(cred.resolveActive())
                .thenReturn(
                        new TwilioCredentialService.DecryptedCredential(
                                "AC1", "t", "k", "s", "+15550009999"));
        when(dedup.tryClaim(eq(ChannelType.WHATSAPP), anyString())).thenReturn(true);
        pairedSession(sessions, false);
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just("ok"));
        when(gate.evaluate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.autoApproved());
        return new TwilioWebhookController(
                mock(TwilioSignatureValidator.class),
                cred,
                sessions,
                chat,
                gate,
                tenants,
                events,
                mock(WorkflowTriggerNodeService.class),
                mock(WorkflowTriggerPort.class),
                mock(AuditLogService.class),
                dedup,
                ex);
    }

    private static ResponseEntity<String> twilioPost(TwilioWebhookController c) {
        return c.inboundWhatsApp(
                Map.of(
                        "AccountSid",
                        "AC1",
                        "MessageSid",
                        "SM1",
                        "From",
                        "whatsapp:+15550001111",
                        "Body",
                        "hi"),
                mock(HttpServletRequest.class));
    }

    @Test
    void twilioNeverShedsEvenWhenTheDelegateRejectsEverything() {
        ResponseEntity<String> r =
                twilioPost(twilio(shedding(), mock(WorkflowEventPublisher.class)));

        assertEquals(200, r.getStatusCode().value());
        verify(dedup, never()).release(any(), anyString());
        verify(chat)
                .chat(
                        any(TurnContext.class),
                        argThat(
                                turn ->
                                        java.util.Objects.equals(
                                                turn.userMessage(),
                                                "hi"))); // ran on the request thread
    }

    @Test
    void twilioAtCapWithOverflowExhaustedStillProcessesOnTheRequestThread() {
        ResponseEntity<String> r =
                twilioPost(twilio(full(false, 0, 0), mock(WorkflowEventPublisher.class)));

        assertEquals(200, r.getStatusCode().value());
        verify(dedup, never()).release(any(), anyString());
        verify(chat)
                .chat(
                        any(TurnContext.class),
                        argThat(turn -> java.util.Objects.equals(turn.userMessage(), "hi")));
    }

    @Test
    void twilioPairingNoticePathTakesNoLlmStepAtTheCap() {
        TwilioWebhookController c = twilio(full(false, 0, 0), mock(WorkflowEventPublisher.class));
        ChannelSession unpaired = mock(ChannelSession.class);
        when(unpaired.isPaired()).thenReturn(false);
        when(unpaired.isPairingNoticeSent()).thenReturn(true);
        when(sessions.resolveOrCreate(any(), anyString(), anyString())).thenReturn(unpaired);

        assertEquals(200, twilioPost(c).getStatusCode().value());
        verify(chat, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        verify(dedup, never()).release(any(), anyString());
    }

    @Test
    void twilioWaitsForASlotInsteadOfShedding() throws Exception {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(
                        Executors.newVirtualThreadPerTaskExecutor(),
                        1,
                        1,
                        4,
                        5_000,
                        0,
                        false,
                        null);
        TenantContext.set(TenantContext.of("tenant_test", "sub", java.util.Set.of()));
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ex.submit(
                "hold",
                () -> {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                    }
                });
        TenantContext.clear();
        TwilioWebhookController c = twilio(ex, mock(WorkflowEventPublisher.class));

        java.util.concurrent.atomic.AtomicReference<ResponseEntity<String>> out =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> out.set(twilioPost(c)));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (ex.waiting() != 1) { // parked in the FIFO queue: no fixed sleep
            org.junit.jupiter.api.Assertions.assertTrue(
                    System.nanoTime() < deadline, "request never parked");
            Thread.onSpinWait();
        }
        release.countDown();
        t.join(5_000);

        assertEquals(200, out.get().getStatusCode().value());
        verify(dedup, never()).release(any(), anyString());
        verify(chat, timeout(5_000))
                .chat(
                        any(TurnContext.class),
                        argThat(turn -> java.util.Objects.equals(turn.userMessage(), "hi")));
        ex.destroy();
    }

    // ---- Telegram ----

    private record Tg(
            TelegramWebhookController controller, EncryptionService enc, TelegramApiClient api) {}

    private Tg telegram(InboundChannelExecutor ex, boolean paired) {
        TelegramCredentialRepository repo = mock(TelegramCredentialRepository.class);
        EncryptionService enc = mock(EncryptionService.class);
        TelegramApiClient api = mock(TelegramApiClient.class);
        when(tenants.resolveTenantSchema(ExternalAccountProvider.TELEGRAM, "bot"))
                .thenReturn(Optional.of("tenant_test"));
        TelegramCredential cred = mock(TelegramCredential.class);
        when(cred.getWebhookSecret()).thenReturn("sec");
        when(cred.getEncryptedBotToken()).thenReturn("enc");
        when(repo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(cred));
        when(enc.decrypt("enc")).thenReturn("bot-token");
        when(dedup.tryClaim(eq(ChannelType.TELEGRAM), anyString())).thenReturn(true);
        ChannelSession session = pairedSession(sessions, false);
        when(session.isPaired()).thenReturn(paired);
        when(session.isPairingNoticeSent()).thenReturn(true);
        when(sessions.peek(ChannelType.TELEGRAM, "chat-1")).thenReturn(Optional.of(session));
        when(gate.evaluate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.autoApproved());
        return new Tg(
                new TelegramWebhookController(
                        tenants,
                        repo,
                        enc,
                        new TelegramUpdateService(
                                sessions,
                                chat,
                                gate,
                                mock(WorkflowEventPublisher.class),
                                mock(AuditLogService.class),
                                dedup,
                                api,
                                mock(WorkflowTriggerNodeService.class),
                                mock(WorkflowTriggerPort.class),
                                ex),
                        ex),
                enc,
                api);
    }

    private static com.fasterxml.jackson.databind.JsonNode update() throws Exception {
        return MAPPER.readTree(
                "{\"update_id\":\"42\",\"message\":{\"chat\":{\"id\":\"chat-1\"},\"from\":{\"id\":\"u1\"},\"text\":\"hi\"}}");
    }

    @Test
    void telegramCheapPathsTakeNoSlotSoTheyAreNeverShedEvenAtTheCap() throws Exception {
        // Existing unpaired sender (notice already sent): peek() says cheap, so an inline permit is
        // used
        // although the executor is full for this tenant.
        Tg tg = telegram(full(false, 0, 0), false);
        ResponseEntity<Void> r = tg.controller().inbound("bot", update(), "sec");

        assertEquals(200, r.getStatusCode().value());
        verify(dedup, never()).release(any(), anyString());
        verify(chat, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void telegramLlmTurnForAPairedSenderIsShedAtTheCapWithTheClaimReleased() throws Exception {
        assertEquals(
                503,
                telegram(full(false, 0, 0), true)
                        .controller()
                        .inbound("bot", update(), "sec")
                        .getStatusCode()
                        .value());
        verify(dedup).release(ChannelType.TELEGRAM, "42");
        verify(chat, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void telegramDecryptFailureDoesNotLeaveADedupClaimBehind() throws Exception {
        Tg tg = telegram(InboundChannelExecutor.synchronous(), true);
        when(tg.enc().decrypt("enc")).thenThrow(new IllegalStateException("bad key"));

        ResponseEntity<Void> r = tg.controller().inbound("bot", update(), "sec");

        assertEquals(200, r.getStatusCode().value());
        verify(dedup, never()).tryClaim(any(ChannelType.class), anyString());
    }

    @Test
    void telegramAcceptedUpdateRunsItsTurnOnTheRequestThreadHoldingASlot() throws Exception {
        InboundChannelExecutor real =
                new InboundChannelExecutor(Executors.newVirtualThreadPerTaskExecutor(), 4, 4, null);
        Tg tg = telegram(real, true);
        java.util.concurrent.atomic.AtomicReference<Thread> ranOn =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        inv -> {
                            ranOn.set(Thread.currentThread());
                            return Flux.just("ok");
                        });

        ResponseEntity<Void> r = tg.controller().inbound("bot", update(), "sec");

        assertEquals(200, r.getStatusCode().value());
        // Same thread as before the executor existed: the update is acked after the turn, so the
        // next
        // update for the chat is not started first (per-chat ordering preserved).
        assertEquals(Thread.currentThread(), ranOn.get());
        verify(chat)
                .chat(
                        any(TurnContext.class),
                        argThat(
                                turn ->
                                        java.util.Objects.equals(turn.sessionId(), "s1")
                                                && java.util.Objects.equals(turn.subjectKey(), FROM)
                                                && java.util.Objects.equals(
                                                        turn.userMessage(), "hi")));
        verify(dedup, never()).release(any(), anyString());
        assertEquals(0, real.waiting());
        real.destroy();
    }

    // ---- cap-based shedding happens BEFORE any side effect ----

    /**
     * An executor that is already full for tenant_test (its one slot is held and never finishes).
     */
    private static InboundChannelExecutor full(boolean shedAcks, long waitMs, int overflowMax) {
        InboundChannelExecutor ex =
                new InboundChannelExecutor(r -> {}, 1, 1, 4, waitMs, overflowMax, shedAcks, null);
        TenantContext.set(TenantContext.of("tenant_test", "sub", java.util.Set.of()));
        ex.submit("hold", () -> {});
        TenantContext.clear();
        return ex;
    }

    @Test
    void metaCapShedWritesNothingAndTwoModesAnswer503OrAck() {
        MetaWhatsAppWebhookController c = meta(full(false, 0, 0));
        assertEquals(503, c.inbound(META_TEXT, "sha256=sig").getStatusCode().value());
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
        verify(dedup).release(ChannelType.WHATSAPP, "wamid.T1");

        MetaWhatsAppWebhookController acking = meta(full(true, 0, 0));
        assertEquals(200, acking.inbound(META_TEXT, "sha256=sig").getStatusCode().value());
    }

    @Test
    void metaPairingNoticePathTakesNoSlotAtTheCap() {
        MetaWhatsAppWebhookController c = meta(full(false, 0, 0));
        ChannelSession unpaired = mock(ChannelSession.class);
        when(unpaired.isPaired()).thenReturn(false);
        when(unpaired.isPairingNoticeSent()).thenReturn(true);
        when(sessions.peek(ChannelType.WHATSAPP, FROM)).thenReturn(Optional.of(unpaired));
        when(sessions.resolveOrCreate(any(), anyString(), anyString())).thenReturn(unpaired);

        assertEquals(200, c.inbound(META_TEXT, "sha256=sig").getStatusCode().value());
        verify(dedup, never()).release(any(), anyString());
    }

    @Test
    void metaVoiceCapShedWritesNothing() {
        MetaWhatsAppWebhookController c = meta(full(false, 0, 0));
        WhatsAppVoiceNoteService voice = mock(WhatsAppVoiceNoteService.class);
        when(voice.isEnabled()).thenReturn(true);
        c.setVoiceNotes(voice);

        assertEquals(503, c.inbound(META_AUDIO, "sha256=sig").getStatusCode().value());
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
        verify(dedup).release(ChannelType.WHATSAPP, "wamid.A1");
    }

    @Test
    void telegramCapShedWritesNothingAndHonoursShedMode() throws Exception {
        assertEquals(
                503,
                telegram(full(false, 0, 0), true)
                        .controller()
                        .inbound("bot", update(), "sec")
                        .getStatusCode()
                        .value());
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
        verify(dedup).release(ChannelType.TELEGRAM, "42");

        assertEquals(
                200,
                telegram(full(true, 0, 0), true)
                        .controller()
                        .inbound("bot", update(), "sec")
                        .getStatusCode()
                        .value());
    }
}
