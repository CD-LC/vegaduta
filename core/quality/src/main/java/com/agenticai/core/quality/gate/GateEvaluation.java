package com.agenticai.core.quality.gate;

import java.time.Instant;
import java.util.UUID;

/**
 * The outcome of asking "may this agent version be labelled PRODUCTION right
 * now" - the gate report a blocked caller surfaces and the badge a console
 * renders next to the Promote button.
 *
 * <p>{@code allowed} is the only field the enforcement hook reads; everything
 * else is the WHY, carried so the 412 body is a real report rather than a
 * bare refusal. {@code reason} is stable machine-readable (UI branches on it);
 * {@code message} is the human sentence.
 */
public record GateEvaluation(
    boolean configured,
    boolean enabled,
    UUID agentId,
    UUID versionId,
    UUID datasetId,
    String datasetName,
    Integer maxRunAgeHours,
    boolean allowed,
    Reason reason,
    String message,
    UUID qualifyingRunId,
    String verdict,
    Double overallScore,
    Instant runFinishedAt,
    boolean simulationRequired,
    UUID simulationSessionId,
    String simulationVerdict,
    Instant simulationFinishedAt
) {

    /** Why the gate answered what it answered - the stable contract for clients. */
    public enum Reason {
        /** No gate row exists for this agent - promote is unconstrained. */
        NO_GATE,
        /** A gate exists but is switched off. */
        GATE_DISABLED,
        /** A qualifying PASSED run exists (fresh enough, bound to this config). */
        PASSING_RUN,
        /** The version id does not belong to this agent (or does not exist). */
        VERSION_NOT_FOUND,
        /** Gate on, but no SUCCEEDED run has ever tested this configuration. */
        NO_QUALIFYING_RUN,
        /** Latest qualifying run's verdict was FAILED. */
        RUN_FAILED,
        /**
         * Latest qualifying run's verdict was INCONCLUSIVE - the judges
         * measured nothing. Blocked, not passed: honest absence is never a
         * pass (the same contract {@code EvalRunVerdict} documents).
         */
        RUN_INCONCLUSIVE,
        /** Latest qualifying PASSED run is older than the gate's freshness window. */
        RUN_STALE,
        /**
         * Runs of the gated dataset exist for this configuration, but none
         * on the dataset's CURRENT content hash (V343) - the dataset was
         * edited after its last run, so no run testifies for the cases as
         * they now stand. Re-running the dataset is the only fix.
         */
        DATASET_STALE,
        /**
         * The gate requires simulation evidence (V394) but no judged
         * simulation session has ever run this configuration.
         */
        SIM_NO_QUALIFYING_RUN,
        /**
         * Latest judged simulation session on this configuration had at least
         * one FAILED persona - the candidate did not survive the staging twin.
         */
        SIM_FAILED,
        /**
         * Latest judged simulation session had INCONCLUSIVE personas (and no
         * failures) - the sims measured nothing; blocked, same honest-absence
         * contract as {@link #RUN_INCONCLUSIVE}.
         */
        SIM_INCONCLUSIVE,
        /** Latest all-PASSED simulation session is older than the sim freshness window. */
        SIM_STALE
    }

    public static GateEvaluation ungated(UUID agentId, UUID versionId, Reason reason) {
        return new GateEvaluation(
            reason != Reason.NO_GATE, false, agentId, versionId,
            null, null, null, true, reason,
            reason == Reason.NO_GATE
                ? "No eval gate is configured for this agent."
                : "The eval gate for this agent is disabled.",
            null, null, null, null,
            false, null, null, null);
    }

    public static GateEvaluation blocked(
        AgentEvalGate gate, String datasetName, UUID versionId, Reason reason, String message,
        UUID qualifyingRunId, String verdict, Double overallScore, Instant runFinishedAt
    ) {
        return new GateEvaluation(
            true, gate.isEnabled(), gate.getAgentId(), versionId,
            gate.getDatasetId(), datasetName, gate.getMaxRunAgeHours(),
            false, reason, message,
            qualifyingRunId, verdict, overallScore, runFinishedAt,
            gate.isRequireSimulation(), null, null, null);
    }

    /**
     * A simulation-leg block: the eval leg already PASSED (its evidence is
     * carried in the run fields), and the sim session row explains which
     * staging evidence failed. {@code simVerdict} is the session aggregate -
     * FAILED when any persona failed, else INCONCLUSIVE.
     */
    public static GateEvaluation blockedSimulation(
        AgentEvalGate gate, String datasetName, UUID versionId, Reason reason, String message,
        UUID qualifyingRunId, Double overallScore, Instant runFinishedAt,
        UUID simulationSessionId, String simVerdict, Instant simulationFinishedAt
    ) {
        return new GateEvaluation(
            true, gate.isEnabled(), gate.getAgentId(), versionId,
            gate.getDatasetId(), datasetName, gate.getMaxRunAgeHours(),
            false, reason, message,
            qualifyingRunId, "PASSED", overallScore, runFinishedAt,
            true, simulationSessionId, simVerdict, simulationFinishedAt);
    }

    public static GateEvaluation passed(
        AgentEvalGate gate, String datasetName, UUID versionId,
        UUID qualifyingRunId, Double overallScore, Instant runFinishedAt
    ) {
        return passed(gate, datasetName, versionId, qualifyingRunId, overallScore, runFinishedAt,
            null, null);
    }

    public static GateEvaluation passed(
        AgentEvalGate gate, String datasetName, UUID versionId,
        UUID qualifyingRunId, Double overallScore, Instant runFinishedAt,
        UUID simulationSessionId, Instant simulationFinishedAt
    ) {
        return new GateEvaluation(
            true, true, gate.getAgentId(), versionId,
            gate.getDatasetId(), datasetName, gate.getMaxRunAgeHours(),
            true, Reason.PASSING_RUN,
            "Dataset \"" + datasetName + "\" passed for this configuration"
                + (overallScore != null ? String.format(" (score %.3f)", overallScore) : "")
                + (gate.isRequireSimulation() ? "; simulation staging also passed" : "")
                + ".",
            qualifyingRunId, "PASSED", overallScore, runFinishedAt,
            gate.isRequireSimulation(), simulationSessionId,
            simulationSessionId != null ? "PASSED" : null, simulationFinishedAt);
    }
}
