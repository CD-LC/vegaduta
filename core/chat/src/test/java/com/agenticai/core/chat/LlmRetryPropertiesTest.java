package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@code app.llm.retry.*}: the bounded retry of a transient provider failure. The defaults are
 * the shipped {@code application.yml} values; {@code maxAttempts} clamps at 1 ("never retry") and
 * {@code jitter} clamps into [0, 1].
 */
class LlmRetryPropertiesTest {

    @Test
    void theShippedDefaultsAreTheDocumentedOnes() {
        LlmRetryProperties p = new LlmRetryProperties();

        assertEquals(2, p.getMaxAttempts());
        assertEquals(Duration.ofSeconds(1), p.getInitialBackoff());
        assertEquals(Duration.ofSeconds(10), p.getMaxBackoff());
        assertEquals(0.5, p.getJitter());
        assertTrue(p.isRetryRateLimited());
        assertTrue(p.isHonorRetryAfter());
        assertEquals(Duration.ofSeconds(30), p.getMaxRetryAfter());
        assertTrue(p.isStreamPreFirstToken());
    }

    @Test
    void maxAttemptsCanNeverGoBelowOne() {
        LlmRetryProperties p = new LlmRetryProperties();
        p.setMaxAttempts(0);
        assertEquals(1, p.getMaxAttempts());
        p.setMaxAttempts(-9);
        assertEquals(1, p.getMaxAttempts());
        p.setMaxAttempts(5);
        assertEquals(5, p.getMaxAttempts());
    }

    @Test
    void jitterIsClampedIntoTheUnitInterval() {
        LlmRetryProperties p = new LlmRetryProperties();
        p.setJitter(-0.5);
        assertEquals(0.0, p.getJitter());
        p.setJitter(2.0);
        assertEquals(1.0, p.getJitter());
        p.setJitter(0.25);
        assertEquals(0.25, p.getJitter());
    }

    @Test
    void everyOtherSetterRoundTrips() {
        LlmRetryProperties p = new LlmRetryProperties();
        p.setInitialBackoff(Duration.ofMillis(250));
        p.setMaxBackoff(Duration.ofMinutes(1));
        p.setMaxRetryAfter(Duration.ofSeconds(90));
        p.setRetryRateLimited(false);
        p.setHonorRetryAfter(false);
        p.setStreamPreFirstToken(false);

        assertEquals(Duration.ofMillis(250), p.getInitialBackoff());
        assertEquals(Duration.ofMinutes(1), p.getMaxBackoff());
        assertEquals(Duration.ofSeconds(90), p.getMaxRetryAfter());
        assertFalse(p.isRetryRateLimited());
        assertFalse(p.isHonorRetryAfter());
        assertFalse(p.isStreamPreFirstToken());
    }
}
