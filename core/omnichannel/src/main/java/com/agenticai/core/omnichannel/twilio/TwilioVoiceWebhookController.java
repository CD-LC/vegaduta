package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.omnichannel.trigger.WorkflowSyncTriggerResult;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.speech.SpeechTextNormalizer;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Master Roadmap 2026 item #17 (real-time voice agents) - the inbound-call
 * half of Twilio Conversation Relay, mirroring {@link TwilioWebhookController}'s
 * exact tenant-resolution/signature-validation shape for the WhatsApp
 * channel. Twilio calls {@link #inboundCall} the moment someone dials a
 * tenant's configured Twilio voice number; the TwiML this returns tells
 * Twilio to open a WebSocket back to {@link VoiceRelayWebSocketHandler}
 * (registered at {@code /webhook/twilio/voice-relay} - see {@link
 * VoiceRelayWebSocketConfig}), which is where the actual conversation
 * happens. This controller's only job is the TwiML handoff; the per-tenant
 * default-agent config VOICE needs (same as every other {@code ChannelType})
 * lives in {@link VoiceChannelController} instead, since that's an {@code
 * /api/channels/**} admin endpoint, not a {@code /webhook/**} one - keeping
 * them in one class would mix two different Spring Security matcher
 * postures ({@code permitAll()} vs {@code ROLE_tenant-admin}) under a single
 * {@code @RequestMapping}.
 *
 * <p><b>Channel-based trigger selection (#422), extended to CALL:</b> the
 * canvas's workflow-trigger CALL option shipped disabled ("coming soon")
 * because nothing here checked for a bound workflow - every inbound call
 * went straight to Conversation Relay unconditionally. {@link #inboundCall}
 * now checks {@link WorkflowTriggerNodeService#bindingFor} FIRST,
 * same "channel binding takes over from the channel's own default
 * behavior" precedence {@link TwilioWebhookController} already established
 * for WHATSAPP/SMS: a CALL-bound workflow gets a
 * {@code <Gather input="speech">} prompt instead of the Conversation Relay
 * handoff, and {@link #collectSpeech} (the gather's callback) runs that
 * workflow SYNCHRONOUSLY via {@link WorkflowTriggerPort#triggerSync} once
 * the caller finishes speaking - a caller on the line is live and waiting,
 * unlike WhatsApp/SMS's fire-and-forget {@code triggerAsync}. No workflow
 * bound to CALL means zero behavior change from before: every call still
 * goes to Conversation Relay exactly as it always has.
 *
 * <p><b>No outbound "send" endpoint exists for VOICE</b>, unlike every other
 * channel's {@code *SendController} - a voice reply is spoken live over the
 * Conversation Relay WebSocket by {@link VoiceRelayWebSocketHandler}, there
 * is nothing to queue or resend later.
 *
 * <p><b>wss:// URL construction</b> reuses the exact same {@code
 * app.omnichannel.public-base-url} override {@link TwilioSignatureValidator}
 * already relies on for the identical "Twilio needs a real public HTTPS URL,
 * not whatever Tomcat reconstructs behind a proxy/ngrok tunnel" problem - see
 * that class's javadoc. The scheme is swapped http(s) -> ws(s) since
 * Conversation Relay's {@code url} attribute is a WebSocket endpoint, not an
 * HTTP one.
 *
 * <p><b>Deliberately NOT wired into {@link
 * com.agenticai.core.omnichannel.session.InboundMessageDedupService}</b>,
 * unlike every message-shaped inbound controller (Wave-2 idempotency pass).
 * A voice webhook is a live call, not a replayable message: if Twilio
 * retries this webhook it's because the caller never got TwiML back, so the
 * call is still ringing and MUST be answered on the retry - claiming the
 * CallSid on the first (failed) attempt and then ACKing the retry with empty
 * TwiML would hang up on a live caller. There's also no double-processing to
 * prevent: the response IS the TwiML handoff, generated per request with no
 * message-log/agent-reply/workflow side effects to duplicate ({@link
 * #collectSpeech}'s synchronous workflow run happens on a Twilio {@code
 * <Gather>} action callback, which Twilio does not retry the way it does
 * message webhooks).
 */
@RestController
@RequestMapping("/webhook/twilio")
public class TwilioVoiceWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TwilioVoiceWebhookController.class);
    private static final String REQUESTED_BY_PREFIX = "system:voice-inbound:";
    private static final String WELCOME_GREETING = "Hi, connecting you now.";

    private final TwilioSignatureValidator signatureValidator;
    private final TwilioCredentialService credentialService;
    private final TenantResolutionService tenantResolutionService;
    private final WorkflowTriggerNodeService workflowTriggerNodeService;
    private final WorkflowTriggerPort workflowTriggerPort;
    private final String publicBaseUrlOverride;
    private final VoiceRelayTicketService voiceRelayTicketService;

    public TwilioVoiceWebhookController(
        TwilioSignatureValidator signatureValidator,
        TwilioCredentialService credentialService,
        TenantResolutionService tenantResolutionService,
        WorkflowTriggerNodeService workflowTriggerNodeService,
        WorkflowTriggerPort workflowTriggerPort,
        VoiceRelayTicketService voiceRelayTicketService,
        @Value("${app.omnichannel.public-base-url:}") String publicBaseUrlOverride
    ) {
        this.signatureValidator = signatureValidator;
        this.credentialService = credentialService;
        this.tenantResolutionService = tenantResolutionService;
        this.workflowTriggerNodeService = workflowTriggerNodeService;
        this.workflowTriggerPort = workflowTriggerPort;
        this.publicBaseUrlOverride = publicBaseUrlOverride;
        this.voiceRelayTicketService = voiceRelayTicketService;
    }

    /**
     * Twilio's inbound-call webhook - same trust posture as {@link
     * TwilioWebhookController#inboundWhatsApp}: resolve tenant from the
     * unauthenticated {@code AccountSid} claim FIRST (needed to look up
     * which tenant's Twilio auth token to validate the signature against),
     * then verify the signature actually proves the caller holds that
     * tenant's real secret before trusting anything else in the payload.
     * {@link TenantContext} stays set for the whole method now (not cleared
     * right after signature validation) since the CALL-trigger branch below
     * needs it for a schema-scoped lookup too.
     */
    @PostMapping(value = "/voice", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> inboundCall(
        @RequestParam Map<String, String> formParams,
        HttpServletRequest servletRequest
    ) {
        String accountSid = formParams.getOrDefault("AccountSid", "");
        String tenantSchema = tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, accountSid)
            .orElse(TenantSchemaResolver.DEFAULT_SCHEMA);

        TenantContext.set(TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + accountSid, Set.of()));
        try {
            TwilioCredentialService.DecryptedCredential credential = credentialService.resolveActive();
            signatureValidator.validateOrThrow(
                servletRequest, formParams, servletRequest.getHeader("X-Twilio-Signature"), credential.authToken()
            );

            // Channel-based trigger selection (#422), extended to CALL - see
            // class javadoc. A bound workflow takes over from Conversation
            // Relay entirely, same precedence WHATSAPP/SMS already use.
            String from = formParams.getOrDefault("From", "");
            Optional<WorkflowTriggerNodeService.TriggerBinding> binding = workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.CALL);
            if (binding.isPresent()) {
                if (!workflowTriggerNodeService.tryAcquireTriggerSlot(WorkflowTriggerChannel.CALL, from)) {
                    log.info("Inbound call from {} skipped - CALL workflow trigger rate limit exceeded", maskCaller(from));
                    return twiml(say("This line is receiving too many calls right now. Please try again shortly.") + "<Hangup/>");
                }
                log.info("Inbound call from {} routed to workflow {} (CALL trigger)", maskCaller(from), binding.get().workflowId());
                // "speech dtmf" (not "speech" alone) since V308: a workflow
                // whose bound Trigger leads straight into a Voice Menu node
                // (see WorkflowNodeType#VOICE_MENU) needs the caller able to
                // press a digit right here at the very first prompt, before
                // the workflow has even started - collectSpeech below reads
                // whichever of SpeechResult/Digits Twilio actually populated.
                // Purely additive for every workflow that only ever expects
                // speech: Twilio still reports SpeechResult exactly as before
                // when the caller talks instead of pressing a key.
                return twiml(
                    "<Gather input=\"speech dtmf\" action=\"/webhook/twilio/voice/collect\" method=\"POST\" speechTimeout=\"auto\">"
                        + say("Hi! Please say what you'd like help with after the beep.")
                        + "</Gather>"
                        + say("I didn't catch that - please call back and try again.")
                );
            }

            // "To" is the tenant's own number that the caller dialled, and
            // it reaches us on a request whose Twilio signature was verified
            // above - so it is the trustworthy half of the pair the relay
            // handler reads when it picks the agent. See
            // VoiceRelayWebSocketHandler#resolveDialedNumber.
            // Admission ticket for the relay socket, minted HERE because this is
            // the only point in a call where Twilio has proven who it is (the
            // signature check above). Sent both ways Twilio can carry it: a
            // <Parameter> (arrives in the setup frame's customParameters - the
            // documented channel) and the query string (kept because ?to=
            // already rides there). See VoiceRelayTicketService.
            String ticket = voiceRelayTicketService.mint(accountSid, formParams.getOrDefault("CallSid", ""), from);
            String wssUrl = resolveRelayUrl(servletRequest, formParams.getOrDefault("To", ""));
            wssUrl += (wssUrl.contains("?") ? "&" : "?") + "ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8);
            log.info("Inbound voice call {} - handing off to Conversation Relay at {}", formParams.get("CallSid"), wssUrl);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Response><Connect><ConversationRelay url=\"" + escapeXml(wssUrl) + "\" welcomeGreeting=\""
                    + WELCOME_GREETING + "\"><Parameter name=\"ticket\" value=\"" + escapeXml(ticket)
                    + "\" /></ConversationRelay></Connect></Response>"
            );
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The CALL-trigger {@code <Gather>}'s callback - Twilio posts the
     * transcribed {@code SpeechResult} (or, since V308, a pressed {@code
     * Digits}) here once the caller stops talking. Runs the bound workflow
     * SYNCHRONOUSLY ({@link WorkflowTriggerPort#triggerSync}, not {@code
     * triggerAsync}) since the caller is live on the line waiting to hear
     * something back, then either speaks the run's output and hangs up, or -
     * if the run halted on a {@code voice_menu} node - renders that node's
     * DTMF gather instead (see {@link #renderVoiceResult}). Own {@link
     * TenantContext} resolution - this is a separate HTTP request from
     * {@link #inboundCall}, Twilio does not carry any session across the two.
     */
    @PostMapping(value = "/voice/collect", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> collectSpeech(
        @RequestParam Map<String, String> formParams,
        HttpServletRequest servletRequest
    ) {
        String accountSid = formParams.getOrDefault("AccountSid", "");
        String tenantSchema = tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, accountSid)
            .orElse(TenantSchemaResolver.DEFAULT_SCHEMA);

        TenantContext.set(TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + accountSid, Set.of()));
        try {
            TwilioCredentialService.DecryptedCredential credential = credentialService.resolveActive();
            signatureValidator.validateOrThrow(
                servletRequest, formParams, servletRequest.getHeader("X-Twilio-Signature"), credential.authToken()
            );

            String from = formParams.getOrDefault("From", "");
            String speech = formParams.getOrDefault("SpeechResult", "");
            String digits = formParams.getOrDefault("Digits", "");
            Optional<WorkflowTriggerNodeService.TriggerBinding> binding = workflowTriggerNodeService.bindingFor(WorkflowTriggerChannel.CALL);
            if (binding.isEmpty()) {
                return twiml(say("Sorry, I didn't get a clear message.") + "<Hangup/>");
            }
            // V308: a workflow whose Trigger leads straight into a Voice Menu
            // node needs to run even when the caller pressed a key (or said/
            // pressed nothing) at this very first gather instead of speaking -
            // the run itself decides what happens next. Speech still wins
            // when both are present (the caller can only do one at a time in
            // practice, but SpeechResult is the more deliberate signal).
            String triggerInput = !speech.isBlank() ? speech
                : !digits.isBlank() ? digits
                : "(call started)";

            WorkflowSyncTriggerResult result = workflowTriggerPort.triggerSync(
                binding.get().workflowId(), binding.get().nodeId(), triggerInput, REQUESTED_BY_PREFIX + maskCaller(from),
                new InboundTriggerContext(WorkflowTriggerChannel.CALL, from)
            );
            return renderVoiceResult(result);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The Voice Menu node's {@code <Gather input="dtmf">} callback (V308) -
     * Twilio posts the pressed {@code Digits} (blank if the gather simply
     * timed out) here, correlated to the specific pending {@code
     * workflow_voice_menu_request} row via the {@code requestId} query
     * parameter {@link #renderVoiceResult} put on the action URL. Same
     * tenant-resolution/signature-validation shape as {@link #collectSpeech}
     * - a live call keeps re-entering this controller once per digit-gather
     * round trip, each one its own HTTP request with its own signature to
     * verify, for as many {@code voice_menu} nodes as the bound workflow's
     * graph chains together.
     */
    @PostMapping(value = "/voice/menu-collect", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> collectMenuDigits(
        @RequestParam Map<String, String> formParams,
        HttpServletRequest servletRequest
    ) {
        String accountSid = formParams.getOrDefault("AccountSid", "");
        String tenantSchema = tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, accountSid)
            .orElse(TenantSchemaResolver.DEFAULT_SCHEMA);

        TenantContext.set(TenantContext.of(tenantSchema, REQUESTED_BY_PREFIX + accountSid, Set.of()));
        try {
            TwilioCredentialService.DecryptedCredential credential = credentialService.resolveActive();
            signatureValidator.validateOrThrow(
                servletRequest, formParams, servletRequest.getHeader("X-Twilio-Signature"), credential.authToken()
            );

            String requestId = formParams.getOrDefault("requestId", "");
            if (requestId.isBlank()) {
                log.warn("Voice menu DTMF collection arrived with no requestId - nothing to resume.");
                return twiml(say("Sorry, something went wrong with that menu.") + "<Hangup/>");
            }
            String digits = formParams.getOrDefault("Digits", "");
            WorkflowSyncTriggerResult result = workflowTriggerPort.collectVoiceMenuDigits(
                requestId, digits, REQUESTED_BY_PREFIX + "dtmf");
            return renderVoiceResult(result);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Shared by {@link #collectSpeech} and {@link #collectMenuDigits}: a
     * {@link WorkflowSyncTriggerResult} either carries a finished run's
     * answer to speak, or (V308) a {@code voice_menu} node's halt describing
     * the NEXT DTMF gather to render - see {@link
     * WorkflowSyncTriggerResult#pendingVoiceMenu()}'s javadoc for why that
     * one field changes what the rest of the result means. {@code
     * requestId} rides the action URL's query string, which {@link
     * TwilioSignatureValidator} now signs correctly (see that class's
     * javadoc) - the standard, Twilio-documented way to thread per-gather
     * context through a webhook.
     */
    private ResponseEntity<String> renderVoiceResult(WorkflowSyncTriggerResult result) {
        WorkflowSyncTriggerResult.PendingVoiceMenu menu = result.pendingVoiceMenu();
        if (menu != null) {
            String action = "/webhook/twilio/voice/menu-collect?requestId="
                + URLEncoder.encode(menu.requestId(), StandardCharsets.UTF_8);
            String finishOnKey = menu.finishOnKey() == null || menu.finishOnKey().isBlank() ? "#" : menu.finishOnKey();
            StringBuilder gather = new StringBuilder("<Gather input=\"dtmf\" action=\"")
                .append(escapeXml(action)).append("\" method=\"POST\" timeout=\"")
                .append(Math.max(1, menu.timeoutSeconds())).append("\" finishOnKey=\"")
                .append(escapeXml(finishOnKey)).append("\"");
            if (menu.numDigits() != null && menu.numDigits() > 0) {
                gather.append(" numDigits=\"").append(menu.numDigits()).append("\"");
            }
            gather.append(">");
            String promptText = menu.promptText() == null || menu.promptText().isBlank()
                ? "Please make your selection." : menu.promptText();
            return twiml(gather.toString() + say(promptText) + "</Gather>" + say("I didn't get any input.") + "<Hangup/>");
        }
        String spoken = result.outputText() == null || result.outputText().isBlank()
            ? (result.succeeded() ? "Done, but there was nothing to report back." : "Sorry, something went wrong handling that.")
            : result.outputText();
        return twiml(say(truncateForSpeech(spoken)) + "<Hangup/>");
    }

    private ResponseEntity<String> twiml(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body("<Response>" + body + "</Response>");
    }

    /**
     * TwiML {@code <Say>} is a text-to-speech instruction, so whatever goes in
     * here is read out loud verbatim - including any markdown the workflow's
     * output text happens to carry (2026-07-29: "voice is reading all the
     * special characters"). {@link SpeechTextNormalizer} is applied before the
     * XML escaping, so a workflow that answers in markdown is spoken as prose
     * rather than as "asterisk asterisk". Normalizing here rather than at the
     * caller means both {@code <Say>} sites in this controller get it.
     */
    private String say(String text) {
        return "<Say>" + escapeXml(SpeechTextNormalizer.normalizeForSpeech(text)) + "</Say>";
    }

    /** TwiML {@code <Say>} has no documented hard length limit, but a very long spoken response is a poor phone experience regardless - keep it to a couple of sentences' worth. Runs BEFORE {@link #say}'s normalization, so the 600-character budget is measured on the raw text; normalization only ever shortens it further. */
    private String truncateForSpeech(String text) {
        return text.length() <= 600 ? text : text.substring(0, 600) + "...";
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String maskCaller(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return "***" + value.substring(value.length() - 4);
    }

    /**
     * {@code dialedNumber} rides along as a {@code ?to=} query parameter so
     * per-number agent routing still works even if Conversation Relay's
     * {@code setup} message does not carry a {@code to} field - see
     * {@link VoiceRelayWebSocketHandler#resolveDialedNumber} for which of the
     * two wins and why both exist. It is URL-encoded rather than
     * concatenated raw: an E.164 number leads with {@code +}, and {@code +}
     * in a query string decodes to a space, which would silently turn
     * {@code +15551234567} into {@code " 15551234567"} and match no mapping.
     * The caller runs the result through {@link #escapeXml} before embedding
     * it in the TwiML attribute, so a second parameter's {@code &amp;} would
     * not produce malformed XML if one is ever added here.
     */
    private String resolveRelayUrl(HttpServletRequest request, String dialedNumber) {
        String base;
        if (StringUtils.hasText(publicBaseUrlOverride)) {
            base = publicBaseUrlOverride.replaceAll("/+$", "");
        } else {
            base = request.getRequestURL().toString().replace(request.getRequestURI(), "");
        }
        String wsBase = base.startsWith("https://") ? "wss://" + base.substring("https://".length())
            : base.startsWith("http://") ? "ws://" + base.substring("http://".length())
            : base;
        String relayUrl = wsBase + "/webhook/twilio/voice-relay";
        if (!StringUtils.hasText(dialedNumber)) {
            return relayUrl;
        }
        return relayUrl + "?to=" + URLEncoder.encode(dialedNumber, StandardCharsets.UTF_8);
    }
}
