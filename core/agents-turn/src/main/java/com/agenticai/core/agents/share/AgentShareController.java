package com.agenticai.core.agents.share;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.AgentChatStreamResponder;
import com.agenticai.core.agents.turn.ChatLimitsProperties;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * {@code /api/share/agents/{shareId}} - the PUBLIC half of "even registered consumers should be
 * able to share their agents to external world". No auth of any kind; possession of the unguessable
 * share id is the whole credential, same posture as {@code DemoShareController}. The OWNER half
 * (mint/inspect/revoke) is {@link AgentShareOwnerController}.
 *
 * <p><b>Unlike the demo share surface, a viewer turn here is metered normally.</b> The demo pattern
 * needed a bespoke "anonymous budget" because a demo sandbox has no tenant plan behind it; a real
 * agent's share instead runs the viewer's turn inside the OWNING tenant's own {@link TenantContext}
 * (resolved cross-tenant below, then set by hand exactly like {@code WorkflowWebhookController}
 * does for its shared-secret trigger), so {@link AgentChatService#chat} applies that tenant's
 * normal plan limits/budget/ moderation to it automatically - nothing bespoke to build. The two
 * share-specific lids on top are {@link AgentShareService#tryAcquireChat} (per-IP rate limit) and
 * the report/auto-revoke threshold.
 */
@RestController
@RequestMapping("/api/share/agents")
public class AgentShareController {

    private static final Logger log = LoggerFactory.getLogger(AgentShareController.class);
    /** {@code app.chat.limits.share-turn-timeout}; a hand-built controller keeps the shipped default. */
    private volatile Duration turnTimeout = new ChatLimitsProperties().getShareTurnTimeout();
    private static final String REQUESTED_BY_PREFIX = "system:agent-share:";

    private final AgentShareService shareService;
    private final AgentChatService agentChatService;
    private final TenantLimits tenantLimits;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final ShareVisitorSessions visitorSessions;

    /** Sets {@code app.chat.limits.share-turn-timeout}; until it is called the shipped default applies. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void useLimits(ChatLimitsProperties limits) {
        this.turnTimeout = (limits == null ? new ChatLimitsProperties() : limits).getShareTurnTimeout();
    }

    public AgentShareController(
            AgentShareService shareService,
            AgentChatService agentChatService,
            TenantLimits tenantLimits,
            ProviderModelCatalogService providerModelCatalogService,
            ShareVisitorSessions visitorSessions) {
        this.shareService = shareService;
        this.agentChatService = agentChatService;
        this.tenantLimits = tenantLimits;
        this.providerModelCatalogService = providerModelCatalogService;
        this.visitorSessions = visitorSessions;
    }

    /**
     * {@code brandDisplayName}/{@code brandLogoUrl} (white-label minimum-real build,
     * PRICING-MONETIZATION-AUDIT-2026-07-31.md item C2): the owning tenant's own branding, or both
     * {@code null} for platform (VegaDuta) branding. Sourced from {@link
     * TenantLimits#brandDisplayName}'s entitlement-gated EFFECTIVE form - non-null only while the
     * owning tenant's plan actually includes {@code white_label} - so the viewer page can trust
     * these fields blindly: a downgrade off UNLIMITED flips this response back to platform branding
     * on the next load with no cleanup anywhere.
     *
     * <p>{@code supportsImageInput}/{@code supportsAudioInput} (multimodal share playground):
     * resolved server-side from {@link ProviderModelCatalogService} against the agent's own
     * provider/model, the same lookup {@code AgentPlayground.tsx} already does client-side for the
     * authenticated console - computed here instead so the anonymous page can gate its camera/mic
     * controls off this one already-fetched call, with no new anonymous catalog endpoint needed.
     */
    public record ShareMetaResponse(
            String agentName,
            String brandDisplayName,
            String brandLogoUrl,
            boolean supportsImageInput,
            boolean supportsAudioInput) {}

    /**
     * {@code sessionToken} (SEC-005, optional): the visitor token a previous response returned.
     * Absent/forged/foreign-share -> a new visitor (new, empty conversation).
     */
    public record ShareChatRequest(
            @Size(max = 2000) String message, @Size(max = 128) String sessionToken) {}

    /**
     * {@code sessionToken}: the visitor token to send back on the next turn to continue this
     * conversation.
     */
    public record ShareChatResponse(String reply, String sessionToken) {}

    public record ShareReportRequest(@Size(max = 500) String reason) {}

    /**
     * Body of {@code POST /{shareId}/chat/stream} - the streaming counterpart of {@link
     * ShareChatRequest}, carrying what the vision Show/Watch flow and the LiveKit voice worker both
     * need on top of a plain text message: inline frame attachments, the vision session id they
     * belong to, and (worker calls only) an explicit {@code sessionKey} - see {@link #chatStream}'s
     * javadoc.
     */
    public record ShareChatStreamRequest(
            @Size(max = 2000) String message,
            List<ChatInlineAttachment> attachments,
            String visionSessionId,
            String sessionKey) {}

    /**
     * Public share metadata - what the landing frame renders before the viewer says anything. 410
     * for unknown, revoked, expired or deleted (deliberately indistinguishable, same "this link is
     * done" posture as the demo share surface).
     */
    @GetMapping("/{shareId}")
    public ResponseEntity<?> meta(@PathVariable String shareId) {
        return withResolvedShare(
                shareId,
                share -> {
                    Optional<Agent> agent = shareService.loadSharedAgent(share.getAgentId());
                    if (agent.isEmpty()) {
                        // withResolvedShare already proved the agent exists; this only
                        // fires on a delete racing this request, and gets the same
                        // indistinguishable "gone" surface.
                        return ResponseEntity.status(HttpStatus.GONE)
                                .body(Map.of("error", "expired"));
                    }
                    // withResolvedShare set TenantContext to the owning tenant's
                    // schema, so this reads the OWNER's entitlement/branding - the
                    // anonymous viewer has no tenant of their own.
                    String ownerTenantId =
                            TenantContext.current().map(TenantContext::getTenantId).orElse(null);
                    String brandDisplayName =
                            ownerTenantId == null
                                    ? null
                                    : tenantLimits.brandDisplayName(ownerTenantId).orElse(null);
                    String brandLogoUrl =
                            ownerTenantId == null
                                    ? null
                                    : tenantLimits.brandLogoUrl(ownerTenantId).orElse(null);
                    boolean supportsImageInput = false;
                    boolean supportsAudioInput = false;
                    if (agent.get().getProviderName() != null && agent.get().getModel() != null) {
                        Optional<ProviderModelResponse> modelEntry =
                                providerModelCatalogService
                                        .listModels(agent.get().getProviderName())
                                        .stream()
                                        .filter(m -> agent.get().getModel().equals(m.modelId()))
                                        .findFirst();
                        supportsImageInput =
                                modelEntry
                                        .map(ProviderModelResponse::supportsImageInput)
                                        .orElse(false);
                        supportsAudioInput =
                                modelEntry
                                        .map(ProviderModelResponse::supportsAudioInput)
                                        .orElse(false);
                    }
                    return ResponseEntity.ok(
                            new ShareMetaResponse(
                                    agent.get().getName(),
                                    brandDisplayName,
                                    brandLogoUrl,
                                    supportsImageInput,
                                    supportsAudioInput));
                });
    }

    /**
     * Streaming counterpart of {@link #chat} - camera/screen frame turns (from the share page's own
     * capture UI) and the LiveKit voice worker's per-turn calls (see {@code
     * AgentShareVoiceController}) both need this instead of the blocking endpoint: frames ride as
     * inline attachments through the SAME 10-arg {@link AgentChatService#chat} overload the
     * authenticated {@code /api/agents/{agentId}/chat} endpoint uses, and the worker's bridge code
     * expects a token-streamed reply, not one blocking response.
     *
     * <p>{@code sessionKey} (or the {@code X-Share-Session} header) is a SEC-005 visitor token
     * ({@link ShareVisitorSessions}): the LiveKit worker passes the one minted server-side at
     * call-token issuance ({@code AgentShareVoiceController#token}); the share page passes the one
     * a previous response returned in {@code X-Session-Id}. Only a token this server signed FOR
     * this {@code shareId} is honoured; anything else is ignored and a fresh visitor is issued (and
     * returned in {@code X-Session-Id}) - a caller can never name another visitor's conversation,
     * and nothing is derived from the client IP any more (IP is used only as the rate-limit
     * bucket).
     *
     * <p>This is strictly additive - the existing blocking {@link #chat} endpoint the plain text
     * thread already uses is completely untouched.
     *
     * <p>Declared as {@code Object} on purpose, same as {@code
     * OpenAiCompatController#chatCompletions}: Spring MVC picks the return-value handler from the
     * declared generic type, and a {@code ResponseEntity<?>} carrying a {@code Flux} is handed to
     * the message converters ("No converter ... text/event-stream", HTTP 500) instead of being
     * streamed. The rejections go out as explicit JSON for the same reason - {@code produces}
     * narrows negotiation to text/event-stream, which no converter can write a {@code Map} as.
     */
    @PostMapping(value = "/{shareId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object chatStream(
            @PathVariable String shareId,
            @Valid @RequestBody(required = false) ShareChatStreamRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        String message =
                request == null || request.message() == null ? "" : request.message().trim();
        if (message.isEmpty() || message.length() > 2000) {
            return streamRejection(HttpStatus.UNPROCESSABLE_ENTITY, "invalid");
        }
        String clientIp = servletRequest.getRemoteAddr();
        // SEC-005: the visitor is whoever the server-signed token says - never the IP,
        // never an unsigned caller-chosen key (ignored -> fresh visitor, fresh history).
        String presented =
                request != null && request.sessionKey() != null && !request.sessionKey().isBlank()
                        ? request.sessionKey()
                        : servletRequest.getHeader(ShareVisitorSessions.HEADER);
        ShareVisitorSessions.Visitor visitor = visitorSessions.resolveOrIssue(shareId, presented);
        // Rate limit: a VERIFIED visitor (the LiveKit worker, calling from the
        // voice-agent container's own IP) is bucketed per visitor; everything else
        // per client IP, so dropping the token can never buy a fresh budget.
        String rateLimitKey =
                visitor.issued()
                        ? "share:" + shareId + ":" + AgentShareService.ipHash(clientIp)
                        : "share:" + shareId + ":v:" + visitor.visitorId();
        if (!shareService.tryAcquireVoiceTurn(rateLimitKey)) {
            return streamRejection(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        Optional<AgentShareService.ResolvedShare> resolved =
                shareService.resolveActiveShare(shareId);
        if (resolved.isEmpty()) {
            return streamRejection(HttpStatus.GONE, "expired");
        }
        Flux<String> stream;
        try {
            AgentShare share = resolved.get().share();
            shareService.recordViewer(share);
            List<ChatInlineAttachment> attachments =
                    request != null && request.attachments() != null
                            ? request.attachments()
                            : List.of();
            String visionSessionId = request == null ? null : request.visionSessionId();
            // SEC-005: sessionId AND long-term-memory subject are both per VISITOR.
            // The old per-SHARE subject pooled every visitor's extracted facts into
            // one memory any other visitor could query.
            String sessionKey = ShareVisitorSessions.chatSessionId(shareId, visitor.visitorId());
            String subjectKey =
                    ShareVisitorSessions.memorySubject(share.getId(), visitor.visitorId());

            StringBuilder emitted = new StringBuilder();
            ChatTurnRequest chatTurn =
                    ChatTurnRequest.builder(share.getAgentId(), sessionKey, subjectKey, message)
                            .inlineAttachments(attachments)
                            .visionSessionId(visionSessionId)
                            .build();
            TurnContext turnContext =
                    TurnContext.forEntry(TenantContext.current().orElse(null), chatTurn);
            stream =
                    agentChatService
                            .chat(turnContext, chatTurn)
                            .doOnNext(emitted::append)
                            // Same mid-stream-failure posture as AgentController#chat - see that
                            // method's own comment on STREAM_ERROR_MARKER for why an error before
                            // the first token still propagates normally instead of being masked.
                            .onErrorResume(
                                    e -> {
                                        if (emitted.isEmpty()) {
                                            return Flux.error(e);
                                        }
                                        log.warn(
                                                "Agent share chat stream failed mid-response for"
                                                    + " share {} after {} chars already streamed",
                                                shareId,
                                                emitted.length(),
                                                e);
                                        return Flux.just(
                                                AgentController.STREAM_ERROR_MARKER
                                                        + AgentChatStreamResponder
                                                                .midStreamFailureMessage(e));
                                    });
        } finally {
            // See AgentShareService#resolveActiveShare's javadoc: clearing happens here,
            // right after the (lazy) Flux is built, not after it finishes streaming -
            // matching the same context-lifetime boundary the authenticated Flux-returning
            // voice endpoint already relies on.
            TenantContext.clear();
        }
        // The token rides back in X-Session-Id (already CORS-exposed by WebConfig). Set on the
        // servlet response directly: the SseEmitter return value carries no headers of its own,
        // and anything set before the emitter starts is kept on the streamed response.
        servletResponse.setHeader(AgentController.SESSION_ID_HEADER, visitor.token());
        // Subscribed only after the clear above, exactly where MVC itself would have
        // subscribed to a returned Flux.
        return toEmitter(stream);
    }

    private static ResponseEntity<Map<String, String>> streamRejection(
            HttpStatus status, String error) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }

    /**
     * Bridges the token stream onto an MVC {@link SseEmitter}, one {@code data:} frame per token; a
     * client disconnect cancels the agent turn.
     */
    private static SseEmitter toEmitter(Flux<String> tokens) {
        SseEmitter emitter = new SseEmitter(-1L);
        Disposable sub =
                tokens.subscribe(
                        token -> {
                            try {
                                emitter.send(SseEmitter.event().data(token));
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete);
        emitter.onCompletion(sub::dispose);
        emitter.onTimeout(sub::dispose);
        emitter.onError(e -> sub.dispose());
        return emitter;
    }

    /**
     * A viewer's chat turn: per-IP rate limit, then the agent's normal chat path inside the owning
     * tenant's own context.
     */
    @PostMapping("/{shareId}/chat")
    public ResponseEntity<?> chat(
            @PathVariable String shareId,
            @Valid @RequestBody(required = false) ShareChatRequest request,
            HttpServletRequest servletRequest) {
        String message =
                request == null || request.message() == null ? "" : request.message().trim();
        if (message.isEmpty() || message.length() > 2000) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid"));
        }
        String clientIp = servletRequest.getRemoteAddr();
        if (!shareService.tryAcquireChat(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "rate_limited"));
        }
        return withResolvedShare(
                shareId,
                share -> {
                    shareService.recordViewer(share);
                    // SEC-005: server-issued, HMAC-signed visitor - see ShareVisitorSessions.
                    String presented =
                            request != null
                                            && request.sessionToken() != null
                                            && !request.sessionToken().isBlank()
                                    ? request.sessionToken()
                                    : servletRequest.getHeader(ShareVisitorSessions.HEADER);
                    ShareVisitorSessions.Visitor visitor =
                            visitorSessions.resolveOrIssue(shareId, presented);
                    String chatSessionId =
                            ShareVisitorSessions.chatSessionId(shareId, visitor.visitorId());
                    String subjectKey =
                            ShareVisitorSessions.memorySubject(share.getId(), visitor.visitorId());
                    try {
                        ChatTurnRequest chatTurn =
                                ChatTurnRequest.builder(
                                                share.getAgentId(),
                                                chatSessionId,
                                                subjectKey,
                                                message)
                                        .build();
                        TurnContext turnContext =
                                TurnContext.forEntry(
                                        TenantContext.current().orElse(null), chatTurn);
                        String reply =
                                agentChatService
                                        .chat(turnContext, chatTurn)
                                        .collect(Collectors.joining())
                                        .block(turnTimeout);
                        if (reply == null || reply.isBlank()) {
                            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                    .body(Map.of("error", "no_reply"));
                        }
                        return ResponseEntity.ok()
                                .header(AgentController.SESSION_ID_HEADER, visitor.token())
                                .body(new ShareChatResponse(reply, visitor.token()));
                    } catch (TenantLimitExceededException e) {
                        log.warn(
                                "Agent share chat turn failed - tenant limit exceeded for share {}",
                                shareId,
                                e);
                        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                                .body(
                                        Map.of(
                                                "error",
                                                "quota_exhausted",
                                                "message",
                                                "The owner is a Free plan user and their"
                                                        + " daily message quota has been"
                                                        + " exhausted. It will reset after 24"
                                                        + " hours."));
                    } catch (RuntimeException e) {
                        log.warn("Agent share chat turn failed for share {}", shareId, e);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Map.of("error", "upstream_failed"));
                    }
                });
    }

    /**
     * Anyone with the link can report it - same "204 always, revocation decided silently" posture
     * as the demo share surface.
     */
    @PostMapping("/{shareId}/report")
    public ResponseEntity<?> report(
            @PathVariable String shareId,
            @Valid @RequestBody(required = false) ShareReportRequest request) {
        return withResolvedShare(
                shareId,
                share -> {
                    shareService.report(share, request == null ? null : request.reason());
                    return ResponseEntity.noContent().build();
                });
    }

    // ------------------------------------------------------------------

    /**
     * The full cross-tenant sequence every public endpoint above needs: resolve which schema owns
     * this share id, set {@link TenantContext} by hand (this path never runs {@code
     * TenantContextFilter} - no JWT), look up the share row and reject
     * revoked/unknown/agent-deleted the same indistinguishable way, run the caller's action, then
     * ALWAYS clear the context in the finally block so this request-handling thread never leaks a
     * tenant identity into whatever request it serves next.
     */
    private ResponseEntity<?> withResolvedShare(
            String shareId, java.util.function.Function<AgentShare, ResponseEntity<?>> action) {
        Optional<String> tenantSchema = shareService.resolveTenantSchema(shareId);
        if (tenantSchema.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        TenantContext.set(
                TenantContext.of(tenantSchema.get(), REQUESTED_BY_PREFIX + shareId, Set.of()));
        try {
            Optional<AgentShare> share = shareService.resolveByShareId(shareId);
            // SEC-051: an expired link is "gone" exactly like a revoked one.
            if (share.isEmpty() || !shareService.isLive(share.get())) {
                return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
            }
            // Existence only - deliberately NOT AgentService#get (a VIEWER
            // permission check). See AgentShareService#loadSharedAgent for
            // why: this caller is an anonymous link bearer with a synthetic,
            // role-less subject, so the per-user grant model has nothing to
            // check here, and routing through it would 404 every live share
            // link the moment V155 backfills a real owner onto these rows.
            // The share row's own state (found, not revoked, under the report
            // threshold) plus the per-IP rate limit ARE this surface's
            // authorization, and both are enforced.
            Optional<Agent> agent = shareService.loadSharedAgent(share.get().getAgentId());
            if (agent.isEmpty()) {
                // The agent itself was deleted after the share was minted - same "gone" surface as
                // an expired/revoked share.
                return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
            }
            return action.apply(share.get());
        } finally {
            TenantContext.clear();
        }
    }
}
