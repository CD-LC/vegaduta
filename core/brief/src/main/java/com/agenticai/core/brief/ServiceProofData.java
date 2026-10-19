package com.agenticai.core.brief;

import com.agenticai.core.agents.experiment.AgentExperiment;
import com.agenticai.core.agents.experiment.AgentExperimentService;
import com.agenticai.core.agents.experiment.ExperimentMetricsReader;
import com.agenticai.core.audit.AuditLogEntry;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.quality.dataset.EvalRun;
import com.agenticai.core.quality.dataset.EvalRunConflictException;
import com.agenticai.core.quality.dataset.EvalRunService;
import com.agenticai.core.quality.dataset.EvalRunStatus;
import com.agenticai.core.quality.dataset.EvalRunVerdict;
import com.agenticai.core.quality.stats.EvalReliabilityService;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.DatasetNoiseFloorResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.NoiseFloorResponse;
import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.repo.TurnFeedbackRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link ProofData} over the real services: eval runs and the noise floor from {@code core/quality} (the same
 * {@link EvalReliabilityService} the quality console reads), thumbs from {@code core/rl}'s {@code turn_feedback},
 * experiment arms from {@code core/agents}, guardrail strikes from the audit log.
 *
 * <p><b>Why experiments are measured here and not through {@code AgentExperimentService#metrics}.</b> That method's
 * feedback query filters {@code turn_feedback.signal} on {@code 'UP'}/{@code 'DOWN'} while the column holds the
 * {@link FeedbackSignal} names ({@code THUMBS_UP}/{@code THUMBS_DOWN}), so its thumbs numbers are always empty. This
 * class reads the same table with the real names, over the experiment's own window; the call counts still come
 * from {@link ExperimentMetricsReader}.
 */
@Service
public class ServiceProofData implements ProofData {

    private static final Logger log = LoggerFactory.getLogger(ServiceProofData.class);

    /** Audit action recording one guardrail strike against a brief's agent; target is the brief. */
    static final String STRIKE_ACTION = "BRIEF_GUARDRAIL_STRIKE";
    private static final int MAX_RUNS = 100;

    private final EvalRunService evalRunService;
    private final EvalReliabilityService reliabilityService;
    private final TurnFeedbackRepository feedbackRepository;
    private final ExperimentMetricsReader metricsReader;
    private final AgentExperimentService experimentService;
    private final AuditLogService auditLogService;

    public ServiceProofData(
        EvalRunService evalRunService,
        EvalReliabilityService reliabilityService,
        TurnFeedbackRepository feedbackRepository,
        ExperimentMetricsReader metricsReader,
        AgentExperimentService experimentService,
        AuditLogService auditLogService
    ) {
        this.evalRunService = evalRunService;
        this.reliabilityService = reliabilityService;
        this.feedbackRepository = feedbackRepository;
        this.metricsReader = metricsReader;
        this.experimentService = experimentService;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------------ eval

    @Override
    public List<EvalRunPoint> evalRuns(String datasetId) {
        List<EvalRunPoint> out = new ArrayList<>();
        for (EvalRun run : evalRunService.listRuns(UUID.fromString(datasetId), MAX_RUNS)) {
            if (run.getStatus() != EvalRunStatus.SUCCEEDED || run.getOverallScore() == null) {
                continue;
            }
            EvalRunVerdict verdict = run.getVerdict();
            out.add(new EvalRunPoint(run.getId().toString(), run.getCreatedAt(), run.getOverallScore(),
                run.getPassThreshold(), verdict == EvalRunVerdict.PASSED, verdict == null ? null : verdict.name()));
        }
        Collections.reverse(out);
        return out;
    }

    @Override
    public Double noiseFloor(String agentId, String datasetId) {
        DatasetNoiseFloorResponse response = reliabilityService.noiseFloorForDataset(
            UUID.fromString(agentId), UUID.fromString(datasetId));
        NoiseFloorResponse floor = response.noiseFloor();
        if (floor == null) {
            return null;
        }
        if (floor.configurationSuspect()) {
            // The two "identical" runs differed systematically (the agent changed between them): that is drift, not a
            // floor, and the reliability module says not to use it quietly. No floor means no significance claim.
            log.info("Dataset {}: noise floor withheld - the two latest runs differ systematically", datasetId);
            return null;
        }
        return floor.floor();
    }

    @Override
    public String startEvalRun(String agentId, String datasetId) {
        try {
            return evalRunService.triggerRun(UUID.fromString(agentId), UUID.fromString(datasetId)).getId().toString();
        } catch (EvalRunConflictException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ traffic and feedback

    @Override
    public int realRuns(List<String> agentIds, Instant since) {
        long total = 0;
        for (String id : agentIds) {
            Object[] row = metricsReader.queryLlmCallStats(UUID.fromString(id), since, null);
            total += row == null || row.length == 0 || row[0] == null ? 0 : ((Number) row[0]).longValue();
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    @Override
    public Thumbs thumbs(List<String> agentIds, Instant since) {
        long up = 0;
        long down = 0;
        for (String id : agentIds) {
            UUID agent = UUID.fromString(id);
            up += feedbackRepository.countByAgentIdAndSignalAndCreatedAtGreaterThanEqual(agent, FeedbackSignal.THUMBS_UP, since);
            down += feedbackRepository.countByAgentIdAndSignalAndCreatedAtGreaterThanEqual(agent, FeedbackSignal.THUMBS_DOWN, since);
        }
        return new Thumbs(up, down);
    }

    // ------------------------------------------------------------------ experiments

    @Override
    public List<ExperimentRow> experiments(String agentId) {
        UUID agent = UUID.fromString(agentId);
        List<AgentExperiment> rows;
        try {
            rows = experimentService.list(agent);
        } catch (RuntimeException e) {
            // Most likely the caller may not view this agent's configuration: show nothing, invent nothing.
            log.debug("Experiments of agent {} not readable: {}", agentId, e.getMessage());
            return List.of();
        }
        List<ExperimentRow> out = new ArrayList<>();
        for (AgentExperiment e : rows) {
            Instant from = e.getStartedAt() != null ? e.getStartedAt() : e.getCreatedAt();
            Instant to = e.getStoppedAt() != null ? e.getStoppedAt() : Instant.now();
            out.add(new ExperimentRow(e.getId().toString(), agentId, e.getName(), e.getStatus().name(), e.getCreatedAt(),
                variant(e.getControlAgentId(), from, to), variant(e.getTreatmentAgentId(), from, to)));
        }
        return out;
    }

    private Variant variant(UUID agentId, Instant from, Instant to) {
        Object[] calls = metricsReader.queryLlmCallStats(agentId, from, to);
        long callCount = num(calls, 0);
        long errors = num(calls, 3);
        long up = feedbackRepository.countByAgentIdAndSignalAndCreatedAtBetween(agentId, FeedbackSignal.THUMBS_UP, from, to);
        long down = feedbackRepository.countByAgentIdAndSignalAndCreatedAtBetween(agentId, FeedbackSignal.THUMBS_DOWN, from, to);
        long rated = up + down;
        return new Variant(agentId.toString(), callCount, rated,
            rated == 0 ? null : 100.0 * up / rated, callCount == 0 ? null : 100.0 * errors / callCount);
    }

    private static long num(Object[] row, int index) {
        return row == null || row.length <= index || row[index] == null ? 0L : ((Number) row[index]).longValue();
    }

    // ------------------------------------------------------------------ strikes

    @Override
    public int strikes(UUID briefId, Instant since) {
        String id = briefId.toString();
        int count = 0;
        for (AuditLogEntry entry : auditLogService.list(STRIKE_ACTION, "brief", since, null)) {
            if (id.equals(entry.getTargetId())) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void recordStrike(UUID briefId, String agentId, String detail) {
        auditLogService.record("system:brief-trust", STRIKE_ACTION, "brief", briefId.toString(), AuditOutcome.SUCCESS,
            "agent=" + agentId + " " + detail);
    }
}
