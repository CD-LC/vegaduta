package com.agenticai.core.templates;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.templates.dto.TemplateCreateRequest;
import com.agenticai.core.templates.dto.TemplateDetail;
import com.agenticai.core.templates.dto.TemplateImportResponse;
import com.agenticai.core.templates.dto.TemplateRatingRequest;
import com.agenticai.core.templates.dto.TemplateRatingView;
import com.agenticai.core.templates.dto.TemplateResponse;
import com.agenticai.core.templates.dto.TemplateReview;
import com.agenticai.core.templates.dto.TemplateSummary;
import com.agenticai.core.templates.dto.TemplateUpdateRequest;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle + moderation + snapshot orchestration + import for community templates. Every state
 * change goes through {@link #transition}, which is the single enforcement point for {@link
 * TemplateStatus}'s legal-transition graph, and every state change (submit/approve/reject/withdraw)
 * plus import is audited through {@link AuditLogService}, exactly like {@code core/sharing} audits
 * grant/revoke.
 *
 * <p><b>Public visibility rule (non-negotiable):</b> the two public reads ({@link #gallery}, {@link
 * #galleryDetail}) return APPROVED rows only, and the list never carries {@code config_json}. Every
 * non-approved template is invisible to anyone but its owner/an admin, and a request for one by an
 * outsider is a 404 (never 403) - see {@link TemplateNotFoundException}.
 */
@Service
public class CommunityTemplateService {

    private static final Logger log = LoggerFactory.getLogger(CommunityTemplateService.class);

    private final CommunityTemplateRepository repository;
    private final CommunityTemplateRatingRepository ratingRepository;
    private final TemplateSnapshotService snapshotService;
    private final AuditLogService auditLogService;
    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final WorkflowService workflowService;
    private final TemplateImportRateLimiter importRateLimiter;
    private final CommunityTemplateMapper mapper;
    private final TemplateReviewRunner reviewRunner;
    private final TemplateInstantiator instantiator;

    /**
     * Max DRAFT templates a single tenant may create per 24h window (abuse cap - see the contract).
     */
    private final int maxSubmissionsPerTenantPerDay;

    public CommunityTemplateService(
            CommunityTemplateRepository repository,
            CommunityTemplateRatingRepository ratingRepository,
            TemplateSnapshotService snapshotService,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            AgentService agentService,
            AgentGuardrailService agentGuardrailService,
            AgentRepository agentRepository,
            WorkflowService workflowService,
            TemplateImportRateLimiter importRateLimiter,
            TemplateReadinessService readinessService,
            TemplateAiReviewService aiReviewService,
            @Value("${app.templates.max-submissions-per-tenant-per-day:25}")
                    int maxSubmissionsPerTenantPerDay) {
        this.repository = repository;
        this.ratingRepository = ratingRepository;
        this.snapshotService = snapshotService;
        this.auditLogService = auditLogService;
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.workflowService = workflowService;
        this.importRateLimiter = importRateLimiter;
        this.mapper = new CommunityTemplateMapper(snapshotService, readinessService, objectMapper);
        this.reviewRunner =
                new TemplateReviewRunner(snapshotService, readinessService, aiReviewService, mapper);
        this.instantiator =
                new TemplateInstantiator(
                        snapshotService, agentService, agentGuardrailService, workflowService);
        this.maxSubmissionsPerTenantPerDay = maxSubmissionsPerTenantPerDay;
    }

    // ================================ member ================================

    /**
     * Create a DRAFT template by taking an allow-list snapshot of one of the caller's own
     * agents/workflows. The client never supplies {@code config_json} - {@link
     * TemplateSnapshotService} builds it server-side from the source entity.
     */
    @Transactional
    public TemplateResponse createDraft(TemplateCreateRequest request) {
        String subject = TemplateAccess.requireSubject();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);

        enforceSubmissionCap(tenantId);

        String configJson =
                switch (request.kind()) {
                    case AGENT ->
                            snapshotService.snapshotAgent(loadOwnedAgentEntity(request.sourceId()));
                    case WORKFLOW ->
                            snapshotService.snapshotWorkflow(
                                    loadOwnedWorkflowEntity(request.sourceId(), subject));
                };

        CommunityTemplate template =
                new CommunityTemplate(
                        request.kind(),
                        request.title(),
                        request.summary(),
                        request.category(),
                        snapshotService.writeTags(request.tags()),
                        configJson,
                        tenantId,
                        subject);
        CommunityTemplate saved = repository.save(template);
        audit(subject, "template.create", saved, "Created DRAFT " + request.kind() + " template");
        return mapper.toResponse(saved);
    }

    /** Edit the presentation of the caller's own DRAFT/REJECTED template (config unchanged). */
    @Transactional
    public TemplateResponse edit(UUID id, TemplateUpdateRequest request) {
        String subject = TemplateAccess.requireSubject();
        CommunityTemplate template = requireOwned(id, subject);
        if (template.getStatus() != TemplateStatus.DRAFT
                && template.getStatus() != TemplateStatus.REJECTED) {
            throw new IllegalTemplateTransitionException(
                    template.getStatus(), template.getStatus());
        }
        template.applyEdit(
                request.title(),
                request.summary(),
                request.category(),
                snapshotService.writeTags(request.tags()),
                null);
        return mapper.toResponse(repository.save(template));
    }

    /**
     * DRAFT/REJECTED/WITHDRAWN -> PENDING. Owner only.
     *
     * <p>The gate, in order: (1) the deterministic rules - blockers stop the submission before any
     * model is called; (2) the AI reviewer - a high-confidence blocker stops it, anything else is
     * recorded for the admin; (3) the combined review is stored on the template and
     * platform-derived tags are merged in. Only then does the template enter the moderation queue.
     */
    @Transactional
    public TemplateResponse submit(UUID id) {
        String subject = TemplateAccess.requireSubject();
        CommunityTemplate template = requireOwned(id, subject);
        TemplateReview review = reviewRunner.run(template, true);
        if (!review.rules().blockers().isEmpty()) {
            throw new TemplateNotReadyException(review.rules());
        }
        if (!review.ai().blockers().isEmpty()) {
            throw new TemplateNotReadyException(TemplateReviewRunner.mergeAi(review.rules(), review.ai()));
        }
        transition(template, TemplateStatus.PENDING, subject, null);
        template.recordReview(
                mapper.writeJson(review), reviewRunner.mergedTags(template, review.rules().derivedTags()));
        audit(
                subject,
                "template.submit",
                template,
                "Submitted for review (AI review: " + review.ai().status() + ")");
        return mapper.toResponse(template);
    }

    /**
     * Check a template against the standard WITHOUT submitting - same rules, same AI reviewer.
     * Owner only; nothing is stored or changed. The UI calls this so authors see every problem up
     * front.
     */
    @Transactional(readOnly = true)
    public TemplateReview validate(UUID id, boolean withAi) {
        String subject = TemplateAccess.requireSubject();
        CommunityTemplate template = requireOwned(id, subject);
        return reviewRunner.run(template, withAi);
    }

    /** Owner pulls their own template (PENDING or APPROVED) back to WITHDRAWN. */
    @Transactional
    public TemplateResponse withdraw(UUID id) {
        String subject = TemplateAccess.requireSubject();
        CommunityTemplate template = requireOwned(id, subject);
        transition(template, TemplateStatus.WITHDRAWN, subject, null);
        audit(subject, "template.withdraw", template, "Withdrawn by owner");
        return mapper.toResponse(template);
    }

    /** The caller's own submissions across every status. */
    @Transactional(readOnly = true)
    public List<TemplateResponse> mine() {
        String subject = TemplateAccess.requireSubject();
        return repository.findBySubmittedByOrderByUpdatedAtDesc(subject).stream()
                .map(mapper::toResponse)
                .toList();
    }

    /**
     * Instantiate a NEW agent/workflow in the caller's tenant from an APPROVED template (or one the
     * caller owns, any status), going through {@code AgentService.create}/{@code
     * WorkflowService.create} - never a raw insert, so tenant caps, FREE-tier coercion, ownership
     * stamping and validation all apply. Capped per-user-per-day by {@link
     * TemplateImportRateLimiter} (429 over the cap).
     */
    @Transactional
    public TemplateImportResponse importTemplate(UUID id) {
        String subject = TemplateAccess.requireSubject();
        importRateLimiter.assertWithinDailyCap(subject);
        CommunityTemplate template = requireImportable(id, subject);

        UUID newId = instantiator.instantiate(template);

        template.recordImport();
        repository.save(template);
        audit(
                subject,
                "template.import",
                template,
                "Imported into a new " + template.getKind() + " (" + newId + ")");
        return new TemplateImportResponse(template.getKind(), newId);
    }

    // ================================ public ================================

    /**
     * APPROVED-only gallery, filterable by kind/category/tag, sortable, paginated. Never carries
     * config_json. {@code sort} values: {@code recent} (default - most recently approved first),
     * {@code imports} (most imported first), {@code rating} (highest average first, most ratings
     * breaking ties). Anything else falls back to {@code recent} - a permissive sort parameter is a
     * browse affordance, not a validation contract.
     */
    @Transactional(readOnly = true)
    public List<TemplateSummary> gallery(
            TemplateKind kind, String category, String tag, int page, int size, String sort) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Sort ordering =
                switch (blankToNull(sort) == null ? "recent" : sort) {
                    case "imports" -> Sort.by(Direction.DESC, "importCount");
                    case "rating" ->
                            Sort.by(Direction.DESC, "ratingAvg")
                                    .and(Sort.by(Direction.DESC, "ratingCount"));
                    default -> Sort.by(Direction.DESC, "reviewedAt");
                };
        PageRequest pageable = PageRequest.of(safePage, safeSize, ordering);
        String normalizedCategory = blankToNull(category);
        List<CommunityTemplate> approved;
        if (kind != null && normalizedCategory != null) {
            approved =
                    repository.findByStatusAndKindAndCategory(
                            TemplateStatus.APPROVED, kind, normalizedCategory, pageable);
        } else if (kind != null) {
            approved = repository.findByStatusAndKind(TemplateStatus.APPROVED, kind, pageable);
        } else if (normalizedCategory != null) {
            approved =
                    repository.findByStatusAndCategory(
                            TemplateStatus.APPROVED, normalizedCategory, pageable);
        } else {
            approved = repository.findByStatus(TemplateStatus.APPROVED, pageable);
        }
        String normalizedTag = blankToNull(tag);
        return approved.stream()
                .filter(
                        t ->
                                normalizedTag == null
                                        || snapshotService
                                                .parseTags(t.getTagsJson())
                                                .contains(normalizedTag))
                .map(mapper::toSummary)
                .toList();
    }

    /**
     * One APPROVED template's public detail (config shown read-only). 404 for anything not
     * approved.
     */
    @Transactional(readOnly = true)
    public TemplateDetail galleryDetail(UUID id) {
        CommunityTemplate template =
                repository
                        .findByIdAndStatus(id, TemplateStatus.APPROVED)
                        .orElseThrow(TemplateNotFoundException::new);
        return mapper.toDetail(template);
    }

    /**
     * Public "recent reviews" listing for one APPROVED template. Anonymous by design: {@link
     * TemplateRatingView} carries no rater identity. 404 for a non-approved template, same
     * anti-enumeration posture as {@link #galleryDetail}.
     */
    @Transactional(readOnly = true)
    public List<TemplateRatingView> galleryRatings(UUID id, int page, int size) {
        CommunityTemplate template =
                repository
                        .findByIdAndStatus(id, TemplateStatus.APPROVED)
                        .orElseThrow(TemplateNotFoundException::new);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return ratingRepository
                .findByTemplateIdOrderByCreatedAtDesc(
                        template.getId(), PageRequest.of(Math.max(page, 0), safeSize))
                .stream()
                .map(
                        r ->
                                new TemplateRatingView(
                                        r.getStars(),
                                        r.getReview(),
                                        r.getCreatedAt(),
                                        r.getUpdatedAt()))
                .toList();
    }

    // ============================== ratings ==============================

    /**
     * Upsert the caller's rating of an APPROVED template - one row per (template, rater) enforced
     * by {@code uq_community_template_rating_rater}, so a second submission REPLACES the previous
     * vote rather than stacking. Self-rating is refused ({@link TemplateRatingForbiddenException},
     * 409): the template is public so the refusal can say why. A non-approved or non-owned template
     * is a 404, same as any other invisible-id request.
     *
     * <p>The denormalised {@code rating_count}/{@code rating_avg} on the template are recomputed in
     * this same transaction, so the gallery never reads a torn aggregate.
     */
    @Transactional
    public TemplateSummary rate(UUID id, TemplateRatingRequest request) {
        String subject = TemplateAccess.requireSubject();
        CommunityTemplate template = requireApprovedForMarketplace(id);
        if (subject.equals(template.getSubmittedBy())) {
            throw new TemplateRatingForbiddenException();
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);

        Optional<CommunityTemplateRating> existing =
                ratingRepository.findByTemplateIdAndRaterSubject(id, subject);
        if (existing.isPresent()) {
            existing.get().applyUpdate(request.stars(), request.review());
            ratingRepository.save(existing.get());
        } else {
            ratingRepository.save(
                    new CommunityTemplateRating(
                            id, subject, tenantId, request.stars(), request.review()));
        }
        recomputeRatingAggregate(template);
        audit(
                subject,
                existing.isPresent() ? "template.rerate" : "template.rate",
                template,
                "Rated " + request.stars() + "/5");
        return mapper.toSummary(template);
    }

    /** Remove the caller's own rating, if any. Idempotent - no rating is a silent no-op. */
    @Transactional
    public TemplateSummary unrate(UUID id) {
        String subject = TemplateAccess.requireSubject();
        CommunityTemplate template = requireApprovedForMarketplace(id);
        ratingRepository.deleteByTemplateIdAndRaterSubject(id, subject);
        recomputeRatingAggregate(template);
        return mapper.toSummary(template);
    }

    private CommunityTemplate requireApprovedForMarketplace(UUID id) {
        return repository
                .findByIdAndStatus(id, TemplateStatus.APPROVED)
                .orElseThrow(TemplateNotFoundException::new);
    }

    private void recomputeRatingAggregate(CommunityTemplate template) {
        long count = ratingRepository.countByTemplateId(template.getId());
        Double avg = ratingRepository.averageStars(template.getId());
        template.applyRatingAggregate(count, avg == null ? 0 : avg);
        repository.save(template);
    }

    // ================================ admin ================================

    /** The moderation queue - PENDING templates, oldest first. Admin only. */
    @Transactional(readOnly = true)
    public List<TemplateResponse> pendingQueue() {
        TemplateAccess.requireAdmin();
        return repository.findByStatusOrderBySubmittedAtAsc(TemplateStatus.PENDING).stream()
                .map(mapper::toResponse)
                .toList();
    }

    /**
     * Admin list of live (APPROVED) templates, so an admin can pick one to withdraw (e.g. after a
     * report) without reusing the public gallery endpoint. Returns the full owner/admin projection
     * - including submitter + source tenant - which the public gallery deliberately never exposes.
     * Admin only.
     */
    @Transactional(readOnly = true)
    public List<TemplateResponse> approvedForModeration() {
        TemplateAccess.requireAdmin();
        return repository.findByStatusOrderByReviewedAtDesc(TemplateStatus.APPROVED).stream()
                .map(mapper::toResponse)
                .toList();
    }

    /** PENDING -> APPROVED. Admin only. Records the approving admin + timestamp. */
    @Transactional
    public TemplateResponse approve(UUID id) {
        String admin = TemplateAccess.requireAdmin();
        CommunityTemplate template = requireExisting(id);
        transition(template, TemplateStatus.APPROVED, admin, null);
        audit(admin, "template.approve", template, "Approved for the public gallery");
        return mapper.toResponse(template);
    }

    /** PENDING -> REJECTED with a reason the submitter can see. Admin only. */
    @Transactional
    public TemplateResponse reject(UUID id, String reason) {
        String admin = TemplateAccess.requireAdmin();
        CommunityTemplate template = requireExisting(id);
        transition(template, TemplateStatus.REJECTED, admin, reason);
        audit(
                admin,
                "template.reject",
                template,
                "Rejected: " + (reason == null ? "(no reason)" : reason));
        return mapper.toResponse(template);
    }

    /** APPROVED -> WITHDRAWN (e.g. after a report). Admin only. */
    @Transactional
    public TemplateResponse adminWithdraw(UUID id, String reason) {
        String admin = TemplateAccess.requireAdmin();
        CommunityTemplate template = requireExisting(id);
        transition(template, TemplateStatus.WITHDRAWN, admin, reason);
        audit(
                admin,
                "template.withdraw",
                template,
                "Withdrawn by admin: " + (reason == null ? "(no reason)" : reason));
        return mapper.toResponse(template);
    }

    /**
     * Award the marketplace "verified" badge. Admin only, APPROVED templates only - the badge
     * attests that staff exercised the template beyond the moderation pass, which is meaningless
     * for something not even published. Idempotent: re-verifying an already-verified template just
     * refreshes verified_by/at.
     */
    @Transactional
    public TemplateResponse verify(UUID id) {
        String admin = TemplateAccess.requireAdmin();
        CommunityTemplate template = requireExisting(id);
        if (template.getStatus() != TemplateStatus.APPROVED) {
            throw new IllegalArgumentException("Only APPROVED templates can be verified.");
        }
        template.markVerified(admin);
        repository.save(template);
        audit(admin, "template.verify", template, "Verified badge awarded");
        return mapper.toResponse(template);
    }

    /** Remove the verified badge (design drifted, or awarded in error). Admin only. */
    @Transactional
    public TemplateResponse unverify(UUID id) {
        String admin = TemplateAccess.requireAdmin();
        CommunityTemplate template = requireExisting(id);
        template.markUnverified();
        repository.save(template);
        audit(admin, "template.unverify", template, "Verified badge removed");
        return mapper.toResponse(template);
    }

    // ============================== internals ==============================

    /**
     * The single lifecycle enforcement point. Throws {@link IllegalTemplateTransitionException}
     * (409) if the move isn't in {@link TemplateStatus#canTransitionTo}; otherwise applies it and
     * saves.
     */
    private void transition(
            CommunityTemplate template, TemplateStatus target, String actor, String reason) {
        if (!template.getStatus().canTransitionTo(target)) {
            throw new IllegalTemplateTransitionException(template.getStatus(), target);
        }
        template.transitionTo(target, actor, reason);
        repository.save(template);
    }

    /**
     * Load the caller's own agent ENTITY - via {@code AgentService.get} for the access check, then
     * the entity for full-field snapshotting.
     */
    Agent loadOwnedAgentEntity(UUID agentId) {
        // Enforces VIEWER access / same-tenant existence (throws 404 otherwise) exactly like every
        // other agent read.
        agentService.get(agentId);
        return agentRepository.findById(agentId).orElseThrow(TemplateNotFoundException::new);
    }

    /**
     * Load the caller's own workflow ENTITY - tenant-scoped existence via {@code
     * WorkflowService.get}, then owner/admin/legacy visibility.
     */
    private WorkflowDefinition loadOwnedWorkflowEntity(UUID workflowId, String subject) {
        WorkflowDefinition definition;
        try {
            definition = workflowService.get(workflowId);
        } catch (RuntimeException e) {
            // WorkflowNotFoundException (cross-tenant / missing) -> uniform 404.
            throw new TemplateNotFoundException();
        }
        String createdBy = definition.getCreatedBy();
        boolean visible = createdBy == null || createdBy.equals(subject) || TemplateAccess.isAdmin();
        if (!visible) {
            throw new TemplateNotFoundException();
        }
        return definition;
    }

    private CommunityTemplate requireExisting(UUID id) {
        return repository.findById(id).orElseThrow(TemplateNotFoundException::new);
    }

    /**
     * Owner-scoped load: a non-owner (non-admin-context member endpoint) gets 404, never 403 -
     * anti-enumeration.
     */
    private CommunityTemplate requireOwned(UUID id, String subject) {
        CommunityTemplate template = requireExisting(id);
        if (!subject.equals(template.getSubmittedBy())) {
            throw new TemplateNotFoundException();
        }
        return template;
    }

    /** Importable = APPROVED (public) OR owned by the caller (any status). Anything else is 404. */
    private CommunityTemplate requireImportable(UUID id, String subject) {
        CommunityTemplate template = requireExisting(id);
        if (template.getStatus() == TemplateStatus.APPROVED
                || subject.equals(template.getSubmittedBy())) {
            return template;
        }
        throw new TemplateNotFoundException();
    }

    private void enforceSubmissionCap(String tenantId) {
        if (tenantId == null) {
            return;
        }
        long recent =
                repository.countBySourceTenantIdAndCreatedAtAfter(
                        tenantId, Instant.now().minus(Duration.ofDays(1)));
        if (recent >= maxSubmissionsPerTenantPerDay) {
            log.warn(
                    "Template submission cap hit for tenant {} ({} in last 24h, cap {})",
                    tenantId,
                    recent,
                    maxSubmissionsPerTenantPerDay);
            throw new TemplateSubmissionLimitException(maxSubmissionsPerTenantPerDay);
        }
    }

    private void audit(String actor, String actionType, CommunityTemplate template, String detail) {
        auditLogService.record(
                actor,
                actionType,
                "template",
                template.getId().toString(),
                AuditOutcome.SUCCESS,
                detail);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
