package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Locks in what {@code llm_call_log.error_message} actually carries on a
 * failed call. The original bug: {@code error.getMessage()} was persisted
 * verbatim, and for a raw {@link WebClientResponseException} (the streaming
 * path - see {@link ProviderErrorTranslator}'s class javadoc for the shape
 * taxonomy) that message is just "400 Bad Request from POST
 * https://api.x.ai/v1/chat/completions" - the provider's JSON body explaining
 * WHY (model does not exist, unsupported parameter, ...) was silently
 * discarded even though {@code getResponseBodyAsString()} had it the whole
 * time. The advisor now appends the (secret-redacted, whitespace-collapsed)
 * body after the original message.
 */
class LlmMetricsAdvisorTest {

    private final LlmCallLogService callLogService = mock(LlmCallLogService.class);
    private final CallAdvisorChain chain = mock(CallAdvisorChain.class);
    private final LlmMetricsAdvisor advisor = new LlmMetricsAdvisor(
        new LlmCallMetricsService(), callLogService, ProviderName.OPENAI, "grok-3",
        new SimpleMeterRegistry(), new LlmPricingCatalog());

    private static ChatClientRequest request() {
        return new ChatClientRequest(new Prompt("hi"), Map.of());
    }

    /** Runs one failing {@code adviseCall} and returns the {@code errorMessage} handed to {@link LlmCallLogService#record}. */
    private String recordedErrorMessage(RuntimeException failure) {
        when(chain.nextCall(any())).thenThrow(failure);
        assertThrows(RuntimeException.class, () -> advisor.adviseCall(request(), chain));
        ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
        verify(callLogService).record(
            any(), any(), any(), any(), any(), any(), any(),
            eq(false), errorMessage.capture(), any(), any(), any(),
            anyLong(), any(), any(), any(), any(),
            // cacheCreationTokens, cacheReadTokens - added when
            // LlmPricingCatalog.estimateCostUsd gained the Anthropic
            // prompt-cache overload (2026-08-08).
            any(), any(),
            // agentVersionId - added with agent versioning (5cdf7cf5).
            any(),
            // billableReasoningTokens - added 2026-10-05 so the durable
            // row prices the same reasoning tokens the metric does.
            any(),
            // estimatedCostUsdOverride - the advisor's own estimate (zero for a local zero_cost row).
            any());
        return errorMessage.getValue();
    }

    @Test
    void webClientResponseExceptionBodyIsAppendedAfterTheOriginalMessage() {
        WebClientResponseException wcre = WebClientResponseException.create(
            400, "Bad Request", HttpHeaders.EMPTY,
            "{\"error\":{\"message\":\"The model `grok-5` does not exist\"}}".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

        String recorded = recordedErrorMessage(wcre);

        assertTrue(recorded.startsWith(wcre.getMessage()),
            "the original exception message must stay first, unchanged: " + recorded);
        assertTrue(recorded.contains(" — "), "message and body are joined with the em-dash separator: " + recorded);
        assertTrue(recorded.contains("The model `grok-5` does not exist"),
            "the provider's own reason must be persisted, not just the status line: " + recorded);
    }

    @Test
    void providerBodyIsFoundThroughWrapperExceptions() {
        // Spring AI's model clients routinely wrap the HTTP exception in a
        // generic RuntimeException (see ProviderErrorTranslator's "Failed to
        // generate content" writeup) - the body must be recovered from the
        // cause chain, not just the outermost exception.
        WebClientResponseException wcre = WebClientResponseException.create(
            404, "Not Found", HttpHeaders.EMPTY,
            "{\"error\":{\"message\":\"model not found\"}}".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

        String recorded = recordedErrorMessage(new RuntimeException("Failed to generate content", wcre));

        assertTrue(recorded.startsWith("Failed to generate content"));
        assertTrue(recorded.contains("model not found"), recorded);
    }

    @Test
    void restClientResponseExceptionBodyIsAppendedToo() {
        RestClientResponseException rcre = new RestClientResponseException(
            "422 Unprocessable Entity", HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity", HttpHeaders.EMPTY,
            "{\"error\":{\"message\":\"unsupported parameter: reasoning_effort\"}}".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

        String recorded = recordedErrorMessage(rcre);

        assertTrue(recorded.contains("unsupported parameter: reasoning_effort"), recorded);
    }

    @Test
    void credentialShapedSubstringsInTheBodyAreRedacted() {
        WebClientResponseException wcre = WebClientResponseException.create(
            401, "Unauthorized", HttpHeaders.EMPTY,
            "{\"error\":{\"message\":\"Bearer sk_live_superSecretValue123 is not valid\"}}".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

        String recorded = recordedErrorMessage(wcre);

        assertTrue(recorded.contains("Bearer ***"), recorded);
        assertFalse(recorded.contains("sk_live_superSecretValue123"),
            "a credential that reached an error body must never be persisted to llm_call_log");
    }

    @Test
    void plainMessageWithoutATypedHttpExceptionIsPersistedUnchanged() {
        // Spring AI's own NonTransient/TransientAiException shape - the ENTIRE
        // message is already "<status> - <body>" with no typed HTTP exception
        // in the chain, so there is nothing separate to append.
        String message = "404 - {\"error\":{\"message\":\"model not found\"}}";

        String recorded = recordedErrorMessage(new RuntimeException(message));

        assertEquals(message, recorded);
    }

    @Test
    void bodyAlreadyContainedInTheMessageIsNotAppendedAgain() {
        String body = "{\"error\":{\"message\":\"nope\"}}";
        RestClientResponseException rcre = new RestClientResponseException(
            "404 - " + body, HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
            body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        String recorded = recordedErrorMessage(rcre);

        assertEquals("404 - " + body, recorded);
        assertFalse(recorded.contains(" — "), "an already-embedded body must not be duplicated: " + recorded);
    }

    @Test
    void bodyWhitespaceIsCollapsedAndTheTruncateCapStillApplies() {
        String longReason = "x".repeat(3000);
        WebClientResponseException wcre = WebClientResponseException.create(
            400, "Bad Request", HttpHeaders.EMPTY,
            ("{\n  \"error\": {\n    \"message\": \"" + longReason + "\"\n  }\n}").getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);

        String recorded = recordedErrorMessage(wcre);

        assertFalse(recorded.contains("\n"), "a multi-line JSON body must collapse to one line");
        assertEquals(2001, recorded.length(), "the existing 2000-char cap (+ellipsis) must remain the final step");
        assertTrue(recorded.endsWith("…"));
    }
}
