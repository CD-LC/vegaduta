package com.agenticai.core.chat;

import com.agenticai.core.security.TenantContext;
import io.opentelemetry.api.trace.Span;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.SignalType;

/**
 * Records one finished stream subscription. Runs on whatever thread delivered the terminal signal,
 * so the originating call's tenant context is re-applied explicitly (see {@code LlmMetricsAdvisor#withTenantContext});
 * never throws (an exception out of {@code doFinally} would be dropped by Reactor anyway, and must never
 * reach the chat turn).
 *
 * <p>Cancellation is logged too: a stream cancelled by a client disconnect or the stream-idle
 * timeout was still billed by the provider for every token generated so far. It is recorded with
 * the usage seen so far, {@code success=false}, {@code exceptionType} {@link
 * CancellationException} and an error message starting with {@value
 * LlmMetricsAdvisor#STREAM_CANCELLED_MESSAGE_PREFIX}; it is deliberately NOT counted as a failure
 * in {@link LlmCallMetricsService} or {@code llm.call.errors}, but gets its own {@code
 * llm.stream.cancelled} counter.
 */
final class StreamFinalizer {

    private static final Logger log = LoggerFactory.getLogger(StreamFinalizer.class);

    private final LlmCallMetricsService metrics;
    private final LlmCallObserver observer;
    private final String model;

    StreamFinalizer(LlmCallMetricsService metrics, LlmCallObserver observer, String model) {
        this.metrics = metrics;
        this.observer = observer;
        this.model = model;
    }

    void finalizeStream(
            SignalType signal,
            Throwable failure,
            Usage usageSoFar,
            ChatClientRequest request,
            Optional<TenantContext> capturedContext,
            String requestId,
            long latencyMs,
            Instant startedAt,
            Span span,
            String snapshotId) {
        // SE-12: a stream is often cancelled by disposing a Reactor scheduler task, which
        // interrupts the worker thread (Future.cancel(true)); the terminal signal then runs here
        // with the interrupt flag set. Postgres/Hikari treat that as a dead socket and the JPA
        // EntityManager cannot be opened, so the outcome row would be lost. Record with the flag
        // cleared, then put it back so the caller's cancellation is still observed.
        boolean wasInterrupted = Thread.interrupted();
        try {
            if (signal == SignalType.ON_COMPLETE) {
                metrics.recordCall(latencyMs, true);
                LlmMetricsAdvisor.withTenantContext(
                        capturedContext,
                        () -> {
                            observer.observe(
                                    request,
                                    usageSoFar,
                                    true,
                                    null,
                                    requestId,
                                    latencyMs,
                                    startedAt,
                                    span,
                                    false);
                            LlmCallRequestParams.attachSnapshotUsage(
                                    request, snapshotId, usageSoFar);
                        });
            } else if (signal == SignalType.ON_ERROR) {
                metrics.recordCall(latencyMs, false);
                Throwable error =
                        failure != null
                                ? failure
                                : new IllegalStateException("stream terminated with an error");
                // Tokens streamed before the failure were billed by the provider, so they are
                // recorded rather than dropped.
                LlmMetricsAdvisor.withTenantContext(
                        capturedContext,
                        () ->
                                observer.observe(
                                        request,
                                        usageSoFar,
                                        false,
                                        error,
                                        requestId,
                                        latencyMs,
                                        startedAt,
                                        span,
                                        false));
            } else if (signal == SignalType.CANCEL) {
                // Not a provider failure.
                metrics.recordCall(latencyMs, true);
                CancellationException cancelled =
                        new CancellationException(
                                LlmMetricsAdvisor.STREAM_CANCELLED_MESSAGE_PREFIX
                                        + ": stream cancelled by the consumer after "
                                        + latencyMs
                                        + " ms (client disconnect or stream-idle timeout); token"
                                        + " counts are the usage reported before cancellation and"
                                        + " may undercount output");
                // A marker, not a fault: a stack trace of this finalizer would only be noise in
                // llm_call_log.stack_trace.
                cancelled.setStackTrace(new StackTraceElement[0]);
                LlmMetricsAdvisor.withTenantContext(
                        capturedContext,
                        () ->
                                observer.observe(
                                        request,
                                        usageSoFar,
                                        false,
                                        cancelled,
                                        requestId,
                                        latencyMs,
                                        startedAt,
                                        span,
                                        true));
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to record LLM stream outcome {} for model {} - {}",
                    signal,
                    model,
                    e.toString());
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
