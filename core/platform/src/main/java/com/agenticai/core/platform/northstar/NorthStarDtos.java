package com.agenticai.core.platform.northstar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Response shapes of {@code GET /api/platform/metrics/north-star}. Aggregates
 * only - there is deliberately no field anywhere in here that can carry a user,
 * tenant or agent identity. Every nullable numeric is null for exactly one of
 * two reasons, and {@code suppressed} says which: fewer than
 * {@link NorthStarAggregator#MIN_CONTRIBUTORS} users contributed to the cell
 * (suppressed = true), or the value is undefined (zero denominator).
 */
public final class NorthStarDtos {

    private NorthStarDtos() {
    }

    /** One ISO week (Monday start, UTC). {@code partial} marks the still-running current week. */
    public record WeekPoint(
        LocalDate weekStart,
        boolean partial,
        /** Distinct users with at least one user-initiated, successful run. */
        Long wau,
        /** Terminal (succeeded or failed) runs of every kind. */
        Long runs,
        /** Share of runs that were scheduled, event-triggered or channel-delivered (not interactive). */
        Double scheduledShare,
        /** Succeeded / (succeeded + failed); cancelled runs are excluded from both sides. */
        Double successRate,
        /** Runs by kind: INTERACTIVE, SCHEDULED, TRIGGERED, CHANNEL. */
        Map<String, Long> runsByKind,
        boolean suppressed
    ) {
    }

    /** Users who signed up in {@code weekStart}'s week. */
    public record CohortPoint(
        LocalDate weekStart,
        Long signups,
        Long activated,
        Double medianMinutes,
        boolean suppressed
    ) {
    }

    /** Channel-delivered (Quick Task) runs for one delivery channel. */
    public record ChannelPoint(String channel, Long runs, Double successRate, boolean suppressed) {
    }

    /** Conversation volume for one language. Always empty today - see the note the service emits. */
    public record LanguagePoint(String language, Long conversations, boolean suppressed) {
    }

    /** Eval-run reliability for runs that replayed each case {@code k} times. */
    public record PassKGroup(int k, Long cases, Double passPowK, boolean suppressed) {
    }

    /**
     * pass^k = share of eval cases where ALL k repetitions passed (tau-bench
     * definition, https://arxiv.org/abs/2406.12045). {@code average} is taken
     * over the un-suppressed k groups only so a suppressed group cannot be
     * recovered by subtraction.
     */
    public record PassK(Double average, Long cases, List<PassKGroup> byK, boolean suppressed) {
    }

    public record Coverage(int tenantsTotal, int tenantsScanned, int tenantsSkipped, boolean truncated) {
    }

    public record NorthStarResponse(
        Instant generatedAt,
        List<WeekPoint> weeks,
        Double timeToFirstRunMedianMinutes,
        List<CohortPoint> timeToFirstRunCohorts,
        List<LanguagePoint> languages,
        List<ChannelPoint> channels,
        PassK passK,
        Coverage coverage,
        List<String> notes
    ) {
    }
}
