package com.agenticai.core.agents.version;

import java.time.Instant;
import java.util.UUID;

/**
 * SPI consulted by {@link AgentVersionService#setLabel} before a version may
 * carry the PRODUCTION label - the "CI for agents" hook: an implementation
 * decides whether the configuration being promoted has evidence it works.
 *
 * <p>The interface lives in the CONSUMER's module (the same choice {@code
 * core/quality}'s {@code AgentTurnReplayer} makes and documents): the eval
 * implementation lives in {@code core/quality} where the datasets, runs and
 * judges are, and the bridge that satisfies this contract sits in
 * {@code core/app}, which is the only module that can see both. This module
 * therefore compiles and runs with zero providers (no bean = no gate, promote
 * unconstrained) and never takes a Maven dependency on the quality module.
 *
 * <p>Only the PRODUCTION label is gated. STAGING is the "try it" pointer a
 * tenant moves freely - gating it would demand a passing eval before the
 * tenant is even allowed to MARK something as under test, which inverts the
 * workflow the gate exists to enforce.
 */
public interface AgentPromotionGate {

    /**
     * @param agentId   the agent being promoted
     * @param versionId the version row about to receive the PRODUCTION label
     * @return the decision - {@code allowed} plus the reason and the
     *     qualifying-run evidence (or the absence of it). Implementations
     *     must return {@code allowed=true} when no gate is configured:
     *     this SPI is opt-in enforcement, not a default block.
     */
    GateDecision evaluatePromotion(UUID agentId, UUID versionId);

    /**
     * The gate's answer. {@code reason} is a stable machine-readable code
     * (the quality implementation uses its {@code GateEvaluation.Reason}
     * names); {@code message} is the human sentence shown in the 412 body.
     * {@code qualifyingRunId}/{@code verdict}/{@code overallScore}/
     * {@code runFinishedAt} carry the evidence when a run exists.
     */
    record GateDecision(
        boolean allowed,
        boolean gateConfigured,
        boolean gateEnabled,
        String reason,
        String message,
        UUID qualifyingRunId,
        String verdict,
        Double overallScore,
        Instant runFinishedAt
    ) {
        /** Convenience for a decision made without any gate evaluation (e.g. no provider bean). */
        public static GateDecision unconstrained() {
            return new GateDecision(true, false, false, "NO_GATE", "No promotion gate provider.", null, null, null, null);
        }
    }
}
