package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.TurnLifecycleStepEvents;
import com.agenticai.core.agents.turn.TurnReplyOutcome.Kind;
import com.agenticai.core.chat.LlmRetryPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

/**
 * What the turn tells a client that asked for step events about retries, failovers, continuation passes
 * and how the reply ended ({@link TurnLifecycleStepEvents}): the translation from the turn's own state
 * (a {@link TurnReplyOutcome.Kind}, a failed call) to the event's structured fields, in one place, so
 * the streamed and the buffered depths say the same thing and no stage builds an event by hand.
 *
 * <p>Everything here is fail-open and a no-op on a turn that did not ask for step events: the emitter
 * is the request's, or the disabled one.
 */
final class TurnLifecycleEvents {

    /** Reason code of a failure that is a rate limit (HTTP 429). */
    static final String RATE_LIMITED = "rate_limited";

    static final String TIMEOUT = "timeout";
    static final String PROVIDER_UNAVAILABLE = "provider_unavailable";
    static final String AUTH_FAILED = "auth_failed";
    static final String BILLING = "billing";
    static final String MODEL_NOT_FOUND = "model_not_found";
    static final String PROVIDER_ERROR = "provider_error";

    /** The tool call the model produced could not be used, so the reply is asked for again. */
    static final String MALFORMED_TOOL_CALL = "malformed_tool_call";

    private TurnLifecycleEvents() {}

    /** The {@code finishReason} a reply that ended as {@code kind} is reported with. */
    static String finishReasonOf(Kind kind) {
        if (kind == null) {
            return TurnLifecycleStepEvents.FINISH_STOP;
        }
        return switch (kind) {
            case COMPLETE -> TurnLifecycleStepEvents.FINISH_STOP;
            case TRUNCATED -> TurnLifecycleStepEvents.FINISH_LENGTH;
            case REFUSED -> TurnLifecycleStepEvents.FINISH_SAFETY;
            case MALFORMED_TOOL_CALL -> TurnLifecycleStepEvents.FINISH_MALFORMED_TOOL_CALL;
            case EMPTY -> TurnLifecycleStepEvents.FINISH_EMPTY;
            case INTERRUPTED -> TurnLifecycleStepEvents.FINISH_INTERRUPTED;
        };
    }

    /** The reply of the turn ended as {@code state}: one {@code finish} event. */
    static void finish(AgentStepEventEmitter events, TurnReplyOutcome.State state) {
        Kind kind = state == null ? Kind.COMPLETE : state.kind();
        String detail = state == null ? null : state.detail();
        TurnLifecycleStepEvents.on(events).finish(finishReasonOf(kind), detail);
    }

    /** A pass that carries a cut-off reply on has started: {@code pass} of at most {@code maxPasses}. */
    static void cutOffPass(AgentStepEventEmitter events, int pass, int maxPasses) {
        TurnLifecycleStepEvents.on(events)
                .continuation(pass, maxPasses, TurnLifecycleStepEvents.CONTINUE_CUT_OFF);
    }

    /** A pass that carries on a reply stopped at an announced step has started. */
    static void unfinishedStepPass(AgentStepEventEmitter events, int pass, int maxPasses) {
        TurnLifecycleStepEvents.on(events)
                .continuation(pass, maxPasses, TurnLifecycleStepEvents.CONTINUE_UNFINISHED_STEP);
    }

    /** The platform is carrying the task on after a person decided a held tool call. */
    static void approvalContinuation(AgentStepEventEmitter events) {
        TurnLifecycleStepEvents.on(events).continuation(1, 1, TurnLifecycleStepEvents.CONTINUE_APPROVAL);
    }

    /**
     * A finish-reason pass of the streamed or the buffered depth is starting: a continuation of a cut-off reply
     * ({@code cutOff}) or one more try after an unusable tool call, {@code used} passes of that kind having run
     * before it, {@code limit} allowed.
     */
    static void finishPass(AgentStepEventEmitter events, boolean cutOff, int used, int limit) {
        if (cutOff) {
            cutOffPass(events, used + 1, limit);
        } else {
            toolCallRetry(events, used, limit);
        }
    }

    /**
     * The reply is asked for again after an unusable tool call: the try about to run is number
     * {@code retriesMade + 2} of {@code maxRetries + 1}.
     */
    static void toolCallRetry(AgentStepEventEmitter events, int retriesMade, int maxRetries) {
        TurnLifecycleStepEvents.on(events).retry(retriesMade + 2, maxRetries + 1, MALFORMED_TOOL_CALL);
    }

    /**
     * A model call that failed before its first token is being made again: {@code retryNumber} is 1 for
     * the first retry, {@code maxAttempts} the total tries the platform will make.
     */
    static void callRetry(AgentStepEventEmitter events, int retryNumber, int maxAttempts, Throwable failure) {
        TurnLifecycleStepEvents.on(events).retry(retryNumber + 1, maxAttempts, reasonOf(failure));
    }

    /** The turn moved to {@code toModel} because of {@code cause}. */
    static void failover(AgentStepEventEmitter events, String toModel, Throwable cause) {
        TurnLifecycleStepEvents.on(events).failover(toModel, reasonOf(cause));
    }

    /** A short code for why a provider call failed, from the status found in its cause chain. */
    static String reasonOf(Throwable failure) {
        if (failure == null) {
            return PROVIDER_ERROR;
        }
        int status;
        try {
            status = LlmRetryPolicy.statusOf(failure);
        } catch (RuntimeException e) {
            status = 0;
        }
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status == 408 || DegradedRetrySignal.isTimeout(failure)) {
            return TIMEOUT;
        }
        if (status == 401 || status == 403) {
            return AUTH_FAILED;
        }
        if (status == 402) {
            return BILLING;
        }
        if (status == 404) {
            return MODEL_NOT_FOUND;
        }
        if (status >= 500 || hasTransportFailure(failure)) {
            return PROVIDER_UNAVAILABLE;
        }
        return PROVIDER_ERROR;
    }

    /** Failures the retry policy treats as transient (no HTTP status): the provider could not be reached or said so. */
    private static boolean hasTransportFailure(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth++ < 16; t = t.getCause() == t ? null : t.getCause()) {
            String name = t.getClass().getSimpleName();
            if (name.equals("TransientAiException")
                    || name.equals("ResourceAccessException")
                    || name.equals("WebClientRequestException")
                    || name.equals("ServerException")
                    || t instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The hosted chain's failovers, told from outside it: wraps the per-rung attempt and the rung-failure
     * listener the chain is given, so the chain itself stays as it is. The first attempt is the first
     * rung; every later one is a move to another rung (or to the rung a self-heal replaced), and names the
     * model it moved to and the failure that caused it.
     */
    static final class HostedChain {

        private final AgentStepEventEmitter events;
        private final List<HostedChainLink> chain;
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();

        HostedChain(AgentStepEventEmitter events, List<HostedChainLink> chain) {
            this.events = events;
            this.chain = chain == null ? List.of() : chain;
        }

        /** {@code attempt}, telling the client each time it is run for a rung after the first. */
        Function<ChatClient, Flux<String>> attempt(Function<ChatClient, Flux<String>> attempt) {
            if (events == null || !events.isEnabled()) {
                return attempt;
            }
            // A new run of the chain (the wrapper is built when the chain starts): nothing failed yet.
            attempts.set(0);
            lastFailure.set(null);
            return client -> {
                if (attempts.getAndIncrement() > 0) {
                    failover(events, modelOf(client), lastFailure.get());
                }
                return attempt.apply(client);
            };
        }

        /** {@code listener}, noting the failure first so the next move can name it. */
        BiConsumer<HostedChainLink, Throwable> onRungFailure(BiConsumer<HostedChainLink, Throwable> listener) {
            if (events == null || !events.isEnabled()) {
                return listener;
            }
            return (link, failure) -> {
                lastFailure.set(failure);
                if (listener != null) {
                    listener.accept(link, failure);
                }
            };
        }

        private String modelOf(ChatClient client) {
            for (HostedChainLink link : chain) {
                if (link.client() == client) {
                    return link.model();
                }
            }
            return null;
        }
    }
}
