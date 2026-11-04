package com.agenticai.core.quality.memeval;

import com.agenticai.core.quality.stats.ConfidenceInterval;
import com.agenticai.core.quality.stats.PairedComparisonResult;

/**
 * Aggregated memory metrics, the input of {@link MemoryScorecardGate}. Every rate carries its counts
 * and a Wilson interval; every A/B difference is a {@link PairedComparisonResult} with the scenario
 * (not the repeat) as the independent unit. Optional parts are {@code null} when the data to compute
 * them was not in the run (the gate then reports UNPROVEN, never a fabricated pass).
 */
public record MemoryScorecard(
    Meta meta,
    Gain gain,
    Recall recall,
    Mistakes mistakes,
    Contradiction contradiction,
    Staleness staleness,
    Poisoning poisoning,
    Isolation isolation,
    Overhead overhead
) {

    /** {@code probes} counts distinct probes in the M arm; {@code mistakeCases} / {@code attackCases} distinct scenarios. */
    public record Meta(String subject, int scenarios, int repeats, int probes, int mistakeCases, int attackCases) {}

    /** hits out of total with a 95% Wilson interval; {@code wilson} is null when total is 0. */
    public record Rate(int hits, int total, ConfidenceInterval wilson) {
        public double value() {
            return total == 0 ? 0.0 : (double) hits / total;
        }

        public boolean measured() {
            return total > 0;
        }
    }

    /** M1 on the memory-dependent subset, B vs M (variant = M). {@code m1Oracle} null without an ORACLE arm. */
    public record Gain(PairedComparisonResult m1, int memoryDependentCases, int memoryDependentProbes, Double m1Oracle) {}

    /** M2 (answer or injection), M2 retriever-only, M2 of the oracle on the same probes, and M3 precision. */
    public record Recall(Rate m2, Rate m2Retriever, Rate m2Oracle, Rate m3) {}

    /** M4 over MISTAKE probes. */
    public record Mistakes(Rate baseline, Rate memory, PairedComparisonResult paired) {}

    /** H1 over all probes; {@code paired} is per-scenario rates, variant = M. */
    public record Contradiction(Rate baseline, Rate memory, PairedComparisonResult paired) {}

    /** H2 on UPDATE probes ({@code memoryOnUpdates}) and on all probes. */
    public record Staleness(Rate memoryOnUpdates, Rate memoryAll) {}

    /** H3: attack scenario runs in the M arm and the two stages. */
    public record Poisoning(Rate persisted, Rate influenced, int attackScenarios) {}

    /** H4 (over probes) and H5 (over forget trials) in the M arm. */
    public record Isolation(Rate leaks, Rate forget, int isolationProbes) {}

    /** C1-C4. */
    public record Overhead(Tokens tokens, Latency latency, Growth growth) {}

    /** C1/C2: tokens and dollars per turn and per successful probe, M against B. */
    public record Tokens(
        double perTurnB, double perTurnM, double perTurnOverhead,
        Double perSuccessB, Double perSuccessM, Double perSuccessRatio, ConfidenceInterval perSuccessRatioCi,
        Double dollarsPerSuccessB, Double dollarsPerSuccessM
    ) {}

    /** C3: p95 turn latency and time to first token in ms; ttft fields are null when not measured. */
    public record Latency(double p95TurnB, double p95TurnM, Double p95TtftB, Double p95TtftM) {}

    /** C4: store growth in the M arm. */
    public record Growth(double factsPer100Turns, long storeBytes, double duplicateRatio, int maxItemsPerUser) {}
}
