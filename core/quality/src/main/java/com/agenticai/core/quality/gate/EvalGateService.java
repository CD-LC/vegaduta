package com.agenticai.core.quality.gate;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.quality.dataset.AgentHeadVersionLookup;
import com.agenticai.core.quality.dataset.EvalDataset;
import com.agenticai.core.quality.dataset.EvalDatasetService;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The eval gate: decides whether an agent version may be labelled PRODUCTION (the platform's
 * "publish"/"promote" action - see {@code AgentVersionService#setLabel}, which consults this
 * through the {@code AgentPromotionGate} SPI bridged in {@code core/app}).
 *
 * <h2>What "qualifying run" means</h2>
 *
 * A run qualifies a promote of version V when it (a) ran the gate's dataset, (b) SUCCEEDED, and (c)
 * was bound at trigger time to a version whose {@code config_json} is BYTE-IDENTICAL to V's.
 * Binding is by config CONTENT, not by row id: the snapshot codec emits canonical JSON, so the same
 * configuration stored under two different version rows (edit A to B and back to A) still counts as
 * tested - which is true: the run replayed that exact configuration.
 *
 * <h2>Verdict rules</h2>
 *
 * The LATEST qualifying run decides: PASSED and fresh enough = allowed; FAILED, INCONCLUSIVE,
 * missing or stale = blocked with a specific reason. "Latest wins" is deliberate - a PASSED run
 * followed by a FAILED run on the same config is a regression signal, not a pass. INCONCLUSIVE
 * blocks rather than passes, continuing the honest-absence contract {@code EvalRunVerdict} and
 * {@code TokenBaseline} both document: "the judges measured nothing" is not evidence of quality.
 *
 * <h2>Module boundary</h2>
 *
 * Everything here reads through repositories or native SQL - this module cannot reference {@code
 * AgentVersion} (see {@code AgentDefinitionLookup}'s javadoc). The agents side calls in via the
 * SPI, never the reverse.
 */
@Service
public class EvalGateService {

    private static final Logger log = LoggerFactory.getLogger(EvalGateService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    @PersistenceContext private EntityManager entityManager;

    /**
     * When true, a qualifying run with {@code repeats > 1} must also clear the pass^k bar ({@link
     * PassHatKGate}) - the Wilson lower bound of "cases whose every repetition passed" - on top of
     * the mean-based verdict. Default OFF: existing gated agents keep today's behaviour.
     */
    @Value("${app.quality.gate.pass-hat-k.enabled:true}")
    private boolean passHatKEnabled = false;

    /** Required Wilson lower bound for the pass^k check; see {@link PassHatKGate}. */
    @Value("${app.quality.gate.pass-hat-k.min-lower-bound:0.5}")
    private double passHatKMinLowerBound = 0.5;

    private final AgentEvalGateRepository gateRepository;
    private final EvalDatasetService datasetService;
    private final AgentHeadVersionLookup headVersionLookup;

    public EvalGateService(
            AgentEvalGateRepository gateRepository,
            EvalDatasetService datasetService,
            AgentHeadVersionLookup headVersionLookup) {
        this.gateRepository = gateRepository;
        this.datasetService = datasetService;
        this.headVersionLookup = headVersionLookup;
    }

    // ------------------------------------------------------------------
    // Gate configuration
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<AgentEvalGate> findGate(UUID agentId) {
        return gateRepository.findByAgentId(agentId);
    }

    /**
     * Creates or replaces this agent's gate. The dataset must belong to the agent (a gate on
     * another agent's dataset would test the wrong prompts); {@code requireDataset} enforces that
     * binding.
     */
    @Transactional
    public AgentEvalGate upsertGate(
            UUID agentId, UUID datasetId, boolean enabled, Integer maxRunAgeHours) {
        return upsertGate(agentId, datasetId, enabled, maxRunAgeHours, false, null);
    }

    @Transactional
    public AgentEvalGate upsertGate(
            UUID agentId,
            UUID datasetId,
            boolean enabled,
            Integer maxRunAgeHours,
            boolean requireSimulation,
            Integer maxSimulationAgeHours) {
        EvalDataset dataset = datasetService.requireDataset(agentId, datasetId);
        if (maxRunAgeHours != null && maxRunAgeHours <= 0) {
            throw new IllegalArgumentException(
                    "maxRunAgeHours must be positive, or null for no freshness window.");
        }
        if (maxSimulationAgeHours != null && maxSimulationAgeHours <= 0) {
            throw new IllegalArgumentException(
                    "maxSimulationAgeHours must be positive, or null for no freshness window.");
        }
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        AgentEvalGate existing = gateRepository.findByAgentId(agentId).orElse(null);
        if (existing != null) {
            existing.update(
                    dataset.getId(),
                    enabled,
                    maxRunAgeHours,
                    requireSimulation,
                    maxSimulationAgeHours,
                    actor);
            return gateRepository.save(existing);
        }
        return gateRepository.save(
                new AgentEvalGate(
                        agentId,
                        dataset.getId(),
                        enabled,
                        maxRunAgeHours,
                        requireSimulation,
                        maxSimulationAgeHours,
                        actor));
    }

    /** Removes the gate entirely. Returns false when none existed. */
    @Transactional
    public boolean deleteGate(UUID agentId) {
        Optional<AgentEvalGate> gate = gateRepository.findByAgentId(agentId);
        gate.ifPresent(gateRepository::delete);
        return gate.isPresent();
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    /**
     * Evaluates the gate for promoting {@code versionId} of {@code agentId}. Never throws for an
     * unconfigured/disabled gate - those are the two "allowed" answers; blocks carry a reason +
     * message.
     */
    @Transactional(readOnly = true)
    public GateEvaluation evaluate(UUID agentId, UUID versionId) {
        Optional<AgentEvalGate> maybeGate = gateRepository.findByAgentId(agentId);
        if (maybeGate.isEmpty()) {
            return GateEvaluation.ungated(agentId, versionId, GateEvaluation.Reason.NO_GATE);
        }
        AgentEvalGate gate = maybeGate.get();
        if (!gate.isEnabled()) {
            return GateEvaluation.ungated(agentId, versionId, GateEvaluation.Reason.GATE_DISABLED);
        }

        // The version must belong to this agent - a foreign or nonexistent id
        // can never be promoted by a gate configured on another lineage.
        if (!versionBelongsToAgent(agentId, versionId)) {
            return GateEvaluation.blocked(
                    gate,
                    datasetName(gate),
                    versionId,
                    GateEvaluation.Reason.VERSION_NOT_FOUND,
                    "Version " + versionId + " is not a stored configuration of this agent.",
                    null,
                    null,
                    null,
                    null);
        }

        String datasetName = datasetName(gate);
        // The dataset's CURRENT content hash (V343): only a run snapshotted
        // against exactly this content qualifies - a dataset edited after
        // its last run leaves every prior run testifying for cases that no
        // longer exist in that form.
        String currentHash = datasetService.computeContentHash(gate.getDatasetId());
        Optional<RunRow> latest =
                latestQualifyingRun(gate.getDatasetId(), agentId, versionId, currentHash);
        if (latest.isEmpty()) {
            // Distinguish "never ran this config" from "ran it, but on a
            // dataset snapshot that has since been edited" - the remedies
            // are the same (re-run) but the diagnosis is not.
            if (anyRunForConfig(gate.getDatasetId(), agentId, versionId)) {
                return GateEvaluation.blocked(
                        gate,
                        datasetName,
                        versionId,
                        GateEvaluation.Reason.DATASET_STALE,
                        "Dataset \""
                                + datasetName
                                + "\" was edited after its last completed run on this "
                                + "configuration - that run tested a different snapshot of the cases. "
                                + "Re-run the dataset; the new run binds to the current dataset hash.",
                        null,
                        null,
                        null,
                        null);
            }
            return GateEvaluation.blocked(
                    gate,
                    datasetName,
                    versionId,
                    GateEvaluation.Reason.NO_QUALIFYING_RUN,
                    "No completed eval run of \""
                            + datasetName
                            + "\" has tested this configuration. "
                            + "Run the gated dataset first - the run binds itself to the version under test.",
                    null,
                    null,
                    null,
                    null);
        }

        RunRow run = latest.get();
        switch (run.verdict()) {
            case "FAILED" -> {
                return GateEvaluation.blocked(
                        gate,
                        datasetName,
                        versionId,
                        GateEvaluation.Reason.RUN_FAILED,
                        "Latest eval run of \""
                                + datasetName
                                + "\" on this configuration FAILED"
                                + scoreSuffix(run)
                                + ". Fix the agent and re-run the dataset.",
                        run.id(),
                        "FAILED",
                        run.overallScore(),
                        run.finishedAt());
            }
            case "INCONCLUSIVE" -> {
                return GateEvaluation.blocked(
                        gate,
                        datasetName,
                        versionId,
                        GateEvaluation.Reason.RUN_INCONCLUSIVE,
                        "Latest eval run of \""
                                + datasetName
                                + "\" measured nothing (judges "
                                + "unavailable or every case errored). An inconclusive run is not a pass "
                                + "- re-run once a judge provider is healthy.",
                        run.id(),
                        "INCONCLUSIVE",
                        run.overallScore(),
                        run.finishedAt());
            }
            default -> {
                // PASSED - freshness window is the last check.
                if (gate.getMaxRunAgeHours() != null
                        && run.finishedAt() != null
                        && run.finishedAt()
                                .isBefore(
                                        Instant.now()
                                                .minus(
                                                        Duration.ofHours(
                                                                gate.getMaxRunAgeHours())))) {
                    return GateEvaluation.blocked(
                            gate,
                            datasetName,
                            versionId,
                            GateEvaluation.Reason.RUN_STALE,
                            "Latest passing run of \""
                                    + datasetName
                                    + "\" is older than the gate's "
                                    + gate.getMaxRunAgeHours()
                                    + "h freshness window - re-run the dataset.",
                            run.id(),
                            "PASSED",
                            run.overallScore(),
                            run.finishedAt());
                }
                if (passHatKEnabled && run.repeats() > 1) {
                    PassHatKGate.Decision d =
                            PassHatKGate.decide(
                                    caseRollouts(run.id(), run.repeats()), passHatKMinLowerBound);
                    if (!d.applicable()) {
                        // Fail-closed: no per-case repetition data to verify.
                        return GateEvaluation.blocked(
                                gate,
                                datasetName,
                                versionId,
                                GateEvaluation.Reason.RUN_FAILED,
                                "Latest eval run of \""
                                        + datasetName
                                        + "\" has no per-case repetition "
                                        + "data, so pass^k reliability cannot be verified - re-run the dataset.",
                                run.id(),
                                "PASSED",
                                run.overallScore(),
                                run.finishedAt());
                    }
                    if (!d.passed()) {
                        return GateEvaluation.blocked(
                                gate,
                                datasetName,
                                versionId,
                                GateEvaluation.Reason.RUN_FAILED,
                                String.format(
                                        "Latest eval run of \"%s\" passed on the mean score but is not "
                                                + "reliable enough: only %d of %d cases passed all %d repetitions "
                                                + "(pass^k %.2f, 95%% lower bound %.2f < required %.2f). "
                                                + "Fix the flaky cases and re-run the dataset.",
                                        datasetName,
                                        d.casesAllPassed(),
                                        d.cases(),
                                        run.repeats(),
                                        d.passHatK(),
                                        d.lowerBound(),
                                        d.threshold()),
                                run.id(),
                                "PASSED",
                                run.overallScore(),
                                run.finishedAt());
                    }
                }
                if (gate.isRequireSimulation()) {
                    GateEvaluation simBlock =
                            evaluateSimulationLeg(gate, datasetName, versionId, run);
                    if (simBlock != null) {
                        return simBlock;
                    }
                    // Evaluate again cheaply - the sim leg qualified; fetch the
                    // session id for the response without a second query path.
                    SimLeg sim = latestQualifyingSimulation(agentId, versionId).orElse(null);
                    return GateEvaluation.passed(
                            gate,
                            datasetName,
                            versionId,
                            run.id(),
                            run.overallScore(),
                            run.finishedAt(),
                            sim != null ? sim.sessionId() : null,
                            sim != null ? sim.lastCompletedAt() : null);
                }
                return GateEvaluation.passed(
                        gate,
                        datasetName,
                        versionId,
                        run.id(),
                        run.overallScore(),
                        run.finishedAt());
            }
        }
    }

    /**
     * The staging leg (V394): the latest simulation session of this agent whose judged runs were
     * bound to this exact configuration decides, with the same "latest wins" posture as the eval
     * leg - a PASSED staging round followed by a FAILED one is a regression, not a pass. Any FAILED
     * persona blocks; INCONCLUSIVE blocks rather than passes; only an all-PASSED judged session
     * inside the freshness window qualifies.
     *
     * <p>Returns {@code null} when the leg passes; otherwise the blocking evaluation carrying both
     * the eval run that passed and the sim session that did not.
     */
    private GateEvaluation evaluateSimulationLeg(
            AgentEvalGate gate, String datasetName, UUID versionId, RunRow evalRun) {
        Optional<SimLeg> latest = latestQualifyingSimulation(gate.getAgentId(), versionId);
        if (latest.isEmpty()) {
            return GateEvaluation.blockedSimulation(
                    gate,
                    datasetName,
                    versionId,
                    GateEvaluation.Reason.SIM_NO_QUALIFYING_RUN,
                    "This gate requires simulation staging, but no judged simulation session has run "
                            + "this configuration. Run a simulation session against the candidate first - "
                            + "runs bind themselves to the version under test.",
                    evalRun.id(),
                    evalRun.overallScore(),
                    evalRun.finishedAt(),
                    null,
                    null,
                    null);
        }
        SimLeg sim = latest.get();
        if (sim.failed() > 0) {
            return GateEvaluation.blockedSimulation(
                    gate,
                    datasetName,
                    versionId,
                    GateEvaluation.Reason.SIM_FAILED,
                    String.format(
                            "Latest simulation session on this configuration had %d FAILED persona(s) "
                                    + "(%d passed, %d inconclusive) - the candidate did not survive staging. "
                                    + "Fix the agent and re-run the simulation.",
                            sim.failed(), sim.passed(), sim.inconclusive()),
                    evalRun.id(),
                    evalRun.overallScore(),
                    evalRun.finishedAt(),
                    sim.sessionId(),
                    "FAILED",
                    sim.lastCompletedAt());
        }
        if (sim.inconclusive() > 0) {
            return GateEvaluation.blockedSimulation(
                    gate,
                    datasetName,
                    versionId,
                    GateEvaluation.Reason.SIM_INCONCLUSIVE,
                    String.format(
                            "Latest simulation session on this configuration measured nothing "
                                    + "for %d persona(s) (verdict INCONCLUSIVE - judges unavailable or every "
                                    + "turn errored). An inconclusive session is not a pass - re-run once the "
                                    + "judge provider is healthy.",
                            sim.inconclusive()),
                    evalRun.id(),
                    evalRun.overallScore(),
                    evalRun.finishedAt(),
                    sim.sessionId(),
                    "INCONCLUSIVE",
                    sim.lastCompletedAt());
        }
        if (gate.getMaxSimulationAgeHours() != null
                && sim.lastCompletedAt() != null
                && sim.lastCompletedAt()
                        .isBefore(
                                Instant.now()
                                        .minus(
                                                Duration.ofHours(
                                                        gate.getMaxSimulationAgeHours())))) {
            return GateEvaluation.blockedSimulation(
                    gate,
                    datasetName,
                    versionId,
                    GateEvaluation.Reason.SIM_STALE,
                    "Latest all-PASSED simulation session is older than the gate's "
                            + gate.getMaxSimulationAgeHours()
                            + "h freshness window - re-run the simulation.",
                    evalRun.id(),
                    evalRun.overallScore(),
                    evalRun.finishedAt(),
                    sim.sessionId(),
                    "PASSED",
                    sim.lastCompletedAt());
        }
        return null;
    }

    /**
     * The gate decision for the agent's CURRENT live config (its head version) - what the console's
     * gate badge needs: "would promoting what we are running right now pass?". Empty when the agent
     * has never been versioned (nothing to evaluate against).
     */
    @Transactional(readOnly = true)
    public Optional<GateEvaluation> evaluateHead(UUID agentId) {
        return headVersionLookup
                .findHeadVersionId(agentId)
                .map(headId -> evaluate(agentId, headId));
    }

    // ------------------------------------------------------------------
    // Native reads (module boundary - see class javadoc)
    // ------------------------------------------------------------------

    private boolean versionBelongsToAgent(UUID agentId, UUID versionId) {
        @SuppressWarnings("unchecked")
        List<Object> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT agent_id FROM agent_version WHERE id = CAST(:id AS uuid)")
                        .setParameter("id", versionId.toString())
                        .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) {
            return false;
        }
        return agentId.equals(rows.get(0));
    }

    /**
     * The newest SUCCEEDED run of the gate's dataset bound to a version whose config is
     * byte-identical to the version under evaluation AND snapshotted against the dataset's CURRENT
     * content hash ({@code dataset_hash = :datasetHash}, V343). Runs from before V260 (null version
     * binding) or V343 (null hash) can never qualify - they prove a config was tested but cannot
     * prove WHICH config, or WHICH dataset content.
     */
    private Optional<RunRow> latestQualifyingRun(
            UUID datasetId, UUID agentId, UUID versionId, String datasetHash) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT r.id, r.verdict, r.overall_score, r.finished_at, r.repeats "
                                        + "FROM eval_run r "
                                        + "WHERE r.dataset_id = CAST(:datasetId AS uuid) "
                                        + "  AND r.status = 'SUCCEEDED' "
                                        + "  AND r.dataset_hash = :datasetHash "
                                        + "  AND r.agent_version_id IN ("
                                        + "      SELECT v.id FROM agent_version v "
                                        + "      WHERE v.agent_id = CAST(:agentId AS uuid) "
                                        + "        AND v.config_json = (SELECT t.config_json FROM agent_version t "
                                        + "                             WHERE t.id = CAST(:versionId AS uuid))) "
                                        + "ORDER BY r.finished_at DESC NULLS LAST, r.created_at DESC "
                                        + "LIMIT 1")
                        .setParameter("datasetId", datasetId.toString())
                        .setParameter("agentId", agentId.toString())
                        .setParameter("versionId", versionId.toString())
                        .setParameter("datasetHash", datasetHash)
                        .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        Double score =
                row[2] instanceof BigDecimal bd
                        ? bd.doubleValue()
                        : row[2] instanceof Number n ? n.doubleValue() : null;
        Instant finishedAt =
                row[3] instanceof Timestamp ts
                        ? ts.toInstant()
                        : row[3] instanceof Instant i ? i : null;
        int repeats = row.length > 4 && row[4] instanceof Number n ? n.intValue() : 1;
        return Optional.of(new RunRow((UUID) row[0], (String) row[1], score, finishedAt, repeats));
    }

    /**
     * Per-case repetition outcomes of one run, rebuilt from the {@code REPEATS} pseudo-dimension
     * the run worker persists in {@code scores_json} ({@code score = passVotes / repetitions}).
     *
     * <p>FAIL-CLOSED: a case that cannot be measured (status not SCORED - every repetition errored
     * or was unavailable - or no readable {@code REPEATS} score, or unparseable JSON) is counted as
     * a case with zero passes, never silently dropped, because dropping it would inflate the pass^k
     * fraction. Each such case is logged.
     */
    List<PassHatKGate.CaseRollouts> caseRollouts(UUID runId, int repeats) {
        @SuppressWarnings("unchecked")
        List<Object> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT c.status, c.scores_json FROM eval_run_case_result c "
                                        + "WHERE c.run_id = CAST(:runId AS uuid)")
                        .setParameter("runId", runId.toString())
                        .getResultList();
        List<PassHatKGate.CaseRollouts> out = new ArrayList<>();
        int unmeasured = 0;
        for (Object row : rows) {
            Object[] cols = row instanceof Object[] arr ? arr : null;
            Object status = cols != null && cols.length > 0 ? cols[0] : null;
            Object json = cols != null && cols.length > 1 ? cols[1] : null;
            Integer passes = null;
            if ("SCORED".equals(status) && json instanceof String doc) {
                try {
                    JsonNode score = MAPPER.readTree(doc).path("REPEATS").path("score");
                    if (score.isNumber()) {
                        passes = (int) Math.round(score.asDouble() * repeats);
                    }
                } catch (Exception e) {
                    log.warn("pass^k gate: unparseable scores_json for a case of run {}", runId);
                }
            }
            if (passes == null) {
                unmeasured++;
                passes = 0;
            }
            out.add(new PassHatKGate.CaseRollouts(repeats, passes));
        }
        if (unmeasured > 0) {
            log.warn(
                    "pass^k gate: run {} has {} case(s) without a measurable REPEATS score; "
                            + "counted as not-all-passed (fail-closed)",
                    runId,
                    unmeasured);
        }
        return out;
    }

    /**
     * Whether ANY SUCCEEDED run of the gated dataset tested this configuration - same
     * version-config equality as {@link #latestQualifyingRun} but WITHOUT the dataset-hash match.
     * True + empty qualifying run = the dataset was edited after its last run ({@code
     * Reason.DATASET_STALE}); false = {@code NO_QUALIFYING_RUN}.
     */
    private boolean anyRunForConfig(UUID datasetId, UUID agentId, UUID versionId) {
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

    /**
     * The latest simulation session of this agent containing at least one JUDGED run ({@code
     * verdict IS NOT NULL} - a completed conversation the judge scored; ERROR/CANCELLED runs carry
     * no verdict and can never testify either way) bound to a version whose config_json is
     * byte-identical to the candidate's - the same content binding {@link #latestQualifyingRun}
     * uses for eval runs, applied to {@code simulation_run.agent_version_id} (V344). Sessions whose
     * runs judged a different configuration contribute nothing - a persona that passed an OLD
     * prompt says nothing about THIS one. Verdict counts aggregate the session's matching judged
     * runs; {@code lastCompletedAt} is their latest completion.
     */
    private Optional<SimLeg> latestQualifyingSimulation(UUID agentId, UUID versionId) {
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

    private String datasetName(AgentEvalGate gate) {
        EvalDataset dataset = entityManager.find(EvalDataset.class, gate.getDatasetId());
        return dataset != null ? dataset.getName() : gate.getDatasetId().toString();
    }

    private static String scoreSuffix(RunRow run) {
        return run.overallScore() != null ? String.format(" (score %.3f)", run.overallScore()) : "";
    }

    /**
     * One simulation session's judged-run aggregate on the candidate config - the staging verdict.
     */
    private record SimLeg(
            UUID sessionId, int passed, int failed, int inconclusive, Instant lastCompletedAt) {}

    private record RunRow(
            UUID id, String verdict, Double overallScore, Instant finishedAt, int repeats) {}
}
