package com.agenticai.core.platform.moderation;

import com.agenticai.core.guardrails.moderation.ModerationPackState;
import com.agenticai.core.guardrails.moderation.ModerationPolicyPack;
import com.agenticai.core.guardrails.moderation.ModerationPolicyPackNotAvailableException;
import com.agenticai.core.guardrails.moderation.ModerationPolicyScope;
import com.agenticai.core.guardrails.moderation.ModerationPolicyService;
import com.agenticai.core.guardrails.moderation.ModerationReviewAlreadyDecidedException;
import com.agenticai.core.guardrails.moderation.ModerationReviewItem;
import com.agenticai.core.guardrails.moderation.ModerationReviewNotFoundException;
import com.agenticai.core.guardrails.moderation.ModerationReviewStatus;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Safety and Moderation admin surface - {@code /api/platform/moderation/**}
 * (roadmap P6, core-3). Backs the four panels on web-admin's {@code /safety}
 * page, which shipped as labelled "coming soon" against exactly these paths.
 *
 * <p><b>2026-08 unification:</b> same four URL groups, new backing. This
 * controller previously fronted a private copy of the moderation domain
 * (its own pack enum, its own tables) that the LIVE engine in
 * core/guardrails - called from {@code AgentChatService} on every inbound
 * turn - never read. Policy packs and thresholds now read/write the engine's
 * own {@code platform_setting} keys through {@link ModerationPolicyService}
 * (the single owner of the key scheme), and the review queue reads the REAL
 * per-tenant-schema queue via {@link PlatformModerationQueueService}'s
 * fan-out. Contract changes for the (zero existing) callers: pack ids are the
 * engine's configKeys ({@code foul_language}/{@code restricted_content}/
 * {@code extremism}, with the old {@code profanity}/{@code restricted}/
 * {@code terrorism} accepted on the way in as read-side aliases), scope
 * gains GLOBAL, thresholds are per-pack 0-100 ints (previously four global
 * 0.0-1.0 scalars nothing read), and the review decision requires a
 * {@code tenantId} - a review-item UUID alone is not resolvable without
 * knowing which tenant schema holds it.
 *
 * <p>Authorization is the blanket {@code SecurityConfig} rule
 * {@code .requestMatchers("/api/platform/**").hasAuthority("ROLE_platform-admin")},
 * same as every other controller under this prefix - no separate check here.
 * (The tenant-side reviewer surface at {@code /api/moderation/review} keeps
 * its own in-controller ROLE_tenant-admin check; different audience, same
 * underlying audited service.)
 *
 * <p>Kept as its own controller rather than methods on {@code PlatformController}
 * for the reason that class documents about itself: it is long-lived and under
 * concurrent edits, and a self-contained feature gains nothing from living in it
 * except a merge-conflict surface. {@code PlanLimitsController} is the same
 * pattern.
 *
 * <p><b>One deliberate refusal.</b> {@code PUT /policy-packs} will not enable
 * the CSAM pack, returning 409 with an explanation. web-admin's own safety page
 * states the constraint - the flow "will not ship until it is reviewed" with
 * counsel, because detection carries mandatory reporting obligations and needs a
 * restricted evidence trail. Shipping detection without those is not a partial
 * feature; it is a compliance hazard. See {@link ModerationPolicyPack#CSAM}.
 */
@RestController
@RequestMapping("/api/platform/moderation")
public class ModerationController {

    private final ModerationPolicyService policy;
    private final ModerationThresholdService thresholds;
    private final PlatformModerationQueueService reviewQueue;
    private final ModerationBlocklistService blocklists;

    public ModerationController(ModerationPolicyService policy,
                                ModerationThresholdService thresholds,
                                PlatformModerationQueueService reviewQueue,
                                ModerationBlocklistService blocklists) {
        this.policy = policy;
        this.thresholds = thresholds;
        this.reviewQueue = reviewQueue;
        this.blocklists = blocklists;
    }

    // ---------------------------------------------------------------- packs

    /**
     * @param scope GLOBAL, PLAN (default) or TENANT
     * @param key   the plan name or tenant id (ignored for GLOBAL); defaults
     *              to FREE so a bare GET returns something meaningful rather
     *              than an error
     */
    @GetMapping("/policy-packs")
    public PolicyPacksResponse getPolicyPacks(
        @RequestParam(name = "scope", defaultValue = "PLAN") String scope,
        @RequestParam(name = "key", defaultValue = "FREE") String key) {
        ModerationPolicyScope parsed = parseScope(scope);
        String scopeKey = parsed == ModerationPolicyScope.GLOBAL ? null : key;
        return new PolicyPacksResponse(parsed.name(), scopeKey, policy.catalogue(parsed, scopeKey));
    }

    @PutMapping("/policy-packs")
    public PolicyPacksResponse updatePolicyPacks(@RequestBody PolicyPacksUpdateRequest body, Principal principal) {
        ModerationPolicyScope scope = parseScope(body.scope());
        String key = scope == ModerationPolicyScope.GLOBAL
            ? null
            : (body.key() == null || body.key().isBlank() ? "FREE" : body.key());

        if (body.packs() != null && !body.packs().isEmpty()) {
            // Validate everything BEFORE writing anything - a partial apply on
            // a safety surface would leave the operator believing something
            // took effect that did not. (setPackEnabled re-checks the legal
            // gate per pack as its own backstop.)
            for (Map.Entry<String, Boolean> e : body.packs().entrySet()) {
                ModerationPolicyPack pack = ModerationPolicyPack.fromConfigKey(e.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown policy pack: " + e.getKey()));
                if (Boolean.TRUE.equals(e.getValue()) && pack.requiresLegalReview()) {
                    throw new ModerationPolicyPackNotAvailableException(pack.configKey());
                }
            }
            for (Map.Entry<String, Boolean> e : body.packs().entrySet()) {
                ModerationPolicyPack pack = ModerationPolicyPack.fromConfigKey(e.getKey()).orElseThrow();
                policy.setPackEnabled(pack, scope, key, Boolean.TRUE.equals(e.getValue()), actor(principal));
            }
        }
        return new PolicyPacksResponse(scope.name(), key, policy.catalogue(scope, key));
    }

    // ----------------------------------------------------------- thresholds

    @GetMapping("/thresholds")
    public ModerationThresholdService.Thresholds getThresholds() {
        return thresholds.current();
    }

    @PutMapping("/thresholds")
    public ModerationThresholdService.Thresholds updateThresholds(
        @RequestBody ThresholdsUpdateRequest body, Principal principal) {
        return thresholds.update(
            body.moderationEnabled(), body.externalClassifierEnabled(), body.packs(), actor(principal));
    }

    // --------------------------------------------------------- review queue

    @GetMapping("/review-queue")
    public ReviewQueueResponse getReviewQueue(
        @RequestParam(name = "status", required = false) String status) {
        ModerationReviewStatus parsed = parseStatus(status);
        List<ReviewItemResponse> items = reviewQueue.list(parsed).stream()
            .map(t -> ReviewItemResponse.from(t.tenantSchema(), t.item()))
            .toList();
        return new ReviewQueueResponse(items, reviewQueue.pendingCount());
    }

    @PostMapping("/review-queue/{id}/decision")
    public ReviewItemResponse decide(@PathVariable("id") UUID id,
                                     @RequestBody ReviewDecisionRequest body,
                                     Principal principal) {
        if (body.tenantId() == null || body.tenantId().isBlank()) {
            throw new IllegalArgumentException(
                "tenantId is required: the review queue is per-tenant-schema, so an item id alone "
                    + "is not resolvable (use the tenantId returned by GET /review-queue)");
        }
        String tenantId = body.tenantId().trim();
        String actor = actor(principal);
        // Deliberately a string switch, NOT an enum: core/guardrails' public
        // ModerationDecision is the ENGINE verdict (ALLOW/BLOCK/QUEUE_FOR_REVIEW),
        // not a reviewer action - an auto-import of it here would compile with
        // only BLOCK in common and break APPROVE/ESCALATE. The reviewer
        // vocabulary maps 1:1 onto the three audited transitions instead.
        ModerationReviewItem decided = switch (normalizeDecision(body.decision())) {
            case "APPROVE" -> reviewQueue.approve(tenantId, id, actor, body.note());
            case "BLOCK" -> reviewQueue.block(tenantId, id, actor, body.note());
            case "ESCALATE" -> reviewQueue.escalate(tenantId, id, actor, body.note());
            default -> throw new IllegalArgumentException(
                "decision must be one of APPROVE, BLOCK, ESCALATE (got: " + body.decision() + ")");
        };
        return ReviewItemResponse.from(tenantId, decided);
    }

    // ----------------------------------------------------------- blocklists

    @GetMapping("/blocklists")
    public BlocklistsResponse getBlocklists(@RequestParam(name = "type", required = false) String type) {
        ModerationBlocklistType parsed = type == null || type.isBlank()
            ? null
            : parseBlocklistType(type);
        List<BlocklistEntryResponse> entries = blocklists.list(parsed).stream()
            .map(BlocklistEntryResponse::from)
            .toList();
        return new BlocklistsResponse(entries);
    }

    @PutMapping("/blocklists")
    public BlocklistEntryResponse upsertBlocklistEntry(@RequestBody BlocklistUpsertRequest body,
                                                       Principal principal) {
        ModerationBlocklistType type = parseBlocklistType(body.type());
        return BlocklistEntryResponse.from(blocklists.upsert(
            type, body.value(), body.active() == null || body.active(), body.reason(), actor(principal)));
    }

    // -------------------------------------------------------------- helpers

    private static String actor(Principal principal) {
        return principal != null ? principal.getName() : "platform-admin";
    }

    private static ModerationPolicyScope parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return ModerationPolicyScope.PLAN;
        }
        try {
            return ModerationPolicyScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("scope must be GLOBAL, PLAN or TENANT (got: " + raw + ")");
        }
    }

    private static ModerationReviewStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return ModerationReviewStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "status must be one of PENDING, APPROVED, BLOCKED, ESCALATED, ALL (got: " + raw + ")");
        }
    }

    private static String normalizeDecision(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static ModerationBlocklistType parseBlocklistType(String raw) {
        try {
            return ModerationBlocklistType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                "type must be one of USER, PHONE, DOMAIN, REGION (got: " + raw + ")");
        }
    }

    // ----------------------------------------------------------- exceptions
    // Handled locally rather than in GlobalExceptionHandler: these types are
    // specific to this controller, and that class is heavily contended.

    @ExceptionHandler(ModerationPolicyPackNotAvailableException.class)
    ResponseEntity<Map<String, Object>> onPackUnavailable(ModerationPolicyPackNotAvailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "policy_pack_unavailable", "pack", e.getPackId(), "message", e.getMessage()));
    }

    @ExceptionHandler(ModerationReviewNotFoundException.class)
    ResponseEntity<Map<String, Object>> onNotFound(ModerationReviewNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "not_found", "message", e.getMessage()));
    }

    // The guardrails review service throws its typed already-decided
    // exception (not IllegalStateException, which the pre-unification handler
    // here caught) - without this handler the app-wide advice's catch-all
    // would degrade one reviewer racing another from a 409 into a 500.
    @ExceptionHandler(ModerationReviewAlreadyDecidedException.class)
    ResponseEntity<Map<String, Object>> onAlreadyDecided(ModerationReviewAlreadyDecidedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "already_decided", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> onBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "invalid_request", "message", e.getMessage()));
    }

    // --------------------------------------------------------------- shapes

    /** {@code key} is null for GLOBAL scope. */
    public record PolicyPacksResponse(String scope, String key, List<ModerationPackState> packs) {
    }

    /** {@code packs} maps pack id to desired enabled state; omitted packs are left alone. */
    public record PolicyPacksUpdateRequest(String scope, String key, Map<String, Boolean> packs) {
    }

    /** Every field optional - null (or an omitted pack) means "leave unchanged". */
    public record ThresholdsUpdateRequest(Boolean moderationEnabled, Boolean externalClassifierEnabled,
                                          Map<String, ModerationThresholdService.PackThresholdsUpdate> packs) {
    }

    public record ReviewQueueResponse(List<ReviewItemResponse> items, long pendingCount) {
    }

    /**
     * One review-queue row, shaped from the engine's tenant-schema entity.
     * Differences from the pre-unification response, deliberate and safe
     * because that contract had zero callers: {@code content} is the FULL
     * flagged content (the engine's queue stores it complete, by design, so
     * the reviewer can actually judge it - not a 280-char excerpt of a copy),
     * {@code confidence} is the engine's 0-100 int (not a 0.0-1.0 double),
     * and {@code tenantId} is the fan-out routing token the decision endpoint
     * requires back.
     */
    public record ReviewItemResponse(UUID id, String tenantId, String packId, String decidingLayer,
                                     int confidence, String reason, String content, String direction,
                                     String status, String createdAt, String reviewedBy, String reviewedAt,
                                     String reviewNotes) {
        static ReviewItemResponse from(String tenantSchema, ModerationReviewItem i) {
            return new ReviewItemResponse(
                i.getId(), tenantSchema, i.getPolicyPack(), i.getDecidingLayer(),
                i.getConfidence(), i.getReason(), i.getContent(), i.getDirection(),
                i.getStatus().name(),
                i.getCreatedAt() != null ? i.getCreatedAt().toString() : null,
                i.getReviewedBy(),
                i.getReviewedAt() != null ? i.getReviewedAt().toString() : null,
                i.getReviewNotes());
        }
    }

    /** {@code tenantId} is required - see {@link #decide}. */
    public record ReviewDecisionRequest(String tenantId, String decision, String note) {
    }

    public record BlocklistsResponse(List<BlocklistEntryResponse> entries) {
    }

    public record BlocklistEntryResponse(UUID id, String type, String value, String reason,
                                         boolean active, String createdBy, String createdAt) {
        static BlocklistEntryResponse from(ModerationBlocklistEntry e) {
            return new BlocklistEntryResponse(
                e.getId(), e.getEntryType().name(), e.getValue(), e.getReason(), e.isActive(),
                e.getCreatedBy(), e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        }
    }

    /** {@code active} defaults to true when omitted; send false to unblock (the row is kept for audit). */
    public record BlocklistUpsertRequest(String type, String value, Boolean active, String reason) {
    }
}
