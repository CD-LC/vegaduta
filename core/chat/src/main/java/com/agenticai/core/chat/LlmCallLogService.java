package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one {@link LlmCallLog} row per real LLM call, called from {@link LlmMetricsAdvisor}
 * alongside (not instead of) the existing in-memory {@link LlmCallMetricsService} counters - see
 * {@code LlmCallLog}'s javadoc for why both exist. A logging failure here must never break the chat
 * turn that triggered it - {@link #record} swallows and logs any exception rather than propagating
 * it back into the advisor chain, matching this codebase's standing "a missing metric never breaks
 * the real feature" rule (same posture {@code AgentMemoryService} recall failures already follow).
 */
@Service
public class LlmCallLogService {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLogService.class);

    private final LlmCallLogRepository repository;
    private final LlmPricingCatalog pricingCatalog;

    public LlmCallLogService(LlmCallLogRepository repository, LlmPricingCatalog pricingCatalog) {
        this.repository = repository;
        this.pricingCatalog = pricingCatalog;
    }

    /** Setter-injected (optional): without it the monthly counter is simply not maintained. */
    private volatile LlmSpendCounterWriter counterWriter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCounterWriter(LlmSpendCounterWriter counterWriter) {
        this.counterWriter = counterWriter;
    }

    /** Setter-injected (optional) so slim contexts and direct-constructor tests need none. */
    private volatile List<HostedSpendListener> hostedSpendListeners = List.of();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHostedSpendListeners(List<HostedSpendListener> listeners) {
        this.hostedSpendListeners = listeners == null ? List.of() : List.copyOf(listeners);
    }

    /**
     * Pre-C1 signature kept for callers/tests that predate the {@code agent_version_id} pin -
     * delegates with a null pin, which the column tolerates (rows that predate versioning are
     * honestly "unknown").
     */
    public void record(
            UUID agentId,
            String sessionId,
            ProviderName providerName,
            String model,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            boolean success,
            String errorMessage,
            String requestId,
            String exceptionType,
            String stackTrace,
            long latencyMs,
            Instant startedAt,
            String experimentVariants,
            String traceId,
            String spanId,
            Long cacheCreationTokens,
            Long cacheReadTokens) {
        record(
                agentId,
                sessionId,
                providerName,
                model,
                promptTokens,
                completionTokens,
                totalTokens,
                success,
                errorMessage,
                requestId,
                exceptionType,
                stackTrace,
                latencyMs,
                startedAt,
                experimentVariants,
                traceId,
                spanId,
                cacheCreationTokens,
                cacheReadTokens,
                null,
                null);
    }

    /**
     * @param billableReasoningTokens reasoning ("thinking") tokens the provider reported OUTSIDE
     *     {@code completionTokens} and bills at the output rate - {@link
     *     ReasoningTokenAccounting#billableReasoningTokens()} verbatim (Gemini's {@code
     *     thoughtsTokenCount}, or an OpenAI-compatible endpoint that demonstrably excluded
     *     reasoning from completion). {@code null} for every provider that folds reasoning into
     *     completion already, and for failed calls with no usage. Without this the stored {@code
     *     estimated_cost_usd} under-prices thinking calls relative to the {@code llm.cost.usd}
     *     counter and the span metadata the same advisor emits - the 2026-10-05 audit found exactly
     *     that divergence.
     */
    public void record(
            UUID agentId,
            String sessionId,
            ProviderName providerName,
            String model,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            boolean success,
            String errorMessage,
            String requestId,
            String exceptionType,
            String stackTrace,
            long latencyMs,
            Instant startedAt,
            String experimentVariants,
            String traceId,
            String spanId,
            Long cacheCreationTokens,
            Long cacheReadTokens,
            UUID agentVersionId,
            Long billableReasoningTokens) {
        record(
                agentId, sessionId, providerName, model, promptTokens, completionTokens, totalTokens, success,
                errorMessage, requestId, exceptionType, stackTrace, latencyMs, startedAt, experimentVariants,
                traceId, spanId, cacheCreationTokens, cacheReadTokens, agentVersionId, billableReasoningTokens,
                null);
    }

    /**
     * As above, with the cost the advisor already worked out for this call. A hosted-pool row the
     * operator runs locally ({@code zero_cost}) is priced at zero by the advisor's own catalog; this
     * service only holds the shared catalog, so re-estimating here booked the unknown-model default
     * rate against a free local model - into the monthly spend counter and the hosted-spend cap too.
     *
     * @param estimatedCostUsdOverride the advisor's estimate; {@code null} re-estimates from the tokens
     */
    public void record(
            UUID agentId,
            String sessionId,
            ProviderName providerName,
            String model,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            boolean success,
            String errorMessage,
            String requestId,
            String exceptionType,
            String stackTrace,
            long latencyMs,
            Instant startedAt,
            String experimentVariants,
            String traceId,
            String spanId,
            Long cacheCreationTokens,
            Long cacheReadTokens,
            UUID agentVersionId,
            Long billableReasoningTokens,
            BigDecimal estimatedCostUsdOverride) {
        Runnable write =
                () ->
                        inTransaction(
                                () ->
                                        persist(
                                                agentId,
                                                sessionId,
                                                providerName,
                                                model,
                                                promptTokens,
                                                completionTokens,
                                                totalTokens,
                                                success,
                                                errorMessage,
                                                requestId,
                                                exceptionType,
                                                stackTrace,
                                                latencyMs,
                                                startedAt,
                                                experimentVariants,
                                                traceId,
                                                spanId,
                                                cacheCreationTokens,
                                                cacheReadTokens,
                                                agentVersionId,
                                                billableReasoningTokens,
                                                estimatedCostUsdOverride));
        if (!streamEnd.isAsync()) {
            write.run();
            return;
        }
        // The write runs on a pool thread (the advisor's stream-end hook runs on the HTTP client's
        // thread): carry the tenant schema over as data, as the advisor already does for its own
        // thread.
        Optional<TenantContext> tenant = TenantContext.current();
        streamEnd.run(() -> TenantScope.of(tenant).run(write));
    }

    private void inTransaction(Runnable work) {
        LlmCallLogTransactions tx = transactions;
        if (tx == null) {
            work.run();
        } else {
            tx.run(work);
        }
    }

    /**
     * Setter-injected (optional): absent in slim contexts and direct-constructor tests, which then
     * run inline.
     */
    private volatile StreamEndExecutor streamEnd = StreamEndExecutor.INLINE;

    private volatile LlmCallLogTransactions transactions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStreamEndExecutor(StreamEndExecutor executor) {
        this.streamEnd = executor == null ? StreamEndExecutor.INLINE : executor;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTransactions(LlmCallLogTransactions transactions) {
        this.transactions = transactions;
    }

    private void persist(
            UUID agentId,
            String sessionId,
            ProviderName providerName,
            String model,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            boolean success,
            String errorMessage,
            String requestId,
            String exceptionType,
            String stackTrace,
            long latencyMs,
            Instant startedAt,
            String experimentVariants,
            String traceId,
            String spanId,
            Long cacheCreationTokens,
            Long cacheReadTokens,
            UUID agentVersionId,
            Long billableReasoningTokens,
            BigDecimal estimatedCostUsdOverride) {
        try {
            // Priced with the real provider tag so llm.pricing.missing attributes the
            // durable-row estimate to the same provider the advisor's own estimate tags.
            BigDecimal estimatedCostUsd =
                    estimatedCostUsdOverride != null
                            ? estimatedCostUsdOverride
                            : pricingCatalog.estimateCostUsd(
                            providerName == null ? null : providerName.name(),
                            model,
                            promptTokens,
                            completionTokens,
                            cacheCreationTokens,
                            cacheReadTokens,
                            billableReasoningTokens);
            LlmCallLog row =
                    new LlmCallLog(
                            agentId,
                            sessionId,
                            providerName,
                            model,
                            promptTokens,
                            completionTokens,
                            totalTokens,
                            estimatedCostUsd,
                            success,
                            errorMessage,
                            requestId,
                            exceptionType,
                            stackTrace,
                            latencyMs,
                            startedAt,
                            experimentVariants,
                            traceId,
                            spanId,
                            cacheCreationTokens,
                            cacheReadTokens,
                            agentVersionId);
            row.setReasoningTokens(billableReasoningTokens);
            repository.save(row);
            addToMonthlyCounter(startedAt, estimatedCostUsd);
            if (providerName == ProviderName.PLATFORM_HOSTED) {
                notifyHostedSpend(estimatedCostUsd);
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to persist LlmCallLog for model {} (call itself already completed) -"
                            + " {}",
                    model,
                    e.getMessage());
        }
    }

    /**
     * Adds this call's cost to {@code llm_spend_month} (V481) in the log row's transaction, so the
     * two commit or roll back together, behind a savepoint inside {@link LlmSpendCounterWriter} so
     * a failing counter write cannot take the log row down with it. Zero-cost calls (failures,
     * unpriced models) skip the write: the value would not change, and the budget check falls back
     * to the SUM when no row exists.
     */
    private void addToMonthlyCounter(Instant startedAt, BigDecimal costUsd) {
        LlmSpendCounterWriter writer = counterWriter;
        if (writer == null || costUsd == null || costUsd.signum() == 0) {
            return;
        }
        writer.add(startedAt, costUsd);
    }

    /** Hosted-pool spend counter feed (monthly spend cap). BYOK providers never get here. */
    private void notifyHostedSpend(BigDecimal costUsd) {
        if (costUsd == null || costUsd.signum() <= 0 || hostedSpendListeners.isEmpty()) {
            return;
        }
        String tenant =
                com.agenticai.core.security.TenantContext.current()
                        .map(com.agenticai.core.security.TenantContext::getTenantId)
                        .orElse(null);
        if (tenant == null || tenant.isBlank()) {
            return;
        }
        for (HostedSpendListener listener : hostedSpendListeners) {
            try {
                listener.onHostedSpend(tenant, costUsd);
            } catch (RuntimeException e) {
                log.warn(
                        "Hosted spend listener {} failed for tenant {} - {}",
                        listener.getClass().getSimpleName(),
                        tenant,
                        e.getMessage());
            }
        }
    }

    /**
     * Infrastructure audit fix - {@code LlmQualityJudgeService} (core/agents) samples a turn's
     * quality strictly AFTER the turn's real answer has already been logged by {@link
     * LlmMetricsAdvisor}, so by the time the judge verdict is ready, the trace id for that exact
     * turn is already durable on this table (see {@code trace_id}, V138 migration) - this looks it
     * back up by session id so the verdict can be pushed to Langfuse as a score on the SAME trace
     * the turn itself produced, rather than a session-level score with no specific trace to point
     * at. Returns {@code null} (never throws) when there is no row for this session yet, or the row
     * has no trace id (tracing off) - the caller falls back to a session-scoped score in that case.
     */
    @Transactional(readOnly = true)
    public String mostRecentTraceIdForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return repository
                .findTopBySessionIdOrderByStartedAtDesc(sessionId)
                .map(LlmCallLog::getTraceId)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<LlmCallLog> recent(int limit, Boolean successFilter) {
        List<LlmCallLog> rows =
                successFilter == null
                        ? repository.findTop200ByOrderByStartedAtDesc()
                        : repository.findTop200BySuccessOrderByStartedAtDesc(successFilter);
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    @Transactional(readOnly = true)
    public List<LlmProviderUsageSummary> usageSummaryByProvider() {
        return repository.summarizeByProvider();
    }

    /**
     * Cost-transparency panel: real per-provider prompt-cache effectiveness over the given window -
     * see {@link LlmCallLogRepository#cacheEffectivenessSince}. A window with zero calls for a
     * provider means that provider is simply absent from the returned list; the caller (the
     * spend-protection endpoint) must not fill in a fabricated zero for it.
     */
    @Transactional(readOnly = true)
    public List<LlmCallLogRepository.CacheEffectivenessRow> cacheEffectivenessSince(Instant since) {
        return repository.cacheEffectivenessSince(since);
    }

    @Transactional(readOnly = true)
    public List<LlmCallLog> forTask(String sessionId) {
        return repository.findBySessionIdOrderByStartedAtAsc(sessionId);
    }

    /**
     * See {@link
     * LlmCallLogRepository#findBySessionIdAndAgentIdAndStartedAtBetweenOrderByStartedAtAsc}.
     */
    @Transactional(readOnly = true)
    public List<LlmCallLog> forSessionAgentWindow(
            String sessionId, UUID agentId, Instant from, Instant to) {
        if (sessionId == null || agentId == null || from == null || to == null) {
            return List.of();
        }
        return repository.findBySessionIdAndAgentIdAndStartedAtBetweenOrderByStartedAtAsc(
                sessionId, agentId, from, to);
    }

    /**
     * Task #959 - one agent's all-time call volume/latency/success-rate aggregate. See {@link
     * AgentLlmCallStats}'s javadoc.
     */
    @Transactional(readOnly = true)
    public AgentLlmCallStats statsForAgent(UUID agentId) {
        return repository.statsForAgent(agentId);
    }

    /**
     * Task #959 - one agent's most recent failed calls, newest first (see {@link
     * LlmCallLogRepository#findTop5ByAgentIdAndSuccessOrderByStartedAtDesc}).
     */
    @Transactional(readOnly = true)
    public List<LlmCallLog> recentFailuresForAgent(UUID agentId) {
        return repository.findTop5ByAgentIdAndSuccessOrderByStartedAtDesc(agentId, false);
    }

    /**
     * Cross-provider totals for the Dashboard's "LLM calls"/"LLM latency" readouts - see {@link
     * LlmOverallUsageSummary}'s javadoc.
     */
    @Transactional(readOnly = true)
    public LlmOverallUsageSummary overallSummary() {
        return repository.overallSummary();
    }

    /**
     * Real replacement for the AXON Dashboard telemetry strip's jittering {@code LiveTick}
     * placeholders - see {@link LlmDashboardMetrics}'s javadoc for the exact window each field
     * uses. Every number here comes straight from {@link LlmCallLog} rows this tenant's schema
     * already has; nothing is invented if a window happens to be empty.
     */
    @Transactional(readOnly = true)
    public LlmDashboardMetrics dashboardMetrics() {
        Instant now = Instant.now();
        Instant last24h = now.minus(24, ChronoUnit.HOURS);
        Instant last5min = now.minus(5, ChronoUnit.MINUTES);
        Instant startOfToday =
                now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();

        LlmCallLogRepository.LatencyPercentilesRow percentiles =
                repository.latencyPercentilesSince(last24h);
        long calls24h =
                percentiles != null && percentiles.getCalls() != null ? percentiles.getCalls() : 0;

        List<LlmCallLogRepository.ModelCallCountRow> modelRows =
                repository.modelCallCountsSince(last24h);
        long totalModelCalls =
                modelRows.stream()
                        .mapToLong(LlmCallLogRepository.ModelCallCountRow::getCalls)
                        .sum();
        List<LlmDashboardMetrics.ModelShare> modelSplit =
                modelRows.stream()
                        .map(
                                r ->
                                        new LlmDashboardMetrics.ModelShare(
                                                r.getModel(),
                                                r.getCalls(),
                                                totalModelCalls == 0
                                                        ? 0.0
                                                        : (r.getCalls() * 100.0) / totalModelCalls))
                        .toList();

        long tokens5min = repository.tokensSince(last5min);
        Double tokensPerMinuteRecent = tokens5min > 0 ? tokens5min / 5.0 : null;

        BigDecimal costToday = repository.costSince(startOfToday);
        long conversationsToday = repository.distinctConversationsSince(startOfToday);
        BigDecimal costPerConversationToday =
                conversationsToday > 0 && costToday != null
                        ? costToday.divide(
                                BigDecimal.valueOf(conversationsToday),
                                6,
                                java.math.RoundingMode.HALF_UP)
                        : null;

        return new LlmDashboardMetrics(
                calls24h,
                tokensPerMinuteRecent,
                percentiles != null ? roundToLong(percentiles.getP50()) : null,
                percentiles != null ? roundToLong(percentiles.getP95()) : null,
                percentiles != null ? roundToLong(percentiles.getP99()) : null,
                costToday,
                costPerConversationToday,
                conversationsToday,
                modelSplit);
    }

    private static Long roundToLong(Double value) {
        return value == null ? null : Math.round(value);
    }
}
