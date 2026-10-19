package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import io.opentelemetry.api.trace.Span;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable facts about one finished LLM call, built once by {@link LlmCallObserver} and read by
 * every {@link LlmCallSink}. Everything a sink needs is derived here (tokens, cache and reasoning
 * accounting, cost estimate, error text, trace ids) so sinks never recompute or disagree.
 *
 * @param streamCancelled true only for a stream cancelled by its consumer (counted in {@code
 *     llm.stream.cancelled}, never as a provider error)
 * @param failure {@code null} for a successful call
 * @param span the span captured on the originating thread; may be {@code null}
 */
record LlmCallObservation(
        ProviderName providerName,
        String model,
        Attribution attribution,
        Tokens tokens,
        Failure failure,
        boolean success,
        boolean streamCancelled,
        String requestId,
        long latencyMs,
        Instant startedAt,
        Span span) {

    /** Who the call is attributed to; every field is optional. */
    record Attribution(
            UUID agentId,
            String sessionId,
            String experimentVariants,
            UUID agentVersionId,
            String promptVersion) {}

    /** Token counts and the cost derived from them; {@code null} counts mean "not reported". */
    record Tokens(
            Long prompt,
            Long completion,
            Long total,
            CacheTokenAccounting cache,
            ReasoningTokenAccounting reasoning,
            BigDecimal estimatedCostUsd) {}

    /** A failed call's persisted error text. {@code consumerCancellation} is not a provider fault. */
    record Failure(
            String message,
            String exceptionType,
            String stackTrace,
            boolean consumerCancellation) {}

    /** Whether the error-rate meter should count this call. */
    boolean countAsError() {
        return !success && !(failure != null && failure.consumerCancellation());
    }

    String exceptionType() {
        return failure == null ? null : failure.exceptionType();
    }

    /** 32-hex trace id of {@link #span}, or {@code null} when tracing is off / no valid span. */
    String traceId() {
        io.opentelemetry.api.trace.SpanContext ctx = validContext();
        return ctx == null ? null : ctx.getTraceId();
    }

    /** 16-hex span id of {@link #span}, or {@code null} when tracing is off / no valid span. */
    String spanId() {
        io.opentelemetry.api.trace.SpanContext ctx = validContext();
        return ctx == null ? null : ctx.getSpanId();
    }

    private io.opentelemetry.api.trace.SpanContext validContext() {
        if (span == null) {
            return null;
        }
        try {
            io.opentelemetry.api.trace.SpanContext ctx = span.getSpanContext();
            return ctx != null && ctx.isValid() ? ctx : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
