package com.agenticai.core.agents.vision;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two shared-contract session endpoints:
 *
 * <pre>
 * POST /api/vision/sessions            {agentId, mode, source} -> 200 (403/429/503 per VisionSessionService)
 * POST /api/vision/sessions/{id}/end   {}                      -> 200 (404 for an unknown/expired id)
 * </pre>
 *
 * <p>Authenticated + tenant-scoped by {@code SecurityConfig}'s default {@code
 * anyRequest().authenticated()} rule - deliberately NO {@code permitAll} carve-out; any tenant
 * member may start a session for themselves (the quotas are per-user, so there is nothing shared to
 * protect at role level). All policy decisions (platform flag, plan entitlement, approval policy,
 * quotas) live in {@link VisionSessionService}, mapped to their statuses in {@code
 * GlobalExceptionHandler} - this class only resolves who is asking, same split as {@code
 * AgentController#chat}.
 */
@RestController
@RequestMapping("/api/vision/sessions")
public class VisionSessionController {

    private final VisionSessionService sessionService;
    private final AgentService agentService;

    public VisionSessionController(VisionSessionService sessionService, AgentService agentService) {
        this.sessionService = sessionService;
        this.agentService = agentService;
    }

    /**
     * Body of {@code POST /api/vision/sessions} - enum-typed so a bad mode/source 400s at
     * deserialization.
     */
    public record VisionSessionStartRequest(
            @NotNull(message = "agentId is required") UUID agentId,
            @NotNull(message = "mode is required (SNAPSHOT, WATCH or LIVE)") VisionSessionMode mode,
            @NotNull(message = "source is required (CAMERA or SCREEN)") VisionSource source) {}

    /** 200 body of {@code POST /api/vision/sessions} - the shared-contract field names, exactly. */
    public record VisionSessionStartResponse(
            String sessionId,
            VisionSessionMode mode,
            VisionSource source,
            Instant startedAt,
            long frameAllowanceRemaining) {}

    /** 200 body of {@code POST /api/vision/sessions/{sessionId}/end}. */
    public record VisionSessionEndResponse(
            String sessionId, Instant endedAt, long framesAnalyzed, long durationSeconds) {}

    @PostMapping
    public VisionSessionStartResponse start(
            @Valid @RequestBody VisionSessionStartRequest request, Principal principal) {
        // Frames are analysed through the agent's chat path, so starting a session takes the
        // same access as chatting with it.
        agentService.requireAccess(request.agentId(), PermissionLevel.RUNNER);
        VisionSessionService.StartResult result =
                sessionService.start(
                        tenantId(),
                        subjectKey(principal),
                        request.agentId(),
                        request.mode(),
                        request.source());
        return new VisionSessionStartResponse(
                result.session().sessionId(),
                result.session().mode(),
                result.session().source(),
                Instant.ofEpochMilli(result.session().startedAtEpochMilli()),
                result.frameAllowanceRemaining());
    }

    /**
     * The contract's body is an empty JSON object - accepted and ignored (also tolerated absent).
     */
    @PostMapping("/{sessionId}/end")
    public VisionSessionEndResponse end(
            @PathVariable String sessionId,
            @RequestBody(required = false) String ignoredBody,
            Principal principal) {
        VisionSessionService.EndResult result =
                sessionService.end(sessionId, tenantId(), subjectKey(principal));
        return new VisionSessionEndResponse(
                result.sessionId(),
                result.endedAt(),
                result.framesAnalyzed(),
                result.durationSeconds());
    }

    private String tenantId() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No TenantContext for an authenticated /api/vision request - SecurityConfig should have blocked this"));
    }

    /**
     * The same per-user key {@code AgentController#chat} derives for its {@code subjectKey} - the
     * authenticated Keycloak principal when the security profile is on. The quota windows this keys
     * must line up with the ones the chat path charges frames against, or a user's allowance would
     * split into two disconnected buckets. No-security dev fallback is the TenantContext subject
     * (or a fixed marker), never null.
     */
    private String subjectKey(Principal principal) {
        if (principal != null && StringUtils.hasText(principal.getName())) {
            return principal.getName();
        }
        return TenantContext.current().map(TenantContext::getSubject).orElse("anonymous");
    }
}
