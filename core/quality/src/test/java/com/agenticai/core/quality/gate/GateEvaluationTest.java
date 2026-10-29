package com.agenticai.core.quality.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.quality.gate.GateEvaluation.Reason;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gate report is the contract a blocked promote surfaces: {@code allowed} is the only bit the
 * enforcement hook reads, and everything else must be a true statement about WHY. The factories -
 * not the callers - assemble that pairing, so they are what this test pins, together with the
 * {@link AgentEvalGate} row they read from.
 */
class GateEvaluationTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();

    private static AgentEvalGate enabledGate() {
        return new AgentEvalGate(AGENT, DATASET, true, 72, "admin@example.com");
    }

    // ---------------------------------------------------------------- AgentEvalGate

    @Test
    void aNewGateCapturesItsPolicyAndAuditFields() {
        AgentEvalGate gate = enabledGate();

        assertEquals(AGENT, gate.getAgentId());
        assertEquals(DATASET, gate.getDatasetId());
        assertTrue(gate.isEnabled());
        assertEquals(72, gate.getMaxRunAgeHours());
        assertFalse(gate.isRequireSimulation());
        assertNull(gate.getMaxSimulationAgeHours());
        assertEquals("admin@example.com", gate.getCreatedBy());
        assertEquals("admin@example.com", gate.getUpdatedBy());
        assertEquals(gate.getCreatedAt(), gate.getUpdatedAt());
    }

    @Test
    void theSimulationLegIsOptInAndCarriesItsOwnFreshnessWindow() {
        AgentEvalGate gate =
                new AgentEvalGate(AGENT, DATASET, true, null, true, 24, "admin@example.com");

        assertTrue(gate.isRequireSimulation());
        assertEquals(24, gate.getMaxSimulationAgeHours());
        assertNull(gate.getMaxRunAgeHours(), "null max age means any age qualifies");
    }

    @Test
    void updateRebindsThePolicyAndRestampsTheAuditFields() throws Exception {
        AgentEvalGate gate = enabledGate();
        UUID otherDataset = UUID.randomUUID();

        gate.update(otherDataset, false, 12, true, 6, "other@example.com");

        assertEquals(otherDataset, gate.getDatasetId());
        assertFalse(gate.isEnabled());
        assertEquals(12, gate.getMaxRunAgeHours());
        assertTrue(gate.isRequireSimulation());
        assertEquals(6, gate.getMaxSimulationAgeHours());
        assertEquals("other@example.com", gate.getUpdatedBy());
        assertEquals("admin@example.com", gate.getCreatedBy(), "the creator does not move");

        gate.update(DATASET, true, 48, "third@example.com");
        assertFalse(gate.isRequireSimulation(), "the legacy overload resets the sim leg");
        assertNull(gate.getMaxSimulationAgeHours());
    }

    // ---------------------------------------------------------------- ungated

    @Test
    void noGateMeansAllowedAndUnconfigured() {
        GateEvaluation eval = GateEvaluation.ungated(AGENT, VERSION, Reason.NO_GATE);

        assertTrue(eval.allowed());
        assertFalse(eval.configured());
        assertFalse(eval.enabled());
        assertEquals(Reason.NO_GATE, eval.reason());
        assertEquals(AGENT, eval.agentId());
        assertEquals(VERSION, eval.versionId());
        assertNull(eval.datasetId());
        assertTrue(eval.message().contains("No eval gate"));
        assertNull(eval.qualifyingRunId());
    }

    @Test
    void aDisabledGateIsConfiguredButStillAllowed() {
        GateEvaluation eval = GateEvaluation.ungated(AGENT, VERSION, Reason.GATE_DISABLED);

        assertTrue(eval.allowed());
        assertTrue(eval.configured());
        assertEquals(Reason.GATE_DISABLED, eval.reason());
        assertTrue(eval.message().contains("disabled"));
    }

    // ---------------------------------------------------------------- blocked / passed

    @Test
    void blockedCarriesTheGateAndTheRunEvidenceThatFailed() {
        AgentEvalGate gate = enabledGate();
        UUID runId = UUID.randomUUID();
        Instant finished = Instant.parse("2026-10-01T00:00:00Z");

        GateEvaluation eval =
                GateEvaluation.blocked(
                        gate,
                        "Golden set",
                        VERSION,
                        Reason.RUN_FAILED,
                        "the latest run failed",
                        runId,
                        "FAILED",
                        0.42,
                        finished);

        assertFalse(eval.allowed());
        assertTrue(eval.configured());
        assertTrue(eval.enabled());
        assertEquals(AGENT, eval.agentId());
        assertEquals(VERSION, eval.versionId());
        assertEquals(DATASET, eval.datasetId());
        assertEquals("Golden set", eval.datasetName());
        assertEquals(72, eval.maxRunAgeHours());
        assertEquals(Reason.RUN_FAILED, eval.reason());
        assertEquals("the latest run failed", eval.message());
        assertEquals(runId, eval.qualifyingRunId());
        assertEquals("FAILED", eval.verdict());
        assertEquals(0.42, eval.overallScore());
        assertEquals(finished, eval.runFinishedAt());
        assertFalse(eval.simulationRequired());
        assertNull(eval.simulationSessionId());
    }

    @Test
    void blockedSimulationCarriesThePassedEvalLegAndTheFailedSimLeg() {
        AgentEvalGate gate = new AgentEvalGate(AGENT, DATASET, true, null, true, 24, "admin");
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant runFinished = Instant.parse("2026-10-01T00:00:00Z");
        Instant simFinished = Instant.parse("2026-10-02T00:00:00Z");

        GateEvaluation eval =
                GateEvaluation.blockedSimulation(
                        gate,
                        "Golden set",
                        VERSION,
                        Reason.SIM_FAILED,
                        "a persona failed",
                        runId,
                        0.9,
                        runFinished,
                        sessionId,
                        "FAILED",
                        simFinished);

        assertFalse(eval.allowed());
        assertEquals(Reason.SIM_FAILED, eval.reason());
        assertEquals(
                "PASSED",
                eval.verdict(),
                "the eval leg already passed; the sim leg is what failed");
        assertTrue(eval.simulationRequired());
        assertEquals(sessionId, eval.simulationSessionId());
        assertEquals("FAILED", eval.simulationVerdict());
        assertEquals(simFinished, eval.simulationFinishedAt());
        assertEquals(runId, eval.qualifyingRunId());
    }

    @Test
    void passedReportsTheQualifyingRunAndASpeakableMessage() {
        AgentEvalGate gate = enabledGate();
        UUID runId = UUID.randomUUID();

        GateEvaluation eval =
                GateEvaluation.passed(gate, "Golden set", VERSION, runId, 0.913, null);

        assertTrue(eval.allowed());
        assertTrue(eval.enabled());
        assertEquals(Reason.PASSING_RUN, eval.reason());
        assertEquals("PASSED", eval.verdict());
        assertEquals(runId, eval.qualifyingRunId());
        assertTrue(eval.message().contains("Golden set"));
        assertTrue(eval.message().contains("0.913"));
        assertFalse(
                eval.message().contains("simulation"),
                "a gate without the sim leg says nothing about simulation");
        assertNull(eval.simulationVerdict());
    }

    @Test
    void passedWithASimulationLegSaysSoAndCarriesTheSession() {
        AgentEvalGate gate = new AgentEvalGate(AGENT, DATASET, true, null, true, 24, "admin");
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant simFinished = Instant.parse("2026-10-02T00:00:00Z");

        GateEvaluation eval =
                GateEvaluation.passed(
                        gate, "Golden set", VERSION, runId, null, null, sessionId, simFinished);

        assertTrue(eval.allowed());
        assertTrue(eval.simulationRequired());
        assertEquals("PASSED", eval.simulationVerdict());
        assertEquals(sessionId, eval.simulationSessionId());
        assertEquals(simFinished, eval.simulationFinishedAt());
        assertTrue(eval.message().contains("simulation staging also passed"));
        assertFalse(
                eval.message().contains("score"),
                "no score, no fabricated score fragment in the message");
    }
}
