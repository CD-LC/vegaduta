package com.agenticai.core.omnichannel.meta;

import static com.agenticai.core.omnichannel.meta.MetaWhatsAppReplyFlow.maskRecipient;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.outbound.ChannelTextSends;
import com.agenticai.core.omnichannel.relay.EdgeRelayHandoff;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.ConversationHeldByHumanException;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.omnichannel.whatsappvoice.VoiceNoteOutcome;
import com.agenticai.core.omnichannel.whatsappvoice.WhatsAppAudioRef;
import com.agenticai.core.omnichannel.whatsappvoice.WhatsAppVoiceNoteService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound WhatsApp via direct Meta Cloud API - the Meta-native counterpart to {@code
 * TwilioWebhookController}, reusing every downstream primitive it does (pairing gate,
 * approval-gated reply, workflow event) but structurally different at the front door because Meta's
 * webhook contract is itself structurally different from Twilio's:
 *
 * <ul>
 *   <li><b>A one-time GET verification handshake</b> ({@link #verify}) that Meta calls once when a
 *       tenant configures this URL in their Meta App dashboard - echoes back {@code hub.challenge}
 *       only if {@code hub.verify_token} matches {@code app.meta.webhook-verify-token}, a single
 *       platform-wide secret (not per-tenant - this handshake happens before any tenant context can
 *       be resolved at all, the same "platform- wide, not per-tenant" reasoning {@code
 *       EmailOutputService}/{@code TelegramOutputService} already use for their own shared
 *       credentials).
 *   <li><b>JSON, not form-encoded, and signed differently</b> - {@code X-Hub-Signature-256}
 *       (HMAC-SHA256 over the raw body) via {@link MetaWhatsAppSignatureValidator}, not Twilio's
 *       URL+params HMAC-SHA1.
 *   <li><b>Tenant resolution keyed by Phone Number ID</b>, read out of the payload's {@code
 *       entry[].changes[].value.metadata.phone_number_id} before the body can be trusted - same
 *       "resolve tenant first from an unverified but self-defeating-to-forge identifier, then
 *       verify using that tenant's own secret" reasoning {@code TwilioWebhookController}'s javadoc
 *       explains in full.
 *   <li><b>Batched payloads</b> - Meta's schema allows multiple {@code entry}/{@code changes} per
 *       call and mixes message events with delivery- status callbacks in the same shape; this
 *       handler processes every {@code text} message found and silently skips anything else (status
 *       callbacks, non-text message types) rather than erroring - an honest MVP scope, not full
 *       WhatsApp message-type coverage.
 * </ul>
 *
 * <p>Replies are queued through the exact same {@code HumanApprovalGateService.evaluate(<WhatsApp
 * ChannelTextSend action type>, ...)} call Twilio's webhook uses - the actual send then goes
 * through {@link com.agenticai.core.omnichannel.WhatsAppDispatchService}, which picks Meta or
 * Twilio per tenant independently of which channel the inbound message arrived on.
 *
 * <p><b>Channel-based trigger selection (#422 follow-up fix):</b> this controller originally never
 * checked {@link WorkflowTriggerNodeService} - only {@code TwilioWebhookController} did. A workflow
 * bound to the WHATSAPP trigger channel would fire for a Twilio-connected number but silently never
 * fire for a Meta-connected one. Fixed by adding the identical check, in the same place (after the
 * pairing gate, before the default agent-chat fallback).
 *
 * <p><b>Async since #617</b> ("takes too much time to reply back... improving the overall
 * performance") - same fix, same rationale, same {@link TenantContext} capture-then-restore shape
 * as {@code TwilioWebhookController}'s javadoc explains in full: the actual {@link
 * AgentChatService#chat} round trip and approval-gated send now run on a background task instead of
 * this request thread, so Meta's required fast 200 (see {@link #inbound}'s comment on why non-2xx
 * is avoided) no longer waits on a real LLM call.
 */
@RestController
@RequestMapping("/webhook/meta/whatsapp")
public class MetaWhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppWebhookController.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String VOICE_NOTE_PREVIEW = "[voice note]";

    private final String webhookVerifyToken;
    private final MetaWhatsAppSignatureValidator signatureValidator;
    private final MetaWhatsAppCredentialService credentialService;
    private final ChannelSessionService channelSessionService;
    private final TenantResolutionService tenantResolutionService;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final InboundMessageDedupService dedupService;
    private final InboundChannelExecutor inboundExecutor;
    private final MetaWhatsAppReplyFlow replies;
    private EdgeRelayHandoff edgeRelay;

    /** Edge relay (2026-10-14), optional so every existing construction site stays unchanged. */
    @Autowired(required = false)
    public void setEdgeRelay(EdgeRelayHandoff edgeRelay) {
        this.edgeRelay = edgeRelay;
    }

    public MetaWhatsAppWebhookController(
            @Value("${app.meta.webhook-verify-token:}") String webhookVerifyToken,
            MetaWhatsAppSignatureValidator signatureValidator,
            MetaWhatsAppCredentialService credentialService,
            ChannelSessionService channelSessionService,
            AgentChatService agentChatService,
            HumanApprovalGateService gateService,
            ChannelTextSends channelTextSends,
            TenantResolutionService tenantResolutionService,
            WorkflowEventPublisher workflowEventPublisher,
            WorkflowTriggerNodeService workflowTriggerNodeService,
            WorkflowTriggerPort workflowTriggerPort,
            AuditLogService auditLogService,
            InboundMessageDedupService dedupService,
            InboundChannelExecutor inboundExecutor) {
        this.webhookVerifyToken = webhookVerifyToken;
        this.signatureValidator = signatureValidator;
        this.credentialService = credentialService;
        this.channelSessionService = channelSessionService;
        this.tenantResolutionService = tenantResolutionService;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.dedupService = dedupService;
        this.inboundExecutor = inboundExecutor;
        this.replies =
                new MetaWhatsAppReplyFlow(
                        channelSessionService,
                        agentChatService,
                        gateService,
                        channelTextSends,
                        workflowEventPublisher,
                        workflowTriggerNodeService,
                        workflowTriggerPort,
                        auditLogService,
                        () -> this.edgeRelay,
                        () -> this.voiceNotes);
    }

    /**
     * Voice notes in the sender's language (core/omnichannel {@code whatsappvoice}).
     * Setter-injected and optional on purpose: the bean exists only when {@code
     * app.omnichannel.whatsapp.voice-notes.enabled=true}. With it absent (the default) this
     * controller behaves exactly as before - an audio message is logged and skipped like any
     * non-text type - and the constructor every existing test builds is unchanged.
     */
    private volatile WhatsAppVoiceNoteService voiceNotes;

    @Autowired(required = false)
    public void setVoiceNotes(WhatsAppVoiceNoteService voiceNotes) {
        this.voiceNotes = voiceNotes;
    }

    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        // Constant-time: String.equals returns at the first differing byte.
        boolean tokenMatches =
                verifyToken != null
                        && !webhookVerifyToken.isBlank()
                        && java.security.MessageDigest.isEqual(
                                verifyToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                webhookVerifyToken.getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8));
        if (!"subscribe".equals(mode) || !tokenMatches) {
            log.warn(
                    "Rejected Meta webhook verification handshake (mode={}, token match={})",
                    mode,
                    tokenMatches);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
    }

    @PostMapping
    public ResponseEntity<Void> inbound(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false)
                    String signatureHeader) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Could not parse inbound Meta webhook body as JSON", e);
            return ResponseEntity.ok()
                    .build(); // 200 regardless - Meta retries/disables the webhook on non-2xx.
        }

        boolean rejected = false;
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                String phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);
                if (phoneNumberId == null) {
                    continue;
                }
                rejected |= handleChange(phoneNumberId, value, rawBody, signatureHeader);
            }
        }
        if (rejected) {
            // Overloaded (inbound executor cap hit, after the bounded FIFO wait). The rejected
            // messages'
            // dedup claims were released, so a 503 makes Meta redeliver the payload: messages
            // already
            // accepted are skipped by the dedup claim and only the shed ones are processed on the
            // retry.
            // Caveat: that skip relies on Redis dedup, which fails OPEN (see
            // InboundChannelExecutor), so
            // during a Redis outage accepted messages may be answered twice on redelivery.
            // shed-mode=ack drops them instead and answers 200 (Meta is never throttled).
            return inboundExecutor.shedAcks()
                    ? ResponseEntity.ok().build()
                    : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * @return true if at least one message was shed because the inbound executor was at capacity.
     */
    private boolean handleChange(
            String phoneNumberId, JsonNode value, String rawBody, String signatureHeader) {
        Optional<String> tenantSchema =
                tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.META_WHATSAPP, phoneNumberId);
        if (tenantSchema.isEmpty()) {
            log.warn(
                    "No tenant link found for Meta Phone Number ID ***{} - ignoring.",
                    lastFour(phoneNumberId));
            return false;
        }
        boolean rejected = false;

        TenantContext.set(
                TenantContext.of(
                        tenantSchema.get(),
                        MetaWhatsAppReplyFlow.REQUESTED_BY_PREFIX + phoneNumberId,
                        Set.of()));
        try {
            // resolveActive() throws MetaWhatsAppNotConfiguredException (mapped
            // to 400, caught below) if this tenant's credential was somehow
            // deactivated between the link being created and now.
            MetaWhatsAppCredentialService.DecryptedCredential credential =
                    credentialService.resolveActive();
            signatureValidator.validateOrThrow(rawBody, signatureHeader, credential.appSecret());

            for (JsonNode message : value.path("messages")) {
                if (rejected) {
                    // The executor already shed one message of this change after the full bounded
                    // wait. Do not
                    // park the request for another wait per remaining message (that would outlast
                    // Meta's own
                    // timeout and cause overlapping redeliveries): leave the rest unclaimed so the
                    // 503
                    // redelivery processes them.
                    continue;
                }
                WhatsAppVoiceNoteService voice = voiceNotes;
                if (voice != null
                        && voice.isEnabled()
                        && "audio".equals(message.path("type").asText())) {
                    if (!handleVoiceNote(voice, message, credential)) {
                        dedupService.release(ChannelType.WHATSAPP, message.path("id").asText(null));
                        rejected = true;
                    }
                    continue;
                }
                if (!"text".equals(message.path("type").asText())) {
                    log.info(
                            "Skipping non-text Meta WhatsApp message type '{}'",
                            message.path("type").asText());
                    continue;
                }
                // Master-Roadmap-2026.md item #1, gap (b)#3 - the same fix
                // TwilioWebhookController (MessageSid) and
                // TelegramWebhookController (update_id) already carry: Meta
                // retries a webhook delivery on anything but a fast 2xx, so
                // the same message can arrive twice. Claimed on
                // messages[].id (Meta's stable per-message wamid) AFTER
                // signature validation - an unauthenticated caller must not
                // be able to burn a real message's dedup key - and BEFORE
                // any processing, per message rather than per payload since
                // Meta batches: a retried batch skips exactly the messages
                // already claimed on the first delivery. The 200 for the
                // whole payload still goes out either way (see #inbound).
                String messageId = message.path("id").asText(null);
                if (!dedupService.tryClaim(ChannelType.WHATSAPP, messageId)) {
                    log.info("Ignoring duplicate Meta WhatsApp message id {}", messageId);
                    continue;
                }
                String from = message.path("from").asText("");
                String body = message.path("text").path("body").asText("");
                if (!handleTextMessage(from, body)) {
                    dedupService.release(ChannelType.WHATSAPP, messageId);
                    rejected = true;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to process Meta WhatsApp webhook change: {}", e.getMessage());
        } finally {
            TenantContext.clear();
        }
        return rejected;
    }

    /**
     * @return false only if the reply could not be admitted to the inbound executor (caller
     *     releases the claim).
     */
    private boolean handleTextMessage(String from, String body) {
        // Admission BEFORE any side effect (session create, workflow event); see
        // InboundChannelExecutor. A message knowably cheap without a side effect (existing unpaired
        // sender, or a workflow bound to WhatsApp) takes an inline permit instead of an LLM slot,
        // so
        // LLM saturation never sheds it.
        boolean cheap =
                workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.WHATSAPP).isPresent()
                        || channelSessionService
                                .peek(ChannelType.WHATSAPP, from)
                                .map(x -> !x.isPaired())
                                .orElse(false);
        InboundChannelExecutor.Permit permit =
                cheap ? inboundExecutor.inline() : inboundExecutor.acquire("whatsapp_meta");
        if (permit == null) {
            log.warn(
                    "Inbound executor at capacity - whatsapp_meta text shed before any processing"
                            + " (claim released)");
            return false;
        }
        try {
            log.info("Inbound WhatsApp (Meta) message from {}", maskRecipient(from));
            ChannelSession session =
                    channelSessionService.resolveOrCreate(ChannelType.WHATSAPP, from, body);

            if (!session.isPaired()) {
                replies.handleUnpairedSender(session, from);
                return true;
            }
            return replies.routeInboundText(permit, session, from, body, null);
        } finally {
            permit.release(); // no-op once the LLM step took the slot
        }
    }

    /**
     * The voice-note half of {@link #handleChange}: claim, pairing gate, then transcribe + normal
     * turn off this thread.
     *
     * @return false only if the voice note could not be admitted to the inbound executor (caller
     *     releases the claim).
     */
    private boolean handleVoiceNote(
            WhatsAppVoiceNoteService voice,
            JsonNode message,
            MetaWhatsAppCredentialService.DecryptedCredential credential) {
        // Same dedup claim a text message gets, after signature validation and before any work.
        String messageId = message.path("id").asText(null);
        if (!dedupService.tryClaim(ChannelType.WHATSAPP, messageId)) {
            log.info("Ignoring duplicate Meta WhatsApp message id {}", messageId);
            return true;
        }
        String from = message.path("from").asText("");
        // Admission BEFORE any side effect (session create, transcription); see
        // InboundChannelExecutor.
        // The permit is the worker's slot: it is held through transcription and the inline chat
        // step.
        // An existing unpaired sender only gets the pairing notice (no transcription), so it is
        // inline.
        boolean cheap =
                channelSessionService
                        .peek(ChannelType.WHATSAPP, from)
                        .map(x -> !x.isPaired())
                        .orElse(false);
        InboundChannelExecutor.Permit permit =
                cheap ? inboundExecutor.inline() : inboundExecutor.acquire("whatsapp_meta");
        if (permit == null) {
            log.warn(
                    "Inbound executor at capacity - whatsapp_meta voice note shed before any"
                            + " processing (claim released)");
            return false;
        }
        try {
            return routeVoiceNote(permit, voice, message, credential, from);
        } finally {
            permit.release(); // no-op once the worker took the slot
        }
    }

    private boolean routeVoiceNote(
            InboundChannelExecutor.Permit permit,
            WhatsAppVoiceNoteService voice,
            JsonNode message,
            MetaWhatsAppCredentialService.DecryptedCredential credential,
            String from) {
        log.info("Inbound WhatsApp (Meta) voice note from {}", maskRecipient(from));
        // The preview is a fixed label - the audio is not transcribed yet and must not be before
        // pairing.
        ChannelSession session =
                channelSessionService.resolveOrCreate(
                        ChannelType.WHATSAPP, from, VOICE_NOTE_PREVIEW);
        if (!session.isPaired()) {
            replies.handleUnpairedSender(session, from);
            return true;
        }
        Optional<WhatsAppAudioRef> ref = WhatsAppAudioRef.parse(message);
        if (ref.isEmpty()) {
            replies.sendReplyThroughApprovalGate(
                    from,
                    channelSessionService.consumeAiDisclosurePrefix(session.getId())
                            + voice.fallbackText(VoiceNoteOutcome.INVALID_AUDIO_REF));
            return true;
        }
        WhatsAppVoiceNoteService.Credentials voiceCredentials =
                new WhatsAppVoiceNoteService.Credentials(
                        credential.phoneNumberId(), credential.accessToken());
        WhatsAppAudioRef audioRef = ref.get();
        if (!permit.run(
                com.agenticai.core.security.concurrent.ContextPropagatingExecutor.propagating(
                        () -> {
                            try {
                                WhatsAppVoiceNoteService.Transcription transcription =
                                        voice.transcribe(audioRef, voiceCredentials, from);
                                if (!transcription.ok()) {
                                    if (transcription.notifyUser()) {
                                        replies.sendReplyThroughApprovalGate(
                                                from,
                                                channelSessionService.consumeAiDisclosurePrefix(
                                                                session.getId())
                                                        + voice.fallbackText(
                                                                transcription.outcome()));
                                    }
                                    return;
                                }
                                // Pass the transcript through the same chokepoint a typed message
                                // passes: it lands in the conversation log, and the inbound safety
                                // scan (and a human-handoff hold) now sees what was actually SAID -
                                // the "[voice note]" placeholder above scanned nothing.
                                ChannelSession transcribedSession =
                                        channelSessionService.resolveOrCreate(
                                                ChannelType.WHATSAPP, from, transcription.text());
                                // null permit: this worker already holds a slot; the chat step runs
                                // inline in it.
                                replies.routeInboundText(
                                        null,
                                        transcribedSession,
                                        from,
                                        transcription.text(),
                                        new MetaWhatsAppReplyFlow.VoiceTurn(
                                                voiceCredentials, transcription.languageCode()));
                            } catch (ConversationHeldByHumanException e) {
                                log.info(
                                        "WhatsApp (Meta) voice note from {} held for a human"
                                                + " operator - agent not invoked",
                                        maskRecipient(from));
                            } catch (RuntimeException e) {
                                log.warn(
                                        "Async WhatsApp (Meta) voice note processing failed for"
                                                + " session {}: {}",
                                        session.getId(),
                                        e.getClass().getSimpleName());
                            }
                        }))) {
            log.warn(
                    "Inbound executor at capacity - whatsapp_meta voice note for session {}"
                            + " rejected by executor (claim released)",
                    session.getId());
            return false;
        }
        return true;
    }

    private static String lastFour(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return value.substring(value.length() - 4);
    }
}
