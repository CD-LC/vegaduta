package com.agenticai.core.quality.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.EvaluationContext;
import com.agenticai.core.quality.EvaluationResult;
import com.agenticai.core.quality.EvaluationStatus;
import com.agenticai.core.quality.EvaluationType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * The honesty contract of the LLM-judge evaluators: a wired judge's ACTUAL
 * verdict comes through, and an unavailable judge (no active provider,
 * gateway failure, blank/malformed completion) yields an explicit
 * UNAVAILABLE result - never the old fabricated neutral 0.5 that looked
 * exactly like a real measurement.
 *
 * <p>Exercised through {@link AccuracyEvaluator} and {@link SafetyEvaluator};
 * all five judge evaluators share the same pipeline
 * ({@link AbstractLlmJudgeEvaluator}), differing only in prompt text. The
 * judge {@link ChatClient} is obtained through {@link JudgeChatClientFactory},
 * which resolves the tenant's own active BYOK provider.
 */
class LlmJudgeEvaluatorTest {

    private static EvaluationContext context() {
        return new EvaluationContext(
            UUID.randomUUID(),
            "test-agent",
            "You are a helpful assistant",
            "What is the boiling point of water at sea level?",
            "Water boils at 100 degrees Celsius at sea level.",
            List.of(),
            0,
            0,
            "test-model"
        );
    }

    private static JudgeChatClientFactory factoryReturning(String verdict) {
        JudgeChatClientFactory factory = mock(JudgeChatClientFactory.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn(verdict);
        when(factory.buildJudgeClient(anyInt())).thenReturn(chatClient);
        return factory;
    }

    @Test
    void wiredJudgeReturnsTheJudgesActualVerdict() {
        JudgeChatClientFactory factory =
            factoryReturning("SCORE: 0.9\nFEEDBACK: Factually correct and well qualified.");

        EvaluationResult result = new AccuracyEvaluator(factory).evaluate(context());

        assertEquals(EvaluationStatus.EVALUATED, result.status());
        assertEquals(0.9, result.score());
        assertEquals("Factually correct and well qualified.", result.feedback());
    }

    @Test
    void unavailableJudgeDoesNotYieldAPlausibleMidRangeScore() {
        JudgeChatClientFactory factory = mock(JudgeChatClientFactory.class);
        when(factory.buildJudgeClient(anyInt()))
            .thenThrow(new RuntimeException("no active provider configured"));

        EvaluationResult result = new AccuracyEvaluator(factory).evaluate(context());

        assertEquals(EvaluationStatus.UNAVAILABLE, result.status());
        assertNull(result.score(),
            "An unavailable judge must produce NO score - the previous 0.5 here was fabricated data");
        assertTrue(result.feedback().contains("unavailable"),
            "The reason for the missing evaluation must be visible to callers");
    }

    @Test
    void blankCompletionIsUnavailableNotAVerdict() {
        JudgeChatClientFactory factory = factoryReturning("   ");

        EvaluationResult result = new AccuracyEvaluator(factory).evaluate(context());

        assertEquals(EvaluationStatus.UNAVAILABLE, result.status());
        assertNull(result.score());
    }

    @Test
    void malformedVerdictIsUnavailableNotDefaulted() {
        // The old parseScore defaulted a missing/unparseable SCORE line to
        // 0.5 - also fabrication. A verdict we can't read is a failed
        // evaluation, not a mediocre one.
        JudgeChatClientFactory factory = factoryReturning("I think this response is pretty good overall!");

        EvaluationResult result = new SafetyEvaluator(factory).evaluate(context());

        assertEquals(EvaluationStatus.UNAVAILABLE, result.status());
        assertNull(result.score());
    }

    @Test
    void safetyJudgeVerdictComesThroughForFlaggedContent() {
        JudgeChatClientFactory factory =
            factoryReturning("SCORE: 0.25\nFEEDBACK: Response ignores a guardrail in the system prompt.");

        EvaluationResult result = new SafetyEvaluator(factory).evaluate(context());

        assertEquals(EvaluationStatus.EVALUATED, result.status());
        assertEquals(0.25, result.score());
    }

    @Test
    void everyJudgeRequestCarriesTemperatureZeroOnTheRequestItself() {
        // Temperature 0 must be a property of the judge REQUEST, not only of
        // the client defaults the factory happened to build - a sampled judge
        // scores the same answer differently run to run, which the eval
        // reliability stats then misread as a regression.
        JudgeChatClientFactory factory = mock(JudgeChatClientFactory.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt(any(Prompt.class)).call().content()).thenReturn("SCORE: 0.8\nFEEDBACK: fine");
        when(factory.buildJudgeClient(anyInt())).thenReturn(chatClient);

        new HelpfulnessEvaluator(factory).evaluate(context());

        org.mockito.ArgumentCaptor<Prompt> sent = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(chatClient, org.mockito.Mockito.atLeastOnce()).prompt(sent.capture());
        Prompt prompt = sent.getAllValues().get(sent.getAllValues().size() - 1);
        assertEquals(0.0, prompt.getOptions().getTemperature());
        // Only temperature is overridden - model id and max tokens stay the
        // factory's (Spring AI merges per-request options field by field).
        assertNull(prompt.getOptions().getModel());
        assertNull(prompt.getOptions().getMaxTokens());
    }

    @Test
    void slightlyOutOfRangeButFiniteScoreIsClampedNotDiscarded() {
        JudgeChatClientFactory factory = factoryReturning("SCORE: 1.2\nFEEDBACK: Excellent.");

        EvaluationResult result = new AccuracyEvaluator(factory).evaluate(context());

        assertEquals(EvaluationStatus.EVALUATED, result.status());
        assertEquals(1.0, result.score());
    }

    @Test
    void aMalformedVerdictIsAJudgeOutageWithTheTextQuotedSoARunCanStopOnIt() {
        JudgeChatClientFactory factory = factoryReturning("Looks good to me, nice answer.");

        EvaluationResult result = new AccuracyEvaluator(factory).evaluate(context());

        assertTrue(EvaluationResult.isJudgeUnavailable(result), result.feedback());
        assertTrue(result.feedback().contains("unparseable output 'Looks good to me, nice answer.'"), result.feedback());
    }

    @Test
    void aJudgeCallThatHangsIsCutOffAtTheTimeoutAndReportedAsAnOutage() {
        JudgeChatClientFactory factory = mock(JudgeChatClientFactory.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return null;
        });
        when(factory.buildJudgeClient(anyInt())).thenReturn(chatClient);
        when(factory.callTimeout()).thenReturn(java.time.Duration.ofMillis(100));

        long started = System.nanoTime();
        EvaluationResult result = new AccuracyEvaluator(factory).evaluate(context());
        long tookMs = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(EvaluationResult.isJudgeUnavailable(result), result.feedback());
        assertTrue(result.feedback().contains("timed out after 0.1"), result.feedback());
        assertTrue(tookMs < 5_000, "cut off at the timeout, not after the provider's 10 s: " + tookMs);
    }

    @Test
    void anHttpFailureNamesItsStatus() {
        JudgeChatClientFactory factory = mock(JudgeChatClientFactory.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt(any(Prompt.class))).thenThrow(org.springframework.web.client.HttpClientErrorException.create(
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
            org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));
        when(factory.buildJudgeClient(anyInt())).thenReturn(chatClient);

        EvaluationResult result = new AccuracyEvaluator(factory).evaluate(context());

        assertTrue(EvaluationResult.isJudgeUnavailable(result), result.feedback());
        assertTrue(result.feedback().contains("HTTP 429 Too Many Requests"), result.feedback());
    }
}
