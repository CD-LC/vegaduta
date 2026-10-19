package com.agenticai.core.agents.experiment;

import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.agents.experiment.AgentExperimentDtos.CreateExperimentRequest;
import com.agenticai.core.agents.experiment.AgentExperimentDtos.ExperimentMetricsResponse;
import com.agenticai.core.agents.experiment.AgentExperimentDtos.ExperimentResponse;
import com.agenticai.core.agents.experiment.AgentExperimentDtos.StopExperimentRequest;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for A/B experiments on agent versions:
 *
 * <ul>
 *   <li>POST   /api/agents/{agentId}/experiments - create (DRAFT)</li>
 *   <li>GET    /api/agents/{agentId}/experiments - list, newest first</li>
 *   <li>GET    /api/agents/{agentId}/experiments/{experimentId} - one experiment</li>
 *   <li>POST   /api/agents/{agentId}/experiments/{experimentId}/start</li>
 *   <li>POST   /api/agents/{agentId}/experiments/{experimentId}/stop</li>
 *   <li>GET    /api/agents/{agentId}/experiments/{experimentId}/metrics</li>
 * </ul>
 *
 * <p>Follows {@code EvalGateController}'s conventions: no extra security
 * annotations (access is enforced inside {@code AgentExperimentService} via
 * {@code AgentService#requireAccess}), no {@code SecurityConfig} change -
 * {@code /api/**} already falls through to authenticated.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/experiments")
public class AgentExperimentController {

    private final AgentExperimentService experimentService;

    public AgentExperimentController(AgentExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @PostMapping
    public ResponseEntity<ExperimentResponse> create(
        @PathVariable UUID agentId, @RequestBody CreateExperimentRequest request
    ) {
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        AgentExperiment experiment = experimentService.create(
            agentId, request.name(), request.treatmentVersionId(),
            request.trafficPercent() == null ? 0 : request.trafficPercent(),
            request.autoStopErrorThresholdPct(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ExperimentResponse.of(experiment));
    }

    @GetMapping
    public ResponseEntity<List<ExperimentResponse>> list(@PathVariable UUID agentId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            experimentService.list(agentId).stream().map(ExperimentResponse::of).toList(),
            limit, offset, 50, 500);
    }

    @GetMapping("/{experimentId}")
    public ExperimentResponse get(@PathVariable UUID agentId, @PathVariable UUID experimentId) {
        return ExperimentResponse.of(experimentService.get(agentId, experimentId));
    }

    @PostMapping("/{experimentId}/start")
    public ExperimentResponse start(@PathVariable UUID agentId, @PathVariable UUID experimentId) {
        return ExperimentResponse.of(experimentService.start(agentId, experimentId));
    }

    @PostMapping("/{experimentId}/stop")
    public ExperimentResponse stop(
        @PathVariable UUID agentId, @PathVariable UUID experimentId, @RequestBody(required = false) StopExperimentRequest request
    ) {
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        String reason = request != null ? request.reason() : null;
        return ExperimentResponse.of(experimentService.stop(agentId, experimentId, reason, actor));
    }

    @GetMapping("/{experimentId}/metrics")
    public ExperimentMetricsResponse metrics(@PathVariable UUID agentId, @PathVariable UUID experimentId) {
        return ExperimentMetricsResponse.of(experimentService.metrics(agentId, experimentId));
    }
}
