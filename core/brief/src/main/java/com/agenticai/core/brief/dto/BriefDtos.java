package com.agenticai.core.brief.dto;

import com.agenticai.core.brief.model.Brief;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request/response shapes for {@code /api/briefs} (Earned Autonomy contract, section 2). Fields the
 * later layers own (baseline, lastDelta, ledgerSummary) are declared now so the wire shape the web
 * builds against does not change, and are null until the proof ledger exists.
 */
public final class BriefDtos {

    private BriefDtos() {
    }

    /** {@code POST /api/briefs} and {@code PUT /api/briefs/{id}} body. */
    public record BriefRequest(Brief brief, String note) {
    }

    /** {@code POST /api/briefs/plan} response. */
    public record PlanResponse(Brief brief, List<ReadinessRow> readiness, List<String> warnings) {
    }

    /** {@code POST /api/briefs} response. */
    public record CreateResponse(UUID id, int version, String status) {
    }

    /** {@code PUT /api/briefs/{id}} response: the new version. */
    public record UpdateResponse(UUID id, int version, String status) {
    }

    /** One row of {@code GET /api/briefs}. */
    public record ListItem(
        UUID id,
        String title,
        String rung,
        String status,
        int currentVersion,
        Double baseline,
        Double lastDelta,
        Map<String, Integer> readinessSummary,
        Instant updatedAt
    ) {
    }

    public record VersionSummary(int version, Integer parentVersion, String note, String createdBy, Instant createdAt) {
    }

    public record BindingView(String ref, String kind, String entityId) {
    }

    /** {@code GET /api/briefs/{id}}. */
    public record Detail(
        UUID id,
        String status,
        Brief brief,
        List<ReadinessRow> readiness,
        List<VersionSummary> versions,
        String rung,
        List<BindingView> bindings,
        Object ledgerSummary
    ) {
    }

    /** {@code POST /api/briefs/{id}/readiness}. */
    public record ReadinessResponse(List<ReadinessRow> readiness, Map<String, Integer> summary) {
    }

    /** Optional {@code DELETE} body. */
    public record DeleteRequest(Boolean purgeEntities) {
    }
}
