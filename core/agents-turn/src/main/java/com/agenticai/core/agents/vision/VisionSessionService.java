package com.agenticai.core.agents.vision;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The lifecycle of one vision session ({@code POST /api/vision/sessions} / {@code
 * .../{sessionId}/end}, see {@link VisionSessionController}): a Redis-backed record (24h TTL, JSON
 * blob - text metadata ONLY, never frame bytes; frames are ephemeral by design, see the research
 * doc's privacy non-negotiables) tracking who is capturing what, plus a running {@code
 * framesAnalyzed} counter that chat turns carrying this session's id bump ({@code
 * AgentChatService}).
 *
 * <p>{@link #start} is the governance chokepoint, checked in cheapest/hardest-to-argue-with order:
 *
 * <ol>
 *   <li>platform flag off -> {@link VisionDisabledException} (503);
 *   <li>WATCH/LIVE without the plan's {@code live_vision_allowed} entitlement (V180) -> {@link
 *       VisionNotAllowedForPlanException} (403);
 *   <li>WATCH/LIVE while the tenant's approval policy still requires human approval for {@code
 *       camera.capture.start} -> {@link VisionApprovalRequiredException} (403). The approval-policy
 *       patterns are otherwise consumed only by {@code GuardedToolCallback} (agent-initiated TOOL
 *       calls, which park a pending {@code ApprovalRequest} and wait) - that pause-and-resume shape
 *       can't work here because the requester IS the human, synchronously waiting on this response,
 *       so the policy is consulted explicitly and enforced as a synchronous refusal instead. {@code
 *       camera.capture.start} is NOT in the platform's default-sensitive patterns (the user
 *       pointing their own camera after the panel's consent flow is not an agent acting on the
 *       world), so a fresh tenant's WATCH/LIVE works out of the box; this 403 fires only for
 *       tenants whose admin added a {@code requiresApproval=true} override row. One-off SNAPSHOT
 *       sessions skip this check on purpose - the user's own explicit click is the human approval
 *       for a single frame;
 *   <li>no frame headroom left today (or, for WATCH/LIVE, no minute headroom) -> {@link
 *       VisionQuotaExceededException} (429).
 * </ol>
 *
 * <p>Frame QUOTA enforcement stays in {@link VisionUsageService} at the chat chokepoint - this
 * class only attributes frames to a session for the end summary, so a client that never starts a
 * session still can't dodge metering.
 */
@Service
public class VisionSessionService {

    private static final Logger log = LoggerFactory.getLogger(VisionSessionService.class);

    /** One WARN per minute while Redis is down, not one per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    /**
     * The approval-policy action type consulted for continuous capture. Deliberately NOT in {@code
     * ApprovalPolicyService.DEFAULT_SENSITIVE_PATTERNS} - a fresh tenant's WATCH/LIVE works out of
     * the box, and only an explicit tenant {@code requiresApproval=true} override row blocks it
     * (any future agent-initiated camera TOOL gets the standard GuardedToolCallback gate under the
     * same name).
     */
    static final String CAMERA_CAPTURE_ACTION = "camera.capture.start";

    /**
     * Stable prefix stamped onto {@link VisionApprovalRequiredException}'s message so a client can
     * tell this 403 apart from {@link VisionNotAllowedForPlanException}'s identically-statused 403
     * without parsing prose. {@code GlobalExceptionHandler} also puts a proper {@code code:
     * "VISION_APPROVAL_REQUIRED"} JSON field on the response for the same purpose (see its
     * handler's javadoc) - that's the long-term signal, but {@code web/app/lib/api.ts}'s
     * quiet-error path for this endpoint doesn't thread arbitrary body fields through to callers
     * yet, so this message prefix is what {@code CameraPanel.tsx} actually reads today. Stripped
     * back off before the message reaches a user (see {@code CameraPanel.watchErrorMessage}).
     */
    static final String APPROVAL_REQUIRED_MESSAGE_PREFIX = "[VISION_APPROVAL_REQUIRED] ";

    private static final String SESSION_KEY_PREFIX = "agentic:vision-session:";
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final VisionProperties properties;
    private final VisionUsageService usageService;
    private final TenantLimits tenantLimits;
    private final ApprovalPolicyService approvalPolicyService;

    public VisionSessionService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            VisionProperties properties,
            VisionUsageService usageService,
            TenantLimits tenantLimits,
            ApprovalPolicyService approvalPolicyService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.usageService = usageService;
        this.tenantLimits = tenantLimits;
        this.approvalPolicyService = approvalPolicyService;
    }

    /**
     * The stored session record - {@code startedAtEpochMilli} rather than an {@code Instant} field
     * so serialization needs no JavaTimeModule (mirrors why {@code PlanLimitsService} hand-rolls
     * its cache format: keep the Redis payload dependency-free and boring).
     */
    public record VisionSessionRecord(
            String sessionId,
            String tenantId,
            String userSubject,
            UUID agentId,
            VisionSessionMode mode,
            VisionSource source,
            long startedAtEpochMilli,
            long framesAnalyzed) {}

    /**
     * What {@link #start} hands the controller - the record plus the live allowance number the
     * contract exposes.
     */
    public record StartResult(VisionSessionRecord session, long frameAllowanceRemaining) {}

    /** What {@link #end} hands the controller. */
    public record EndResult(
            String sessionId, Instant endedAt, long framesAnalyzed, long durationSeconds) {}

    public StartResult start(
            String tenantId,
            String userSubject,
            UUID agentId,
            VisionSessionMode mode,
            VisionSource source) {
        if (!properties.isEnabled()) {
            throw new VisionDisabledException(
                    "Server-side vision is turned off on this platform right now. Try again later.");
        }
        TenantPlan plan = tenantLimits.plan(tenantId);
        if (mode.isContinuous()) {
            if (!tenantLimits.liveVisionAllowed(tenantId)) {
                throw new VisionNotAllowedForPlanException(
                        "Continuous "
                                + mode.name().toLowerCase(java.util.Locale.ROOT)
                                + " vision sessions aren't included in your plan - "
                                + "upgrade to let agents watch a camera or screen live. One-off snapshots still work on every plan.");
            }
            // See the class javadoc's item 3 for why this is a synchronous
            // refusal rather than a parked ApprovalRequest.
            if (approvalPolicyService.requiresApproval(CAMERA_CAPTURE_ACTION)) {
                throw new VisionApprovalRequiredException(
                        APPROVAL_REQUIRED_MESSAGE_PREFIX
                                + "Your workspace's approval policy requires admin approval for continuous camera/screen capture. "
                                + "A tenant admin can allow it by setting the '"
                                + CAMERA_CAPTURE_ACTION
                                + "' approval policy to not require approval.");
            }
            usageService.checkWatchMinutesHeadroom(tenantId, userSubject, plan);
        }
        long remaining = usageService.framesRemaining(tenantId, userSubject, plan);
        if (remaining <= 0) {
            throw new VisionQuotaExceededException(
                    "Daily vision frame limit reached ("
                            + usageService.framesDailyCapFor(plan)
                            + " frames/day on your plan). Try again tomorrow.");
        }

        VisionSessionRecord record =
                new VisionSessionRecord(
                        UUID.randomUUID().toString(),
                        tenantId,
                        userSubject,
                        agentId,
                        mode,
                        source,
                        Instant.now().toEpochMilli(),
                        0);
        save(record);
        return new StartResult(record, remaining);
    }

    /**
     * Attributes {@code frames} already-quota-checked frames to a session's running counter (called
     * from {@code AgentChatService} when a turn carries a {@code visionSessionId}). Best-effort by
     * design: an unknown/ expired session id, a session belonging to another tenant, or a Redis
     * hiccup logs and moves on - the turn itself must never fail over its session ATTRIBUTION,
     * because the actual quota was already enforced by {@link VisionUsageService#recordFrames}
     * regardless.
     */
    public void recordFrames(String sessionId, String tenantId, int frames) {
        if (frames <= 0) {
            return;
        }
        try {
            VisionSessionRecord record = load(sessionId);
            if (record == null || !record.tenantId().equals(tenantId)) {
                log.debug(
                        "Ignoring vision frame attribution to unknown/foreign session '{}'",
                        sessionId);
                return;
            }
            save(
                    new VisionSessionRecord(
                            record.sessionId(),
                            record.tenantId(),
                            record.userSubject(),
                            record.agentId(),
                            record.mode(),
                            record.source(),
                            record.startedAtEpochMilli(),
                            record.framesAnalyzed() + frames));
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to attribute {} frame(s) to vision session '{}' - continuing without",
                    frames,
                    sessionId,
                    e);
        }
    }

    /**
     * Finalizes a session: deletes the record, books the wall-clock minutes of a continuous session
     * against the user's daily window ({@link VisionUsageService#recordWatchMinutes} - rounded UP
     * so a 10-second session costs a minute, not zero), and returns the stats the contract
     * promises. 404 ({@link VisionSessionNotFoundException}) for an id this tenant has no live
     * record of, and for a session someone else started: only the subject that started it may end
     * it (a member, or one share visitor), so ending another person's session looks exactly like an
     * unknown id.
     */
    public EndResult end(String sessionId, String tenantId, String userSubject) {
        VisionSessionRecord record = load(sessionId);
        if (record == null
                || !record.tenantId().equals(tenantId)
                || !record.userSubject().equals(userSubject)) {
            throw new VisionSessionNotFoundException(sessionId);
        }
        try {
            redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
        } catch (RuntimeException e) {
            // Best effort: the record expires on its own (24h TTL) and the user's minutes below
            // must still be booked.
            REDIS_DOWN_LOG.warn(
                    "vision-session",
                    "Could not delete vision session record (Redis error: "
                            + e.getClass().getSimpleName()
                            + ") - it will expire on its own");
        }
        Instant endedAt = Instant.now();
        long durationSeconds =
                Math.max(
                        0,
                        Duration.between(
                                        Instant.ofEpochMilli(record.startedAtEpochMilli()), endedAt)
                                .toSeconds());
        if (record.mode().isContinuous()) {
            long minutes = (durationSeconds + 59) / 60;
            usageService.recordWatchMinutes(tenantId, record.userSubject(), minutes);
        }
        return new EndResult(sessionId, endedAt, record.framesAnalyzed(), durationSeconds);
    }

    /**
     * A vision session IS its Redis record, so with Redis down there is nothing to fall back to.
     * What changes is how that is reported: a clean 503 (mapped by GlobalExceptionHandler's
     * ResponseStatusException handling) instead of the catch-all 500 + an Issues-portal record. Bug
     * fix, no flag.
     */
    private static ResponseStatusException redisUnavailable(RuntimeException cause) {
        REDIS_DOWN_LOG.warn(
                "vision-session",
                "Vision sessions unavailable (Redis error: "
                        + cause.getClass().getSimpleName()
                        + ")");
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Vision sessions are temporarily unavailable. Try again shortly.",
                cause);
    }

    private VisionSessionRecord load(String sessionId) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
        } catch (RuntimeException e) {
            throw redisUnavailable(e);
        }
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, VisionSessionRecord.class);
        } catch (JacksonException e) {
            log.warn("Corrupt vision-session record for '{}' - treating as absent", sessionId, e);
            return null;
        }
    }

    private void save(VisionSessionRecord record) {
        try {
            String json = objectMapper.writeValueAsString(record);
            // Fresh 24h TTL on every write - a session being actively used
            // stays alive; an abandoned one (tab closed, end never called)
            // still expires without a cleanup job.
            redisTemplate
                    .opsForValue()
                    .set(SESSION_KEY_PREFIX + record.sessionId(), json, SESSION_TTL);
        } catch (org.springframework.dao.DataAccessException e) {
            throw redisUnavailable(e);
        } catch (JacksonException e) {
            // A record of our own fixed shape failing to serialize is a bug,
            // not an operational condition.
            throw new IllegalStateException("Could not serialize vision session record", e);
        }
    }
}
