package com.agenticai.core.agents.edge;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.devices.DeviceAuthResolver;
import com.agenticai.core.rl.api.RlTurnRecorder;
import com.agenticai.core.rl.domain.RewardSource;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ONE place an edge
 * (on-device) turn's outcome gets recorded, closing a real gap: before this,
 * nothing anywhere measured how often on-device replies actually succeed vs.
 * silently fall back to hosted vs. error outright (confirmed by direct
 * repo research — {@code core.rl}'s reward loop and every telemetry sink
 * only ever see hosted {@code AgentChatService} turns).
 *
 * <p>Fired once per completed turn from {@code web/app/lib/edge/tierRouter.ts}
 * (fire-and-forget — a failed report must never affect what the user sees).
 * Three things happen per call, deliberately decoupled from each other:
 * <ol>
 *   <li>A structured log line, ALWAYS, regardless of authentication — this
 *       stack already ships container logs to Loki/Grafana, so edge quality
 *       stays queryable even for anonymous callers.</li>
 *   <li>Edge Wave 2 (EDGE-B, migration V191): an {@link EdgeTurn} row, ONLY
 *       for tenant-resolved (Keycloak-authenticated) callers that pass the
 *       RUNNER access gate — the metering half of finding E-4/F-25 (edge
 *       turns used to be log-grep-only). The anonymous {@code /try} surface
 *       stays log-only: there is no tenant schema to write into for it.</li>
 *   <li>An {@link RlTurnRecorder} episode, ONLY when the caller carries a
 *       real Keycloak session — the anonymous public {@code /try} surface
 *       gets logging only, since there is no real tenant/agent identity to
 *       attribute a reward to. Mirrors the composite reward vocabulary
 *       {@code AgentChatService} already uses for hosted turns
 *       ({@code role="user"/"assistant"}, {@code actionType="message"/
 *       "answer"} — see its {@code recordStep} call sites) rather than
 *       inventing a parallel one, and matches hosted's OWN current
 *       instrumentation fidelity: {@code tokens} stays null (hosted doesn't
 *       populate it either yet), only {@code latencyMs} is real. A
 *       successful edge turn also reports a {@code COST} reward of 1.0
 *       (maximally cheap — edge inference is literally free) alongside
 *       {@code TASK_SUCCESS}, an honest signal a future policy pass could use
 *       to prefer edge when quality is comparable. Deliberately NOT attempted
 *       here: making edge a selectable arm in the hosted {@code MODEL_ROUTE}
 *       bandit — edge routing happens client-side before any server call, so
 *       that is a real architectural change for a separate pass, not a
 *       reward-recording detail.</li>
 * </ol>
 *
 * <p>Never fails loudly: a malformed/unauthenticated/unresolvable request
 * still gets a best-effort log line and a 202, so a caller can genuinely
 * fire-and-forget without error handling of its own.
 *
 * <p><b>Device-token callers (2026-08-07):</b> a valid {@code X-Device-Token}
 * header now counts as tenant-resolved too (the same {@link DeviceAuthResolver}
 * fallthrough {@code EdgeToolExecutionController#withCallerSubject} uses, and
 * only consulted when no Keycloak session is present) - desktop-agent daemons'
 * turn reports upgrade from log-only to persisted {@link EdgeTurn} rows + RL
 * episodes. An invalid/revoked token degrades to log-only (still 202), never
 * an error, preserving the fire-and-forget contract above.
 *
 * <p><b>Abuse lid (pentest L7, 2026-08-27):</b> this endpoint is
 * {@code permitAll()} and previously had no rate limit and no payload bound
 * before the log line - an anonymous flood was a free log-volume amplifier.
 * Now: a per-IP fixed-window lid ({@link EdgeTurnTelemetryRateLimiter},
 * default 300/hour, live-tunable) plus a {@link #MAX_BODY_BYTES} payload cap,
 * both dropping silently with a 202 so the fire-and-forget contract holds.
 */
@RestController
@RequestMapping("/api/edge/turns")
public class EdgeTurnTelemetryController {

    private static final Logger log = LoggerFactory.getLogger(EdgeTurnTelemetryController.class);

    /**
     * Pentest L7 (2026-08-27): hard lid on the request body of this
     * unauthenticated endpoint. A legitimate report is a couple hundred bytes
     * (a UUID, a few short strings, two numbers); 8 KB is an order of
     * magnitude of slack. Anything larger is an abuse payload - dropped
     * before the log line, so an anonymous caller can no longer pump
     * megabytes per request into the log pipeline. Checked from the declared
     * {@code Content-Length} (fire-and-forget browser beacons always carry
     * one; a chunked request reports -1 and is instead bounded by the
     * per-field truncation below).
     */
    static final long MAX_BODY_BYTES = 8 * 1024;

    private final AgentService agentService;
    private final ObjectProvider<RlTurnRecorder> rlTurnRecorder;
    private final EdgeTurnRepository edgeTurnRepository;
    private final DeviceAuthResolver deviceAuthResolver;
    private final EdgeTurnTelemetryRateLimiter rateLimiter;

    public EdgeTurnTelemetryController(
        AgentService agentService, ObjectProvider<RlTurnRecorder> rlTurnRecorder,
        EdgeTurnRepository edgeTurnRepository, DeviceAuthResolver deviceAuthResolver,
        EdgeTurnTelemetryRateLimiter rateLimiter
    ) {
        this.agentService = agentService;
        this.rlTurnRecorder = rlTurnRecorder;
        this.edgeTurnRepository = edgeTurnRepository;
        this.deviceAuthResolver = deviceAuthResolver;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Mirrors {@code RouteTurnResult}'s outcome vocabulary in tierRouter.ts —
     * {@code outcome} is {@code "success"}, {@code "fallback"} (a typed tier
     * failure the router fell through from, e.g. {@code all-tiers-failed}),
     * or {@code "error"} (the belt-and-braces catch in {@code routeTurn}).
     * {@code tier} is 0 (Chrome built-in) or 1 (WebLLM); {@code sessionKey}
     * is the same value {@code routeTurn}'s caller already threads through
     * (e.g. the agent id) — carried for log correlation, not used for access
     * control (the RUNNER check below is what actually gates the RL write).
     */
    public record EdgeTurnReportRequest(
        UUID agentId,
        String sessionKey,
        Integer tier,
        String modelId,
        String outcome,
        String reason,
        Long latencyMs
    ) {
    }

    @PostMapping
    public ResponseEntity<Void> report(
        @RequestBody EdgeTurnReportRequest request,
        @RequestHeader(value = "X-Device-Token", required = false) String deviceToken,
        Principal principal,
        HttpServletRequest servletRequest
    ) {
        // Pentest L7 (2026-08-27): this endpoint is permitAll and had no
        // abuse control at all. Oversized payloads and over-the-lid callers
        // are dropped BEFORE the log line and both tenant-scoped sinks -
        // still 202, preserving the fire-and-forget contract (a flood script
        // learns nothing from the response either way).
        if (servletRequest != null && servletRequest.getContentLengthLong() > MAX_BODY_BYTES) {
            return ResponseEntity.accepted().build();
        }
        if (!rateLimiter.tryAcquire(clientIp(servletRequest))) {
            return ResponseEntity.accepted().build();
        }
        if (request == null || request.agentId() == null || !StringUtils.hasText(request.outcome())) {
            return ResponseEntity.badRequest().build();
        }
        boolean success = "success".equals(request.outcome());

        // Free-text fields are truncated to the same bounds the persistence
        // path uses (see persistTurn) so an anonymous caller cannot use the
        // log line itself as an amplifier into the log pipeline.
        log.info(
            "edge_turn_report agentId={} tier={} modelId={} outcome={} reason={} latencyMs={}",
            request.agentId(), request.tier(), truncate(request.modelId(), 128),
            truncate(request.outcome(), 16), truncate(request.reason(), 512), request.latencyMs()
        );

        if (isKeycloakAuthenticated(principal)) {
            recordTenantScoped(request, success);
        } else if (StringUtils.hasText(deviceToken)) {
            // X-Device-Token fallthrough (same DeviceAuthResolver pattern as
            // EdgeToolExecutionController#withCallerSubject, checked only when
            // there is no Keycloak session): a desktop agent daemon has no
            // browser JWT, so before this its reports fell into the anonymous
            // log-only bucket even though the token maps to a real tenant.
            // DeviceAuthResolver.run resolves the token to the tenant schema
            // and populates TenantContext for the duration, so requireAccess/
            // persistTurn/recordEpisode work exactly as they do for a JWT
            // caller. Unlike the tool endpoints, an invalid/revoked token must
            // NOT surface as an error (this endpoint's fire-and-forget
            // contract: always 202) - it degrades to log-only instead.
            try {
                deviceAuthResolver.run(deviceToken, device -> {
                    recordTenantScoped(request, success);
                    return null;
                });
            } catch (RuntimeException e) {
                log.debug("Edge turn device-token resolution failed - staying log-only: {}", e.toString());
            }
        }
        return ResponseEntity.accepted().build();
    }

    /**
     * The two tenant-scoped sinks (EdgeTurn row + RL episode), shared by both
     * credential forms; the caller guarantees a tenant schema is bound
     * (Keycloak via {@code TenantContextFilter}, device token via
     * {@link DeviceAuthResolver#run}). Never throws past itself.
     */
    private void recordTenantScoped(EdgeTurnReportRequest request, boolean success) {
        try {
            // Same RUNNER-level anti-enumeration check the edge tool
            // endpoints use, done ONCE here for both sinks below - a
            // caller can only attribute telemetry/RL data to an agent it
            // can actually run. Throws for missing/cross-tenant/unshared
            // agents, skipping both sinks.
            agentService.requireAccess(request.agentId(), PermissionLevel.RUNNER);
            // Edge Wave 2 (EDGE-B, V191): the DB row that makes edge
            // share-of-load measurable. Only tenant-resolved callers reach
            // this line; the anonymous /try surface stays log-only, exactly
            // as before - it has no tenant schema to write into. Persist
            // failure never blocks the RL path (each sink fails
            // independently).
            try {
                persistTurn(request);
            } catch (RuntimeException e) {
                log.debug("Edge turn persistence skipped for agent {}: {}", request.agentId(), e.toString());
            }
            recordEpisode(request, success);
        } catch (RuntimeException e) {
            // Anti-enumeration lookups (AgentNotFoundException /
            // ResourceAccessDeniedException) and any RL-side failure both
            // land here - the log line above already captured the signal
            // that matters, so this never becomes a caller-visible error.
            log.debug("Edge turn RL recording skipped for agent {}: {}", request.agentId(), e.toString());
        }
    }

    /** Bounded at write time (truncation, never rejection) - see {@link EdgeTurn}'s javadoc. */
    private void persistTurn(EdgeTurnReportRequest request) {
        edgeTurnRepository.save(new EdgeTurn(
            request.agentId(),
            truncate(request.sessionKey(), 255),
            request.tier() == null ? null : request.tier().shortValue(),
            truncate(request.modelId(), 128),
            truncate(request.outcome(), 16),
            truncate(request.reason(), 512),
            request.latencyMs()
        ));
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /**
     * {@code getRemoteAddr()} - deliberately NOT trusting
     * {@code X-Forwarded-For} directly at this layer, identical posture and
     * reason as {@code LandingEventController#clientIp}: a client-settable
     * header trusted blindly would hand the caller a fresh IP per request
     * and walk straight through the lid. Tomcat's {@code RemoteIpValve}
     * (see {@code server.tomcat.remoteip.trusted-proxies}) already resolves
     * the real visitor IP for requests that came through the front proxy.
     */
    private static String clientIp(HttpServletRequest request) {
        return request == null ? "unknown" : request.getRemoteAddr();
    }

    private static boolean isKeycloakAuthenticated(Principal principal) {
        return principal != null
            && StringUtils.hasText(principal.getName())
            && !(principal instanceof AnonymousAuthenticationToken);
    }

    private void recordEpisode(EdgeTurnReportRequest request, boolean success) {
        RlTurnRecorder recorder = rlTurnRecorder.getIfAvailable();
        if (recorder == null) {
            return; // No RL policy wired in this deployment - matches AgentChatService's own no-op posture.
        }
        // RUNNER-level requireAccess already ran in report() (once, shared
        // with the EdgeTurn persistence sink) before this method is reached.

        String turnRef = "edge:" + UUID.randomUUID();
        UUID episodeId = recorder.beginTurn(request.agentId(), request.sessionKey(), turnRef, null, Map.of());
        recorder.recordStep(episodeId, "user", "message", null, null, null, null);
        recorder.recordStep(
            episodeId, "assistant", "answer", null, null, null, request.latencyMs()
        );
        recorder.endTurn(episodeId, true);
        recorder.addReward(episodeId, RewardSource.TASK_SUCCESS, success ? 1.0 : 0.0, null);
        if (success) {
            // Edge inference costs nothing - an honest, maximally-cheap signal.
            recorder.addReward(episodeId, RewardSource.COST, 1.0, null);
        }
        recorder.computeReward(episodeId);
    }
}
