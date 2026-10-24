package com.agenticai.core.brief;

import com.agenticai.core.brief.EnvoyPorts.GateState;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.quality.dataset.EvalDataset;
import com.agenticai.core.quality.dataset.EvalDatasetCase;
import com.agenticai.core.quality.dataset.EvalDatasetService;
import com.agenticai.core.quality.gate.EvalGateService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The eval half of {@link ServiceEnvoyPorts}: the brief's eval dataset (created, replaced case by case, deleted) and
 * the agent's quality gate. Goes through {@link EvalDatasetService} and {@link EvalGateService} only.
 */
@Component
class EnvoyEvalPorts {

    private static final Logger log = LoggerFactory.getLogger(EnvoyEvalPorts.class);

    private final EvalDatasetService datasetService;
    private final EvalGateService gateService;

    EnvoyEvalPorts(
            EvalDatasetService datasetService,
            EvalGateService gateService) {
        this.datasetService = datasetService;
        this.gateService = gateService;
    }

    // ------------------------------------------------------------------ eval + gate

    public String createEvalDataset(
            String agentId, String name, double passThreshold, List<Brief.EvalCase> cases) {
        UUID agent = UUID.fromString(agentId);
        EvalDataset dataset =
                datasetService.createDataset(
                        // No pinned mode: the dataset follows the workspace's default (the full pipeline as shipped).
                        agent, name, "Created from the solution's brief.", passThreshold, null);
        try {
            addCases(agent, dataset.getId(), cases);
        } catch (RuntimeException e) {
            try {
                datasetService.deleteDataset(agent, dataset.getId());
            } catch (RuntimeException cleanup) {
                log.warn(
                        "Could not remove eval dataset {} after a failed case insert",
                        dataset.getId(),
                        cleanup);
            }
            throw e;
        }
        return dataset.getId().toString();
    }

    public void updateEvalDataset(
            String datasetId, String name, double passThreshold, List<Brief.EvalCase> cases) {
        UUID id = UUID.fromString(datasetId);
        UUID agent = agentOfDataset(id);
        datasetService.updateDataset(agent, id, name, null, passThreshold, null);
        for (EvalDatasetCase existing : datasetService.listCases(id)) {
            datasetService.deleteCase(agent, id, existing.getId());
        }
        addCases(agent, id, cases);
    }

    public void deleteEvalDataset(String datasetId) {
        UUID id = UUID.fromString(datasetId);
        datasetService.deleteDataset(agentOfDataset(id), id);
    }

    public void armGate(String agentId, String datasetId, double minScore, Integer maxRunAgeHours) {
        UUID agent = UUID.fromString(agentId);
        UUID dataset = UUID.fromString(datasetId);
        EvalDataset existing = datasetService.requireDataset(agent, dataset);
        datasetService.updateDataset(
                agent, dataset, existing.getName(), existing.getDescription(), minScore, null);
        gateService.upsertGate(agent, dataset, true, maxRunAgeHours);
    }

    public GateState gateState(String agentId) {
        UUID agent = UUID.fromString(agentId);
        return gateService
                .findGate(agent)
                .map(
                        g -> {
                            double threshold =
                                    datasetService
                                            .findDataset(g.getDatasetId())
                                            .map(EvalDataset::getPassThreshold)
                                            .orElse(0.7);
                            return new GateState(
                                    g.getDatasetId().toString(), threshold, g.getMaxRunAgeHours());
                        })
                .orElse(null);
    }

    public void disarmGate(String agentId) {
        gateService.deleteGate(UUID.fromString(agentId));
    }

    private UUID agentOfDataset(UUID datasetId) {
        return datasetService
                .findDataset(datasetId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Eval dataset " + datasetId + " no longer exists."))
                .getAgentId();
    }

    private void addCases(UUID agent, UUID dataset, List<Brief.EvalCase> cases) {
        int position = 0;
        for (Brief.EvalCase c : cases) {
            String expected =
                    c.expected() != null && !c.expected().isBlank()
                            ? c.expected()
                            : "Rubric: " + c.rubric();
            datasetService.addCase(agent, dataset, c.input(), expected, null, position++);
        }
    }
}
