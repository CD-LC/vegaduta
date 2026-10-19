package com.agenticai.core.chat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CancellationException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Error text helpers for the persisted {@code llm_call_log} row. */
final class LlmCallErrors {

    /**
     * Bounded cause-chain walk depth for {@link #findProviderResponseBody} - same guard {@code
     * ProviderErrorTranslator#findCause} uses against a pathological chain.
     */
    private static final int MAX_CAUSE_DEPTH = 8;

    private LlmCallErrors() {}

    static boolean isStreamCancellation(Throwable error) {
        return error instanceof CancellationException
                && error.getMessage() != null
                && error.getMessage().startsWith(LlmMetricsAdvisor.STREAM_CANCELLED_MESSAGE_PREFIX);
    }

    /**
     * Builds the {@code error_message} actually worth persisting. For the providers whose non-2xx
     * surfaces as a raw {@link WebClientResponseException} (the streaming path - see {@link
     * ProviderErrorTranslator}'s class javadoc) or {@link RestClientResponseException}, {@code
     * getMessage()} is just "&lt;status&gt; &lt;reason&gt; from &lt;method&gt; &lt;uri&gt;" - the
     * provider's JSON body explaining WHY is only reachable via {@code getResponseBodyAsString()}.
     * Appends the body (secret-redacted, whitespace-collapsed) when one exists anywhere in the cause
     * chain and the message doesn't already contain it - Spring AI's own
     * NonTransient/TransientAiException already embeds the body, so that shape is not duplicated.
     */
    static String messageWithProviderBody(Throwable error) {
        String message = error.getMessage();
        String body = findProviderResponseBody(error);
        if (body == null) {
            return message;
        }
        String collapsedBody =
                ProviderErrorTranslator.redactSecrets(body).replaceAll("\\s+", " ").trim();
        if (collapsedBody.isEmpty()
                || (message != null && message.replaceAll("\\s+", " ").contains(collapsedBody))) {
            return message;
        }
        return message == null || message.isBlank()
                ? collapsedBody
                : message + " — " + collapsedBody;
    }

    /**
     * Walks {@code error}'s cause chain (including {@code error} itself, capped at {@link
     * #MAX_CAUSE_DEPTH}) for the first exception type known to carry the upstream HTTP response
     * body separately from its message, including the self-referential-cause guard.
     */
    private static String findProviderResponseBody(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof WebClientResponseException wcre) {
                return wcre.getResponseBodyAsString();
            }
            if (current instanceof RestClientResponseException rcre) {
                return rcre.getResponseBodyAsString();
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
            depth++;
        }
        return null;
    }

    static String stackTraceOf(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
