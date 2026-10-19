package com.agenticai.core.chat;

/**
 * Writes the durable {@code llm_call_log} row for a finished call (success, failure, or cancelled
 * stream). The trace and span ids come from the observation's span so a log row can be jumped
 * straight to its Langfuse trace. {@link LlmCallLogService#record} is itself fail-open; this sink
 * adds no behaviour beyond the length caps the column sizes require.
 */
final class LlmCallLogSink implements LlmCallSink {

    private final LlmCallLogService callLogService;

    LlmCallLogSink(LlmCallLogService callLogService) {
        this.callLogService = callLogService;
    }

    @Override
    public void onCall(LlmCallObservation obs) {
        LlmCallObservation.Attribution who = obs.attribution();
        LlmCallObservation.Tokens tokens = obs.tokens();
        LlmCallObservation.Failure failure = obs.failure();
        callLogService.record(
                who.agentId(),
                who.sessionId(),
                obs.providerName(),
                obs.model(),
                tokens.prompt(),
                tokens.completion(),
                tokens.total(),
                obs.success(),
                truncate(failure == null ? null : failure.message()),
                obs.requestId(),
                obs.exceptionType(),
                truncateStackTrace(failure == null ? null : failure.stackTrace()),
                obs.latencyMs(),
                obs.startedAt(),
                who.experimentVariants(),
                obs.traceId(),
                obs.spanId(),
                tokens.cache().billableCacheWriteTokens(),
                tokens.cache().billableCacheReadTokens(),
                who.agentVersionId(),
                tokens.reasoning().billableReasoningTokens(),
                tokens.estimatedCostUsd());
    }

    /**
     * Generous but bounded - a recursive cause chain (Spring AI wrapper -> SDK exception -> HTTP
     * client exception) can run long.
     */
    private static String truncateStackTrace(String stackTrace) {
        if (stackTrace == null) {
            return null;
        }
        return stackTrace.length() > 8000 ? stackTrace.substring(0, 8000) + "…" : stackTrace;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 2000 ? message.substring(0, 2000) + "…" : message;
    }
}
