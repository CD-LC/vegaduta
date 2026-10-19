package com.agenticai.core.templates.dto;

import com.agenticai.core.templates.TemplateKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public gallery <b>card</b>: everything needed to render a summary tile, and
 * NOTHING else. Deliberately carries no {@code config_json} (the list must never
 * ship snapshots in bulk), no status (every row is APPROVED), and no submitter
 * identity / source tenant. Fetch {@code GET /api/templates/gallery/{id}} for the
 * inspectable config.
 */
public record TemplateSummary(
    UUID id,
    TemplateKind kind,
    String title,
    String summary,
    String category,
    List<String> tags,
    long importCount,
    boolean verified,
    long ratingCount,
    double ratingAvg,
    int version,
    Instant createdAt,
    Instant updatedAt
) {
}
