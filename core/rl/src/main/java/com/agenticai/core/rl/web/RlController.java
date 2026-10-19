package com.agenticai.core.rl.web;

import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.policy.PolicyArmAdminService;
import com.agenticai.core.rl.policy.PolicyStore;
import com.agenticai.core.rl.export.RlExportService;
import com.agenticai.core.rl.export.TrajectoryExportService;
import com.agenticai.core.rl.report.RlReportingService;
import com.agenticai.core.rl.web.dto.PolicyArmRequest;
import com.agenticai.core.rl.web.dto.PolicyArmResponse;
import com.agenticai.core.rl.web.dto.PolicyUpdateRequest;
import com.agenticai.core.rl.web.dto.RlPreferenceExport;
import com.agenticai.core.rl.web.dto.RlSummaryResponse;
import com.agenticai.core.rl.web.dto.RlSummaryResponse.PolicyState;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Agent Learning surface consumed by the web {@code AgentLearningPanel}:
 * a read-only dashboard summary any signed-in tenant user can view, plus the
 * per-dimension policy editor.
 *
 * <p>Paths match the delivered frontend ({@code api.getRlSummary} /
 * {@code getRlPolicies} / {@code updateRlPolicy}). {@code /summary} is under
 * {@code SecurityConfig}'s {@code anyRequest().authenticated()} (the panel's
 * read view); the {@code /policies} GET+PUT are pinned to
 * {@code ROLE_tenant-admin} by an explicit matcher there - enabling exploration
 * changes how the agent behaves, so it stays an admin action even though it
 * shares the {@code /api/rl} prefix (the panel also gates the controls behind
 * {@code isAdmin} client-side; this is the server-side enforcement).
 *
 * <p>The per-dimension ARM management endpoints
 * ({@code GET/POST .../policies/{dimension}/arms},
 * {@code DELETE .../policies/{dimension}/arms/{armId}}) complete that editor.
 * Without them a policy could be enabled but had no way to be given anything
 * to choose between, which is exactly the state production was found in:
 * {@code MODEL_ROUTE} enabled with zero arms, so the bandit returned no
 * decision on every turn and every piece of human feedback trained nothing.
 * They fall under the same {@code /api/rl/agents/*&#47;policies/**} matcher and
 * so carry the same {@code ROLE_tenant-admin} gate as the PUT.
 */
@RestController
@RequestMapping("/api/rl")
public class RlController {

    private final RlReportingService reportingService;
    private final PolicyStore policyStore;
    private final RlExportService exportService;
    private final TrajectoryExportService trajectoryExportService;
    private final PolicyArmAdminService armAdminService;

    public RlController(
        RlReportingService reportingService,
        PolicyStore policyStore,
        RlExportService exportService,
        TrajectoryExportService trajectoryExportService,
        PolicyArmAdminService armAdminService
    ) {
        this.reportingService = reportingService;
        this.policyStore = policyStore;
        this.exportService = exportService;
        this.trajectoryExportService = trajectoryExportService;
        this.armAdminService = armAdminService;
    }

    @GetMapping("/agents/{agentId}/summary")
    public RlSummaryResponse summary(@PathVariable UUID agentId) {
        return reportingService.summarize(agentId);
    }

    @GetMapping("/agents/{agentId}/policies")
    public List<PolicyState> policies(@PathVariable UUID agentId) {
        return reportingService.policyStates(agentId);
    }

    @PutMapping("/agents/{agentId}/policies/{dimension}")
    public PolicyState updatePolicy(
        @PathVariable UUID agentId,
        @PathVariable PolicyDimension dimension,
        @Valid @RequestBody PolicyUpdateRequest request
    ) {
        AgentPolicy updated = policyStore.updatePolicy(
            agentId, dimension, request.enabled(), request.explorationRate(), request.algorithm());
        return reportingService.toPolicyState(updated);
    }

    // ---------------------------------------------------------------------
    // Arm management. Toggling a policy on with no arms is a silent no-op
    // (ContextualBanditService#selectArm returns empty when there are no
    // candidates) - that is the production bug these three endpoints fix, so
    // they sit directly beside the toggle they complete. All three are
    // tenant-admin: the SecurityConfig matcher for the PUT above is
    // "/api/rl/agents/*/policies/**", whose ** already spans the extra
    // /{dimension}/arms[/{armId}] segments, so they inherit exactly the same
    // ROLE_tenant-admin gate with no new rule to keep in sync. That is the
    // level they deserve on their own merits anyway - adding a MODEL_ROUTE arm
    // changes which model a tenant's live traffic runs on.

    /**
     * This agent+dimension's arms. Side-effect-free: an agent with no policy
     * row yet returns {@code []} rather than seeding one (unlike the policy
     * editor's GET, which must seed so every dimension is present to toggle).
     */
    @GetMapping("/agents/{agentId}/policies/{dimension}/arms")
    public List<PolicyArmResponse> arms(@PathVariable UUID agentId, @PathVariable PolicyDimension dimension) {
        return armAdminService.list(agentId, dimension);
    }

    /**
     * Adds one arm. Validated against what the runtime actually reads before
     * anything is written - see {@code PolicyArmAdminService#create} and
     * {@code RlArmConfigValidator}. Rejections are 4xx with an operator-
     * readable reason (400 malformed/unusable, 403 FREE plan, 404 unknown
     * agent, 409 duplicate arm key), never a silently-ignored write.
     */
    @PostMapping("/agents/{agentId}/policies/{dimension}/arms")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyArmResponse addArm(
        @PathVariable UUID agentId,
        @PathVariable PolicyDimension dimension,
        @Valid @RequestBody PolicyArmRequest request
    ) {
        return armAdminService.create(agentId, dimension, request);
    }

    /** Retires one arm (and its learned posterior). 404 if it isn't an arm of this agent+dimension. */
    @DeleteMapping("/agents/{agentId}/policies/{dimension}/arms/{armId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteArm(
        @PathVariable UUID agentId,
        @PathVariable PolicyDimension dimension,
        @PathVariable UUID armId
    ) {
        armAdminService.delete(agentId, dimension, armId);
    }

    /**
     * Offline preference-optimization export (DPO pairs + KTO samples) for the
     * out-of-process Python trainer. Admin-only (pinned by the same
     * {@code SecurityConfig} matcher as {@code /policies}) - it returns raw
     * conversation text reconstructed from feedback + episodes.
     */
    @GetMapping("/agents/{agentId}/preferences")
    public RlPreferenceExport preferences(
        @PathVariable UUID agentId,
        @RequestParam(name = "limit", defaultValue = "500") int limit
    ) {
        return exportService.export(agentId, Math.max(1, Math.min(limit, 5000)));
    }

    /**
     * {@code GET /agents/{agentId}/export/trajectories} - NDJSON, one line per
     * recorded episode with its trajectory steps inlined (prompt, tool calls,
     * tool results, answer, reward). The "JSONL export of evals and traces"
     * feed for offline prompt/skill optimization and the {@code rl-trainer}
     * service. Admin-only via the {@code /api/rl/agents/*&#47;export/**}
     * {@code SecurityConfig} matcher - it returns raw conversation text.
     */
    @GetMapping(value = "/agents/{agentId}/export/trajectories", produces = "application/x-ndjson")
    public org.springframework.http.ResponseEntity<String> trajectoriesJsonl(
        @PathVariable UUID agentId,
        @RequestParam(name = "limit", defaultValue = "500") int limit
    ) {
        String body = trajectoryExportService.trajectoriesJsonl(agentId, Math.max(1, Math.min(limit, 2000)));
        return org.springframework.http.ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"trajectories-" + agentId + ".jsonl\"")
            .body(body);
    }

    /**
     * {@code GET /agents/{agentId}/export/preferences} - the DPO/KTO export of
     * {@link #preferences} as NDJSON (one training sample per line), for
     * piping straight into an offline preference trainer.
     */
    @GetMapping(value = "/agents/{agentId}/export/preferences", produces = "application/x-ndjson")
    public org.springframework.http.ResponseEntity<String> preferencesJsonl(
        @PathVariable UUID agentId,
        @RequestParam(name = "limit", defaultValue = "500") int limit
    ) {
        String body = trajectoryExportService.preferencesJsonl(agentId, Math.max(1, Math.min(limit, 5000)));
        return org.springframework.http.ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"preferences-" + agentId + ".jsonl\"")
            .body(body);
    }
}
