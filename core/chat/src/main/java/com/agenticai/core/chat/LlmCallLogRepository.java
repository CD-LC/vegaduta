package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface LlmCallLogRepository extends JpaRepository<LlmCallLog, java.util.UUID> {

    List<LlmCallLog> findTop200ByOrderByStartedAtDesc();

    /**
     * The current tenant schema's most recent LLM call - one aggregate, for "last activity"
     * readouts that previously loaded 200 full rows to read one timestamp. Null when the tenant has
     * never made a call.
     */
    @Query("SELECT MAX(l.startedAt) FROM LlmCallLog l")
    java.time.Instant findLatestStartedAt();

    List<LlmCallLog> findTop200BySuccessOrderByStartedAtDesc(boolean success);

    /**
     * One row per provider actually used so far - the "outstanding balance of activated llm"
     * summary. Deliberately grouped by {@code providerName} only (not model too): this platform's
     * MVP is single-provider-key-per-{@link ProviderName} (see {@code LlmProviderCredential}'s
     * javadoc), so a per-provider total lines up directly with the one activated credential a
     * tenant-admin can see and manage on the Providers screen. {@code SUM(CASE WHEN NOT l.success
     * ...)} rather than a separate query - one round trip for the whole summary card, not N.
     */
    @Query(
            "SELECT new com.agenticai.core.chat.LlmProviderUsageSummary(l.providerName, COUNT(l),"
                + " SUM(CASE WHEN l.success = false THEN 1L ELSE 0L END),"
                + " SUM(COALESCE(l.totalTokens, 0)), SUM(COALESCE(l.estimatedCostUsd, 0)),"
                + " MIN(l.startedAt), MAX(l.startedAt)) FROM LlmCallLog l GROUP BY l.providerName")
    List<LlmProviderUsageSummary> summarizeByProvider();

    /**
     * Cost-per-task view: every call sharing one {@code sessionId}, oldest first, so a task's cost
     * can be summed client-side.
     */
    List<LlmCallLog> findBySessionIdOrderByStartedAtAsc(@Param("sessionId") String sessionId);

    /**
     * Infrastructure audit fix - the single most recent call for a session, used by {@link
     * LlmCallLogService#mostRecentTraceIdForSession} to look up the trace id of the turn {@code
     * LlmQualityJudgeService} (core/agents) just sampled, so its verdict can be pushed to Langfuse
     * as a score on that exact trace.
     */
    java.util.Optional<LlmCallLog> findTopBySessionIdOrderByStartedAtDesc(String sessionId);

    /**
     * One agent's calls inside one session within a time window - the workflow run trace attributes
     * an Agent/Supervisor node's tokens and cost this way (session = the workflow run id, window =
     * the node's own start/end).
     */
    List<LlmCallLog> findBySessionIdAndAgentIdAndStartedAtBetweenOrderByStartedAtAsc(
            String sessionId, java.util.UUID agentId, java.time.Instant from, java.time.Instant to);

    /**
     * Task #959 - the per-agent counterpart to {@link #overallSummary()}, scoped to one {@code
     * agentId} instead of every call across the tenant. {@code COALESCE} around the error-count
     * {@code SUM} for the same reason {@link #overallSummary()} needs it (JPQL {@code SUM} over
     * zero matching rows is {@code NULL}); {@code AVG}/{@code MIN}/{@code MAX} are deliberately
     * left un-coalesced so an agent that has never actually run reads as {@code null} ("no data
     * yet") on every timing field, not a fabricated zero.
     */
    @Query(
            "SELECT new com.agenticai.core.chat.AgentLlmCallStats(COUNT(l), COALESCE(SUM(CASE WHEN"
                    + " l.success = false THEN 1L ELSE 0L END), 0L), AVG(l.latencyMs),"
                    + " MIN(l.startedAt), MAX(l.startedAt)) FROM LlmCallLog l WHERE l.agentId ="
                    + " :agentId")
    AgentLlmCallStats statsForAgent(@Param("agentId") java.util.UUID agentId);

    /**
     * Task #959's "recent failure history" - this agent's most recent failed calls, newest first,
     * capped to a small handful (a reliability panel needs a glance-able list, not a full audit
     * trail - {@code LlmCallLogController}'s existing log viewer already covers that). Reuses the
     * same {@code errorMessage}/{@code exceptionType} durably captured per row by {@link
     * LlmMetricsAdvisor#logCall} - no new instrumentation needed.
     */
    List<LlmCallLog> findTop5ByAgentIdAndSuccessOrderByStartedAtDesc(
            java.util.UUID agentId, boolean success);

    /**
     * The newest call to one provider with the given outcome inside a recent window ({@link
     * CallLogProviderUsageSignal}). The window bounds the backward scan of {@code
     * idx_llm_call_log_started_at} for a provider that has no matching row at all.
     */
    java.util.Optional<LlmCallLog>
            findTopByProviderNameAndSuccessAndStartedAtAfterOrderByStartedAtDesc(
                    ProviderName providerName, boolean success, Instant after);

    /**
     * Calls to one provider with the given outcome since {@code after} (the providers listing's
     * health counts).
     */
    long countByProviderNameAndSuccessAndStartedAtAfter(
            ProviderName providerName, boolean success, Instant after);

    /**
     * #973 - single-provider totals within THIS tenant schema (Hibernate multi-tenancy resolves the
     * schema from {@code TenantContext}, same as every other query on this repository) - {@link
     * #summarizeByProvider()} already computes this per row when grouping across every provider a
     * tenant has ever used; this is the same shape scoped to one {@link ProviderName} up front, so
     * a caller that only cares about one provider (e.g. {@code PlatformObservabilityService}'s
     * platform-wide {@code ProviderName.PLATFORM_HOSTED} cost rollup, summed across every tenant's
     * schema) doesn't have to fetch every other provider's rows just to discard them. Returns a row
     * with zeroed counts and null cost/timestamps (never {@code null} itself) when this tenant has
     * never used the given provider - {@code COALESCE} guards the numeric aggregates the same way
     * {@link #overallSummary()} does.
     */
    @Query(
            "SELECT new com.agenticai.core.chat.LlmProviderUsageSummary(:providerName, COUNT(l),"
                    + " COALESCE(SUM(CASE WHEN l.success = false THEN 1L ELSE 0L END), 0L),"
                    + " COALESCE(SUM(COALESCE(l.totalTokens, 0)), 0L),"
                    + " COALESCE(SUM(COALESCE(l.estimatedCostUsd, 0)), 0), MIN(l.startedAt),"
                    + " MAX(l.startedAt)) FROM LlmCallLog l WHERE l.providerName = :providerName")
    LlmProviderUsageSummary summarizeProvider(@Param("providerName") ProviderName providerName);

    /**
     * Cross-provider totals for {@code SystemStatusController}'s Dashboard readouts - see {@link
     * LlmOverallUsageSummary}'s javadoc for why these moved off the in-memory {@code
     * LlmCallMetricsService} onto this durable table. {@code COALESCE} around the error-count
     * {@code SUM} matches {@link #summarizeByProvider()}'s same guard (JPQL {@code SUM} over zero
     * matching rows is {@code NULL}, not {@code 0}); {@code AVG} is deliberately left un-coalesced
     * so a genuinely empty table reads as {@code null} ("no calls yet"), not a fabricated {@code
     * 0ms} average.
     */
    @Query(
            "SELECT new com.agenticai.core.chat.LlmOverallUsageSummary(COUNT(l), COALESCE(SUM(CASE"
                    + " WHEN l.success = false THEN 1L ELSE 0L END), 0L), AVG(l.latencyMs)) FROM"
                    + " LlmCallLog l")
    LlmOverallUsageSummary overallSummary();

    /**
     * Dashboard telemetry strip (direct feedback: "is this real data? ... expectation is to really
     * plug the live data") - native query since {@code percentile_cont} has no portable JPQL
     * equivalent, and this project is Postgres-only (pgvector, Apache AGE already assume it
     * elsewhere). Window is the last 24h so a low-traffic tenant isn't shown a "percentile"
     * computed from 2-3 calls.
     */
    @Query(
            value =
                    "SELECT COUNT(*) AS calls, "
                            + "PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY latency_ms) AS p50, "
                            + "PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95, "
                            + "PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY latency_ms) AS p99 "
                            + "FROM llm_call_log WHERE started_at >= :since",
            nativeQuery = true)
    LatencyPercentilesRow latencyPercentilesSince(@Param("since") Instant since);

    /**
     * Model routing split (real % per model, not the mockup's fixed Sonnet/Haiku/Opus numbers) over
     * the same 24h window.
     */
    @Query(
            value =
                    "SELECT model AS model, COUNT(*) AS calls FROM llm_call_log WHERE started_at >="
                            + " :since GROUP BY model ORDER BY COUNT(*) DESC",
            nativeQuery = true)
    List<ModelCallCountRow> modelCallCountsSince(@Param("since") Instant since);

    /**
     * Tight window (a few minutes, not 24h) - "what's happening right now," matching the tile's
     * TOKENS/SEC framing.
     */
    @Query(
            value =
                    "SELECT COALESCE(SUM(total_tokens), 0) FROM llm_call_log WHERE started_at >="
                            + " :since",
            nativeQuery = true)
    long tokensSince(@Param("since") Instant since);

    @Query(
            value =
                    "SELECT COALESCE(SUM(estimated_cost_usd), 0) FROM llm_call_log WHERE started_at"
                            + " >= :since",
            nativeQuery = true)
    BigDecimal costSince(@Param("since") Instant since);

    /**
     * The incrementally maintained spend counter for one UTC month (V481 {@code llm_spend_month}),
     * a primary-key read. Null when no call has been recorded for that month yet (callers fall back
     * to {@link #costSince}).
     */
    @Query(
            value = "SELECT cost_usd FROM llm_spend_month WHERE month_start = :monthStart",
            nativeQuery = true)
    BigDecimal monthlySpend(@Param("monthStart") java.time.LocalDate monthStart);

    @Query(
            value =
                    "SELECT COUNT(DISTINCT session_id) FROM llm_call_log WHERE started_at >= :since"
                            + " AND session_id IS NOT NULL",
            nativeQuery = true)
    long distinctConversationsSince(@Param("since") Instant since);

    /**
     * Phase P4 (Spend &amp; Reports, §8): the single grain-aggregation query both the daily rollup
     * job and the "today" live-fallback path run - one row per (provider, model, agent) over the
     * half-open window {@code [from, to)}, carrying every metric {@code llm_daily_usage_rollup}
     * stores. Modality is intentionally NOT grouped here: it is a pure function of {@code model}
     * ({@link ModalityClassifier}), so two rows sharing a model already share a modality - it is
     * assigned in Java after this query rather than duplicating the classification logic in SQL.
     *
     * <p>Native for the same reason {@link #latencyPercentilesSince} is: {@code percentile_cont}
     * has no JPQL form and this project is Postgres-only. Half-open {@code >= from AND < to} so a
     * call at exactly midnight is counted in one day only, never both. {@code COALESCE} around
     * every {@code SUM} for the same all-null-window guard the other aggregates in this repository
     * use.
     */
    @Query(
            value =
                    "SELECT provider_name AS providerName, model AS model, agent_id AS agentId,"
                        + " COUNT(*) AS totalCalls, COALESCE(SUM(CASE WHEN success = false THEN 1"
                        + " ELSE 0 END), 0) AS errorCount, COALESCE(SUM(COALESCE(prompt_tokens,"
                        + " 0)), 0) AS promptTokens, COALESCE(SUM(COALESCE(completion_tokens, 0)),"
                        + " 0) AS completionTokens, COALESCE(SUM(COALESCE(total_tokens, 0)), 0) AS"
                        + " totalTokens, COALESCE(SUM(COALESCE(estimated_cost_usd, 0)), 0) AS"
                        + " estimatedCostUsd, COALESCE(SUM(latency_ms), 0) AS latencySumMs,"
                        + " PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY latency_ms) AS"
                        + " latencyP50Ms, PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latency_ms)"
                        + " AS latencyP95Ms, COUNT(DISTINCT session_id) AS distinctSessions FROM"
                        + " llm_call_log WHERE started_at >= :from AND started_at < :to GROUP BY"
                        + " provider_name, model, agent_id",
            nativeQuery = true)
    List<UsageGrainRow> aggregateGrains(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Day-bucketed totals for a single provider, from {@code from} (inclusive) to now - the
     * Platform Admin cost-dashboard trend line (direct feedback: "total llm cost incurred so far...
     * remaining balance"). Scoped to one {@link ProviderName} up front for the same reason {@link
     * #summarizeProvider} is: {@code PlatformObservabilityService} only ever wants this for {@code
     * ProviderName.PLATFORM_HOSTED} (what the platform itself pays for), never a tenant's BYOK
     * usage. Native query (Postgres {@code date_trunc}, no portable JPQL form, same posture as
     * {@link #aggregateGrains}); {@code providerName} is passed as the enum's stored string name
     * since {@code provider_name} is a plain varchar column here.
     */
    @Query(
            value =
                    "SELECT date_trunc('day', started_at)::date AS usageDate, COUNT(*) AS"
                        + " totalCalls, COALESCE(SUM(COALESCE(total_tokens, 0)), 0) AS totalTokens,"
                        + " COALESCE(SUM(COALESCE(estimated_cost_usd, 0)), 0) AS estimatedCostUsd"
                        + " FROM llm_call_log WHERE provider_name = :providerName AND started_at >="
                        + " :from GROUP BY usageDate ORDER BY usageDate",
            nativeQuery = true)
    List<DailyProviderCostRow> dailyCostForProvider(
            @Param("providerName") String providerName, @Param("from") Instant from);

    /**
     * Cost-transparency panel ("what's protecting your spend"): real, per-provider prompt-cache
     * effectiveness over a recent window (the panel uses last 7 days), from the {@code
     * cache_creation_tokens}/ {@code cache_read_tokens} columns V195 added. Grouped by provider
     * (not model) for the same reason {@link #summarizeByProvider()} is - this platform's
     * one-credential-per-provider shape. Only rows a provider actually produced appear (native
     * {@code GROUP BY} over whatever's in the window) - a provider with zero calls in the window is
     * simply absent from the result list, never a fabricated zero row. {@code COALESCE} around
     * every {@code SUM} for the same all-null-window guard the rest of this repository's aggregates
     * use.
     */
    @Query(
            value =
                    "SELECT provider_name AS providerName, COUNT(*) AS totalCalls,"
                        + " COALESCE(SUM(COALESCE(prompt_tokens, 0)), 0) AS promptTokens,"
                        + " COALESCE(SUM(COALESCE(cache_creation_tokens, 0)), 0) AS"
                        + " cacheCreationTokens, COALESCE(SUM(COALESCE(cache_read_tokens, 0)), 0)"
                        + " AS cacheReadTokens, COALESCE(SUM(CASE WHEN COALESCE(cache_read_tokens,"
                        + " 0) > 0 THEN 1 ELSE 0 END), 0) AS callsWithCacheHit FROM llm_call_log"
                        + " WHERE started_at >= :since GROUP BY provider_name",
            nativeQuery = true)
    List<CacheEffectivenessRow> cacheEffectivenessSince(@Param("since") Instant since);

    /**
     * Projection for {@link #cacheEffectivenessSince} - one provider's windowed cache-token totals.
     */
    interface CacheEffectivenessRow {
        String getProviderName();

        long getTotalCalls();

        long getPromptTokens();

        long getCacheCreationTokens();

        long getCacheReadTokens();

        long getCallsWithCacheHit();
    }

    /**
     * Projection for {@link #dailyCostForProvider} - one calendar day's totals for one provider.
     */
    interface DailyProviderCostRow {
        java.time.LocalDate getUsageDate();

        long getTotalCalls();

        long getTotalTokens();

        BigDecimal getEstimatedCostUsd();
    }

    interface LatencyPercentilesRow {
        Long getCalls();

        Double getP50();

        Double getP95();

        Double getP99();
    }

    interface ModelCallCountRow {
        String getModel();

        Long getCalls();
    }

    /**
     * Projection for {@link #aggregateGrains} - one pre-aggregated grain, modality assigned in
     * Java.
     */
    interface UsageGrainRow {
        String getProviderName();

        String getModel();

        java.util.UUID getAgentId();

        long getTotalCalls();

        long getErrorCount();

        long getPromptTokens();

        long getCompletionTokens();

        long getTotalTokens();

        BigDecimal getEstimatedCostUsd();

        long getLatencySumMs();

        Double getLatencyP50Ms();

        Double getLatencyP95Ms();

        long getDistinctSessions();
    }
}
