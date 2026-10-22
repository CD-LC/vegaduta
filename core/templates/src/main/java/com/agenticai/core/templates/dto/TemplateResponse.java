package com.agenticai.core.templates.dto;

import com.agenticai.core.templates.TemplateKind;
import com.agenticai.core.templates.TemplateStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The OWNER/ADMIN view of a template: the full record including status, the sanitised {@code
 * config}, the moderation trail (reject reason, reviewer, timestamps), and - for admins triaging -
 * the {@code sourceTenantId}. Returned by the member "mine" endpoints and by the admin moderation
 * queue. Never returned by a public endpoint (those use {@link TemplateSummary}/{@link
 * TemplateDetail}).
 */
public record TemplateResponse(
        UUID id,
        TemplateKind kind,
        String title,
        String summary,
        String category,
        List<String> tags,
        TemplateStatus status,
        JsonNode config,
        String sourceTenantId,
        String submittedBy,
        Instant submittedAt,
        String reviewedBy,
        Instant reviewedAt,
        String rejectReason,
        long importCount,
        boolean verified,
        String verifiedBy,
        Instant verifiedAt,
        long ratingCount,
        double ratingAvg,
        int version,
        Instant createdAt,
        Instant updatedAt,
        /**
         * Rules + AI review recorded at submit time; null for templates that pre-date it.
         * Owner/admin only.
         */
        TemplateReview review,
        /** What an importer must activate - derived from the config, so it is always current. */
        List<TemplateRequirement> requirements) {}
