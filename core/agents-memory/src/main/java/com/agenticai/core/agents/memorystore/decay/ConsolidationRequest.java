package com.agenticai.core.agents.memorystore.decay;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Input of one planning call: every row of one subject (the circuit breaker's denominator).
 *
 * @param candidates all of the subject's rows, any status; only ACTIVE ones are planned
 * @param duplicateGroups near-duplicate id sets found by the review detector (word overlap), may be
 *     empty; the planner decides the keeper
 * @param consolidationEnabledSince when consolidation was first enabled for this tenant, or null if
 *     unknown (treated as just now, so the plan is a dry run)
 */
public record ConsolidationRequest(
        String subjectKey,
        List<ItemSnapshot> candidates,
        List<Set<UUID>> duplicateGroups,
        Instant consolidationEnabledSince) {

    public ConsolidationRequest {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        duplicateGroups = duplicateGroups == null ? List.of() : List.copyOf(duplicateGroups);
    }
}
