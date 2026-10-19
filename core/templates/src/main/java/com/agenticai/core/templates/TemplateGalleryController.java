package com.agenticai.core.templates;

import com.agenticai.core.templates.dto.TemplateDetail;
import com.agenticai.core.templates.dto.TemplateRatingView;
import com.agenticai.core.templates.dto.TemplateSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC (no-auth) gallery. Mounted at {@code /api/templates/gallery}, which {@code SecurityConfig}
 * exposes via a {@code GET ... permitAll()} matcher. Both endpoints return APPROVED templates ONLY
 * - that guarantee lives in {@link CommunityTemplateService} (this controller never chooses a
 * status), so an anonymous caller can never reach a draft, a pending submission, or a rejected/
 * withdrawn one. The list endpoint never ships {@code config_json}.
 */
@RestController
@RequestMapping("/api/templates/gallery")
public class TemplateGalleryController {

    private final CommunityTemplateService service;

    public TemplateGalleryController(CommunityTemplateService service) {
        this.service = service;
    }

    /**
     * Approved templates, filterable by kind/category/tag, sortable by {@code sort} ({@code recent}
     * - default, {@code imports}, {@code rating}), paginated. Summary cards only (no config).
     */
    @GetMapping
    public List<TemplateSummary> gallery(
            @RequestParam(required = false) TemplateKind kind,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        return service.gallery(kind, category, tag, page, size, sort);
    }

    /**
     * The published template standard: principles, every rule with how to fix it, the submission
     * steps and a worked example. Public so authors can read it before building; the submit screen
     * renders it too.
     */
    @GetMapping("/standard")
    public TemplateStandard.Guide standard() {
        return TemplateStandard.guide();
    }

    /**
     * One approved template's public detail (config shown read-only for inspection before import).
     */
    @GetMapping("/{id}")
    public TemplateDetail detail(@PathVariable UUID id) {
        return service.galleryDetail(id);
    }

    /**
     * Public reviews for one approved template, newest first. Anonymous by design - {@link
     * TemplateRatingView} carries no rater identity.
     */
    @GetMapping("/{id}/ratings")
    public List<TemplateRatingView> ratings(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.galleryRatings(id, page, size);
    }
}
