package com.agenticai.core.voice;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.providers.modality.SpeechToTextProviderFactory;
import com.agenticai.core.providers.modality.TtsModelProvider;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.logging.LogSafe;
import com.agenticai.core.speech.SpeechStreamNormalizer;
import com.agenticai.core.speech.SpeechTextNormalizer;
import com.agenticai.core.speech.call.CallSpeechQuotaExceededException;
import com.agenticai.core.speech.call.CallSpeechSynthesizer;
import com.agenticai.core.speech.call.CallSpeechTranscriber;
import com.agenticai.core.voice.dto.SpeakRequest;
import com.agenticai.core.voice.dto.SpeakResponse;
import com.agenticai.core.voice.dto.VoiceTokenRequest;
import com.agenticai.core.voice.dto.VoiceTokenResponse;
import com.agenticai.core.voice.dto.VoiceTurnAudioChunkEvent;
import com.agenticai.core.voice.dto.VoiceTurnResponse;
import com.agenticai.core.voice.dto.VoiceTurnTranscriptEvent;
import jakarta.validation.Valid;
import java.io.IOException;
import java.security.Principal;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * Self-hosted in-app voice/video calling - Phase 1 of the self-reliance initiative
 * (browser-to-agent WebRTC via a self-hosted LiveKit server, zero PSTN, zero Twilio; independent of
 * and unrelated to {@code core/omnichannel/twilio}). See this module's pom.xml javadoc for the
 * overall architecture.
 *
 * <p>Two endpoints:
 *
 * <ul>
 *   <li>{@link #issueToken} - mints a short-lived, room-scoped LiveKit access token so the browser
 *       can join the call over WebRTC (mic/camera capture, connection UI). JWT-authenticated,
 *       tenant-scoped, exactly like every other endpoint under {@code /api/**} once the "security"
 *       profile is on - see {@code SecurityConfig}. Also forwards the caller's own bearer token
 *       into the minted LiveKit token's {@code metadata} claim (see {@link
 *       LiveKitTokenService#issueToken(String, String, String, String)}) so the {@code voice-agent}
 *       worker (LiveKit Agents, Python - {@code deploy/docker-compose.yml}'s {@code voice-agent}
 *       service, a DIFFERENT session's scope, not this module's) can call back into {@link
 *       com.agenticai.core.agents.core.AgentController#chat} as this same user once it joins the
 *       room. That worker is assumed to register for LiveKit's AUTOMATIC dispatch (no explicit
 *       {@code agent_name}) so it joins every room this endpoint creates with no explicit
 *       AgentDispatchService call needed here - see the {@code voice-agent} compose service's
 *       comment and {@code docs/AGENT-COORDINATION-2026-07-18.md} for the cross-scope flag on that
 *       assumption.
 *   <li>{@link #turn} - the pragmatic MVP answer to "how does the user's voice actually reach the
 *       agent". This is deliberately NOT wired through LiveKit's own media pipeline: LiveKit has no
 *       first-party Java server-side agent framework (its STT/TTS "Agents" ecosystem is Python/Node
 *       only), and building a Java WebRTC media-track subscriber from scratch was not realistic in
 *       this session. Instead, the browser records a push-to-talk utterance (MediaRecorder) and
 *       POSTs it here as a plain multipart upload - still one request per utterance, not continuous
 *       streaming, whether or not a LiveKit call is also connected. The RESPONSE, however, streams
 *       (Server-Sent Events, one {@code transcript} event, then interleaved {@code reply-delta}/
 *       {@code audio-chunk} events as the reply is generated and each sentence is synthesized, then
 *       one {@code done} summary) rather than blocking until the whole reply and its audio are
 *       ready, so playback can start before the model has finished responding - see this method's
 *       own javadoc for the event sequence. See core/voice's module javadoc and the frontend
 *       CallPanel component for how the two are combined in the UI.
 *   <li>Call recording ({@link VoiceCallRecordingController}) - opt-in (see {@link
 *       LiveKitEgressService}'s {@code enabled} flag, default OFF) LiveKit Egress call recording.
 *       NOT yet wired to the frontend's actual call lifecycle - {@code CallPanel.tsx}'s {@code
 *       startCall}/{@code endCall} need a follow-up change (out of scope for the pass that added
 *       these two endpoints) to call them right after {@code room.connect()} resolves and right
 *       before {@code room.disconnect()}, respectively.
 * </ul>
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceCallController {

    private static final Logger log = LoggerFactory.getLogger(VoiceCallController.class);

    private final AgentService agentService;
    private final LiveKitTokenService tokenService;
    // Reply TTS tiers and the STT fallback, behind the speech module's
    // live-call SPIs (ADR-0011 rule a: elevenlabs/sarvam implement them).
    // Primary TTS: ElevenLabs (tenant's cloned voice, or the public "Rachel"
    // voice). Second tier (2026-08-03 fix): the tenant's own Sarvam
    // credential - a tenant with Sarvam but no ElevenLabs used to get silent
    // "text-only-fallback". Either may be absent (null) in a distribution
    // without that vendor; the tier is then skipped.
    private final CallSpeechSynthesizer elevenLabsTts;
    private final CallSpeechSynthesizer sarvamTts;
    private final VoiceTurnSpeech turnSpeech;
    private final VoiceTurnStream turnStream;
    private final VoiceTurnTranscriber voiceTurnTranscriber;
    // Absent on an edition without realtime/recording voice (personal); then nothing can record a
    // room.
    private final Optional<VoiceCallRoomRegistry> roomRegistry;

    // Sarvam TTS quota gate (2026-08-08 audit fix #2): the Sarvam tier checks
    // the shared cross-provider voice budget (speech.call.CallSpeechQuota)
    // before every synthesis, so a FREE tenant with a Sarvam BYOK credential
    // cannot get unlimited TTS by falling through past ElevenLabs. The
    // platform-subsidised Sarvam path (PlatformSarvamSpeakBackend, a
    // different caller) correctly stays ungated by this BYOK cap.

    public VoiceCallController(
            AgentService agentService,
            AgentChatService agentChatService,
            LiveKitTokenService tokenService,
            Optional<SidecarClient> sidecarClient,
            List<CallSpeechSynthesizer> synthesizers,
            Optional<CallSpeechTranscriber> transcriber,
            Optional<VoiceCallRoomRegistry> roomRegistry,
            SpeechToTextProviderFactory providerFactory) {
        this.agentService = agentService;
        this.tokenService = tokenService;
        this.elevenLabsTts = synthesizer(synthesizers, "elevenlabs");
        this.sarvamTts = synthesizer(synthesizers, "sarvam");
        this.turnSpeech = new VoiceTurnSpeech(elevenLabsTts, sarvamTts);
        this.turnStream = new VoiceTurnStream(agentChatService, turnSpeech);
        this.voiceTurnTranscriber =
                new VoiceTurnTranscriber(sidecarClient, transcriber.orElse(null), providerFactory);
        this.roomRegistry = roomRegistry;
    }

    private static CallSpeechSynthesizer synthesizer(
            List<CallSpeechSynthesizer> synthesizers, String method) {
        return synthesizers.stream()
                .filter(s -> method.equals(s.method()))
                .findFirst()
                .orElse(null);
    }

    /**
     * @param jwt the caller's own resolved bearer token (Spring Security's standard resource-server
     *     binding - the same {@code Authorization: Bearer <token>} header that authenticated this
     *     very request, see {@code web/app/lib/api.ts}'s {@code apiFetch}). {@code null} when
     *     there's no authenticated JWT principal (security profile off, or an anonymous caller) -
     *     same optionality {@code principal} below already has. Forwarded to {@link
     *     LiveKitTokenService#issueToken(String, String, String, String)} so the {@code
     *     voice-agent} worker can read it back off the room once it joins; see that method's
     *     javadoc for the full mechanism and why this path was chosen over a client-side {@code
     *     setMetadata()} call.
     * @param request OPTIONAL body selecting the call mode - see {@link VoiceTokenRequest}. {@code
     *     required = false} keeps every pre-existing caller (which sends no body at all) working
     *     unchanged: absent body, absent/blank {@code mode}, and {@code "voice"} all mint the same
     *     {@code voice-} room as before; {@code "sight"} mints a {@code sight-} room, which is the
     *     sole signal that flips the {@code voice-agent} worker into perception mode.
     */
    @PostMapping("/agents/{agentId}/token")
    public VoiceTokenResponse issueToken(
            @PathVariable UUID agentId,
            @RequestBody(required = false) VoiceTokenRequest request,
            Principal principal,
            @AuthenticationPrincipal Jwt jwt) {
        // 404s if this agent doesn't exist / isn't visible in the caller's
        // tenant schema - same guard AgentController#get relies on.
        agentService.get(agentId);

        String subject =
                principal != null && StringUtils.hasText(principal.getName())
                        ? principal.getName()
                        : "anon-" + UUID.randomUUID();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse("default");
        // The mode string doubles as the room-name prefix ("voice-"/"sight-")
        // - see VoiceTokenRequest's javadoc for why the prefix is the only
        // thing that differs between the two modes. An unrecognized value is
        // coerced to "voice" (logged, not 400d): throwing here would hit
        // GlobalExceptionHandler's catch-all and surface as an opaque 500
        // (see startRecording's javadoc for that pre-existing constraint),
        // which is a worse failure shape for a typo'd client than a working
        // audio-only call.
        String mode =
                request != null && StringUtils.hasText(request.mode())
                        ? request.mode().trim().toLowerCase(Locale.ROOT)
                        : "voice";
        if (!"voice".equals(mode) && !"sight".equals(mode)) {
            log.warn("Unknown voice token mode {} for agent {} - treating as voice", mode, agentId);
            mode = "voice";
        }
        // Room is per-call, not a standing per-agent room, so two concurrent
        // calls with the same agent (or a reconnect after a drop) never
        // collide inside LiveKit.
        String roomName = mode + "-" + agentId + "-" + shortId();
        String identity = tenantId + ":" + subject + ":" + shortId();
        String callerAccessToken = jwt != null ? jwt.getTokenValue() : null;

        // Participant metadata: the caller's bearer token (see this method's
        // javadoc) plus the caller's optional per-call reply-voice pick
        // (VoiceTokenRequest.ttsVoice/ttsVoiceProvider - the worker reads
        // them back off the room and applies them to its TTS plugin for this
        // call only; see voice-agent's main.py _extract_tts_voice). LinkedHashMap
        // (not Map.of) because callerAccessToken is legitimately null.
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(callerAccessToken)) {
            metadata.put("coreAccessToken", callerAccessToken);
        }
        if (request != null && StringUtils.hasText(request.ttsVoice())) {
            String ttsVoice = request.ttsVoice().trim();
            // Opaque provider-side id (an ElevenLabs voice_id, a Sarvam
            // speaker name) - capped so a junk field can't bloat the JWT.
            metadata.put(
                    "ttsVoice", ttsVoice.length() > 120 ? ttsVoice.substring(0, 120) : ttsVoice);
            if (StringUtils.hasText(request.ttsVoiceProvider())) {
                metadata.put("ttsVoiceProvider", request.ttsVoiceProvider().trim());
            }
        }

        String token = tokenService.issueToken(identity, roomName, subject, metadata);
        // Who this room belongs to, so only they can record it (VoiceCallRecordingController).
        roomRegistry.ifPresent(registry -> registry.register(roomName, tenantId, subject));
        // Deliberately logs identity/room/agentId only - NEVER callerAccessToken
        // or the minted LiveKit token itself, same convention this codebase
        // already follows for other bearer credentials.
        log.info(
                "Issued LiveKit token for agent {} room {} identity {}",
                agentId,
                roomName,
                LogSafe.clean(identity));
        return new VoiceTokenResponse(token, tokenService.getUrl(), roomName, identity);
    }

    /**
     * Synthesizes speech for arbitrary text (e.g. an already-rendered chat reply from {@link
     * com.agenticai.core.agents.turn.AgentChatService#chat}), independent of the STT/chat pipeline
     * {@link #turn} runs - lets the text chat UI offer a "play this reply" control without going
     * through the voice call panel. Same ElevenLabs-then-fallback logic as {@link #turn}'s TTS leg:
     * {@code method} comes back {@code "text-only-fallback"} (empty {@code audioBase64}) when
     * ElevenLabs isn't configured, and the frontend is expected to use the browser's Web Speech API
     * in that case.
     */
    @PostMapping("/agents/{agentId}/speak")
    public SpeakResponse speak(
            @PathVariable UUID agentId, @Valid @RequestBody SpeakRequest request) {
        // 404s if this agent doesn't exist / isn't visible in the caller's
        // tenant schema - same guard issueToken relies on. Captured (Stage 2,
        // 2026-08-09 - previously discarded) so an explicit ttsProvider
        // selection can route this call, same as updateVoiceModel already
        // does for the STT side.
        AgentResponse agentInfo = agentService.get(agentId);

        String text = request != null ? request.text() : null;
        if (!StringUtils.hasText(text)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "No text was provided to speak.");
        }

        // What a synthesizer should SAY, as opposed to what the transcript
        // SHOWS: markdown/HTML/emoji/bare URLs rewritten, everything else
        // left alone (Indic scripts included - see SpeechTextNormalizer).
        // ElevenLabsClient#textToSpeech normalizes its own input too, so this
        // is not what makes the audio clean; it's computed here so the
        // "text-only-fallback" branch can hand the browser's Web Speech API
        // the same speech-ready string instead of raw markdown.
        String speechText = SpeechTextNormalizer.normalizeForSpeech(text);

        SpeechSynthesisOutcome synthesis =
                synthesizeSpeech(agentId, speechText, null, agentInfo.ttsProvider());
        log.info(
                "Speak request completed for agent {} textLength={} spokenLength={} method={}"
                        + " audioBase64Length={}",
                agentId,
                text.length(),
                speechText != null ? speechText.length() : 0,
                synthesis.method(),
                synthesis.audioBase64() != null ? synthesis.audioBase64().length() : 0);
        // speechText normally carries the speech-ready request text (see the
        // field's javadoc) - for the quota-exceeded method it instead carries
        // VoiceQuotaService's own visitor-facing reason ("...Upgrade for a
        // higher limit."), the same distinct-signal reuse of this DTO's
        // existing shape {@link SpeakResponse}'s javadoc documents.
        return new SpeakResponse(
                synthesis.audioBase64(),
                synthesis.method(),
                "quota-exceeded".equals(synthesis.method())
                        ? synthesis.quotaMessage()
                        : speechText);
    }

    /**
     * {@link #synthesizeSpeech}'s result. {@code quotaMessage} is non-null ONLY when {@code method}
     * is {@code "quota-exceeded"} - mirrors the same three-outcome shape {@link SpeakResponse}
     * already exposes over the wire, just without that DTO's endpoint-specific {@code speechText}
     * dual-purpose field (see this method's call sites for how each one reassembles its own
     * response shape from this).
     */
    public record SpeechSynthesisOutcome(String audioBase64, String method, String quotaMessage) {}

    /**
     * Extracted from {@link #speak} (small, mechanical extraction - no behavior change at that call
     * site) so a caller with no agent of its own - {@code WorkflowShareVoiceController}
     * (core/workflow, a workflow share has no configured/cloned voice) - can reuse the exact same
     * ElevenLabs -&gt; Sarvam -&gt; text-only-fallback chain {@link #turn}'s per-sentence {@link
     * VoiceTurnSpeech#synthesizeChunk} and {@link #speak} both already ran independently. {@code
     * agentId} is optional (logging/audit context only - {@code null} for a non-agent caller);
     * {@code voiceId} null means "resolve the tenant's own cloned ElevenLabs voice if one exists,
     * else the public 'Rachel' {@link #DEFAULT_ELEVENLABS_VOICE_ID}" - exactly {@link #speak}'s own
     * pre-existing fallback for a caller with no cloned voice; a non-null value (not used by any
     * caller yet) would skip that resolution and speak with the given voice id directly.
     */
    public SpeechSynthesisOutcome synthesizeSpeech(
            UUID agentId, String speechText, String voiceId) {
        return synthesizeSpeech(agentId, speechText, voiceId, null);
    }

    /**
     * TTS provider selector (Stage 2, 2026-08-09): {@code ttsProvider} is the agent's explicit
     * selection ({@code agent.getTtsProvider()}), or null to use the default chain below unchanged
     * - exactly the behavior every caller of the 3-arg overload already got, preserved verbatim.
     *
     * <p><b>Scope of what changes here vs. what doesn't</b> (read before assuming this fully
     * replaces the hardcoded chain): only {@link #speak} (the single-shot "play this reply"
     * endpoint) passes a real {@code ttsProvider} today. {@link #turn}'s live-call streaming
     * pipeline ({@link VoiceTurnSpeech#synthesizeChunk}) does NOT yet look up the agent's selection
     * - it always runs the original hardcoded chain regardless of {@code agent.getTtsProvider()}.
     * Extending {@code synthesizeChunk} the same way is future work (see {@code
     * TextToSpeechProvider}'s javadoc and docs/MULTI-PROVIDER-MODALITY-ARCHITECTURE.md's Stage 2
     * section) - deliberately deferred rather than risking the quota-propagation semantics the
     * 2026-08-08 audit fixes on that streaming path depend on.
     *
     * <p>{@code ttsProvider == SARVAM} skips the ElevenLabs attempt entirely and goes straight to
     * {@link #trySarvamTts} (still the existing, already-quota-checked private helper - no new
     * quota logic introduced). {@code ttsProvider == ELEVENLABS} behaves identically to {@code
     * null} (ElevenLabs was already tried first by default) - selecting it makes the choice
     * explicit/auditable via {@code agent.getTtsProvider()} without changing runtime behavior,
     * since there was no fallback to skip. Either way, a failure still falls through to {@code
     * "text-only-fallback"} - the one universal safety net every path already had, not a new
     * behavior.
     */
    public SpeechSynthesisOutcome synthesizeSpeech(
            UUID agentId, String speechText, String voiceId, TtsModelProvider ttsProvider) {
        if (ttsProvider == TtsModelProvider.SARVAM) {
            byte[] sarvamAudio;
            try {
                sarvamAudio = trySarvamTts(agentId, speechText);
            } catch (CallSpeechQuotaExceededException e) {
                log.info(
                        "Sarvam TTS quota exceeded for agent {} (explicit provider): {}",
                        agentId,
                        e.getMessage());
                return new SpeechSynthesisOutcome(
                        Base64.getEncoder().encodeToString(new byte[0]),
                        "quota-exceeded",
                        e.getMessage());
            }
            boolean ok = sarvamAudio != null && sarvamAudio.length > 0;
            return new SpeechSynthesisOutcome(
                    Base64.getEncoder().encodeToString(ok ? sarvamAudio : new byte[0]),
                    ok ? "sarvam" : "text-only-fallback",
                    null);
        }
        byte[] audio = null;
        String method = "text-only-fallback";
        // Set only when EITHER TTS tier is rejected specifically for quota
        // (2026-08-08 audit findings #1/#2) - distinct from a genuine
        // provider/credential failure, which still falls through to the next
        // tier as before. A quota rejection deliberately does NOT fall
        // through: Sarvam is gated by the same shared VoiceQuotaService
        // ledger (see VoiceTurnSpeech#synthesizeSarvamOnly), so trying it after ElevenLabs
        // was quota-rejected would just throw the same exception again,
        // and silently downgrading to Sarvam/text-only here is exactly the
        // bug being fixed - the tenant needs to see the quota/upgrade
        // message, not a generic "no audio" response.
        CallSpeechQuotaExceededException quotaExceeded = null;
        if (elevenLabsTts != null) {
            try {
                String resolvedVoiceId = voiceId != null ? voiceId : elevenLabsTts.defaultVoice();
                audio = elevenLabsTts.synthesize(speechText, resolvedVoiceId);
                if (audio != null && audio.length > 0) {
                    method = "elevenlabs";
                    try {
                        elevenLabsTts.store(audio, resolvedVoiceId);
                    } catch (Exception e) {
                        log.warn(
                                "Failed to persist TTS audio for agent {}: {} (reply will still be"
                                        + " sent)",
                                agentId,
                                e.getMessage());
                    }
                } else {
                    audio = null;
                }
            } catch (CallSpeechQuotaExceededException e) {
                log.info("ElevenLabs TTS quota exceeded for agent {}: {}", agentId, e.getMessage());
                quotaExceeded = e;
            } catch (Exception e) {
                log.warn(
                        "ElevenLabs TTS generation failed for agent {} (credentials may not be"
                                + " configured): {}",
                        agentId,
                        e.getMessage());
                audio = null;
            }
        }
        if (audio == null && quotaExceeded == null) {
            try {
                audio = trySarvamTts(agentId, speechText);
            } catch (CallSpeechQuotaExceededException e) {
                log.info("Sarvam TTS quota exceeded for agent {}: {}", agentId, e.getMessage());
                quotaExceeded = e;
            }
            if (audio != null && audio.length > 0) {
                method = "sarvam";
            } else {
                audio = null;
            }
        }
        if (audio == null) {
            audio = new byte[0];
        }
        if (quotaExceeded != null) {
            method = "quota-exceeded";
        }
        return new SpeechSynthesisOutcome(
                Base64.getEncoder().encodeToString(audio),
                method,
                quotaExceeded != null ? quotaExceeded.getMessage() : null);
    }

    /**
     * Second TTS tier, tried only after ElevenLabs is unavailable/unconfigured (see {@link #speak}
     * and {@link #turn}'s reply leg) - resolves the tenant's OWN Sarvam credential (same {@code
     * synthesize(text, lang, model, speaker)} overload {@code SarvamSpeakBackend} uses for the
     * agent tool path), so this never spends a platform-subsidised key. Returns {@code null} (not a
     * thrown exception) on any failure, including "no Sarvam credential configured" - callers fall
     * through to text-only. Defaults to {@code en-IN} since neither {@code /speak} nor {@code
     * /turn} carries an explicit target language for the reply.
     *
     * @throws VoiceQuotaExceededException propagated as-is from {@link
     *     VoiceTurnSpeech#synthesizeSarvamOnly}'s quota check (2026-08-08 audit fix #2) - the one
     *     exception type this method does NOT swallow into a {@code null} return, since callers
     *     need to distinguish "quota exceeded" from "Sarvam unavailable" (see {@link #speak}).
     */
    private byte[] trySarvamTts(UUID agentId, String speechText) {
        byte[] wav = turnSpeech.synthesizeSarvamOnly(speechText);
        if (wav != null) {
            try {
                sarvamTts.store(wav, sarvamTts.defaultVoice());
            } catch (Exception e) {
                log.warn(
                        "Failed to persist Sarvam TTS audio for agent {}: {} (reply will still be"
                                + " sent)",
                        agentId,
                        e.getMessage());
            }
        }
        return wav;
    }

    /**
     * Streams the push-to-talk pipeline's result as Server-Sent Events instead of blocking until
     * the whole reply and its audio are ready. Speech-to-text still runs synchronously before the
     * response begins (same provider fallback chain as before: agent's selected provider -> sidecar
     * Whisper -> Sarvam) - a transcription failure still throws and maps to a normal 400, since
     * nothing has streamed yet at that point. Once transcription succeeds, the response streams:
     *
     * <ol>
     *   <li>{@code transcript} - once, immediately ({@link VoiceTurnTranscriptEvent})
     *   <li>{@code reply-delta} - one per token as {@link AgentChatService#chat} emits it (raw
     *       text)
     *   <li>{@code audio-chunk} - one per sentence-sized piece, as soon as that piece's TTS call
     *       completes ({@link VoiceTurnAudioChunkEvent}) - {@code reply-delta} and {@code
     *       audio-chunk} events are interleaved on the wire as they become ready, not sent as two
     *       separate phases, which is what lets playback start before the model has finished
     *       replying
     *   <li>{@code done} - once, after everything above completes ({@link VoiceTurnResponse},
     *       {@code audioBase64} always empty since the audio already went out as chunks)
     * </ol>
     *
     * A failure after the {@code transcript} event (the response is already committed by then)
     * becomes a final {@code error} event instead of silently dropping the connection - see {@link
     * AgentController}'s {@code STREAM_ERROR_MARKER} for the equivalent problem on the text chat
     * stream, solved here with a proper named event instead of a magic-string marker since this
     * endpoint already has named events.
     *
     * <p>Sentence boundaries for {@code audio-chunk} are decided by {@link SpeechStreamNormalizer}
     * (same utility {@code VoiceRelayWebSocketHandler} uses for Twilio's streaming TTS), and the
     * ElevenLabs -> Sarvam -> text-only-fallback TTS chain is unchanged from before - see {@link
     * VoiceTurnSpeech#synthesizeChunk}. Exactly one row is persisted to the ElevenLabs
     * generated-audio ledger (or Sarvam's) for the whole turn, not one per chunk - see {@link
     * VoiceTurnSpeech#persistTurnAudio}.
     */
    @PostMapping(
            value = "/agents/{agentId}/turn",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> turn(
            @PathVariable UUID agentId,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            Principal principal)
            throws IOException {
        String resolvedSessionId =
                StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString();
        String subjectKey =
                principal != null && StringUtils.hasText(principal.getName())
                        ? principal.getName()
                        : resolvedSessionId;
        return streamVoiceTurn(agentId, audio, resolvedSessionId, subjectKey);
    }

    /**
     * The pipeline itself, extracted from {@link #turn} so the public/anonymous agent-share surface
     * can reuse it without an authenticated {@link Principal} - see {@code
     * AgentShareVoiceController} (a different module, {@code core/agents}'s share package) for the
     * caller that passes a share-scoped {@code subjectKey} (e.g. {@code
     * "share:<shareId>:<ipHash>"}) instead of a Keycloak principal name. {@code
     * resolvedSessionId}/{@code subjectKey} are pre-resolved by the caller - this method no longer
     * derives either from a nullable session id or principal. Package-private (not {@code private})
     * specifically so {@code AgentShareVoiceController}, a sibling class in this same {@code
     * com.agenticai.core.voice} package, can call it directly rather than duplicating the
     * STT/TTS/SSE pipeline.
     */
    Flux<ServerSentEvent<String>> streamVoiceTurn(
            UUID agentId, MultipartFile audio, String resolvedSessionId, String subjectKey)
            throws IOException {
        AgentResponse agent = agentService.get(agentId);
        TranscriptionOutcome transcriptionOutcome =
                transcribeAudio(audio, agentId, agent.voiceModelProvider(), agent.voiceModel());
        return turnStream.stream(agentId, resolvedSessionId, subjectKey, transcriptionOutcome);
    }

    /**
     * {@link #transcribeAudio}'s result - {@code method} is the provider id (or {@code
     * "sidecar-whisper"}/{@code "sarvam"}) that actually produced {@code text}, needed for the
     * {@code transcript} SSE event {@link #streamVoiceTurn} sends. A plain {@code String} return
     * (just the text) would lose this - a mutable instance field on this singleton controller bean
     * would work around that only by introducing a race between concurrent requests, so this small
     * record is the extraction's one deliberate departure from "returns the transcribed String
     * text".
     */
    public record TranscriptionOutcome(String text, String method) {}

    /**
     * Extracted from {@link #streamVoiceTurn} (small, mechanical extraction - no behavior change at
     * that call site) so a caller with no {@link AgentResponse} of its own - {@code
     * WorkflowShareVoiceController} (core/workflow, a workflow has no configured voice-model
     * provider) - can reuse the exact same three-tier STT fallback chain: preferred provider (if
     * non-null) -&gt; sidecar Whisper (if present) -&gt; Sarvam. {@code agentId} is optional
     * (logging context only - {@code null} for a non-agent caller). {@code
     * preferredProvider}/{@code preferredModel} null (as every non-agent caller passes) skips the
     * first tier entirely, exactly like {@link #streamVoiceTurn} already did whenever {@code
     * agent.voiceModelProvider()} was null.
     *
     * @throws VoiceTurnAudioEmptyException for every "no usable speech" case (null/empty audio,
     *     too-short audio, an unparseable recording, or a blank transcription result) - the return
     *     value is therefore never blank. Unchanged wording/mapping from before this extraction.
     */
    public TranscriptionOutcome transcribeAudio(
            MultipartFile audio,
            UUID agentId,
            VoiceModelProvider preferredProvider,
            String preferredModel)
            throws IOException {
        return voiceTurnTranscriber.transcribe(audio, agentId, preferredProvider, preferredModel);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Mapped to 400 by {@code GlobalExceptionHandler}, same "not our fault, tell the caller" shape
     * as every sibling *NotConfigured/validation exception in this codebase.
     */
    public static class VoiceTurnAudioEmptyException extends RuntimeException {
        public VoiceTurnAudioEmptyException(String message) {
            super(message);
        }
    }
}
