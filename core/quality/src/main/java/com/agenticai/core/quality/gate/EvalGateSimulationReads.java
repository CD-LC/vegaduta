package com.agenticai.core.quality.gate;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Native read of the simulation leg of the eval gate, split out of {@link EvalGateService}. */
final class EvalGateSimulationReads {

    private EvalGateSimulationReads() {}

    /**
     * One simulation session's judged-run aggregate on the candidate config - the staging verdict.
     */
    record SimLeg(
            UUID sessionId, int passed, int failed, int inconclusive, Instant lastCompletedAt) {}

    /**
     * The latest simulation session of this agent containing at least one JUDGED run ({@code
     * verdict IS NOT NULL} - a completed conversation the judge scored; ERROR/CANCELLED runs carry
     * no verdict and can never testify either way) bound to a version whose config_json is
     * byte-identical to the candidate's - the same content binding {@code
     * EvalGateService#latestQualifyingRun} uses for eval runs, applied to {@code
     * simulation_run.agent_version_id} (V344). Sessions whose runs judged a different configuration
     * contribute nothing - a persona that passed an OLD prompt says nothing about THIS one. Verdict
     * counts aggregate the session's matching judged runs; {@code lastCompletedAt} is their latest
     * completion.
     */
    static Optional<SimLeg> latestQualifyingSimulation(
            EntityManager entityManager, UUID agentId, UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT s.id, "
                                        + "  COUNT(*) FILTER (WHERE r.verdict = 'PASSED'), "
                                        + "  COUNT(*) FILTER (WHERE r.verdict = 'FAILED'), "
                                        + "  COUNT(*) FILTER (WHERE r.verdict = 'INCONCLUSIVE'), "
                                        + "  MAX(r.completed_at) "
                                        + "FROM simulation_session s "
                                        + "JOIN simulation_run r ON r.session_id = s.id "
                                        + "WHERE s.agent_id = CAST(:agentId AS uuid) "
                                        + "  AND r.verdict IS NOT NULL "
                                        + "  AND r.agent_version_id IN ("
                                        + "      SELECT v.id FROM agent_version v "
                                        + "      WHERE v.agent_id = CAST(:agentId AS uuid) "
                                        + "        AND v.config_json = (SELECT t.config_json FROM agent_version t "
                                        + "                             WHERE t.id = CAST(:versionId AS uuid))) "
                                        + "GROUP BY s.id "
                                        + "ORDER BY MAX(r.completed_at) DESC NULLS LAST "
                                        + "LIMIT 1")
                        .setParameter("agentId", agentId.toString())
                        .setParameter("versionId", versionId.toString())
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        Instant lastCompleted =
                row[4] instanceof Timestamp ts
                        ? ts.toInstant()
                        : row[4] instanceof Instant i ? i : null;
        return Optional.of(
                new SimLeg(
                        (UUID) row[0],
                        ((Number) row[1]).intValue(),
                        ((Number) row[2]).intValue(),
                        ((Number) row[3]).intValue(),
                        lastCompleted));
    }

    /**
     * Whether ANY SUCCEEDED run of the gated dataset tested this configuration - same
     * version-config equality as {@code EvalGateService#latestQualifyingRun} but WITHOUT the
     * dataset-hash match. True + empty qualifying run = the dataset was edited after its last run
     * ({@code Reason.DATASET_STALE}); false = {@code NO_QUALIFYING_RUN}.
     */
    static boolean anyRunForConfig(
            EntityManager entityManager, UUID datasetId, UUID agentId, UUID versionId) {
        Number count =
                (Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT COUNT(*) FROM eval_run r "
                                                + "WHERE r.dataset_id = CAST(:datasetId AS uuid) "
                                                + "  AND r.status = 'SUCCEEDED' "
                                                + "  AND r.agent_version_id IN ("
                                                + "      SELECT v.id FROM agent_version v "
                                                + "      WHERE v.agent_id = CAST(:agentId AS uuid) "
                                                + "        AND v.config_json = (SELECT t.config_json FROM agent_version t "
                                                + "                             WHERE t.id = CAST(:versionId AS uuid)))")
                                .setParameter("datasetId", datasetId.toString())
                                .setParameter("agentId", agentId.toString())
                                .setParameter("versionId", versionId.toString())
                                .getSingleResult();
        return count != null && count.longValue() > 0;
    }
}
