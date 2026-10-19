package com.agenticai.core.workflow.share;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowDefinitionRepository;
import com.agenticai.core.workflow.WorkflowNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

/**
 * Workflow sharing to the external world - the workflow counterpart of {@code
 * com.agenticai.core.agents.share.AgentShareService}, mirrored closely (see that class's javadoc
 * for the full reasoning behind this shape). A shared workflow's run dispatches through the normal
 * {@link com.agenticai.core.workflow.WorkflowService#run} path inside the OWNING tenant's own
 * {@link TenantContext} (see {@link WorkflowShareController}), so it is automatically metered
 * against that tenant's own plan/budget/quota exactly like any authenticated run - nothing new to
 * build there. What IS new here: a per-visitor rate limit (abuse control, unrelated to billing) and
 * the same report/auto-revoke posture the agent-share pattern already established.
 *
 * <p>Mint/resolve/revoke happen on the OWNER's tenant schema (normal authenticated request, {@link
 * TenantContext} already set by {@code TenantContextFilter}); {@link #resolveActiveShare} is the
 * one cross-tenant operation - callers (an unauthenticated request) must first resolve which schema
 * owns the share id, then call this. See {@link WorkflowShareController} for exactly that sequence.
 */
@Service
public class WorkflowShareService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowShareService.class);

    /**
     * One WARN per minute per call site while a dependency is down, instead of one (with a stack
     * trace) per request.
     */
    private static final RateLimitedLog REDIS_DOWN_LOG =
            new RateLimitedLog(log, Duration.ofMinutes(1));

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 18 bytes -> 24 URL-safe Base64 chars (144 bits) - same size as agent_share's id. */
    private static final int SHARE_ID_BYTES = 18;

    private static final Pattern SHARE_ID_SHAPE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final String RUN_RATE_LIMIT_KEY_PREFIX = "agentic:workflow-share-run-rate:";

    /**
     * Voice turn - one shared budget per visitor across push-to-talk turns, same reasoning as
     * AgentShareService's voice-turn limiter.
     */
    private static final String VOICE_TURN_RATE_LIMIT_KEY_PREFIX =
            "agentic:workflow-share-voice-turn-rate:";

    private final WorkflowShareRepository repository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final ResourceAccessService resourceAccessService;
    private final TenantResolutionService tenantResolutionService;
    private final StringRedisTemplate redisTemplate;
    private final String appPublicUrl;
    private final int reportRevokeThreshold;
    private final long maxRunsPerMinutePerIp;
    private final long maxVoiceTurnsPerMinute;

    /** SEC-051: see AgentShareService#defaultTtl - same property, same semantics. */
    private Duration defaultTtl = Duration.ofDays(30);

    public WorkflowShareService(
            WorkflowShareRepository repository,
            WorkflowDefinitionRepository workflowDefinitionRepository,
            ResourceAccessService resourceAccessService,
            TenantResolutionService tenantResolutionService,
            StringRedisTemplate redisTemplate,
            @Value("${app.web.public-url:}") String appPublicUrl,
            @Value("${app.workflow.share.report-revoke-threshold:3}") int reportRevokeThreshold,
            @Value("${app.workflow.share.max-runs-per-minute-per-ip:12}")
                    long maxRunsPerMinutePerIp,
            @Value("${app.workflow.share.max-voice-turns-per-minute:20}")
                    long maxVoiceTurnsPerMinute) {
        this.repository = repository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.resourceAccessService = resourceAccessService;
        this.tenantResolutionService = tenantResolutionService;
        this.redisTemplate = redisTemplate;
        this.appPublicUrl =
                appPublicUrl != null && appPublicUrl.endsWith("/")
                        ? appPublicUrl.substring(0, appPublicUrl.length() - 1)
                        : appPublicUrl;
        this.reportRevokeThreshold = reportRevokeThreshold;
        this.maxRunsPerMinutePerIp = maxRunsPerMinutePerIp;
        this.maxVoiceTurnsPerMinute = maxVoiceTurnsPerMinute;
    }

    public record ShareLink(String shareId, String shareUrl) {}

    /**
     * A resolved, active (not revoked, workflow still exists) share - returned by {@link
     * #resolveActiveShare}. {@link TenantContext} is already set to the owning tenant's schema by
     * the time this is returned; the caller owns clearing it (see {@code
     * AgentShareService#resolveActiveShare}'s javadoc for why this can't be a try/finally-wrapped
     * callback for every caller).
     */
    public record ResolvedShare(WorkflowShare share, WorkflowDefinition workflow) {}

    /**
     * The authorization gate for every OWNER-side share operation: loads the workflow - 404 for an
     * id that doesn't exist in the caller's own tenant schema - and then requires the caller to BE
     * its owner, or to hold {@code ROLE_tenant-admin}/{@code ROLE_platform-admin}. Deliberately
     * {@link ResourceAccessService#requireOwnerAccess}, NOT the graded VIEWER/RUNNER/EDITOR {@code
     * WorkflowService#requireAccess} - exposing a workflow to the whole internet must not be easier
     * than granting a single colleague read access (same bar {@code AgentShareService
     * #requireOwnedAgent}'s 2026-07-29 fix established for agents).
     *
     * @throws WorkflowNotFoundException if no such workflow exists in this tenant.
     * @throws com.agenticai.core.sharing.ResourceAccessDeniedException (404-shaped) if the caller
     *     is neither owner nor admin.
     */
    @Transactional(readOnly = true)
    public WorkflowDefinition requireOwnedWorkflow(UUID workflowId) {
        WorkflowDefinition definition =
                workflowDefinitionRepository
                        .findById(workflowId)
                        .orElseThrow(() -> new WorkflowNotFoundException(workflowId));
        resourceAccessService.requireOwnerAccess(ResourceType.WORKFLOW, definition.getCreatedBy());
        return definition;
    }

    /**
     * The PUBLIC (link-bearer) counterpart: plain existence lookup for a workflow behind an
     * already-validated, unrevoked share id, with NO per-user permission check - same reasoning as
     * {@code AgentShareService#loadSharedAgent}'s javadoc (possession of the unguessable share id
     * is the entire credential here).
     *
     * @return empty if the workflow was deleted after the share was minted.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowDefinition> loadSharedWorkflow(UUID workflowId) {
        return workflowDefinitionRepository.findById(workflowId);
    }

    /**
     * Mints (or returns the existing) share for this workflow - idempotent, matching the
     * agent-share pattern. Runs in the CALLER's own tenant context (an authenticated owner action).
     */
    @Transactional
    public ShareLink createOrGet(UUID workflowId) {
        String tenantSchema = currentTenantSchemaOrThrow();
        WorkflowShare existing = repository.findByWorkflowId(workflowId).orElse(null);
        if (existing != null && isLive(existing)) {
            return toLink(existing.getShareId());
        }
        // Revoked OR expired (SEC-051) both mint a brand-new id below.
        if (existing != null) {
            // Previously revoked (e.g. abuse reports) - mint a fresh id
            // rather than silently un-revoking the old one, so a link
            // circulated before the revocation stays dead forever.
            repository.delete(existing);
            // flush() deliberately, right after the delete: Hibernate's
            // ActionQueue executes INSERTs BEFORE DELETEs at flush time, so
            // the queued delete alone does NOT clear uq_workflow_share_workflow_id
            // before the saveAndFlush below inserts the replacement row -
            // seen live in prod as a 500 on share -> revoke -> re-share
            // (PSQLException on that constraint, and the catch-path
            // findByWorkflowId recovery then fails on the aborted
            // transaction). Flushing now forces the DELETE to land first.
            repository.flush();
            tenantResolutionService.unlink(
                    ExternalAccountProvider.WORKFLOW_SHARE, existing.getShareId());
        }
        String shareId = newShareId();
        try {
            // saveAndFlush (not save) deliberately - forces the INSERT to
            // run now instead of at end-of-transaction commit, so the
            // uq_workflow_share_workflow_id violation below is catchable
            // here - same double-click/two-tabs race AgentShareService
            // guards against.
            WorkflowShare minted = new WorkflowShare(workflowId, shareId);
            minted.setExpiresAt(expiryForNewLink());
            repository.saveAndFlush(minted);
        } catch (DataIntegrityViolationException e) {
            return repository
                    .findByWorkflowId(workflowId)
                    .map(share -> toLink(share.getShareId()))
                    .orElseThrow(() -> e);
        }
        tenantResolutionService.link(ExternalAccountProvider.WORKFLOW_SHARE, shareId, tenantSchema);
        return toLink(shareId);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowShare> statusFor(UUID workflowId) {
        return repository.findByWorkflowId(workflowId);
    }

    /**
     * Revokes this workflow's share, if any - the link stops resolving immediately. Never
     * un-revoked by this method; re-sharing mints a brand new id (see {@link #createOrGet}).
     */
    @Transactional
    public void revoke(UUID workflowId) {
        repository
                .findByWorkflowId(workflowId)
                .ifPresent(
                        share -> {
                            share.revoke();
                            tenantResolutionService.unlink(
                                    ExternalAccountProvider.WORKFLOW_SHARE, share.getShareId());
                        });
    }

    /**
     * Cross-tenant: resolves a public share id to the tenant schema that owns it. Shape-checked
     * before any lookup - unknown/malformed both come back empty, indistinguishably.
     */
    public Optional<String> resolveTenantSchema(String shareId) {
        if (shareId == null || !SHARE_ID_SHAPE.matcher(shareId).matches()) {
            return Optional.empty();
        }
        return tenantResolutionService.resolveTenantSchema(
                ExternalAccountProvider.WORKFLOW_SHARE, shareId);
    }

    /**
     * Must be called AFTER {@link TenantContext} has already been set to the schema {@link
     * #resolveTenantSchema} returned.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowShare> resolveByShareId(String shareId) {
        return repository.findByShareId(shareId);
    }

    @Transactional
    public void recordViewer(WorkflowShare share) {
        // The caller's entity is detached (resolved in a read-only tx, open-in-view is off), so
        // mutating it would never reach the database. Re-load the managed row and mutate that.
        repository.findByIdForUpdate(share.getId()).ifPresent(WorkflowShare::recordViewer);
    }

    /**
     * @return true when this report pushed the share past the auto-revoke threshold.
     */
    @Transactional
    public boolean report(WorkflowShare detached, String reason) {
        // Re-load the managed row: the caller's entity is detached, see recordViewer.
        WorkflowShare share = repository.findByIdForUpdate(detached.getId()).orElse(null);
        if (share == null) {
            return false;
        }
        boolean revoked = share.report(Math.max(1, reportRevokeThreshold));
        if (revoked) {
            tenantResolutionService.unlink(
                    ExternalAccountProvider.WORKFLOW_SHARE, share.getShareId());
            log.info(
                    "Workflow share {} auto-revoked after reaching the report threshold",
                    share.getShareId());
        }
        if (reason != null && !reason.isBlank()) {
            String trimmed = reason.strip();
            log.info(
                    "Workflow share {} reported: {}",
                    share.getShareId(),
                    trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed);
        }
        return revoked;
    }

    /**
     * Per-IP fixed-window rate limit on the public run endpoint - abuse control, independent of and
     * in addition to whatever the owning tenant's own plan/budget already enforces on the run
     * itself. Same Redis INCR + conditional EXPIRE, fail-open shape as {@code
     * AgentShareService#tryAcquireChat}.
     */
    public boolean tryAcquireRun(String clientIp) {
        return tryAcquire(RUN_RATE_LIMIT_KEY_PREFIX, clientIp, maxRunsPerMinutePerIp);
    }

    /**
     * Per-visitor limiter for push-to-talk voice turns (WorkflowShareVoiceController#turn) - shares
     * the run rate limit's reasoning but its own, tighter budget.
     */
    public boolean tryAcquireVoiceTurn(String key) {
        return tryAcquire(VOICE_TURN_RATE_LIMIT_KEY_PREFIX, key, maxVoiceTurnsPerMinute);
    }

    /**
     * Shared fixed-window INCR+EXPIRE implementation - see {@link #tryAcquireRun}'s javadoc for the
     * fail-open reasoning.
     */
    private boolean tryAcquire(String keyPrefix, String key, long limitPerMinute) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String redisKey = keyPrefix + key;
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count == null) {
                return true;
            }
            if (count == 1L) {
                redisTemplate.expire(redisKey, Duration.ofMinutes(1));
            }
            return count <= limitPerMinute;
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf(
                    "Workflow share rate limiter ({}) could not reach Redis - failing open",
                    keyPrefix,
                    e);
            return true;
        }
    }

    /**
     * Resolves + validates a share id and, on success, sets {@link TenantContext} to its owning
     * schema - the cross-tenant sequence every public workflow-share endpoint needs (same shape as
     * {@code AgentShareService#resolveActiveShare}).
     *
     * <p>Returns empty for ANY invalid/revoked/gone case (unknown share id, bad shape, revoked, or
     * the workflow itself since-deleted) - deliberately collapsed to one outcome. {@link
     * TenantContext} is left CLEARED on an empty return; the caller owns clearing it on a present
     * return.
     */
    public Optional<ResolvedShare> resolveActiveShare(String shareId) {
        Optional<String> tenantSchema = resolveTenantSchema(shareId);
        if (tenantSchema.isEmpty()) {
            return Optional.empty();
        }
        TenantContext.set(
                TenantContext.of(tenantSchema.get(), "system:workflow-share:" + shareId, Set.of()));
        Optional<WorkflowShare> share = resolveByShareId(shareId);
        if (share.isEmpty() || !isLive(share.get())) {
            TenantContext.clear();
            return Optional.empty();
        }
        Optional<WorkflowDefinition> workflow = loadSharedWorkflow(share.get().getWorkflowId());
        if (workflow.isEmpty()) {
            TenantContext.clear();
            return Optional.empty();
        }
        return Optional.of(new ResolvedShare(share.get(), workflow.get()));
    }

    /**
     * Short stable per-IP key for a viewer's run/session id - never logged, never returned. Same
     * idiom {@code AgentShareService.ipHash} uses.
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
                                        "Workflow sharing requires an authenticated tenant"
                                                + " context"));
    }

    private ShareLink toLink(String shareId) {
        return new ShareLink(shareId, appPublicUrl + "/workflow/" + shareId);
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
    public String endedReason(WorkflowShare share) {
        return endedReason(share, reportRevokeThreshold);
    }

    /** Pure form of {@link #endedReason(WorkflowShare)}, for tests. */
    public static String endedReason(WorkflowShare share, int reportRevokeThreshold) {
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
     * Not revoked and not past {@code expires_at}. NULL {@code expires_at} = legacy link minted
     * before tenant/V365.5: non-expiring, logged by workflow id (never by share id - that is the
     * public credential). Same rule as AgentShareService#isLive.
     */
    public boolean isLive(WorkflowShare share) {
        if (share == null || share.isRevoked()) {
            return false;
        }
        if (share.getExpiresAt() == null) {
            log.info(
                    "Legacy non-expiring workflow share link used for workflow {} (no expires_at) -"
                            + " revoke and re-share it to apply app.sharing.default-ttl",
                    share.getWorkflowId());
            return true;
        }
        return !share.isExpired(Instant.now());
    }
}
