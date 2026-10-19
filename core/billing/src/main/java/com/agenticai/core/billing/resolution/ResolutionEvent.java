package com.agenticai.core.billing.resolution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One resolution decision on one conversation - the metering unit of
 * outcome-based pricing (tenant schema V280; see that migration's header
 * for the outcome/evidence vocabularies and the dedupe model).
 *
 * <p>A record, not a JPA entity: {@code core/billing} is JdbcTemplate-only
 * (see {@code InvoiceRepository} / {@code TenantBillingProfileRepository}
 * for the same shape and its rationale - {@code core/tenancy} depends on
 * this module, so JPA entities here are deliberately avoided).
 */
public record ResolutionEvent(
    UUID id,
    String conversationKey,
    UUID agentId,
    String channel,
    ResolutionOutcome outcome,
    ResolutionEvidence evidence,
    BigDecimal confidence,
    boolean billable,
    String billableReason,
    Long unitPriceMinor,
    String currency,
    Instant resolvedAt,
    String dedupeKey,
    String createdBy,
    Instant disputedAt,
    String disputedBy,
    String disputeReason,
    Instant createdAt
) {
}
