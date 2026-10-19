package com.agenticai.core.brief.dto;

import com.agenticai.core.brief.model.Brief;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for apply, versions, rollback and tune (Earned Autonomy contract, section 2). */
public final class ApplyDtos {

    private ApplyDtos() {
    }

    /**
     * {@code POST /api/briefs/{id}/apply}. {@code background} true returns {@code {jobId}} at once; absent/false
     * runs to completion and returns the result. {@code brief} (optional) is a tuned manifest to save as the
     * next version and apply - it can never change the rung; {@code baseVersion} makes that a compare-and-set
     * against the version the proposal was made from.
     */
    public record ApplyRequest(Boolean background, Brief brief, Integer baseVersion, String note) {
        public boolean isBackground() {
            return Boolean.TRUE.equals(background);
        }
    }

    public record StepDto(int seq, String kind, String ref, String entityId, String status, String error) {
    }

    /**
     * RUNNING | SUCCEEDED | FAILED. {@code failedStep} names the step that stopped a failed apply;
     * {@code orphans} lists entities a failed apply could not remove again (empty when the rollback was clean).
     */
    public record ApplyResult(
        UUID jobId,
        String status,
        int version,
        String error,
        StepDto failedStep,
        List<StepDto> steps,
        List<StepDto> orphans
    ) {
    }

    public record JobStarted(UUID jobId, String status) {
    }

    /** {@code GET /api/briefs/{id}/versions/diff?from&to}. */
    public record VersionDiff(int from, int to, ArrayNode patch) {
    }

    /** {@code POST /api/briefs/{id}/tune}. Nothing is saved or applied. */
    public record TuneRequest(String instruction) {
    }

    public record TuneResult(Brief proposedBrief, ArrayNode diff, String narrative, int baseVersion) {
    }

    /** Optional body of {@code POST .../versions/{v}/rollback}. */
    public record RollbackRequest(Boolean background) {
    }
}
