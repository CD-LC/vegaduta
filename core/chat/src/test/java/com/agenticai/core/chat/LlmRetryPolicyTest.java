package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The retry policy for transient provider failures: bounded attempts, exponential backoff with jitter,
 * the provider's own Retry-After honoured, and 429 retried (it used to be passed straight to the
 * fallback provider without a second try). The sleeper is recorded, never slept on.
 */
class LlmRetryPolicyTest {

    private static LlmRetryProperties props() {
        LlmRetryProperties p = new LlmRetryProperties();
        p.setMaxAttempts(4);
        p.setInitialBackoff(Duration.ofSeconds(1));
        p.setMaxBackoff(Duration.ofSeconds(10));
        p.setJitter(0.0);
        return p;
    }

    private static WebClientResponseException http(int status, String retryAfter, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.add("Retry-After", retryAfter);
        }
        return WebClientResponseException.create(
            status, "status " + status, headers, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static final class RecordingSleeper implements org.springframework.retry.backoff.Sleeper {
        final List<Long> sleptMillis = new ArrayList<>();

        @Override
        public void sleep(long backOffPeriod) {
            sleptMillis.add(backOffPeriod);
        }
    }

    // ---- 429 and 5xx are retried ---------------------------------------------------------------

    @Test
    void aRateLimitedCallIsRetriedAfterTheProvidersRetryAfterHeader() {
        RecordingSleeper sleeper = new RecordingSleeper();
        LlmRetry retry = new SpringRetryLlmRetry(new LlmRetryPolicy(props(), () -> 0.0), sleeper);
        AtomicInteger calls = new AtomicInteger();

        String out = retry.execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw http(429, "7", "{\"error\":\"slow down\"}");
            }
            return "ok";
        });

        assertEquals("ok", out);
        assertEquals(2, calls.get());
        assertEquals(List.of(7_000L), sleeper.sleptMillis, "waits what the provider asked for, not the 1s default");
    }

    @Test
    void retryAfterMillisecondsAndHttpDatesAreUnderstood() {
        LlmRetryPolicy policy = new LlmRetryPolicy(props(), () -> 0.0);

        HttpHeaders ms = new HttpHeaders();
        ms.add("retry-after-ms", "2500");
        WebClientResponseException withMs = WebClientResponseException.create(
            429, "Too Many", ms, new byte[0], StandardCharsets.UTF_8);
        assertEquals(Duration.ofMillis(2500), policy.backoff(1, withMs));

        // An HTTP-date in the past means "now": the exponential delay applies.
        WebClientResponseException past = http(503, "Wed, 21 Oct 2015 07:28:00 GMT", "");
        assertEquals(Duration.ofSeconds(1), policy.backoff(1, past));
    }

    @Test
    void aRateLimitHintInTheBodyIsHonouredWhenNoHeaderSurvived() {
        // Spring AI's default error handler throws "<status> - <body>" and drops the headers.
        LlmRetryPolicy policy = new LlmRetryPolicy(props(), () -> 0.0);
        NonTransientAiException openAi = new NonTransientAiException(
            "429 - {\"error\":{\"message\":\"Rate limit reached for gpt. Please try again in 3.5s.\"}}");
        NonTransientAiException gemini = new NonTransientAiException(
            "429 - {\"error\":{\"code\":429,\"details\":[{\"retryDelay\":\"12s\"}]}}");

        assertTrue(policy.isRetryable(openAi));
        assertEquals(Duration.ofMillis(3500), policy.backoff(1, openAi));
        assertEquals(Duration.ofSeconds(12), policy.backoff(1, gemini));
    }

    @Test
    void aProviderAskingForALongerWaitThanTheCapIsNotWaitedOnSoFallbackCanTakeOver() {
        LlmRetryProperties p = props();
        p.setMaxRetryAfter(Duration.ofSeconds(30));
        RecordingSleeper sleeper = new RecordingSleeper();
        LlmRetry retry = new SpringRetryLlmRetry(new LlmRetryPolicy(p, () -> 0.0), sleeper);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(WebClientResponseException.class, () -> retry.execute(() -> {
            calls.incrementAndGet();
            throw http(429, "300", "");
        }));

        assertEquals(1, calls.get());
        assertTrue(sleeper.sleptMillis.isEmpty());
    }

    @Test
    void rateLimitRetryCanBeSwitchedOff() {
        LlmRetryProperties p = props();
        p.setRetryRateLimited(false);
        LlmRetryPolicy policy = new LlmRetryPolicy(p, () -> 0.0);

        assertFalse(policy.isRetryable(http(429, "1", "")));
        assertTrue(policy.isRetryable(http(503, null, "")), "5xx stays retryable");
    }

    @Test
    void retryAfterCanBeIgnoredByConfiguration() {
        LlmRetryProperties p = props();
        p.setHonorRetryAfter(false);

        assertEquals(Duration.ofSeconds(1), new LlmRetryPolicy(p, () -> 0.0).backoff(1, http(429, "7", "")));
    }

    @Test
    void serverErrorsOnTheStreamingClientAreRetryableToo() {
        LlmRetryPolicy policy = new LlmRetryPolicy(props(), () -> 0.0);

        assertTrue(policy.isRetryable(http(500, null, "")));
        assertTrue(policy.isRetryable(http(503, null, "")));
        assertFalse(policy.isRetryable(http(400, null, "")));
        assertFalse(policy.isRetryable(http(401, null, "")));
        assertFalse(policy.isRetryable(http(404, null, "")));
    }

    @Test
    void aBadRequestOrAuthErrorShapedLikeSpringAisHandlerIsNotRetried() {
        LlmRetryPolicy policy = new LlmRetryPolicy(props(), () -> 0.0);

        assertFalse(policy.isRetryable(new NonTransientAiException("400 - bad request")));
        assertFalse(policy.isRetryable(new NonTransientAiException("401 - invalid api key")));
        assertFalse(policy.isRetryable(new NonTransientAiException("429")), "no status shape: not a rate limit");
        assertTrue(policy.isRetryable(new TransientAiException("503 - overloaded")));
    }

    // ---- backoff -------------------------------------------------------------------------------

    @Test
    void backoffIsExponentialCappedAndJittered() {
        LlmRetryProperties p = props();
        p.setJitter(0.5);
        TransientAiException failure = new TransientAiException("503");

        // random = 0 -> the full exponential delay; random = 1 -> down to (1 - jitter) of it.
        LlmRetryPolicy full = new LlmRetryPolicy(p, () -> 0.0);
        assertEquals(Duration.ofSeconds(1), full.backoff(1, failure));
        assertEquals(Duration.ofSeconds(2), full.backoff(2, failure));
        assertEquals(Duration.ofSeconds(4), full.backoff(3, failure));
        assertEquals(Duration.ofSeconds(10), full.backoff(5, failure), "capped at max-backoff");

        LlmRetryPolicy jittered = new LlmRetryPolicy(p, () -> 1.0);
        assertEquals(Duration.ofMillis(500), jittered.backoff(1, failure));
        assertEquals(Duration.ofSeconds(1), jittered.backoff(2, failure));
    }

    @Test
    void retriesSleepTheBackoffBetweenAttemptsAndStopAtTheConfiguredCount() {
        RecordingSleeper sleeper = new RecordingSleeper();
        LlmRetry retry = new SpringRetryLlmRetry(new LlmRetryPolicy(props(), () -> 0.0), sleeper);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(TransientAiException.class, () -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new TransientAiException("503");
        }));

        assertEquals(4, calls.get());
        assertEquals(List.of(1_000L, 2_000L, 4_000L), sleeper.sleptMillis);
    }

    @Test
    void theLongerOfTheBackoffAndTheRetryAfterWins() {
        LlmRetryPolicy policy = new LlmRetryPolicy(props(), () -> 0.0);

        assertEquals(Duration.ofSeconds(4), policy.backoff(3, http(503, "2", "")), "backoff 4s beats Retry-After 2s");
        assertEquals(Duration.ofSeconds(9), policy.backoff(1, http(503, "9", "")));
    }

    @Test
    void theYmlDefaultsAreTheCodeDefaults() {
        LlmRetryProperties d = new LlmRetryProperties();
        assertEquals(2, d.getMaxAttempts());
        assertEquals(Duration.ofSeconds(1), d.getInitialBackoff());
        assertEquals(Duration.ofSeconds(10), d.getMaxBackoff());
        assertTrue(d.isRetryRateLimited());
        assertTrue(d.isHonorRetryAfter());
    }
}
