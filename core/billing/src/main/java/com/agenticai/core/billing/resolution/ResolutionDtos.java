package com.agenticai.core.billing.resolution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes for {@link ResolutionController}. */
public final class ResolutionDtos {

    private ResolutionDtos() {}

    /** One row of {@code GET /api/tenant/resolutions}. */
    public record ResolutionEventResponse(
        UUID id,
        String conversationKey,
        UUID agentId,
        String channel,
        String outcome,
        String evidence,
        BigDecimal confidence,
        boolean billable,
        String billableReason,
        Long unitPriceMinor,
        String currency,
        Instant resolvedAt,
        Instant disputedAt,
        String disputedBy,
        String disputeReason
    ) {
        static ResolutionEventResponse from(ResolutionEvent e) {
            return new ResolutionEventResponse(
                e.id(), e.conversationKey(), e.agentId(), e.channel(), e.outcome().name(),
                e.evidence().name(), e.confidence(), e.billable(), e.billableReason(),
                e.unitPriceMinor(), e.currency(), e.resolvedAt(), e.disputedAt(),
                e.disputedBy(), e.disputeReason());
        }
    }

    /** {@code GET /api/tenant/resolutions/summary?from&to}. */
    public record ResolutionSummaryResponse(
        Instant from,
        Instant to,
        long totalEvents,
        Map<String, Long> byOutcome,
        long billableCount,
        long billableTotalMinor,
        String currency,
        long configuredUnitPriceMinor,
        boolean freeModeMeteringOnly
    ) {
    }

    /** {@code POST /api/tenant/resolutions} - explicit record for conversations the sweep does not see. */
    public record RecordResolutionRequest(
        String conversationKey,
        UUID agentId,
        String channel,
        String outcome,
        String evidence,
        BigDecimal confidence,
        Instant resolvedAt,
        String dedupeKey
    ) {
    }

    /** {@code POST /api/tenant/resolutions/{id}/dispute}. */
    public record DisputeRequest(String reason) {}

    /** Result of the record/dispute endpoints. */
    public record ResolutionActionResponse(UUID id, boolean recorded) {
        static ResolutionActionResponse of(UUID id, boolean recorded) {
            return new ResolutionActionResponse(id, recorded);
        }
    }

    /** {@code GET /api/tenant/resolutions} list envelope. */
    public record ResolutionListResponse(List<ResolutionEventResponse> events, int limit, int offset) {}
}
