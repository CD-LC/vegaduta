package com.agenticai.core.templates;

import com.agenticai.core.templates.dto.TemplateCreateRequest;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.templates.dto.TemplateImportResponse;
import com.agenticai.core.templates.dto.TemplateRatingRequest;
import com.agenticai.core.templates.dto.TemplateResponse;
import com.agenticai.core.templates.dto.TemplateReview;
import com.agenticai.core.templates.dto.TemplateSummary;
import com.agenticai.core.templates.dto.TemplateUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated MEMBER surface for community templates. Falls through to {@code SecurityConfig}'s
 * default {@code anyRequest().authenticated()} - no {@code permitAll()} rule (that is reserved for
 * the public gallery, mounted separately at {@code /api/templates/gallery}). Every action is scoped
 * to the caller's own templates/resources inside {@link CommunityTemplateService}; a non-owner sees
 * 404, never 403.
 */
@RestController
@RequestMapping("/api/templates")
public class CommunityTemplateController {

    private final CommunityTemplateService service;

    public CommunityTemplateController(CommunityTemplateService service) {
        this.service = service;
    }

    /**
     * Approved templates for the signed-in caller - same data as the public gallery. The web app
     * prefetches {@code GET /api/templates} on sign-in; with only POST mapped on this path Spring
     * threw HttpRequestMethodNotSupportedException, which the catch-all answered 500.
     */
    @GetMapping
    public List<TemplateSummary> list(
            @RequestParam(required = false) TemplateKind kind,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        return service.gallery(kind, category, tag, page, size, sort);
    }

    /** Create a DRAFT from one of my agents/workflows (server does the sanitising snapshot). */
    @PostMapping
    public ResponseEntity<TemplateResponse> create(
            @Valid @RequestBody TemplateCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDraft(request));
    }

    /** Edit my own DRAFT/REJECTED template's presentation. */
    @PutMapping("/{id}")
    public TemplateResponse update(
            @PathVariable UUID id, @Valid @RequestBody TemplateUpdateRequest request) {
        return service.edit(id, request);
    }

    /** DRAFT/REJECTED/WITHDRAWN -> PENDING. */
    @PostMapping("/{id}/submit")
    public TemplateResponse submit(@PathVariable UUID id) {
        return service.submit(id);
    }

    /**
     * Check my template against the published standard without submitting it: the automatic rules
     * always, plus the AI review when {@code ai=true}. Nothing is stored. Returns every blocker and
     * advisory at once, plus what importers will need to activate.
     */
    @PostMapping("/{id}/validate")
    public TemplateReview validate(
            @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean ai) {
        return service.validate(id, ai);
    }

    /** Owner pulls their own template back (PENDING or APPROVED -> WITHDRAWN). */
    @PostMapping("/{id}/withdraw")
    public TemplateResponse withdraw(@PathVariable UUID id) {
        return service.withdraw(id);
    }

    /**
     * Instantiate a new agent/workflow in MY tenant from an APPROVED template (or my own, any
     * status).
     */
    @PostMapping("/{id}/import")
    public ResponseEntity<TemplateImportResponse> importTemplate(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.importTemplate(id));
    }

    /** My submissions with their statuses. */
    @GetMapping("/mine")
    public ResponseEntity<List<TemplateResponse>> mine(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.mine(), limit, offset, 50, 200);
    }

    /**
     * Rate (or re-rate - upsert) an APPROVED marketplace template. One vote per member; a second
     * POST replaces the first. Self-rating is refused (409). Returns the template's updated public
     * summary so the caller can render the new aggregate straight away.
     */
    @PostMapping("/{id}/rating")
    public TemplateSummary rate(
            @PathVariable UUID id, @Valid @RequestBody TemplateRatingRequest request) {
        return service.rate(id, request);
    }

    /** Remove my rating of a template. Idempotent - never having rated is a no-op. */
    @DeleteMapping("/{id}/rating")
    public TemplateSummary unrate(@PathVariable UUID id) {
        return service.unrate(id);
    }
}
