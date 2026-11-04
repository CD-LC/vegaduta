package com.agenticai.core.agents.memorystore;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in user's export of the unified-memory store (ADR-0028 WP6): every {@code memory_item}
 * row whose {@code subject_keys} name the caller, across all of the tenant's agents and every
 * status and trust level - a data-subject export must be complete, so quarantined and pending rows
 * are included. Scoped to the caller's own principal name (the same key chat uses as
 * {@code subjectKey}); there is no way to address another user's rows.
 *
 * <p>Flag gating: 404 while {@code app.memory.unified.enabled} (the platform flag of the WP3 write
 * path, default OFF) is off, so the surface stays dark with the feature; tenant and agent level
 * switches do not restrict a person's own export. No agent access level is needed: the caller only
 * ever receives rows about themselves. Erasure of the same rows lives on the agent-scoped
 * controller ({@code DELETE /api/agents/{agentId}/memory-items/{itemId}}), which runs the lineage
 * cascade.
 */
@RestController
@RequestMapping("/api/memory-items")
public class MemoryItemExportController {

    static final String EXPORT_FILENAME = "memory-items-export.json";
    private static final int MAX_LIMIT = 5000;

    private final MemoryUnifiedProperties properties;
    private final MemoryItemReviewService review;

    public MemoryItemExportController(MemoryUnifiedProperties properties, MemoryItemReviewService review) {
        this.properties = properties;
        this.review = review;
    }

    public record ExportView(Instant exportedAt, String subject, List<MemoryItemController.ItemView> items) {}

    /** Everything the store holds about the caller, as a JSON download. */
    @GetMapping("/export")
    public ResponseEntity<ExportView> export(
            Principal principal, @RequestParam(defaultValue = "1000") int limit) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unified memory is not enabled.");
        }
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String subject = principal.getName();
        List<MemoryItemController.ItemView> items =
                review.forSubject(subject, Math.max(1, Math.min(limit, MAX_LIMIT))).stream()
                        .map(MemoryItemController.ItemView::of)
                        .toList();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + EXPORT_FILENAME + "\"")
                .body(new ExportView(Instant.now(), subject, items));
    }
}
