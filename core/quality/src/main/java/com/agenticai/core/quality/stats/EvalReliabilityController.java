package com.agenticai.core.quality.stats;

import com.agenticai.core.quality.stats.dto.ReliabilityDtos.AgentReliabilityResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.DatasetNoiseFloorResponse;
import com.agenticai.core.quality.stats.dto.ReliabilityDtos.PairedComparisonResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * READ-ONLY REST surface for eval reliability statistics - the endpoints the
 * quality console uses to render a score as {@code 0.62 [0.48-0.75], n=5}
 * instead of {@code 0.62}.
 *
 * <ul>
 *   <li>GET /api/agents/{agentId}/quality/reliability - per-dataset metrics,
 *       intervals, per-case detail and the measured noise floor</li>
 *   <li>GET /api/agents/{agentId}/quality/reliability/noise-floor - the measured
 *       rerun-to-rerun noise floor for one dataset</li>
 *   <li>GET /api/agents/{agentId}/quality/reliability/compare - paired A/B of
 *       two runs of the same dataset, with a verdict that is allowed to be
 *       "cannot tell"</li>
 * </ul>
 *
 * <p><strong>Why a new controller rather than four more methods on
 * {@code QualityController}.</strong> That class is the pre-existing
 * summary/history/statistics/config surface and is owned by the quality
 * workstream; this is a different concern (statistical treatment of eval
 * history), has its own DTO family, and would have doubled the file. Same trade
 * {@code EvalDatasetController} made when it nested the dataset surface under
 * {@code /quality/datasets} rather than growing {@code QualityController}. The
 * URL family stays unified so the console's quality page still owns one
 * coherent set of paths.
 *
 * <p><strong>Nothing here writes.</strong> No rollout is executed, no run is
 * triggered, no row is stored: every number is computed on read from eval
 * history that already exists. That is deliberate for a first cut - an endpoint
 * that could trigger k rollouts is an endpoint that can spend a tenant's model
 * budget, and metering that belongs with the run executor (and the budget
 * governor), not in a statistics reader.
 *
 * <p><strong>Security.</strong> No {@code SecurityConfig} change is needed: a
 * new {@code /api/**} route falls through to
 * {@code .anyRequest().authenticated()}. The plan gate mirrors
 * {@code QualityController#assertPremiumPlan} exactly - this surface is a
 * statistically-honest VIEW of the same judge output and must never be a cheaper
 * route to it than the endpoints it sits beside. Per-resource ownership checks
 * are consciously absent, matching the rest of this module; tenant isolation
 * comes from schema-per-tenant routing, and agent scoping is enforced in the
 * service (a run belonging to another agent is reported as NOT FOUND).
 */
@RestController
@RequestMapping("/api/agents/{agentId}/quality/reliability")
public class EvalReliabilityController {

    private final EvalReliabilityService reliabilityService;
    private final TenantLimits tenantLimits;
    // 2026-10-02 research ungating: app.evals.free-plan-enabled opens the
    // reliability-statistics reads (pass^k, Wilson intervals, paired
    // comparison) to FREE tenants - the "make them the default for
    // researchers" item. All endpoints here are reads over stored results.
    private final boolean freePlanEnabled;

    public EvalReliabilityController(
        EvalReliabilityService reliabilityService,
        TenantLimits tenantLimits,
        @org.springframework.beans.factory.annotation.Value("${app.evals.free-plan-enabled:true}")
        boolean freePlanEnabled
    ) {
        this.reliabilityService = reliabilityService;
        this.tenantLimits = tenantLimits;
        this.freePlanEnabled = freePlanEnabled;
    }

    /**
     * FREE-tier gate - copied from {@code QualityController#assertPremiumPlan}
     * (see its javadoc for the 2026-07-31 C1-audit history), not shared with it,
     * for the reason {@code EvalDatasetController} already records: hoisting the
     * helper into a common base would mean editing a file owned by another
     * workstream purely for a refactor, and four lines are cheaper than the
     * coupling. A caller with no HTTP request context (none exist today) is
     * treated as UNLIMITED rather than NPE-ing - same posture as
     * {@code AgentService}.
     */
    private void assertPremiumPlan() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE && !freePlanEnabled) {
            throw new TenantLimitExceededException(
                "Eval reliability statistics (confidence intervals, pass^k, paired A/B) are a Premium "
                    + "feature - upgrade your plan to view them.");
        }
    }

    /**
     * GET /api/agents/{agentId}/quality/reliability
     *
     * <p>Per-dataset reliability for this agent, reconstructed from the most
     * recent SUCCEEDED runs of each of its datasets (each run contributes one
     * rollout per case - see {@link EvalReliabilityService} for the limits of
     * that reconstruction, all of which are also reported in the response's
     * {@code warnings}).
     *
     * <p>Always 200, even when the agent has no datasets or no runs: the empty
     * report with its {@code warnings} populated is a more useful answer than a
     * 404, because "we have nothing replicated yet" is exactly the state the
     * console needs to render prominently rather than treat as an error.
     *
     * @param maxRuns how many recent SUCCEEDED runs per dataset to pool;
     *     clamped to {@code [1, EvalReliabilityService.MAX_RUNS_PER_DATASET]}
     */
    @GetMapping
    public ResponseEntity<AgentReliabilityResponse> getReliability(
        @PathVariable UUID agentId,
        // Compile-time constant concatenation, so the documented default cannot
        // drift away from the service's own constant.
        @RequestParam(name = "maxRuns",
            defaultValue = "" + EvalReliabilityService.DEFAULT_MAX_RUNS_PER_DATASET) int maxRuns
    ) {
        assertPremiumPlan();
        return ResponseEntity.ok(reliabilityService.reportForAgent(agentId, maxRuns));
    }

    /**
     * GET /api/agents/{agentId}/quality/reliability/noise-floor?datasetId=..
     *
     * <p>The measured rerun-to-rerun noise floor for ONE dataset, addressable on
     * its own so a caller printing "this suite's floor" beside an A/B verdict does
     * not have to fetch a whole reliability report and discard it.
     *
     * <p>{@code noiseFloor} is null when the floor could not be measured (fewer
     * than two SUCCEEDED runs of that dataset, or no case scorable in both), with
     * {@code warnings} saying which. A null floor is NOT a floor of zero: a zero
     * floor would wave every delta through.
     *
     * @param datasetId the dataset to measure; 404 when it does not exist or
     *     belongs to a different agent
     */
    @GetMapping("/noise-floor")
    public ResponseEntity<DatasetNoiseFloorResponse> getNoiseFloor(
        @PathVariable UUID agentId,
        @RequestParam UUID datasetId
    ) {
        assertPremiumPlan();
        return ResponseEntity.ok(reliabilityService.noiseFloorForDataset(agentId, datasetId));
    }

    /**
     * GET /api/agents/{agentId}/quality/reliability/compare?baselineRunId=..&amp;variantRunId=..
     *
     * <p>Paired A/B between two SUCCEEDED runs of the same dataset. The response
     * carries a {@code verdict} that may be {@code CANNOT_TELL},
     * {@code INSIDE_NOISE_FLOOR} or {@code INSUFFICIENT_DATA}; the console must
     * render a winner badge ONLY for {@code VARIANT_BETTER} and
     * {@code BASELINE_BETTER}. The one-sentence {@code explanation} is generated
     * server-side so the UI, the API and the logs cannot tell three different
     * stories about the same delta.
     *
     * @param baselineRunId the incumbent run
     * @param variantRunId the challenger run; {@code candidateRunId} is accepted
     *     as an equivalent spelling because the console workstream independently
     *     settled on "candidate" for the same concept, and one of the two names
     *     had to bend. Accepting both is four lines and removes an integration
     *     failure mode; exactly one of them must be present
     */
    @GetMapping("/compare")
    public ResponseEntity<PairedComparisonResponse> compareRuns(
        @PathVariable UUID agentId,
        @RequestParam UUID baselineRunId,
        @RequestParam(required = false) UUID variantRunId,
        @RequestParam(required = false) UUID candidateRunId
    ) {
        assertPremiumPlan();
        UUID challenger = variantRunId != null ? variantRunId : candidateRunId;
        if (challenger == null) {
            throw new IllegalArgumentException(
                "Give the challenger run as variantRunId (or candidateRunId, its accepted alias).");
        }
        if (variantRunId != null && candidateRunId != null && !variantRunId.equals(candidateRunId)) {
            throw new IllegalArgumentException(
                "variantRunId and candidateRunId are aliases for the same parameter but were given "
                    + "different values (" + variantRunId + " vs " + candidateRunId + ").");
        }
        return ResponseEntity.ok(
            reliabilityService.compareRuns(agentId, baselineRunId, challenger));
    }
}
