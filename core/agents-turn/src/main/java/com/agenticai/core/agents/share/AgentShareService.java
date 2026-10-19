package com.agenticai.core.agents.share;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.ratelimit.RedisFixedWindowCounter;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent sharing to the external world - "even registered consumers should be able to share their
 * agents to external world". Extends the proven demo sandbox share pattern ({@code
 * DemoShareService}/{@code DemoShareController}) to a REAL tenant's REAL agent, with one deliberate
 * simplification: a demo share needed its own "anonymous budget" machinery because a demo sandbox
 * has no tenant plan behind it; a real agent's viewer turns dispatch through the normal {@link
 * com.agenticai.core.agents.turn.AgentChatService} path inside the OWNING tenant's own {@link
 * TenantContext} (see {@link AgentShareController}), so they are automatically metered against that
 * tenant's own plan/budget/quota exactly like any authenticated turn - nothing new to build there.
 * What IS new here: a per-IP rate limit (abuse control that has nothing to do with billing) and the
 * same report/auto-revoke posture the demo pattern already established.
 *
 * <p>Mint/resolve/revoke happen on the OWNER's tenant schema (normal authenticated request, {@link
 * TenantContext} already set by {@code TenantContextFilter}); {@link #resolveByShareId} is the one
 * cross-tenant operation - callers (an unauthenticated request) must first resolve which schema
 * owns the share id via {@link TenantResolutionService}, set {@link TenantContext} by hand, THEN
 * call this. See {@link AgentShareController} for exactly that sequence.
 */
@Service
public class AgentShareService {

    private static final Logger log = LoggerFactory.getLogger(AgentShareService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 18 bytes -> 24 URL-safe Base64 chars (144 bits) - same size as the demo share id. */
    private static final int SHARE_ID_BYTES = 18;

    private static final Pattern SHARE_ID_SHAPE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final String RATE_LIMIT_KEY_PREFIX = "agentic:agent-share-rate:";

    /**
     * Voice/vision session-START limiters - tighter than chat since a live call or a watch session
     * is a standing resource, not a single message.
     */
    private static final String VOICE_SESSION_RATE_LIMIT_KEY_PREFIX =
            "agentic:agent-share-voice-session-rate:";

    private static final String VISION_SESSION_RATE_LIMIT_KEY_PREFIX =
            "agentic:agent-share-vision-session-rate:";

    /**
     * Shared by push-to-talk turns AND the LiveKit worker's per-turn /chat/stream calls - one
     * budget per visitor, not two.
     */
    private static final String VOICE_TURN_RATE_LIMIT_KEY_PREFIX =
            "agentic:agent-share-voice-turn-rate:";

    private final AgentShareRepository repository;
    private final AgentRepository agentRepository;
    private final ResourceAccessService resourceAccessService;
    private final TenantResolutionService tenantResolutionService;
    private final StringRedisTemplate redisTemplate;
    private final String appPublicUrl;
    private final int reportRevokeThreshold;
    private final long maxChatsPerMinutePerIp;
    private final long maxVoiceSessionsPerMinutePerIp;
    private final long maxVoiceTurnsPerMinute;
    private final long maxVisionSessionsPerMinutePerIp;

    /**
     * SEC-051: lifetime stamped on every newly minted link ({@code app.sharing.default-ttl},
     * ISO-8601 or Spring duration syntax, default 30 days). Zero or negative = new links never
     * expire. Setter-injected so the constructor signature stays unchanged.
     */
    private Duration defaultTtl = Duration.ofDays(30);

    public AgentShareService(
            AgentShareRepository repository,
            AgentRepository agentRepository,
            ResourceAccessService resourceAccessService,
            TenantResolutionService tenantResolutionService,
            StringRedisTemplate redisTemplate,
            @Value("${app.web.public-url:}") String appPublicUrl,
            @Value("${app.agent.share.report-revoke-threshold:3}") int reportRevokeThreshold,
            @Value("${app.agent.share.max-chats-per-minute-per-ip:12}") long maxChatsPerMinutePerIp,
            @Value("${app.agent.share.max-voice-sessions-per-minute-per-ip:4}")
                    long maxVoiceSessionsPerMinutePerIp,
            @Value("${app.agent.share.max-voice-turns-per-minute:20}") long maxVoiceTurnsPerMinute,
            @Value("${app.agent.share.max-vision-sessions-per-minute-per-ip:6}")
                    long maxVisionSessionsPerMinutePerIp) {
        this.repository = repository;
        this.agentRepository = agentRepository;
        this.resourceAccessService = resourceAccessService;
        this.tenantResolutionService = tenantResolutionService;
        this.redisTemplate = redisTemplate;
        this.appPublicUrl =
                appPublicUrl != null && appPublicUrl.endsWith("/")
                        ? appPublicUrl.substring(0, appPublicUrl.length() - 1)
                        : appPublicUrl;
        this.reportRevokeThreshold = reportRevokeThreshold;
        this.maxChatsPerMinutePerIp = maxChatsPerMinutePerIp;
        this.maxVoiceSessionsPerMinutePerIp = maxVoiceSessionsPerMinutePerIp;
        this.maxVoiceTurnsPerMinute = maxVoiceTurnsPerMinute;
        this.maxVisionSessionsPerMinutePerIp = maxVisionSessionsPerMinutePerIp;
    }

    public record ShareLink(String shareId, String shareUrl) {}

    /**
     * A resolved, active (not revoked, agent still exists) share - returned by {@link
     * #resolveActiveShare}. {@link TenantContext} is already set to the owning tenant's schema by
     * the time this is returned; the caller owns clearing it (see that method's javadoc for why
     * this can't be a try/finally-wrapped callback for every caller - some, like a
     * Server-Sent-Events stream, need the context to outlive the synchronous method call that built
     * the response).
     */
    public record ResolvedShare(AgentShare share, Agent agent) {}

    /**
     * The authorization gate for every OWNER-side share operation (see {@link
     * AgentShareOwnerController}): loads the agent - 404 for an id that doesn't exist in the
     * caller's own tenant schema, unchanged - and then requires the caller to BE its owner, or to
     * hold {@code ROLE_tenant-admin}/{@code ROLE_platform-admin}.
     *
     * <p><b>Security fix (2026-07-29).</b> The three owner endpoints previously called {@code
     * AgentService#get}, which is {@link com.agenticai.core.sharing.PermissionLevel#VIEWER} - so
     * any tenant member with read access to a colleague's agent (and, while every row still had
     * {@code created_by IS NULL}, literally ANY authenticated tenant member on ANY agent) could
     * mint a public, unauthenticated share URL for it, or revoke the real owner's. {@link
     * ResourceAccessService#requireOwnerAccess} is the same owner-or-admin bar that granting a
     * single colleague read access already required - exposing the agent to the whole internet must
     * not be easier than that.
     *
     * @throws AgentNotFoundException if no such agent exists in this tenant.
     * @throws com.agenticai.core.sharing.ResourceAccessDeniedException (404-shaped) if the caller
     *     is neither owner nor admin.
     */
    @Transactional(readOnly = true)
    public Agent requireOwnedAgent(UUID agentId) {
        Agent agent =
                agentRepository
                        .findById(agentId)
                        .orElseThrow(() -> new AgentNotFoundException(agentId));
        resourceAccessService.requireOwnerAccess(ResourceType.AGENT, agent.getCreatedBy());
        return agent;
    }

    /**
     * The PUBLIC (link-bearer) counterpart: plain existence lookup for an agent behind an
     * already-validated, unrevoked share id, with NO per-user permission check.
     *
     * <p>That is not a weakened check - it is the correct one for this path. Possession of the
     * unguessable share id is the entire credential here (see {@link AgentShareController}'s
     * javadoc), and the anonymous viewer runs under a synthetic {@code
     * "system:agent-share:<shareId>"} subject with no roles that can never match an owner or a
     * grant. Routing it through {@code AgentService#get} (VIEWER) only ever "passed" because every
     * agent still had {@code created_by IS NULL}; the moment {@code
     * tenant/V155__backfill_agent_workflow_owner.sql} gives those rows a real owner, that call
     * would 404 and every live public share link would go dark. The real authorization for this
     * surface is the share row itself (exists, not revoked, under the report threshold) plus the
     * per-IP rate limit - all already enforced before this is reached.
     *
     * @return empty if the agent was deleted after the share was minted - the caller surfaces that
     *     as the same "gone" response as an expired or revoked share.
     */
    @Transactional(readOnly = true)
    public Optional<Agent> loadSharedAgent(UUID agentId) {
        return agentRepository.findById(agentId);
    }

    /**
     * Mints (or returns the existing) share for this agent - idempotent, matching the demo
     * pattern's own reasoning. Runs in the CALLER's own tenant context (an authenticated owner
     * action).
     */
    @Transactional
    public ShareLink createOrGet(UUID agentId) {
        String tenantSchema = currentTenantSchemaOrThrow();
        AgentShare existing = repository.findByAgentId(agentId).orElse(null);
        if (existing != null && isLive(existing)) {
            return toLink(existing.getShareId());
        }
        // Revoked OR expired (SEC-051) both fall through to minting a brand-new
        // id below - an expired link, like a revoked one, stays dead forever.
        if (existing != null) {
            // Previously revoked (e.g. abuse reports) - mint a fresh id rather
            // than silently un-revoking the old one, so a link circulated
            // before the revocation stays dead forever.
            repository.delete(existing);
            // flush() deliberately, right after the delete: Hibernate's
            // ActionQueue executes INSERTs BEFORE DELETEs at flush time, so
            // the queued delete alone does NOT clear uq_agent_share_agent_id
            // before the saveAndFlush below inserts the replacement row -
            // the same share -> revoke -> re-share 500 fixed on
            // WorkflowShareService (identical constraint shape). Flushing
            // now forces the DELETE to land first.
            repository.flush();
            tenantResolutionService.unlink(
                    ExternalAccountProvider.AGENT_SHARE, existing.getShareId());
        }
        String shareId = newShareId();
        try {
            // saveAndFlush (not save) deliberately - forces the INSERT to run
            // now instead of at end-of-transaction commit, so the
            // uq_agent_share_agent_id violation below is catchable here. Two
            // concurrent createOrGet calls for the same agent (double-click,
            // two tabs) can both pass the findByAgentId check above before
            // either commits; the loser's insert hits the unique constraint.
            // "Idempotent, matching the demo pattern" (this method's own
            // javadoc) means the loser should still get back a valid link -
            // the winner's - not a raw 500.
            AgentShare minted = new AgentShare(agentId, shareId);
            minted.setExpiresAt(expiryForNewLink());
            repository.saveAndFlush(minted);
        } catch (DataIntegrityViolationException e) {
            return repository
                    .findByAgentId(agentId)
                    .map(share -> toLink(share.getShareId()))
                    .orElseThrow(() -> e);
        }
        tenantResolutionService.link(ExternalAccountProvider.AGENT_SHARE, shareId, tenantSchema);
        return toLink(shareId);
    }

    @Transactional(readOnly = true)
    public Optional<AgentShare> statusFor(UUID agentId) {
        return repository.findByAgentId(agentId);
    }

    /**
     * Revokes this agent's share, if any - the link stops resolving immediately. Never un-revoked
     * by this method; re-sharing mints a brand new id (see {@link #createOrGet}).
     */
    @Transactional
    public void revoke(UUID agentId) {
        repository
                .findByAgentId(agentId)
                .ifPresent(
                        share -> {
                            share.revoke();
                            tenantResolutionService.unlink(
                                    ExternalAccountProvider.AGENT_SHARE, share.getShareId());
                        });
    }

    /**
     * Cross-tenant: resolves a public share id to the tenant schema that owns it. Shape-checked
     * before any lookup (same defensive posture as {@code DemoShareService#resolveByShareId}) -
     * unknown/malformed both come back empty, indistinguishably.
     */
    public Optional<String> resolveTenantSchema(String shareId) {
        if (shareId == null || !SHARE_ID_SHAPE.matcher(shareId).matches()) {
            return Optional.empty();
        }
        return tenantResolutionService.resolveTenantSchema(
                ExternalAccountProvider.AGENT_SHARE, shareId);
    }

    /**
     * Must be called AFTER {@link TenantContext} has already been set to the schema {@link
     * #resolveTenantSchema} returned.
     */
    @Transactional(readOnly = true)
    public Optional<AgentShare> resolveByShareId(String shareId) {
        return repository.findByShareId(shareId);
    }

    @Transactional
    public void recordViewer(AgentShare share) {
        // The caller's entity is detached (resolved in a read-only tx, open-in-view is off), so
        // mutating it would never reach the database. Re-load the managed row and mutate that.
        repository.findByIdForUpdate(share.getId()).ifPresent(AgentShare::recordViewer);
    }

    /**
     * @return true when this report pushed the share past the auto-revoke threshold.
     */
    @Transactional
    public boolean report(AgentShare detached, String reason) {
        // Re-load the managed row: the caller's entity is detached, see recordViewer.
        AgentShare share = repository.findByIdForUpdate(detached.getId()).orElse(null);
        if (share == null) {
            return false;
        }
        boolean revoked = share.report(Math.max(1, reportRevokeThreshold));
        if (revoked) {
            tenantResolutionService.unlink(ExternalAccountProvider.AGENT_SHARE, share.getShareId());
            log.info(
                    "Agent share {} auto-revoked after reaching the report threshold",
                    share.getShareId());
        }
        if (reason != null && !reason.isBlank()) {
            String trimmed = reason.strip();
            log.info(
                    "Agent share {} reported: {}",
                    share.getShareId(),
                    trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed);
        }
        return revoked;
    }

    /**
     * Per-IP fixed-window rate limit on the public chat endpoint - abuse control, independent of
     * and in addition to whatever the owning tenant's own plan/budget already enforces on the turn
     * itself. See {@link #tryAcquire} for the Redis-down behaviour.
     */
    public boolean tryAcquireChat(String clientIp) {
        return tryAcquire(RATE_LIMIT_KEY_PREFIX, clientIp, maxChatsPerMinutePerIp);
    }

    /**
     * Per-IP limiter on minting a voice/vision LiveKit call token - a live call is a standing
     * resource, so this budget is tighter than a single chat message's.
     */
    public boolean tryAcquireVoiceSession(String clientIp) {
        return tryAcquire(
                VOICE_SESSION_RATE_LIMIT_KEY_PREFIX, clientIp, maxVoiceSessionsPerMinutePerIp);
    }

    /**
     * Per-visitor limiter shared by push-to-talk turns (keyed by {@code ipHash(clientIp)}) and the
     * LiveKit worker's per-turn calls into {@code /chat/stream} (keyed by the {@code
     * shareSessionKey} minted at token-issuance time, since the worker calls from the voice-agent
     * container's own IP, not the visitor's - see {@code AgentShareVoiceController}'s javadoc). One
     * budget per visitor regardless of which transport carried the turn.
     */
    public boolean tryAcquireVoiceTurn(String key) {
        return tryAcquire(VOICE_TURN_RATE_LIMIT_KEY_PREFIX, key, maxVoiceTurnsPerMinute);
    }

    /**
     * Per-IP limiter on starting a vision (camera/screen) session - same reasoning as {@link
     * #tryAcquireVoiceSession}.
     */
    public boolean tryAcquireVisionSession(String clientIp) {
        return tryAcquire(
                VISION_SESSION_RATE_LIMIT_KEY_PREFIX, clientIp, maxVisionSessionsPerMinutePerIp);
    }

    /**
     * Shared fixed-window limiter behind every {@code tryAcquire*} above. Used to fail OPEN on a
     * Redis error - and these anonymous chat/voice/vision turns spend the sharing tenant's LLM
     * budget, so a Redis blip meant unmetered public spend. Now atomic, with an in-process fallback
     * (security audit 2026-10).
     */
    private boolean tryAcquire(String keyPrefix, String key, long limitPerMinute) {
        if (key == null || key.isBlank()) {
            return true;
        }
        return RedisFixedWindowCounter.tryAcquire(
                redisTemplate, keyPrefix + key, limitPerMinute, Duration.ofMinutes(1));
    }

    /**
     * Resolves + validates a share id and, on success, sets {@link TenantContext} to its owning
     * schema - the same cross-tenant sequence {@code AgentShareController}'s own private {@code
     * withResolvedShare} already runs, generalized here for OTHER modules' controllers (voice,
     * vision) that need the identical resolve step but can't reuse that private method or its
     * {@code ResponseEntity}-shaped return type. {@code AgentShareController} itself is left
     * calling its own copy unchanged - lower risk than migrating a working, tested code path for
     * this change.
     *
     * <p>Returns empty for ANY invalid/revoked/gone case (unknown share id, bad shape, revoked, or
     * the agent itself since-deleted) - deliberately collapsed to one outcome, matching this
     * surface's "indistinguishable gone" posture (see {@code AgentShareController}'s class
     * javadoc). {@link TenantContext} is left CLEARED on an empty return.
     *
     * <p><b>The caller owns clearing {@link TenantContext} on a present return</b> - this cannot
     * itself wrap the caller's work in a try/finally the way the {@code AgentShareController}
     * original does, because callers of this method build a {@code Flux} (an SSE chat/voice stream)
     * rather than block synchronously; clearing here would drop the context before the caller has
     * even started building that Flux. This matches the same "set before, clear right after
     * building - not after the reactive pipeline finishes running" boundary the codebase's other
     * permitAll Flux-returning endpoints already carry (see {@code VoiceCallController#turn}, which
     * relies on the same style of context lifetime via {@code TenantContextFilter} for its
     * authenticated equivalent).
     */
    public Optional<ResolvedShare> resolveActiveShare(String shareId) {
        Optional<String> tenantSchema = resolveTenantSchema(shareId);
        if (tenantSchema.isEmpty()) {
            return Optional.empty();
        }
        TenantContext.set(
                TenantContext.of(tenantSchema.get(), "system:agent-share:" + shareId, Set.of()));
        Optional<AgentShare> share = resolveByShareId(shareId);
        if (share.isEmpty() || !isLive(share.get())) {
            TenantContext.clear();
            return Optional.empty();
        }
        Optional<Agent> agent = loadSharedAgent(share.get().getAgentId());
        if (agent.isEmpty()) {
            TenantContext.clear();
            return Optional.empty();
        }
        return Optional.of(new ResolvedShare(share.get(), agent.get()));
    }

    /**
     * Short stable per-IP key for a viewer's conversation/session id - never logged, never
     * returned. Same idiom {@code AgentShareController} already used privately, promoted here so
     * the new voice/vision controllers don't duplicate the SHA-256 logic.
     */
    public static String ipHash(String clientIp) {
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

    private String currentTenantSchemaOrThrow() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Agent sharing requires an authenticated tenant context"));
    }

    private ShareLink toLink(String shareId) {
        return new ShareLink(shareId, appPublicUrl + "/agent/" + shareId);
    }

    private static String newShareId() {
        byte[] bytes = new byte[SHARE_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---- SEC-051: link expiry ----------------------------------------------

    @Value("${app.sharing.default-ttl:P30D}")
    void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    /**
     * The expiry to stamp on a link minted now; null (never expires) when the TTL is zero/negative.
     */
    Instant expiryForNewLink() {
        return defaultTtl == null || defaultTtl.isZero() || defaultTtl.isNegative()
                ? null
                : Instant.now().plus(defaultTtl);
    }

    /** Why a share stopped resolving; see {@link #endedReason}. */
    public static final String ENDED_ABUSE_REPORTS = "ABUSE_REPORTS";
    public static final String ENDED_OWNER_REVOKED = "OWNER_REVOKED";
    public static final String ENDED_EXPIRED = "EXPIRED";

    /**
     * Why this share is no longer live, or {@code null} while it still is. Derived, not stored: a
     * revoked share whose report count reached the auto-revoke threshold was ended by abuse reports
     * (the only path that both counts reports and revokes); a revoked share below it was revoked by
     * its owner; an unrevoked one past {@code expiresAt} expired. If the threshold is lowered after
     * the fact an old owner revoke with enough reports can read as {@code ABUSE_REPORTS}.
     */
    public String endedReason(AgentShare share) {
        return endedReason(share, reportRevokeThreshold);
    }

    /** Pure form of {@link #endedReason(AgentShare)}, for tests. */
    public static String endedReason(AgentShare share, int reportRevokeThreshold) {
        if (share == null) {
            return null;
        }
        if (share.isRevoked()) {
            return share.getReportCount() >= Math.max(1, reportRevokeThreshold)
                ? ENDED_ABUSE_REPORTS : ENDED_OWNER_REVOKED;
        }
        return share.isExpired(java.time.Instant.now()) ? ENDED_EXPIRED : null;
    }

    /**
     * The single "does this link still resolve" rule every public endpoint (and the owner status
     * view) applies: not revoked, and not past {@code expires_at}. A legacy row with NULL {@code
     * expires_at} (minted before tenant/V365.5) is treated as non-expiring on purpose - no link a
     * tenant already handed out goes dark on deploy - and is logged so an operator can find and
     * re-share it. Logged by agent id, never by share id (the share id IS the public credential).
     */
    public boolean isLive(AgentShare share) {
        if (share == null || share.isRevoked()) {
            return false;
        }
        if (share.getExpiresAt() == null) {
            log.info(
                    "Legacy non-expiring agent share link used for agent {} (no expires_at) -"
                            + " revoke and re-share it to apply app.sharing.default-ttl",
                    share.getAgentId());
            return true;
        }
        return !share.isExpired(Instant.now());
    }
}
