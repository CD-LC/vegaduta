package com.agenticai.core.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.llm.retry.*}: the bounded retry of a transient provider failure (HTTP 429, 5xx, a
 * connection that failed before a response), shared by every non-streaming model call and by the
 * streaming turn before its first token. See {@link LlmRetryPolicy} for how the values are applied.
 *
 * <p>The defaults here are the ones shipped in {@code application.yml}; the first three keys are also
 * read by {@link LlmModelSupport}'s constructor as plain {@code @Value}s (hand-built instances in
 * tests), with the same defaults.
 */
@ConfigurationProperties(prefix = "app.llm.retry")
public class LlmRetryProperties {

    /** Attempts per call, the first one included. {@code 1} never retries. */
    private int maxAttempts = 2;

    /** Wait before the first retry; each further retry doubles it. */
    private Duration initialBackoff = Duration.ofSeconds(1);

    /** The longest the exponential backoff grows to. */
    private Duration maxBackoff = Duration.ofSeconds(10);

    /**
     * Fraction (0 to 1) of each backoff that is randomised away, so many callers that failed together do
     * not retry together: the wait is the exponential delay times {@code (1 - jitter * random)}. {@code 0}
     * is deterministic.
     */
    private double jitter = 0.5;

    /** Retry HTTP 429. Off, a rate limit goes straight to the caller's provider fallback. */
    private boolean retryRateLimited = true;

    /** Wait at least the provider's own Retry-After (header, or the hint in the error body) before retrying. */
    private boolean honorRetryAfter = true;

    /**
     * The longest Retry-After worth waiting for. A provider asking for more is not retried here, so the
     * caller's fallback provider can take over instead of the turn sitting idle.
     */
    private Duration maxRetryAfter = Duration.ofSeconds(30);

    /**
     * Retry a streaming turn when it fails before its first token (and before any tool ran), with the
     * same attempts and backoff. Off, only the non-streaming calls are retried.
     */
    private boolean streamPreFirstToken = true;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public double getJitter() {
        return jitter;
    }

    public void setJitter(double jitter) {
        this.jitter = Math.max(0.0, Math.min(1.0, jitter));
    }

    public boolean isRetryRateLimited() {
        return retryRateLimited;
    }

    public void setRetryRateLimited(boolean retryRateLimited) {
        this.retryRateLimited = retryRateLimited;
    }

    public boolean isHonorRetryAfter() {
        return honorRetryAfter;
    }

    public void setHonorRetryAfter(boolean honorRetryAfter) {
        this.honorRetryAfter = honorRetryAfter;
    }

    public Duration getMaxRetryAfter() {
        return maxRetryAfter;
    }

    public void setMaxRetryAfter(Duration maxRetryAfter) {
        this.maxRetryAfter = maxRetryAfter;
    }

    public boolean isStreamPreFirstToken() {
        return streamPreFirstToken;
    }

    public void setStreamPreFirstToken(boolean streamPreFirstToken) {
        this.streamPreFirstToken = streamPreFirstToken;
    }
}
