package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.opentelemetry.api.trace.Span;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Builds the {@link LlmCallObservation} for a finished call and fans it out to the {@link
 * LlmCallSink}s, in order. The isolation contract lives here: an exception while building the
 * observation or inside any sink is logged and swallowed, so observability can never fail (or
 * mis-classify as failed) the model call it observes. Must be invoked inside the call's captured
 * tenant scope.
 */
final class LlmCallObserver {

    private static final Logger log = LoggerFactory.getLogger(LlmCallObserver.class);

    private final ProviderName providerName;
    private final String model;
    private final LlmPricingCatalog pricingCatalog;
    private final List<LlmCallSink> sinks;

    LlmCallObserver(
            ProviderName providerName,
            String model,
            LlmPricingCatalog pricingCatalog,
            List<LlmCallSink> sinks) {
        this.providerName = providerName;
        this.model = model;
        this.pricingCatalog = pricingCatalog;
        this.sinks = List.copyOf(sinks);
    }

    /**
     * @param error the failure, or {@code null} for a successful call
     * @param streamCancelled true when the consumer cancelled a stream (see {@code
     *     LlmMetricsAdvisor#adviseStream})
     */
    void observe(
            ChatClientRequest request,
            Usage usage,
            boolean success,
            Throwable error,
            String requestId,
            long latencyMs,
            Instant startedAt,
            Span span,
            boolean streamCancelled) {
        LlmCallObservation observation;
        try {
            observation =
                    build(
                            request,
                            usage,
                            success,
                            error,
                            requestId,
                            latencyMs,
                            startedAt,
                            span,
                            streamCancelled);
        } catch (RuntimeException e) {
            log.warn("LLM call observation could not be built for model {} - {}", model, e.toString());
            return;
        }
        for (LlmCallSink sink : sinks) {
            try {
                sink.onCall(observation);
            } catch (RuntimeException e) {
                log.warn(
                        "LLM call sink {} failed for model {} - {}",
                        sink.getClass().getSimpleName(),
                        model,
                        e.toString());
            }
        }
    }

    private LlmCallObservation build(
            ChatClientRequest request,
            Usage usage,
            boolean success,
            Throwable error,
            String requestId,
            long latencyMs,
            Instant startedAt,
            Span span,
            boolean streamCancelled) {
        LlmCallObservation.Attribution attribution =
                new LlmCallObservation.Attribution(
                        LlmCallRequestParams.uuid(request, LlmMetricsAdvisor.AGENT_ID_PARAM),
                        LlmCallRequestParams.string(request, LlmMetricsAdvisor.SESSION_ID_PARAM),
                        LlmCallRequestParams.string(
                                request, LlmMetricsAdvisor.EXPERIMENT_VARIANTS_PARAM),
                        LlmCallRequestParams.agentVersionId(request),
                        LlmCallRequestParams.promptVersion(request));

        Long promptTokens = null;
        Long completionTokens = null;
        Long totalTokens = null;
        // CacheTokenAccounting.ABSENT, not null, for a failed call or a response with no usage
        // metadata - "nothing measured" is a value here (see that record's javadoc for why absent
        // and zero must never collapse into each other).
        CacheTokenAccounting cacheAccounting = CacheTokenAccounting.ABSENT;
        if (usage != null) {
            promptTokens =
                    usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue();
            completionTokens =
                    usage.getCompletionTokens() == null
                            ? null
                            : usage.getCompletionTokens().longValue();
            totalTokens =
                    usage.getTotalTokens() == null ? null : usage.getTotalTokens().longValue();
            // Prompt-cache counts are NOT folded into promptTokens for Anthropic; OpenAI and Google
            // GenAI report them INCLUSIVE of promptTokens - the per-provider difference
            // CacheTokenAccounting normalizes.
            cacheAccounting = CacheTokenAccounting.from(usage);
        }
        // Gemini thinking tokens sit OUTSIDE completionTokens and bill at the output rate; OpenAI's
        // are already inside it (see ReasoningTokenAccounting).
        ReasoningTokenAccounting reasoning = ReasoningTokenAccounting.from(usage);
        // The billable accessors return the raw counts for Anthropic and null for the inclusive
        // providers, whose cache reads would double-bill if priced on top of promptTokens.
        // Priced whenever usage was reported, success or not: a stream that failed or was cancelled
        // part-way still billed the tokens it produced.
        java.math.BigDecimal estimatedCostUsd =
                pricingCatalog.estimateCostUsd(
                        providerName == null ? null : providerName.name(),
                        model,
                        promptTokens,
                        completionTokens,
                        cacheAccounting.billableCacheWriteTokens(),
                        cacheAccounting.billableCacheReadTokens(),
                        reasoning.billableReasoningTokens());

        LlmCallObservation.Failure failure = null;
        if (error != null) {
            failure =
                    new LlmCallObservation.Failure(
                            LlmCallErrors.messageWithProviderBody(error),
                            error.getClass().getName(),
                            LlmCallErrors.stackTraceOf(error),
                            LlmCallErrors.isStreamCancellation(error));
        }
        return new LlmCallObservation(
                providerName,
                model,
                attribution,
                new LlmCallObservation.Tokens(
                        promptTokens,
                        completionTokens,
                        totalTokens,
                        cacheAccounting,
                        reasoning,
                        estimatedCostUsd),
                failure,
                success,
                streamCancelled,
                requestId,
                latencyMs,
                startedAt,
                span);
    }
}
