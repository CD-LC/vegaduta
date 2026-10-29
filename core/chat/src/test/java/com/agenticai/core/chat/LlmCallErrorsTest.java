package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The error text persisted on a {@code llm_call_log} row: the provider's own JSON body appended to
 * an otherwise-bare status message (never duplicated when Spring AI already embedded it),
 * whitespace-collapsed and secret-redacted, plus the stream-cancellation signature that marks a
 * client-aborted stream as "not a failure".
 */
class LlmCallErrorsTest {

    private static WebClientResponseException upstream(String body) {
        return WebClientResponseException.create(
                429,
                "Too Many Requests",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void aBareStatusMessageGainsTheProvidersBody() {
        String message =
                LlmCallErrors.messageWithProviderBody(upstream("{\"error\":\"slow down\"}"));

        assertTrue(message.contains("429 Too Many Requests"), message);
        assertTrue(message.contains("{\"error\":\"slow down\"}"), message);
        assertTrue(message.contains(" — "), "message and body are joined by the em-dash separator");
    }

    @Test
    void theBodyIsCollapsedToOneLineAndSecretRedacted() {
        String message =
                LlmCallErrors.messageWithProviderBody(
                        upstream(
                                "{\n  \"error\": \"call https://api.example.com/v1?key=AIzaSecretValue123 failed\"\n}"));

        assertFalse(message.contains("\n"), message);
        assertFalse(
                message.contains("AIzaSecretValue123"),
                "an embedded credential never reaches the log");
        assertTrue(message.contains("key=***"), message);
    }

    @Test
    void aMessageAlreadyCarryingTheBodyIsNotDoubled() {
        // Spring AI's NonTransient/TransientAiException shape embeds the body in the message
        // itself.
        RuntimeException e =
                new RuntimeException(
                        "429 - {\"error\":\"slow down\"}", upstream("{\"error\":\"slow down\"}"));

        String message = LlmCallErrors.messageWithProviderBody(e);

        assertEquals(message.indexOf("slow down"), message.lastIndexOf("slow down"), message);
    }

    @Test
    void noBodyAnywhereLeavesTheMessageAlone() {
        assertNull(LlmCallErrors.messageWithProviderBody(new RuntimeException((String) null)));
        assertTrue(
                LlmCallErrors.messageWithProviderBody(new RuntimeException("plain"))
                        .equals("plain"));
    }

    @Test
    void anEmptyBodyLeavesTheMessageAlone() {
        WebClientResponseException empty =
                WebClientResponseException.create(
                        500,
                        "Server Error",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8);

        assertTrue(LlmCallErrors.messageWithProviderBody(empty).contains("500"));
    }

    @Test
    void theBodyIsFoundThroughWrappers() {
        RuntimeException wrapped =
                new RuntimeException("generic wrapper", upstream("{\"error\":\"quota\"}"));

        String message = LlmCallErrors.messageWithProviderBody(wrapped);

        assertTrue(message.contains("generic wrapper"), message);
        assertTrue(message.contains("quota"), message);
    }

    @Test
    void onlyTheStreamCancelledPrefixMarksACancellation() {
        assertTrue(
                LlmCallErrors.isStreamCancellation(
                        new CancellationException(
                                LlmMetricsAdvisor.STREAM_CANCELLED_MESSAGE_PREFIX + " by client")));
        assertFalse(LlmCallErrors.isStreamCancellation(new CancellationException()));
        assertFalse(
                LlmCallErrors.isStreamCancellation(new CancellationException("some other cancel")));
        assertFalse(
                LlmCallErrors.isStreamCancellation(
                        new RuntimeException(
                                LlmMetricsAdvisor.STREAM_CANCELLED_MESSAGE_PREFIX + " by client")));
    }

    @Test
    void stackTraceOfRendersTheWholeTrace() {
        String trace = LlmCallErrors.stackTraceOf(new IllegalStateException("boom"));

        assertTrue(trace.contains("IllegalStateException"));
        assertTrue(trace.contains("boom"));
        assertTrue(trace.contains("LlmCallErrorsTest"), "the test frame belongs in the trace");
    }
}
