package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.ConversationHeldByHumanException;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.omnichannel.twilio.TwilioWhatsAppChannelAdapter;
import com.agenticai.core.omnichannel.twilio.TwilioWhatsAppTextSend;
import com.agenticai.core.omnichannel.whatsappvoice.VoiceNoteOutcome;
import com.agenticai.core.omnichannel.whatsappvoice.WhatsAppAudioRef;
import com.agenticai.core.omnichannel.whatsappvoice.WhatsAppVoiceNoteService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

/**
 * The voice-note wiring of the Meta WhatsApp webhook. Two things are pinned:
 *
 * <ul>
 *   <li><b>flag off = nothing changes</b>: with no {@link WhatsAppVoiceNoteService} bean (the
 *       default), or one that reports itself disabled, an audio message touches NOTHING - no dedup
 *       claim, no session, no approval gate, no agent - exactly the "skip non-text types" behaviour
 *       that existed before;
 *   <li><b>flag on</b>: pairing is checked before any speech cost, the transcript becomes an
 *       ordinary text turn (plus one language-hint line), and the spoken copy is sent only after
 *       the text reply was released by the approval gate.
 * </ul>
 *
 * Meta, Sarvam and the LLM are mocks; nothing here proves they accept these calls.
 */
class MetaWhatsAppWebhookControllerVoiceNoteTest {

    private static final String PHONE_NUMBER_ID = "1234567890";
    private static final String FROM = "15550001111";
    private static final String AUDIO_BODY =
            """
{"entry":[{"changes":[{"value":{
  "metadata":{"phone_number_id":"1234567890"},
  "messages":[{"type":"audio","id":"wamid.VOICE-1","from":"15550001111",
               "audio":{"mime_type":"audio/ogg; codecs=opus","sha256":"abc=","id":"9001","voice":true}}]
}}]}]}
""";
    private static final String AUDIO_BODY_NO_ID =
            """
            {"entry":[{"changes":[{"value":{
              "metadata":{"phone_number_id":"1234567890"},
              "messages":[{"type":"audio","id":"wamid.VOICE-2","from":"15550001111","audio":{}}]
            }}]}]}
            """;
    private static final String TEXT_BODY =
            """
{"entry":[{"changes":[{"value":{
  "metadata":{"phone_number_id":"1234567890"},
  "messages":[{"type":"text","id":"wamid.TEXT-1","from":"15550001111","text":{"body":"hello"}}]
}}]}]}
""";

    private ChannelSessionService channelSessionService;
    private InboundMessageDedupService dedupService;
    private HumanApprovalGateService gateService;
    private AgentChatService agentChatService;
    private WorkflowEventPublisher workflowEventPublisher;
    private WhatsAppVoiceNoteService voice;
    private ChannelSession session;
    private UUID agentId;
    private MetaWhatsAppWebhookController controller;

    @BeforeEach
    void setUp() {
        MetaWhatsAppSignatureValidator signatureValidator =
                mock(MetaWhatsAppSignatureValidator.class);
        MetaWhatsAppCredentialService credentialService = mock(MetaWhatsAppCredentialService.class);
        channelSessionService = mock(ChannelSessionService.class);
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        dedupService = mock(InboundMessageDedupService.class);
        gateService = mock(HumanApprovalGateService.class);
        agentChatService = mock(AgentChatService.class);
        workflowEventPublisher = mock(WorkflowEventPublisher.class);
        controller =
                new MetaWhatsAppWebhookController(
                        "verify-token",
                        signatureValidator,
                        credentialService,
                        channelSessionService,
                        agentChatService,
                        gateService,
                        new ChannelTextSends(List.of(new TwilioWhatsAppTextSend())),
                        tenantResolutionService,
                        workflowEventPublisher,
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        mock(AuditLogService.class),
                        dedupService,
                        InboundChannelExecutor.synchronous());

        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.META_WHATSAPP, PHONE_NUMBER_ID))
                .thenReturn(Optional.of("tenant_test"));
        when(credentialService.resolveActive())
                .thenReturn(
                        new MetaWhatsAppCredentialService.DecryptedCredential(
                                PHONE_NUMBER_ID, "access-token", "app-secret", "waba-1"));
        when(dedupService.tryClaim(eq(ChannelType.WHATSAPP), anyString())).thenReturn(true);

        agentId = UUID.randomUUID();
        session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(agentId);
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getExternalId()).thenReturn(FROM);
        when(channelSessionService.resolveOrCreate(
                        eq(ChannelType.WHATSAPP), anyString(), anyString()))
                .thenReturn(session);
        when(channelSessionService.consumeAiDisclosurePrefix(any(UUID.class))).thenReturn("");

        voice = mock(WhatsAppVoiceNoteService.class);
        when(voice.isEnabled()).thenReturn(true);
        when(voice.languageHint("hi-IN")).thenReturn("[HINT]");
        when(voice.fallbackText(any())).thenReturn("FALLBACK");

        when(gateService.evaluate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.autoApproved());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubTranscription(WhatsAppVoiceNoteService.Transcription transcription) {
        when(voice.transcribe(
                        any(WhatsAppAudioRef.class),
                        any(WhatsAppVoiceNoteService.Credentials.class),
                        anyString()))
                .thenReturn(transcription);
    }

    private void stubAgentReply(String reply) {
        when(agentChatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(Flux.just(reply));
    }

    // ---- flag off ----

    @Test
    void withNoVoiceBeanAnAudioMessageTouchesNothingAtAll() {
        // Controller as constructed by every pre-existing test: no setVoiceNotes call.
        ResponseEntity<Void> response = controller.inbound(AUDIO_BODY, "sha256=sig");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verifyNoInteractions(
                dedupService,
                channelSessionService,
                gateService,
                agentChatService,
                workflowEventPublisher);
    }

    @Test
    void aDisabledVoiceBeanBehavesTheSameAsNoBean() {
        when(voice.isEnabled()).thenReturn(false);
        controller.setVoiceNotes(voice);

        ResponseEntity<Void> response = controller.inbound(AUDIO_BODY, "sha256=sig");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verifyNoInteractions(
                dedupService,
                channelSessionService,
                gateService,
                agentChatService,
                workflowEventPublisher);
        verify(voice, never()).transcribe(any(), any(), anyString());
    }

    @Test
    void textMessagesAreUnchangedWhetherOrNotVoiceIsEnabled() {
        controller.setVoiceNotes(voice);
        stubAgentReply("hi there");

        controller.inbound(TEXT_BODY, "sha256=sig");

        verify(channelSessionService).resolveOrCreate(ChannelType.WHATSAPP, FROM, "hello");
        // Exactly the user's text - no language hint on a text turn.
        verify(agentChatService, timeout(5_000))
                .chat(
                        any(TurnContext.class),
                        eq(ChatTurnRequest.builder(agentId, "session-1", FROM, "hello").build()));
        verify(voice, never()).languageHint(any());
        verify(voice, never()).transcribe(any(), any(), anyString());
        verify(voice, never()).sendVoiceReply(any(), any(), any(), any());
    }

    // ---- flag on ----

    @Test
    void anUnpairedSenderGetsThePairingNoticeAndNoSpeechIsPaidFor() {
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(false);
        when(session.getPairingCode()).thenReturn("ABC123");
        controller.setVoiceNotes(voice);

        controller.inbound(AUDIO_BODY, "sha256=sig");

        verify(channelSessionService).resolveOrCreate(ChannelType.WHATSAPP, FROM, "[voice note]");
        verify(gateService)
                .evaluate(
                        eq(TwilioWhatsAppChannelAdapter.ACTION_TYPE),
                        anyString(),
                        contains("ABC123"),
                        anyString());
        verify(voice, never()).transcribe(any(), any(), anyString());
        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void aPairedSendersVoiceNoteRunsAsANormalTextTurnWithOneLanguageHintLine() {
        controller.setVoiceNotes(voice);
        stubTranscription(
                new WhatsAppVoiceNoteService.Transcription(
                        VoiceNoteOutcome.TRANSCRIBED, "namaste", "hi-IN", true));
        stubAgentReply("Namaste! Kaise madad karun?");

        ResponseEntity<Void> response = controller.inbound(AUDIO_BODY, "sha256=sig");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(voice, timeout(5_000))
                .transcribe(
                        new WhatsAppAudioRef("9001", "audio/ogg; codecs=opus", "abc=", true),
                        new WhatsAppVoiceNoteService.Credentials(PHONE_NUMBER_ID, "access-token"),
                        FROM);
        verify(agentChatService, timeout(5_000))
                .chat(
                        any(TurnContext.class),
                        eq(
                                ChatTurnRequest.builder(
                                                agentId, "session-1", FROM, "namaste\n\n[HINT]")
                                        .build()));
        verify(gateService, timeout(5_000))
                .evaluate(
                        eq(TwilioWhatsAppChannelAdapter.ACTION_TYPE),
                        anyString(),
                        contains("Namaste! Kaise madad karun?"),
                        anyString());
        // The pairing check ran on a placeholder; the transcript then went through the same
        // chokepoint a typed
        // message does (conversation log + inbound safety scan).
        verify(channelSessionService).resolveOrCreate(ChannelType.WHATSAPP, FROM, "[voice note]");
        verify(channelSessionService).resolveOrCreate(ChannelType.WHATSAPP, FROM, "namaste");
    }

    @Test
    void aTranscriptThatTheSafetyScanHoldsForAHumanNeverReachesTheAgent() throws Exception {
        controller.setVoiceNotes(voice);
        stubTranscription(
                new WhatsAppVoiceNoteService.Transcription(
                        VoiceNoteOutcome.TRANSCRIBED, "namaste", "hi-IN", true));
        // Built BEFORE the stubbing starts: its constructor reads the (mock) session, which would
        // otherwise
        // happen in the middle of when(...).thenThrow(...) and trip Mockito's UnfinishedStubbing
        // check.
        ConversationHeldByHumanException held = new ConversationHeldByHumanException(session);
        when(channelSessionService.resolveOrCreate(
                        eq(ChannelType.WHATSAPP), anyString(), eq("namaste")))
                .thenThrow(held);

        controller.inbound(AUDIO_BODY, "sha256=sig");

        verify(voice, timeout(5_000)).transcribe(any(), any(), anyString());
        verify(channelSessionService, timeout(5_000))
                .resolveOrCreate(ChannelType.WHATSAPP, FROM, "namaste");
        Thread.sleep(300);
        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        verify(voice, never()).sendVoiceReply(any(), any(), any(), any());
    }

    @Test
    void theSpokenCopyIsSentAfterTheTextReplyWasReleased() {
        controller.setVoiceNotes(voice);
        stubTranscription(
                new WhatsAppVoiceNoteService.Transcription(
                        VoiceNoteOutcome.TRANSCRIBED, "namaste", "hi-IN", true));
        stubAgentReply("Namaste! Kaise madad karun?");

        controller.inbound(AUDIO_BODY, "sha256=sig");

        verify(voice, timeout(5_000))
                .sendVoiceReply(
                        eq("Namaste! Kaise madad karun?"), eq("hi-IN"),
                        eq(
                                        new WhatsAppVoiceNoteService.Credentials(
                                                PHONE_NUMBER_ID, "access-token")),
                                eq(FROM));
    }

    @Test
    void aReplyWaitingForHumanApprovalIsNotLeakedAsAudio() throws Exception {
        controller.setVoiceNotes(voice);
        stubTranscription(
                new WhatsAppVoiceNoteService.Transcription(
                        VoiceNoteOutcome.TRANSCRIBED, "namaste", "hi-IN", true));
        stubAgentReply("Namaste!");
        when(gateService.evaluate(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ApprovalEvaluation.pending(UUID.randomUUID()));

        controller.inbound(AUDIO_BODY, "sha256=sig");

        verify(gateService, timeout(5_000))
                .evaluate(
                        eq(TwilioWhatsAppChannelAdapter.ACTION_TYPE),
                        anyString(),
                        contains("Namaste!"),
                        anyString());
        Thread.sleep(300);
        verify(voice, never()).sendVoiceReply(any(), any(), any(), any());
    }

    @Test
    void aVoiceNoteThatCannotBeUsedGetsOneShortTextFallbackAndNoAgentTurn() {
        controller.setVoiceNotes(voice);
        stubTranscription(
                new WhatsAppVoiceNoteService.Transcription(
                        VoiceNoteOutcome.STT_FAILED, null, null, true));

        controller.inbound(AUDIO_BODY, "sha256=sig");

        verify(gateService, timeout(5_000))
                .evaluate(
                        eq(TwilioWhatsAppChannelAdapter.ACTION_TYPE),
                        anyString(),
                        contains("FALLBACK"),
                        anyString());
        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        verify(voice, never()).sendVoiceReply(any(), any(), any(), any());
    }

    @Test
    void aSilencedRateLimitDenialSendsNothing() throws Exception {
        controller.setVoiceNotes(voice);
        stubTranscription(
                new WhatsAppVoiceNoteService.Transcription(
                        VoiceNoteOutcome.RATE_LIMITED, null, null, false));

        controller.inbound(AUDIO_BODY, "sha256=sig");

        verify(voice, timeout(5_000)).transcribe(any(), any(), anyString());
        Thread.sleep(300);
        verify(gateService, never()).evaluate(anyString(), anyString(), anyString(), anyString());
        verify(agentChatService, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void anAudioEntryWithoutAUsableMediaIdGetsTheFallbackWithoutCallingSpeech() {
        controller.setVoiceNotes(voice);

        controller.inbound(AUDIO_BODY_NO_ID, "sha256=sig");

        verify(gateService)
                .evaluate(
                        eq(TwilioWhatsAppChannelAdapter.ACTION_TYPE),
                        anyString(),
                        contains("FALLBACK"),
                        anyString());
        verify(voice, never()).transcribe(any(), any(), anyString());
    }

    @Test
    void aRetriedDeliveryOfTheSameVoiceNoteIsProcessedOnce() {
        controller.setVoiceNotes(voice);
        when(dedupService.tryClaim(ChannelType.WHATSAPP, "wamid.VOICE-1")).thenReturn(false);

        ResponseEntity<Void> response = controller.inbound(AUDIO_BODY, "sha256=sig");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(channelSessionService, never()).resolveOrCreate(any(), anyString(), anyString());
        verify(voice, never()).transcribe(any(), any(), anyString());
    }
}
