package com.agenticai.core.templates;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.templates.dto.ModerationRequest;
import com.agenticai.core.templates.dto.TemplateResponse;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PLATFORM moderation surface for the cross-tenant community gallery. SEC-010: every endpoint here
 * is {@code ROLE_platform-admin} only, enforced three times so that no single mistake reopens it:
 * {@code SecurityConfig}'s {@code /api/admin/templates/**} matcher, the class-level
 * {@code @PreAuthorize} below, and {@link CommunityTemplateService#requireAdmin} (404 for anyone
 * else). A tenant admin - who holds every tenant permission including {@code PERM_MANAGE_TEMPLATES}
 * - must never approve, verify, or list other tenants' submissions.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@PreAuthorize("hasAuthority('ROLE_platform-admin')")
@RequestMapping("/api/admin/templates")
public class AdminTemplateController {

    private final CommunityTemplateService service;

    public AdminTemplateController(CommunityTemplateService service) {
        this.service = service;
    }

    /** The moderation queue - pending submissions, oldest first. */
    @GetMapping("/pending")
    public ResponseEntity<List<TemplateResponse>> pending(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.pendingQueue(), limit, offset, 50, 500);
    }

    /**
     * Live (APPROVED) templates, so an admin can enumerate withdraw targets without hitting the
     * public gallery. Full owner/admin projection (includes submitter + source tenant), unlike the
     * public gallery summary.
     */
    @GetMapping("/approved")
    public ResponseEntity<List<TemplateResponse>> approved(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.approvedForModeration(), limit, offset, 50, 500);
    }

    @PostMapping("/{id}/approve")
    public TemplateResponse approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    /** Reject with a reason the submitter can see - reason is required. */
    @PostMapping("/{id}/reject")
    public TemplateResponse reject(
            @PathVariable UUID id, @Valid @RequestBody ModerationRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("A rejection reason is required.");
        }
        return service.reject(id, request.reason());
    }

    /** Pull an approved template from the gallery (e.g. after a report). Reason optional. */
    @PostMapping("/{id}/withdraw")
    public TemplateResponse withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ModerationRequest request) {
        return service.adminWithdraw(id, request == null ? null : request.reason());
    }

    /**
     * Award the marketplace "verified" badge to an APPROVED template - the staff endorsement
     * layered on top of approval. Idempotent.
     */
    @PostMapping("/{id}/verify")
    public TemplateResponse verify(@PathVariable UUID id) {
        return service.verify(id);
    }

    /** Remove the verified badge (design drifted, or awarded in error). */
    @PostMapping("/{id}/unverify")
    public TemplateResponse unverify(@PathVariable UUID id) {
        return service.unverify(id);
    }
}
