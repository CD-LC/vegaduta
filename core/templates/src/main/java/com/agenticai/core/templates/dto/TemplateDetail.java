package com.agenticai.core.templates.dto;

import com.agenticai.core.templates.TemplateKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Public detail of ONE approved template: the summary fields plus the sanitised {@code config}
 * (shown read-only so a visitor can inspect the design before importing). Still no submitter
 * identity, source tenant, or moderation trail - those are owner/admin-only ({@link
 * TemplateResponse}). {@code config} is safe to expose precisely because no secret was ever stored
 * in it.
 */
public record TemplateDetail(
        UUID id,
        TemplateKind kind,
        String title,
        String summary,
        String category,
        List<String> tags,
        JsonNode config,
        long importCount,
        boolean verified,
        long ratingCount,
        double ratingAvg,
        int version,
        Instant createdAt,
        Instant updatedAt,
        /**
         * What an importer must activate (MCP servers, connections, knowledge base...). Derived
         * from {@code config}.
         */
        List<TemplateRequirement> requirements) {}
