package com.agenticai.core.quality;

import com.agenticai.core.quality.dto.QualityHistoryResponse;
import com.agenticai.core.quality.dto.QualitySummaryResponse;
import com.agenticai.core.quality.dto.QualityStatisticsResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for agent quality/eval endpoints.
 *
 * Provides:
 * - Summary of latest quality scores
 * - Historical quality data (for charts)
 * - Aggregated statistics
 * - Evaluation configuration (which evals are enabled)
 *
 * <p>Pricing/monetization audit (2026-07-31, checklist item C1): this whole
 * surface is PREMIUM+ only. It previously hardcoded {@code isPremium = false}
 * on {@code /config} and had no gate at all on the other three endpoints -
 * the last of the July 27 "six ungated modules" list. Now every endpoint
 * checks the tenant's real plan via {@link TenantLimits#plan(String)} and
 * FREE tenants get a 403 ({@link TenantLimitExceededException}, mapped by
 * {@code GlobalExceptionHandler}) with an upgrade-oriented message - the
 * exact same gate posture as {@code LlmCallLogController}'s tracing gate.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/quality")
public class QualityController {

    private final QualityEvaluationService qualityService;
    private final TenantLimits tenantLimits;
    // 2026-10-02 research ungating: app.evals.free-plan-enabled opens this
    // all-read surface (summary/history/statistics/config - no endpoint here
    // spends a model call) to FREE tenants. The spend-bearing knobs live in
    // EvalDatasetController's run trigger, which carries its own FREE clamps.
    private final boolean freePlanEnabled;

    public QualityController(
        QualityEvaluationService qualityService,
        TenantLimits tenantLimits,
        @org.springframework.beans.factory.annotation.Value("${app.evals.free-plan-enabled:true}")
        boolean freePlanEnabled
    ) {
        this.qualityService = qualityService;
        this.tenantLimits = tenantLimits;
        this.freePlanEnabled = freePlanEnabled;
    }

    /**
     * FREE-tier gate for the quality/evals surface - see this class's javadoc.
     * Mirrors {@code LlmCallLogController#assertPremiumPlan()} exactly:
     * tenantId can be null for callers with no HTTP request context (none
     * exist today, but this guards against silently skipping the check rather
     * than NPE-ing); such callers are treated as UNLIMITED, same posture as
     * {@code AgentService}. Returns the resolved plan so {@code /config} can
     * echo the real tier back to the caller.
     */
    private TenantPlan assertPremiumPlan() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE && !freePlanEnabled) {
            throw new TenantLimitExceededException(
                "Agent quality evaluations (scores, history, statistics) are a Premium feature - "
                    + "upgrade your plan to view them."
            );
        }
        return plan;
    }

    /**
     * GET /api/agents/{agentId}/quality/summary
     *
     * Get the latest quality summary for an agent.
     * Shows the most recent eval scores.
     */
    @GetMapping("/summary")
    public ResponseEntity<QualitySummaryResponse> getSummary(@PathVariable UUID agentId) {
        assertPremiumPlan();
        Optional<AgentQualityRecord> latest = qualityService.getLatestSummary(agentId);

        if (latest.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        AgentQualityRecord record = latest.get();
        QualitySummaryResponse response = new QualitySummaryResponse(
            record.getAgentId(),
            record.getOverallScore(),
            record.getScores(),
            record.getFeedback(),
            record.getTurnNumber(),
            record.getCreatedAt()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/agents/{agentId}/quality/history
     *
     * Get historical quality data for charting.
     * Returns timestamps and scores suitable for time-series visualization.
     *
     * @param limit max number of recent records (default 100)
     */
    @GetMapping("/history")
    public ResponseEntity<QualityHistoryResponse> getHistory(
        @PathVariable UUID agentId,
        @RequestParam(defaultValue = "100") int limit
    ) {
        assertPremiumPlan();
        Map<String, Object> data = qualityService.getHistoricalData(agentId, limit);

        @SuppressWarnings("unchecked")
        List<String> timestamps = (List<String>) data.get("timestamps");

        @SuppressWarnings("unchecked")
        List<Double> overallScores = (List<Double>) data.get("overall_scores");

        // Build per-type scores map
        Map<String, List<Double>> scoresByType = new HashMap<>();
        for (EvaluationType type : EvaluationType.values()) {
            String key = type.name().toLowerCase(java.util.Locale.ROOT) + "_scores";
            if (data.containsKey(key)) {
                @SuppressWarnings("unchecked")
                List<Double> scores = (List<Double>) data.get(key);
                scoresByType.put(type.getDisplayName(), scores);
            }
        }

        QualityHistoryResponse response = new QualityHistoryResponse(
            agentId,
            timestamps,
            overallScores,
            scoresByType,
            timestamps.size()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/agents/{agentId}/quality/statistics
     *
     * Get aggregated quality statistics.
     * Shows average scores, total evaluations, etc.
     */
    @GetMapping("/statistics")
    public ResponseEntity<QualityStatisticsResponse> getStatistics(@PathVariable UUID agentId) {
        assertPremiumPlan();
        QualityEvaluationService.QualityStatistics stats = qualityService.getStatistics(agentId);

        QualityStatisticsResponse response = new QualityStatisticsResponse(
            stats.agentId(),
            stats.totalEvaluations(),
            stats.averageQualityScore(),
            stats.averageScoresByType(),
            stats.generatedAt()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/agents/{agentId}/quality/config
     *
     * Get which evaluations are enabled for this agent (based on plan tier).
     * Shows what the tenant's current plan allows. Gated like the rest of
     * this surface (audit C1): FREE tenants get the 403 upgrade response,
     * so any caller that reaches the body is PREMIUM or UNLIMITED and gets
     * the full evaluation set with their real tier echoed back.
     */
    @GetMapping("/config")
    public ResponseEntity<QualityConfigResponse> getConfig(@PathVariable UUID agentId) {
        TenantPlan plan = assertPremiumPlan();

        List<EvaluationType> enabledTypes = EvalEngine.getEnabledForTier(plan != TenantPlan.FREE);

        QualityConfigResponse response = new QualityConfigResponse(
            agentId,
            enabledTypes,
            plan.name()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Configuration response showing which evaluations are enabled.
     */
    public record QualityConfigResponse(
        UUID agentId,
        List<EvaluationType> enabledEvaluations,
        String planTier
    ) {
    }
}
