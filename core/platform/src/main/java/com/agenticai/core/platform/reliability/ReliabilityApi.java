package com.agenticai.core.platform.reliability;

import java.time.Instant;
import java.util.List;

/**
 * Wire shapes of {@code GET /api/platform/health/dependencies} and
 * {@code GET /api/platform/slo}. Plain records, no behaviour. Every figure is
 * either measured or absent (null / omitted): nothing here is ever estimated
 * to fill a gap - the {@code notes} lists say what is missing and why.
 *
 * <p>Nothing here may carry a secret, a hostname or a URL: {@code detail}
 * strings are written by the probes with that rule in mind.
 */
public final class ReliabilityApi {

    private ReliabilityApi() {
    }

    /** One probed dependency. {@code latencyMs} is null when no probe was actually run (UNKNOWN). */
    public record DependencyEntry(
        String name,
        String category,
        DependencyStatus status,
        Long latencyMs,
        Instant lastCheckedAt,
        String detail
    ) {
    }

    /**
     * @param overall roll-up of the entries, see {@code DependencyHealthService#rollup}
     * @param cacheTtlSeconds how long this result is served before the dependencies are probed again
     */
    public record DependenciesResponse(
        Instant generatedAt,
        int cacheTtlSeconds,
        DependencyStatus overall,
        List<DependencyEntry> dependencies,
        List<String> notes
    ) {
    }

    /** The configured objectives the SLO figures are judged against. */
    public record Objectives(
        double availabilityTarget,
        double apiP95TargetMs,
        double chatTtftP95TargetMs,
        double latencyQuantile
    ) {
    }

    /**
     * Availability = non-5xx share of server requests (actuator excluded).
     *
     * @param burnRate error ratio divided by the error budget (1 - objective); 1.0 means errors arrive exactly as
     *     fast as the objective allows
     * @param budgetRemaining 1 - burnRate: the share of this window's error budget still unspent (negative: overspent)
     * @param verdict OK / WARN / BREACH / INSUFFICIENT_DATA
     */
    public record AvailabilitySlo(
        double requests,
        double serverErrors,
        Double availability,
        Double errorRatio,
        Double burnRate,
        Double budgetRemaining,
        String verdict
    ) {
    }

    /**
     * Latency of one request class, estimated from histogram buckets.
     *
     * @param key chat_turn | chat_ttft | other_api
     * @param slowShare share of requests slower than the target, evaluated at the nearest histogram bucket bound at
     *     or below the target ({@code slowShareBoundMs})
     */
    public record LatencySlo(
        String key,
        String label,
        double count,
        Double p50Ms,
        Double p95Ms,
        Double p99Ms,
        Double targetMs,
        Boolean meetsTarget,
        Double slowShare,
        Double slowShareBoundMs,
        Double burnRate,
        String verdict
    ) {
    }

    public record ProviderErrorRate(String provider, double calls, double errors, Double errorRate) {
    }

    public record JobSlo(double finished, double succeeded, double failed, Double successRate) {
    }

    /**
     * @param scope {@code platform} when the figures aggregate every replica that publishes samples, {@code replica}
     *     when they cover only the replica that answered
     * @param coveredSeconds how much of the requested window the samples actually span (shorter than the window after
     *     a restart)
     */
    public record SloResponse(
        Instant generatedAt,
        int windowHours,
        long coveredSeconds,
        String scope,
        int replicas,
        Objectives objectives,
        AvailabilitySlo availability,
        List<LatencySlo> latency,
        List<ProviderErrorRate> llmProviders,
        JobSlo jobs,
        List<String> notes
    ) {
    }
}
