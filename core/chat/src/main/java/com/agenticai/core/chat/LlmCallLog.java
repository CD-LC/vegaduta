package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One real LLM call, success or failure - direct feedback: "there should be an option to see the
 * llm errors and success logs and also, customer should be able to evaluate the price cost per call
 * or a task, and an outstanding balance of activated llm."
 *
 * <p>Written by {@link LlmMetricsAdvisor} (via {@link LlmCallLogService}) - the same single
 * chokepoint {@code LlmCallMetricsService} already uses for its in-memory counters (see that
 * class's javadoc: every {@link org.springframework.ai.chat.client.ChatClient} this app builds gets
 * the advisor registered once, in {@code AnthropicAgentChatClientFactory}). This table is the
 * durable, queryable counterpart to that in-memory snapshot - {@code LlmCallMetricsService} answers
 * "how many calls since this process started," this table answers "show me every call, including
 * what it cost and why it failed."
 *
 * <p>{@code agentId}/{@code sessionId} are nullable: only populated for call sites that thread them
 * through via {@code ChatClient.prompt().advisors(a -> a.param(...))} (see {@link
 * LlmMetricsAdvisor}'s {@code AGENT_ID_PARAM}/ {@code SESSION_ID_PARAM} constants) - currently
 * {@code AgentChatService} (agent chat + Quick Task, which calls through it), covering the two
 * highest-traffic, user-attributable paths. Lower-traffic internal call sites (guardrail
 * self-consistency/Reflexion follow-up calls, graph-memory entity extraction, workflow-graph
 * generation, Ask-AI assist) still get a full row - cost, tokens, success/error, latency - just
 * with a null agent/ session, surfaced in the log viewer as "system."
 *
 * <p>{@code estimatedCostUsd} is exactly that - an ESTIMATE computed from {@link
 * LlmPricingCatalog}'s hand-maintained per-model $/MTok table against Anthropic's officially
 * published rates, not a real invoice line. It will drift from the tenant's actual Anthropic bill
 * if: token usage details (cache writes/reads, the 30% newer-tokenizer inflation Anthropic
 * documents for Sonnet 5/Opus 4.7+) aren't reflected in the plain prompt/completion token counts
 * {@code Usage} exposes, or if Anthropic's posted price for a model changes after {@link
 * LlmPricingCatalog} was last updated (the Sonnet 5 introductory $2/$10 -&gt; standard $3/$15 step
 * on 2026-09-01 being the concrete near-term example). Good enough for relative comparison and a
 * directional running total - not a substitute for the tenant's real Anthropic Console billing
 * page.
 */
@Entity
@Table(name = "llm_call_log")
public class LlmCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_name", nullable = false, length = 32)
    private ProviderName providerName;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "prompt_tokens")
    private Long promptTokens;

    @Column(name = "completion_tokens")
    private Long completionTokens;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "estimated_cost_usd", precision = 12, scale = 6)
    private BigDecimal estimatedCostUsd;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // Direct feedback ("Log all LLM provider exceptions... Timestamp,
    // User/request ID, Provider name, Request correlation ID, Exception
    // type, Full error message, Stack trace... accessible for future
    // review") - see V82's migration comment for why these three live here
    // rather than only in the server log: this row outlives log rotation.
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "exception_type", length = 255)
    private String exceptionType;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    // Master Roadmap 2026 item #13 (A/B testing & feature flags, §B) - flat
    // "flagKey=variant,flagKey2=variant2" string, nullable (populated only
    // when the call site threads LlmMetricsAdvisor#EXPERIMENT_VARIANTS_PARAM
    // through, same opt-in posture as agentId/sessionId above).
    @Column(name = "experiment_variants", length = 500)
    private String experimentVariants;

    // Infrastructure audit fix (Langfuse tenant/agent/session/cost tagging,
    // V138 migration): the 32-hex OTel trace id / 16-hex OTel span id
    // LlmMetricsAdvisor captured from the current span (if any was active -
    // see that class's javadoc) at the moment this call ran. Nullable, same
    // opt-in posture as every other column here: null when tracing is off
    // (management.tracing.sampling.probability=0.0) or no span was active,
    // never fabricated. Lets a call-log row be jumped straight to its
    // Langfuse trace, and lets LlmQualityJudgeService (core/agents) look the
    // trace id back up by session id to attach its verdict as a Langfuse
    // score via LangfuseScoreClient.
    @Column(name = "trace_id", length = 32)
    private String traceId;

    @Column(name = "span_id", length = 16)
    private String spanId;

    // Cost-transparency panel: real per-call Anthropic prompt-cache write/
    // read token counts (V195 migration) - already extracted by
    // LlmMetricsAdvisor from the provider's own usage metadata and already
    // folded into estimatedCostUsd via LlmPricingCatalog, just not
    // previously persisted as their own queryable columns. Null for any
    // call that doesn't report cache usage (every non-Anthropic provider
    // today, or an Anthropic call that didn't hit the cache path) - never a
    // fabricated 0, same opt-in posture as traceId/spanId above.
    @Column(name = "cache_creation_tokens")
    private Long cacheCreationTokens;

    @Column(name = "cache_read_tokens")
    private Long cacheReadTokens;

    /**
     * C1 version stamping - the {@code agent_version.id} this LLM call ran under (migration V342).
     * Carried in through {@code LlmMetricsAdvisor#AGENT_VERSION_ID_PARAM} or the request thread's
     * {@code AgentVersionPin}; nullable forever for calls made outside a pinned turn, an honest
     * "unknown".
     */
    @Column(name = "agent_version_id")
    private UUID agentVersionId;

    // Reasoning ("thinking") tokens billed OUTSIDE completion_tokens (V491). Null = not reported;
    // never a fabricated 0. Lets a cost computed later from a price table match the cost priced at
    // call time.
    @Column(name = "reasoning_tokens")
    private Long reasoningTokens;

    protected LlmCallLog() {
        // JPA
    }

    public LlmCallLog(
            UUID agentId,
            String sessionId,
            ProviderName providerName,
            String model,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            BigDecimal estimatedCostUsd,
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
            UUID agentVersionId) {
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.providerName = providerName;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.estimatedCostUsd = estimatedCostUsd;
        this.success = success;
        this.errorMessage = errorMessage;
        this.requestId = requestId;
        this.exceptionType = exceptionType;
        this.stackTrace = stackTrace;
        this.latencyMs = latencyMs;
        this.startedAt = startedAt;
        this.experimentVariants = experimentVariants;
        this.traceId = traceId;
        this.spanId = spanId;
        this.cacheCreationTokens = cacheCreationTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.agentVersionId = agentVersionId;
    }

    /**
     * Pre-C1 signature (no version pin) kept for callers and tests that predate the {@code
     * agent_version_id} column - delegates with a null pin, which the nullable column records as an
     * honest "unknown".
     */
    public LlmCallLog(
            UUID agentId,
            String sessionId,
            ProviderName providerName,
            String model,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens,
            BigDecimal estimatedCostUsd,
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
        this(
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
                null);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public ProviderName getProviderName() {
        return providerName;
    }

    public String getModel() {
        return model;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public BigDecimal getEstimatedCostUsd() {
        return estimatedCostUsd;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public String getExperimentVariants() {
        return experimentVariants;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public Long getCacheCreationTokens() {
        return cacheCreationTokens;
    }

    public Long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public UUID getAgentVersionId() {
        return agentVersionId;
    }

    public Long getReasoningTokens() {
        return reasoningTokens;
    }

    public void setReasoningTokens(Long reasoningTokens) {
        this.reasoningTokens = reasoningTokens;
    }
}
