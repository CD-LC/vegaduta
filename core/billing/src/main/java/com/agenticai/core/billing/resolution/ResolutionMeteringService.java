package com.agenticai.core.billing.resolution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

/**
 * The write/decide half of the resolution meter: turns a conversation's
 * end-state into an idempotent {@code resolution_event} row with the
 * billable decision and price snapshot applied. Producers today:
 * {@code ChannelSessionResolutionSweep} (core/app, hourly quiet-close of
 * agent-handled channel conversations) and {@code ResolutionController}'s
 * explicit-record endpoint.
 *
 * <p>Exactly-once is the dedupe_key's job, not this service's:
 * {@link #record} returns empty when the natural key was already recorded,
 * so every producer can safely re-evaluate the same conversation (sweep
 * reruns, webhook retries) without double-billing it.
 */
@Service
public class ResolutionMeteringService {

    private final ResolutionEventRepository repository;
    private final ResolutionPricingService pricing;

    public ResolutionMeteringService(ResolutionEventRepository repository, ResolutionPricingService pricing) {
        this.repository = repository;
        this.pricing = pricing;
    }

    /**
     * Records one resolution decision. Billable only when the outcome is
     * {@link ResolutionOutcome#RESOLVED_AI} and the evidence's billable
     * policy allows it (idle-close is governed by the
     * {@code platform.resolution.bill-idle-close} setting); the price
     * snapshot travels with the row so later price/plan changes never
     * rewrite history.
     *
     * @return the recorded row's id, or empty when {@code dedupeKey} was
     *     already recorded
     */
    @Transactional
    public Optional<UUID> record(RecordRequest request) {
        ResolutionPricingService.PricingSnapshot price = pricing.snapshot();
        boolean billable = isBillable(request.outcome(), request.evidence(), price);
        String reason = billableReason(request.outcome(), request.evidence(), billable, price);
        ResolutionEvent event = new ResolutionEvent(
            UUID.randomUUID(),
            request.conversationKey(),
            request.agentId(),
            request.channel(),
            request.outcome(),
            request.evidence(),
            request.confidence() == null ? BigDecimal.ONE : request.confidence(),
            billable,
            reason,
            billable ? price.unitPriceMinor() : null,
            billable ? price.currency() : null,
            request.resolvedAt() == null ? Instant.now() : request.resolvedAt(),
            request.dedupeKey(),
            request.createdBy(),
            null, null, null,
            Instant.now());
        return repository.insertIfAbsent(event);
    }

    /** Static so {@code ChannelSessionResolutionSweep} can unit-test the same policy it runs. */
    public static boolean isBillable(
        ResolutionOutcome outcome,
        ResolutionEvidence evidence,
        ResolutionPricingService.PricingSnapshot price
    ) {
        if (outcome != ResolutionOutcome.RESOLVED_AI) {
            return false;
        }
        if (evidence == ResolutionEvidence.NO_ESCALATION_IDLE) {
            return price.billIdleClose();
        }
        return true;
    }

    private static String billableReason(
        ResolutionOutcome outcome,
        ResolutionEvidence evidence,
        boolean billable,
        ResolutionPricingService.PricingSnapshot price
    ) {
        if (billable) {
            return "RESOLVED_AI via " + evidence;
        }
        if (outcome != ResolutionOutcome.RESOLVED_AI) {
            return "not AI-resolved (" + outcome + ")";
        }
        return "idle-close billing disabled (platform.resolution.bill-idle-close=false)";
    }

    /** Dispute one billable event. Returns false when the id is unknown to this tenant's schema. */
    @Transactional
    public boolean dispute(UUID id, String disputedBy, String reason) {
        return repository.dispute(id, disputedBy, reason);
    }

    /** The fields a producer must supply; the service fills price/billable/dedupe bookkeeping. */
    public record RecordRequest(
        String conversationKey,
        UUID agentId,
        String channel,
        ResolutionOutcome outcome,
        ResolutionEvidence evidence,
        BigDecimal confidence,
        Instant resolvedAt,
        String dedupeKey,
        String createdBy
    ) {
    }
}
