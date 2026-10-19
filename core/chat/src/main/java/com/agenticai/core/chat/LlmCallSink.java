package com.agenticai.core.chat;

/**
 * An independent subscriber to one finished LLM call. {@link LlmMetricsAdvisor} builds an {@link
 * LlmCallObservation} per call and hands it to every sink through {@link LlmCallObserver}, which
 * isolates failures: a sink that throws is logged and skipped, and neither the remaining sinks nor
 * the model call ever see the exception. Sinks run on whatever thread delivered the terminal
 * signal, inside the call's captured tenant scope, so {@code TenantContext.current()} is the
 * originating call's tenant.
 */
interface LlmCallSink {

    void onCall(LlmCallObservation observation);
}
