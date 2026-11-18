package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Characterisation of the user-facing text and status for each provider error shape. It changes no
 * production code. The framework upgrade swaps the HTTP clients under every provider, so these
 * assertions are the acceptance criterion for "error mapping still works": if a shape stops
 * reaching {@link ProviderErrorTranslator} intact, one of these fails.
 *
 * <p>Shapes covered, by provider family:
 *
 * <ul>
 *   <li>OpenAI-compatible (OpenRouter, Grok, Groq): {@link WebClientResponseException}.
 *   <li>Anthropic: {@code Response exception, Status: [..], Body:[..]}.
 *   <li>Gemini and Ollama (Spring AI retry shape): {@code NNN - body} inside a generic wrapper.
 *   <li>Bedrock and anything else without a structured status: the unstructured branch.
 * </ul>
 */
class ProviderErrorConsumerTextCharacterisationTest {

    private static final String CREDENTIALS =
            "The AI provider rejected our credentials. Check the API key for this provider in Providers settings.";
    private static final String BUSY =
            "The AI provider is receiving too many requests right now. Please wait a moment and try again.";
    private static final String UNAVAILABLE =
            "The AI provider is temporarily unavailable. Please try again in a moment.";
    private static final String TOO_SLOW =
            "The AI provider took too long to respond. Please try again.";
    private static final String GENERIC =
            "The AI provider call failed. Please try again, and contact support if this keeps happening.";

    private final ProviderErrorTranslator translator =
            new ProviderErrorTranslator(new ObjectMapper(), null);

    private static WebClientResponseException webClient(
            int status, String reason, HttpHeaders headers, String body) {
        return WebClientResponseException.create(
                status,
                reason,
                headers,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private static RuntimeException anthropicShape(int status, String body) {
        return new RuntimeException(
                "Response exception, Status: [" + status + "], Body:[" + body + "]");
    }

    private static RuntimeException springAiRetryShape(int status, String body) {
        return new RuntimeException(
                "Failed to generate content", new RuntimeException(status + " - " + body));
    }

    private void assertMapped(Throwable error, int status, String consumerText) {
        UpstreamProviderException result = translator.translate(error);
        assertEquals(status, result.getUpstreamStatus());
        assertEquals(consumerText, result.getConsumerMessage());
        assertFalse(
                result.getConsumerMessage().matches(".*\\b[1-5]\\d\\d\\b.*"),
                "a consumer message must never carry an HTTP status code");
    }

    @Test
    void openAiCompatibleStatusesMapToTheirConsumerText() {
        Map<Integer, String> expected =
                Map.of(
                        401, CREDENTIALS,
                        403, CREDENTIALS,
                        429, BUSY,
                        500, UNAVAILABLE,
                        502, UNAVAILABLE,
                        503, UNAVAILABLE);
        expected.forEach(
                (status, text) ->
                        assertMapped(
                                webClient(
                                        status,
                                        "reason",
                                        HttpHeaders.EMPTY,
                                        "{\"error\":{\"message\":\"upstream said no\"}}"),
                                status,
                                text));
    }

    @Test
    void aRetryAfterHeaderDoesNotChangeTheRateLimitMessage() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "30");
        assertMapped(
                webClient(
                        429,
                        "Too Many Requests",
                        headers,
                        "{\"error\":{\"message\":\"slow down\"}}"),
                429,
                BUSY);
        assertMapped(
                webClient(
                        429,
                        "Too Many Requests",
                        HttpHeaders.EMPTY,
                        "{\"error\":{\"message\":\"slow down\"}}"),
                429,
                BUSY);
    }

    @Test
    void anthropicShapedStatusesMapToTheirConsumerText() {
        assertMapped(anthropicShape(401, "{\"error\":{\"message\":\"x\"}}"), 401, CREDENTIALS);
        assertMapped(anthropicShape(403, "{\"error\":{\"message\":\"x\"}}"), 403, CREDENTIALS);
        assertMapped(anthropicShape(429, "{\"error\":{\"message\":\"x\"}}"), 429, BUSY);
        assertMapped(anthropicShape(500, "{\"error\":{\"message\":\"x\"}}"), 500, UNAVAILABLE);
        assertMapped(
                anthropicShape(529, "{\"error\":{\"message\":\"overloaded\"}}"), 529, UNAVAILABLE);
    }

    @Test
    void springAiRetryShapeUsedByGeminiAndOllamaMapsToTheirConsumerText() {
        assertMapped(springAiRetryShape(401, "{\"error\":{\"message\":\"x\"}}"), 401, CREDENTIALS);
        assertMapped(springAiRetryShape(403, "{\"error\":{\"message\":\"x\"}}"), 403, CREDENTIALS);
        assertMapped(springAiRetryShape(429, "{\"error\":{\"message\":\"x\"}}"), 429, BUSY);
        assertMapped(springAiRetryShape(503, "{\"error\":{\"message\":\"x\"}}"), 503, UNAVAILABLE);
    }

    @Test
    void aTimeoutAnywhereInTheChainBecomesA408WithTheSlowProviderText() {
        assertMapped(
                new RuntimeException("call failed", new SocketTimeoutException("Read timed out")),
                408,
                TOO_SLOW);
        assertMapped(
                new RuntimeException(
                        "call failed", new java.util.concurrent.TimeoutException("deadline")),
                408,
                TOO_SLOW);
    }

    @Test
    void aConnectionResetWithNoStructuredStatusFallsBackToTheGenericBadGateway() {
        // Bedrock's SDK exceptions and a reset socket carry no status the translator reads, so
        // they land in the unstructured branch: status 502 and the default text.
        assertMapped(
                new RuntimeException(
                        "Connection reset", new java.io.IOException("Connection reset by peer")),
                502,
                UNAVAILABLE);
    }

    @Test
    void anUnrecognisedStatusWithNoKeywordsUsesTheGenericText() {
        assertMapped(
                webClient(
                        418,
                        "I'm a teapot",
                        HttpHeaders.EMPTY,
                        "{\"error\":{\"message\":\"short and stout\"}}"),
                418,
                GENERIC);
    }
}
