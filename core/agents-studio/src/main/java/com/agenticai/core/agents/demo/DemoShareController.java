package com.agenticai.core.agents.demo;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.guardrails.moderation.ModerationContext;
import com.agenticai.core.guardrails.moderation.ModerationOutcome;
import com.agenticai.core.guardrails.moderation.ModerationService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.demo.DemoPersona;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionRateLimiter;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.agenticai.core.tenancy.demo.DemoShareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/demo/share/{shareId}} - the PUBLIC half of shareable sandbox agents: what the person
 * who RECEIVES a share link can do. No auth of any kind; possession of the unguessable share id is
 * the whole credential, the same capability-handle posture {@code DemoGeneratedImage} already takes
 * for public image URLs. The OWNER half (minting the link) is {@code DemoShareOwnerController} over
 * in {@code core/tenancy}.
 *
 * <p><b>Why this module:</b> the viewer-chat turn dispatches through {@link AgentChatService},
 * which lives here - the same module-graph reasoning {@link DemoChatController} documents. The
 * share pipeline pieces that are tenancy concepts (viewer cap, report/revoke, share resolution)
 * stay in {@code core/tenancy}'s {@link DemoShareService}.
 *
 * <p><b>A share is not a second sandbox.</b> Every viewer turn (a) spends the owner session's own
 * message allowance ({@code recordMessage} + {@code isMessageCapReached}, exactly like the owner's
 * chat), and (b) runs through the identical {@code AgentChatService} path in the demo tenant's
 * schema, so the FREE-plan platform-pool budgets meter it like any other anonymous turn. On top sit
 * two share-specific lids: the per-IP Redis rate limit and the 5-distinct-viewer cap - see {@link
 * DemoShareService}.
 *
 * <p><b>Error posture - deliberately DIFFERENT from the owner surface.</b> The owner's {@code /try}
 * page gets prose it renders as-is; this surface returns machine-readable {@code {"error": "..."}}
 * keys ({@code viewer_cap}, {@code rate_limited}, {@code budget_exhausted}, {@code moderated},
 * {@code expired}, {@code invalid}) with real HTTP statuses, and the share frontend maps each to
 * its own honest, labelled fallback. What IS inherited from the owner surface: an upstream/model
 * failure still never surfaces as an error - it degrades to a labelled sample reply ({@code
 * simulated: true}), because a broken shop window shared to a stranger is strictly worse than one
 * the owner sees themself.
 */
@RestController
@RequestMapping("/api/demo/share")
public class DemoShareController {

    private static final Logger log = LoggerFactory.getLogger(DemoShareController.class);

    /** Same ceiling, same reasoning, as {@link DemoChatController#TURN_TIMEOUT}. */
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(45);

    private final DemoShareService demoShareService;
    private final DemoSessionService demoSessionService;
    private final DemoSessionRateLimiter rateLimiter;
    private final AgentChatService agentChatService;
    private final AgentRepository agentRepository;
    // ObjectProvider, matching AgentChatService's own moderation wiring: the
    // subsystem is optional-by-config and this surface must fail open on its
    // absence, not refuse to boot.
    private final ObjectProvider<ModerationService> moderationService;

    public DemoShareController(
            DemoShareService demoShareService,
            DemoSessionService demoSessionService,
            DemoSessionRateLimiter rateLimiter,
            AgentChatService agentChatService,
            AgentRepository agentRepository,
            ObjectProvider<ModerationService> moderationService) {
        this.demoShareService = demoShareService;
        this.demoSessionService = demoSessionService;
        this.rateLimiter = rateLimiter;
        this.agentChatService = agentChatService;
        this.agentRepository = agentRepository;
        this.moderationService = moderationService;
    }

    // ------------------------------------------------------------------
    // Wire shapes (binding contract with the share frontend).
    // ------------------------------------------------------------------

    public record ShareMetaResponse(String agentName, String personaLabel, Instant expiresAt) {}

    public record ShareChatRequest(@Size(max = 2000) String message) {}

    /**
     * @param simulated true when {@code reply} is a canned fallback rather than model output -
     *     additive next to the contract's {@code reply}, so the share page can label a degraded
     *     answer honestly, exactly as the owner's /try page does.
     */
    public record ShareChatResponse(String reply, boolean simulated) {}

    public record ShareReportRequest(@Size(max = 500) String reason) {}

    // ------------------------------------------------------------------

    /**
     * Public share metadata - what the landing frame renders before the viewer says anything. 410
     * for unknown, expired or revoked alike (they are deliberately indistinguishable to the viewer:
     * "this link is done").
     */
    @GetMapping("/{shareId}")
    public ResponseEntity<?> meta(@PathVariable("shareId") String shareId) {
        Optional<DemoSession> found = demoShareService.resolveByShareId(shareId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        DemoSession session = found.get();
        if (session.isExpired(Instant.now()) || session.isShareRevoked()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        try {
            demoShareService.recordView(session);
        } catch (RuntimeException e) {
            log.warn("Could not record a share view for share {}", shareId, e);
        }
        return ResponseEntity.ok(
                new ShareMetaResponse(
                        resolveAgentName(session), personaLabel(session), session.getExpiresAt()));
    }

    /**
     * A viewer's chat turn. Pipeline, in order: validate; per-IP rate limit; distinct-viewer cap;
     * the owner session's message allowance (the anonymous budget - see the class javadoc);
     * moderation on the raw viewer message BEFORE dispatch; then the ordinary {@link
     * AgentChatService} turn in the sandbox's own schema.
     */
    @PostMapping("/{shareId}/chat")
    public ResponseEntity<?> chat(
            @PathVariable("shareId") String shareId,
            @Valid @RequestBody(required = false) ShareChatRequest request,
            HttpServletRequest servletRequest) {
        String message =
                request == null || request.message() == null ? "" : request.message().trim();
        if (message.isEmpty() || message.length() > 2000) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid"));
        }

        Optional<DemoSession> found = demoShareService.resolveByShareId(shareId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        DemoSession session = found.get();
        Instant now = Instant.now();
        if (session.isExpired(now) || session.isShareRevoked()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }

        String clientIp = servletRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquireShareChat(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "rate_limited"));
        }
        if (demoShareService.admitViewer(session, clientIp)
                == DemoShareService.ViewerAdmission.CAP_REACHED) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "viewer_cap"));
        }
        // The platform anonymous budget: a viewer turn spends the owner
        // session's own allowance, so a shared link can never out-spend the
        // sandbox it belongs to. Behind this sits the FREE-plan platform-pool
        // budget inside AgentChatService, identical to the owner's turns.
        if (demoSessionService.isMessageCapReached(session)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "budget_exhausted"));
        }

        Optional<UUID> agentId = demoSessionService.ensureSeededAgent(session, null);
        if (agentId.isEmpty()) {
            // No agent even after a re-seed: degrade to a labelled sample,
            // never an error - see the class javadoc.
            log.warn(
                    "Shared sandbox {} has no agent after a re-seed attempt - serving a fallback",
                    session.getId());
            return ResponseEntity.ok(new ShareChatResponse(fallbackReply(), true));
        }

        // Distinct conversation per viewer (keyed by IP hash) so viewers
        // neither see each other's context nor pollute the owner's own
        // sessions; the subjectKey stays the session's - budgets pool.
        String chatSessionId = "share:" + shareId + ":" + ipHash(clientIp);
        String subjectKey = "demo:" + session.getId();

        // Moderation BEFORE dispatch, on the raw viewer message - a stranger
        // typing into someone else's agent is the least-trusted input this
        // platform accepts. BLOCK and QUEUE_FOR_REVIEW both reject (the same
        // refuse-both posture AgentChatService#moderateInbound takes);
        // subsystem errors fail open, also matching that method.
        if (isModerationRejected(session, agentId.get(), chatSessionId, subjectKey, message)) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "moderated"));
        }

        // Pre-charged before the call, exactly like DemoChatController:
        // otherwise a turn that fails upstream is free, and "make the
        // upstream fail" is not a hard bar on an unauthenticated endpoint.
        demoSessionService.recordMessage(session);

        try {
            String reply =
                    demoSessionService.inDemoTenantContext(
                            session,
                            () -> {
                                ChatTurnRequest chatTurn =
                                        ChatTurnRequest.builder(
                                                        agentId.get(),
                                                        chatSessionId,
                                                        subjectKey,
                                                        message)
                                                .build();
                                // ADR-0022 stage 4: the turn context carries the demo tenant set
                                // just above.
                                TurnContext turnContext =
                                        TurnContext.forEntry(
                                                TenantContext.current().orElse(null), chatTurn);
                                return agentChatService
                                        .chat(turnContext, chatTurn)
                                        .collect(Collectors.joining())
                                        .block(TURN_TIMEOUT);
                            });
            if (reply == null || reply.isBlank()) {
                log.warn(
                        "Shared sandbox {} got an empty completion - serving a fallback",
                        session.getId());
                return ResponseEntity.ok(new ShareChatResponse(fallbackReply(), true));
            }
            return ResponseEntity.ok(new ShareChatResponse(reply, false));
        } catch (RuntimeException e) {
            log.warn(
                    "Share chat turn failed for sandbox {} - serving a fallback response",
                    session.getId(),
                    e);
            return ResponseEntity.ok(new ShareChatResponse(fallbackReply(), true));
        }
    }

    /**
     * Anyone with the link can report it. 204 always (once past the per-IP rate limit and the share
     * existing) - a reporter is told nothing about whether their report tipped the auto-revoke
     * threshold, deliberately.
     */
    @PostMapping("/{shareId}/report")
    public ResponseEntity<?> report(
            @PathVariable("shareId") String shareId,
            @Valid @RequestBody(required = false) ShareReportRequest request,
            HttpServletRequest servletRequest) {
        Optional<DemoSession> found = demoShareService.resolveByShareId(shareId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        if (!rateLimiter.tryAcquireShareReport(servletRequest.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "rate_limited"));
        }
        String reason = request == null ? null : request.reason();
        if (reason != null && reason.length() > 500) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid"));
        }
        try {
            demoShareService.report(found.get(), reason);
        } catch (RuntimeException e) {
            log.warn("Could not record a report for share {}", shareId, e);
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------

    /**
     * @return true when moderation says this message must not be dispatched. Runs inside the demo
     *     tenant's context because the moderation subsystem's audit/review writes are
     *     tenant-scoped, same as when {@code AgentChatService} runs it mid-turn.
     */
    private boolean isModerationRejected(
            DemoSession session,
            UUID agentId,
            String chatSessionId,
            String subjectKey,
            String message) {
        ModerationService moderation = moderationService.getIfAvailable();
        if (moderation == null) {
            return false;
        }
        try {
            ModerationOutcome outcome =
                    demoSessionService.inDemoTenantContext(
                            session,
                            () ->
                                    moderation.moderate(
                                            message,
                                            new ModerationContext(
                                                    session.getTenantId().toString(),
                                                    // Demo tenants are always FREE-plan by
                                                    // construction
                                                    // (DemoSessionRecorder#createProvisioningDemoTenant).
                                                    "FREE",
                                                    agentId,
                                                    chatSessionId,
                                                    subjectKey,
                                                    "share_viewer_input")));
            if (!outcome.isAllowed()) {
                log.info(
                        "Moderation rejected a share-viewer message for sandbox {} (decision={},"
                                + " layer={})",
                        session.getId(),
                        outcome.decision(),
                        outcome.decidingLayer());
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            // Fail open, matching AgentChatService#moderateInbound - and the
            // dispatched turn will run inbound moderation again anyway.
            log.warn(
                    "Share-viewer moderation failed for sandbox {} - failing open",
                    session.getId(),
                    e);
            return false;
        }
    }

    /**
     * Agent display name for the share landing frame, best-effort with an honest generic fallback -
     * a metadata miss must never 500 a share link.
     */
    private String resolveAgentName(DemoSession session) {
        UUID agentId = session.getAgentId();
        if (agentId != null) {
            try {
                String name =
                        demoSessionService.inDemoTenantContext(
                                session,
                                () ->
                                        agentRepository
                                                .findById(agentId)
                                                .map(a -> a.getName())
                                                .orElse(null));
                if (name != null && !name.isBlank()) {
                    return name;
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Could not resolve the agent name for shared sandbox {}",
                        session.getId(),
                        e);
            }
        }
        return DemoPersona.fromParam(session.getPersona())
                .map(DemoPersona::defaultAgentName)
                .orElse("Sandbox assistant");
    }

    /**
     * Human persona label ("a kirana / neighbourhood grocery store"), or null for the generic
     * agent.
     */
    private static String personaLabel(DemoSession session) {
        return DemoPersona.fromParam(session.getPersona())
                .map(DemoPersona::businessLabel)
                .orElse(null);
    }

    /**
     * The share surface's single generic sample - unlike {@code DemoChatController#fallbackFor} it
     * does not shape itself to the question, because the viewer has far less context than the owner
     * and a sample that half-matches reads as a wrong answer rather than a sample.
     */
    private static String fallbackReply() {
        return """
        (Sample response - the live model is busy right now, so here's an example of the shape of \
        answer this agent gives.)

        This is someone's practice AI agent, built in a free sandbox. It replies to customers in \
        their own language, drafts messages and product descriptions, and answers questions about \
        the business it was set up for. Ask it something specific - or build your own free agent \
        at the link on this page.\
        """;
    }

    /** Short stable per-IP key for the viewer's conversation id - never logged, never returned. */
    private static String ipHash(String clientIp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    (clientIp == null ? "" : clientIp)
                                            .getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
