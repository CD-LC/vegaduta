package com.agenticai.core.agents.share;

import com.agenticai.core.agents.vision.VisionSessionController;
import com.agenticai.core.agents.vision.VisionSessionMode;
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionSource;
import com.agenticai.core.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/share/agents/{shareId}/vision} - the PUBLIC, anonymous counterpart of {@link
 * VisionSessionController}. Nests under {@code /api/share/agents/**}, already {@code permitAll()}'d
 * in {@code SecurityConfig} - no security config change needed. Used by both the share page's own
 * camera/screen capture UI (Show/Watch) and, for a live "sight" call, {@code
 * voice-agent/app/perception.py}.
 *
 * <p>This is a thin wrapper, not a reimplementation: {@link VisionSessionService#start}/ {@link
 * VisionSessionService#end} are called completely unchanged, with {@code userSubject} set to {@code
 * "share:<shareId>:<ipHash>"} - a stable, per-visitor, per-share key. Every quota/entitlement check
 * that service already does (per-tenant {@code live_vision_allowed} plan gate, per-user
 * frame/watch-minute daily quotas, the platform kill switch) applies automatically, keyed correctly
 * per anonymous visitor, with zero changes to that service. The exceptions it throws already map to
 * 403/429/503 via the existing {@code GlobalExceptionHandler} entries {@link
 * VisionSessionController} itself relies on.
 */
@RestController
@RequestMapping("/api/share/agents/{shareId}/vision")
public class AgentShareVisionController {

    private final AgentShareService shareService;
    private final VisionSessionService sessionService;

    public AgentShareVisionController(
            AgentShareService shareService, VisionSessionService sessionService) {
        this.shareService = shareService;
        this.sessionService = sessionService;
    }

    /**
     * Body of {@code POST /{shareId}/vision/sessions} - same shape as {@link
     * VisionSessionController.VisionSessionStartRequest} minus {@code agentId} (implied by the
     * share).
     */
    public record ShareVisionSessionStartRequest(
            @NotNull(message = "mode is required (SNAPSHOT, WATCH or LIVE)") VisionSessionMode mode,
            @NotNull(message = "source is required (CAMERA or SCREEN)") VisionSource source) {}

    @PostMapping("/sessions")
    public ResponseEntity<?> start(
            @PathVariable String shareId,
            @Valid @RequestBody ShareVisionSessionStartRequest request,
            HttpServletRequest servletRequest) {
        String clientIp = servletRequest.getRemoteAddr();
        if (!shareService.tryAcquireVisionSession(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "rate_limited"));
        }
        Optional<AgentShareService.ResolvedShare> resolved =
                shareService.resolveActiveShare(shareId);
        if (resolved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        try {
            AgentShare share = resolved.get().share();
            shareService.recordViewer(share);
            String tenantId =
                    TenantContext.current()
                            .map(TenantContext::getTenantId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "resolveActiveShare should have set TenantContext"));
            String userSubject = "share:" + shareId + ":" + AgentShareService.ipHash(clientIp);
            VisionSessionService.StartResult result =
                    sessionService.start(
                            tenantId,
                            userSubject,
                            share.getAgentId(),
                            request.mode(),
                            request.source());
            return ResponseEntity.ok(
                    new VisionSessionController.VisionSessionStartResponse(
                            result.session().sessionId(),
                            result.session().mode(),
                            result.session().source(),
                            Instant.ofEpochMilli(result.session().startedAtEpochMilli()),
                            result.frameAllowanceRemaining()));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The contract's body is an empty JSON object - accepted and ignored (also tolerated absent),
     * same as {@link VisionSessionController#end}.
     */
    @PostMapping("/sessions/{sessionId}/end")
    public ResponseEntity<?> end(
            @PathVariable String shareId,
            @PathVariable String sessionId,
            @RequestBody(required = false) String ignoredBody,
            HttpServletRequest servletRequest) {
        Optional<AgentShareService.ResolvedShare> resolved =
                shareService.resolveActiveShare(shareId);
        if (resolved.isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "expired"));
        }
        try {
            String tenantId =
                    TenantContext.current()
                            .map(TenantContext::getTenantId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "resolveActiveShare should have set TenantContext"));
            // The same per-visitor key start() bound the session to.
            String userSubject =
                    "share:"
                            + shareId
                            + ":"
                            + AgentShareService.ipHash(servletRequest.getRemoteAddr());
            VisionSessionService.EndResult result =
                    sessionService.end(sessionId, tenantId, userSubject);
            return ResponseEntity.ok(
                    new VisionSessionController.VisionSessionEndResponse(
                            result.sessionId(),
                            result.endedAt(),
                            result.framesAnalyzed(),
                            result.durationSeconds()));
        } finally {
            TenantContext.clear();
        }
    }
}
