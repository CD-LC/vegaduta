package com.agenticai.core.voice;

import com.agenticai.core.agents.share.AgentShare;
import com.agenticai.core.agents.share.AgentShareService;
import com.agenticai.core.agents.share.ShareVisitorSessions;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.voice.dto.VoiceTokenRequest;
import com.agenticai.core.voice.dto.VoiceTokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/share/agents/{shareId}/voice} - the PUBLIC, anonymous half of self-hosted
 * voice calling (see {@link VoiceCallController}'s own javadoc for the authenticated
 * original). Nests under {@code /api/share/agents/**}, already {@code permitAll()}'d in
 * {@code SecurityConfig} - no security config change needed.
 *
 * <p><b>Why this needs its own token-minting path.</b> {@link VoiceCallController#issueToken}
 * embeds the caller's own Keycloak bearer token in the LiveKit participant {@code metadata}
 * claim, so the {@code voice-agent} worker can call back into {@code
 * /api/agents/{agentId}/chat} as that user once it joins the room ({@code
 * llm_core_bridge.py}'s {@code CoreChatBridgeLLM}). An anonymous share viewer has no JWT to
 * forward - {@code voice-agent/app/main.py}'s {@code _extract_auth_token} refuses to
 * proceed without one. {@link #token} mints a token whose metadata instead carries {@code
 * {"shareId": "<shareId>", "shareSessionKey": "<signed visitor token>"}} - {@code shareId}
 * is already this codebase's sole credential for the whole public share surface (see {@code
 * AgentShareController}'s class javadoc), so this invents no new secret. The worker
 * recognizes a {@code share-}-prefixed room name, skips the auth-token requirement, and
 * calls back into {@code AgentShareController#chatStream} instead - see that method's
 * javadoc for the matching server side of this contract.
 *
 * <p>{@code shareSessionKey} is a SEC-005 visitor token ({@link ShareVisitorSessions}) issued
 * ONCE here - the caller's own token (header {@code X-Share-Session}) when it verifies, so a
 * call continues that visitor's conversation, else a fresh visitor. Nothing is derived from
 * the client IP any more. The worker only ever echoes it back verbatim, and {@code
 * AgentShareController#chatStream} honours it only because this server signed it.
 */
@RestController
@RequestMapping("/api/share/agents/{shareId}/voice")
public class AgentShareVoiceController {

    private static final Logger log = LoggerFactory.getLogger(AgentShareVoiceController.class);

    private final AgentShareService shareService;
    private final LiveKitTokenService tokenService;
    private final VoiceCallController voiceCallController;
    private final ShareVisitorSessions visitorSessions;

    public AgentShareVoiceController(
        AgentShareService shareService, LiveKitTokenService tokenService, VoiceCallController voiceCallController,
        ShareVisitorSessions visitorSessions
    ) {
        this.shareService = shareService;
        this.tokenService = tokenService;
        this.voiceCallController = voiceCallController;
        this.visitorSessions = visitorSessions;
    }

    /**
     * Mints a share-scoped LiveKit token. See this class's javadoc for the metadata
     * contract and why it replaces {@link VoiceCallController#issueToken}'s
     * {@code coreAccessToken} claim. {@code mode} selects {@code voice}/{@code sight}
     * exactly like the authenticated endpoint's {@link VoiceTokenRequest}; an unrecognized
     * value is coerced to {@code voice} (logged, not rejected) for the same reason
     * {@link VoiceCallController#issueToken} does.
     */
    @PostMapping("/token")
    public ResponseEntity<?> token(
        @PathVariable String shareId,
        @RequestBody(required = false) VoiceTokenRequest request,
        HttpServletRequest servletRequest
    ) {
        String clientIp = servletRequest.getRemoteAddr();
        // A live call is a standing resource, not a single message - rate-limited before
        // even resolving the share, same ordering AgentShareController#chat uses.
        if (!shareService.tryAcquireVoiceSession(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate_limited"));
        }
        Optional<AgentShareService.ResolvedShare> resolved = shareService.resolveActiveShare(shareId);
        if (resolved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        try {
            String mode = request != null && StringUtils.hasText(request.mode())
                ? request.mode().trim().toLowerCase(Locale.ROOT)
                : "voice";
            if (!"voice".equals(mode) && !"sight".equals(mode)) {
                log.warn("Unknown share voice token mode {} for share {} - treating as voice", mode, shareId);
                mode = "voice";
            }
            // SEC-005: signed visitor token, never an IP hash.
            String shareSessionKey = visitorSessions
                .resolveOrIssue(shareId, servletRequest.getHeader(ShareVisitorSessions.HEADER))
                .token();
            // "share-" prefix (not just "voice-"/"sight-") is the signal the worker's room
            // parser uses to know this is a share room before it even reads metadata - see
            // this class's javadoc.
            String roomName = "share-" + mode + "-" + shareId + "-" + shortId();
            String identity = "share:" + shareId + ":" + shortId();
            // LinkedHashMap, not Map.of: the optional ttsVoice fields below
            // are legitimately absent for a share caller who never picked a
            // voice (and share callers have no voice list to pick from today
            // - the field exists for parity with VoiceCallController#issueToken).
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("shareId", shareId);
            metadata.put("shareSessionKey", shareSessionKey);
            if (request != null && StringUtils.hasText(request.ttsVoice())) {
                String ttsVoice = request.ttsVoice().trim();
                metadata.put("ttsVoice", ttsVoice.length() > 120 ? ttsVoice.substring(0, 120) : ttsVoice);
                if (StringUtils.hasText(request.ttsVoiceProvider())) {
                    metadata.put("ttsVoiceProvider", request.ttsVoiceProvider().trim());
                }
            }
            String token = tokenService.issueToken(identity, roomName, "visitor", metadata);
            // Deliberately logs shareId/room/identity only, never shareSessionKey or the
            // minted token itself - same convention VoiceCallController#issueToken follows.
            log.info("Issued share-scoped LiveKit token for share {} room {} identity {}", shareId, roomName, identity);
            return ResponseEntity.ok(new VoiceTokenResponse(token, tokenService.getUrl(), roomName, identity));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Push-to-talk for the share page - reuses {@link VoiceCallController#streamVoiceTurn}
     * (the same STT/TTS/SSE pipeline the authenticated endpoint uses) with a share-scoped
     * session/subject key instead of a {@link java.security.Principal}. Unlike {@link
     * #token}, this does NOT require a live LiveKit call at all - see {@code CallPanel.tsx}'s
     * own javadoc for why push-to-talk is the self-contained path that "actually gets a
     * spoken question answered" independent of the call button.
     *
     * <p>Returns {@link ResponseEntity} rather than a raw streaming type for the same
     * reason {@link VoiceCallController#startRecording} does (see its javadoc): a thrown
     * exception with no specific {@code GlobalExceptionHandler} mapping becomes an opaque
     * 500, so the rate-limit/expired cases are built as direct responses instead of thrown.
     * An audio-validation failure inside {@link VoiceCallController#streamVoiceTurn} itself
     * (e.g. {@code VoiceTurnAudioEmptyException}) still throws and still gets that
     * exception's own existing 400 mapping, unchanged.
     */
    @PostMapping(value = "/turn", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> turn(
        @PathVariable String shareId,
        @RequestParam("audio") MultipartFile audio,
        @RequestParam(value = "sessionId", required = false) String sessionId,
        HttpServletRequest servletRequest
    ) throws IOException {
        String clientIp = servletRequest.getRemoteAddr();
        // Shared bucket with the LiveKit worker's /chat/stream turns for this same
        // visitor - see AgentShareService#tryAcquireVoiceTurn's javadoc.
        String rateLimitKey = "share:" + shareId + ":" + AgentShareService.ipHash(clientIp);
        if (!shareService.tryAcquireVoiceTurn(rateLimitKey)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate_limited"));
        }
        Optional<AgentShareService.ResolvedShare> resolved = shareService.resolveActiveShare(shareId);
        if (resolved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        try {
            AgentShare share = resolved.get().share();
            shareService.recordViewer(share);
            // SEC-005: the form's sessionId is honoured only as a server-signed visitor
            // token (the value the previous turn's done event returned); anything else -
            // absent, unsigned, another share's - gets a fresh visitor. The token doubles
            // as the conversation id so the existing share page, which echoes the returned
            // sessionId, keeps its conversation with no client change. Memory subject is
            // per VISITOR, no longer one per-share pool every visitor could read.
            ShareVisitorSessions.Visitor visitor = visitorSessions.resolveOrIssue(
                shareId, StringUtils.hasText(sessionId) ? sessionId : servletRequest.getHeader(ShareVisitorSessions.HEADER));
            String subjectKey = ShareVisitorSessions.memorySubject(share.getId(), visitor.visitorId());
            return ResponseEntity.ok().body(
                voiceCallController.streamVoiceTurn(share.getAgentId(), audio, visitor.token(), subjectKey)
            );
        } finally {
            // See AgentShareService#resolveActiveShare's javadoc - cleared right after the
            // (lazy) Flux is built, not after it finishes streaming.
            TenantContext.clear();
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
