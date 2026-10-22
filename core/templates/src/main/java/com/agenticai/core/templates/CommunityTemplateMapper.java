package com.agenticai.core.templates;

import com.agenticai.core.templates.dto.TemplateDetail;
import com.agenticai.core.templates.dto.TemplateResponse;
import com.agenticai.core.templates.dto.TemplateReview;
import com.agenticai.core.templates.dto.TemplateSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entity-to-DTO mapping for {@link CommunityTemplateService}, plus the JSON reads/writes of the
 * stored config snapshot and review. The public summary/detail shapes never carry submitter or
 * source-tenant fields; only {@link #toResponse} (owner/admin projection) does.
 */
final class CommunityTemplateMapper {

    private static final Logger log = LoggerFactory.getLogger(CommunityTemplateService.class);

    private final TemplateSnapshotService snapshotService;
    private final TemplateReadinessService readinessService;
    private final ObjectMapper objectMapper;

    CommunityTemplateMapper(
            TemplateSnapshotService snapshotService,
            TemplateReadinessService readinessService,
            ObjectMapper objectMapper) {
        this.snapshotService = snapshotService;
        this.readinessService = readinessService;
        this.objectMapper = objectMapper;
    }

    TemplateResponse toResponse(CommunityTemplate t) {
        return new TemplateResponse(
                t.getId(),
                t.getKind(),
                t.getTitle(),
                t.getSummary(),
                t.getCategory(),
                snapshotService.parseTags(t.getTagsJson()),
                t.getStatus(),
                readConfig(t.getConfigJson()),
                t.getSourceTenantId(),
                t.getSubmittedBy(),
                t.getSubmittedAt(),
                t.getReviewedBy(),
                t.getReviewedAt(),
                t.getRejectReason(),
                t.getImportCount(),
                t.isVerified(),
                t.getVerifiedBy(),
                t.getVerifiedAt(),
                t.getRatingCount(),
                t.getRatingAvg(),
                t.getVersion(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                readReview(t.getAiReviewJson()),
                readinessService.requirements(t.getKind(), readConfig(t.getConfigJson())));
    }

    TemplateSummary toSummary(CommunityTemplate t) {
        return new TemplateSummary(
                t.getId(),
                t.getKind(),
                t.getTitle(),
                t.getSummary(),
                t.getCategory(),
                snapshotService.parseTags(t.getTagsJson()),
                t.getImportCount(),
                t.isVerified(),
                t.getRatingCount(),
                t.getRatingAvg(),
                t.getVersion(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    TemplateDetail toDetail(CommunityTemplate t) {
        return new TemplateDetail(
                t.getId(),
                t.getKind(),
                t.getTitle(),
                t.getSummary(),
                t.getCategory(),
                snapshotService.parseTags(t.getTagsJson()),
                readConfig(t.getConfigJson()),
                t.getImportCount(),
                t.isVerified(),
                t.getRatingCount(),
                t.getRatingAvg(),
                t.getVersion(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                readinessService.requirements(t.getKind(), readConfig(t.getConfigJson())));
    }

    JsonNode readConfig(String configJson) {
        try {
            return objectMapper.readTree(configJson == null ? "{}" : configJson);
        } catch (Exception e) {
            // A corrupt stored snapshot must not take down a read - surface an empty object.
            log.warn(
                    "Template has an unreadable config_json - returning empty config for display",
                    e);
            return objectMapper.createObjectNode();
        }
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Could not serialise template review", e);
            return null;
        }
    }

    TemplateReview readReview(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TemplateReview.class);
        } catch (Exception e) {
            log.warn("Stored template review is unreadable - ignoring", e);
            return null;
        }
    }
}
