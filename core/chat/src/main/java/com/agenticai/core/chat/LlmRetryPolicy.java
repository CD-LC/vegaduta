package com.agenticai.core.chat;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.ServerException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Which provider failures are worth another attempt of the SAME call, and how long to wait first.
 * One policy for the non-streaming calls ({@link SpringRetryLlmRetry}) and for a streaming turn that
 * has not produced a token yet (the turn module reads {@link #isRetryable}, {@link #backoff} and
 * {@link #maxAttempts}).
 *
 * <ul>
 *   <li><b>Retried</b>: HTTP 5xx, HTTP 429 (unless {@code retry-rate-limited} is off), connect
 *       failures and other I/O errors before a response.
 *   <li><b>Never retried</b>: a read timeout (it already cost the full wait; a retry doubles the
 *       stall while the provider fallback waits), a refused model-server URL, any other 4xx, and a
 *       failure whose Retry-After is longer than {@code max-retry-after} (the fallback provider is
 *       the better use of that time).
 *   <li><b>Backoff</b>: exponential from {@code initial-backoff}, capped at {@code max-backoff}, with
 *       {@code jitter} so callers that failed together do not retry together; when the provider
 *       stated how long to wait ({@link RetryAfter}) the wait is never shorter than that.
 * </ul>
 */
public final class LlmRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(LlmRetryPolicy.class);

    /** The catalog key of the tenant's attempts per call; platform value {@code app.llm.retry.max-attempts}. */
    public static final String MAX_ATTEMPTS_KEY = "chat.retry.maxAttempts";

    private static final int MAX_CAUSE_DEPTH = 16;

    private static final LlmRetryPolicy DEFAULT = new LlmRetryPolicy(new LlmRetryProperties());

    private final LlmRetryProperties props;
    private final DoubleSupplier random;

    /** Where the tenant's override of the attempts comes from; resolved at call time. Null supplier result = none. */
    private volatile Supplier<SettingsResolver> settings = () -> null;

    public LlmRetryPolicy(LlmRetryProperties props) {
        this(props, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Test seam: {@code random} yields values in [0, 1). */
    public LlmRetryPolicy(LlmRetryProperties props, DoubleSupplier random) {
        this.props = props == null ? new LlmRetryProperties() : props;
        this.random = random;
    }

    /** The policy with the shipped defaults. */
    public static LlmRetryPolicy defaults() {
        return DEFAULT;
    }

    /**
     * Lets the tenant's {@code chat.retry.maxAttempts} setting replace {@code app.llm.retry.max-attempts}. The supplier
     * is asked on every use (the resolver bean may not exist yet when this policy is built) and may return null (the
     * platform value applies). Not for the shared {@link #defaults()} instance. Returns this.
     */
    public LlmRetryPolicy useSettings(Supplier<SettingsResolver> resolver) {
        this.settings = resolver == null ? () -> null : resolver;
        return this;
    }

    /**
     * Attempts per call, the first included, for the tenant of the calling thread: the tenant's
     * {@code chat.retry.maxAttempts} override when there is one, else {@code app.llm.retry.max-attempts}. On a thread
     * that carries no tenant this is the platform value.
     */
    public int maxAttempts() {
        int platform = Math.max(1, props.getMaxAttempts());
        SettingsResolver resolver;
        try {
            resolver = settings.get();
            return resolver == null ? platform : Math.max(1, resolver.getInt(MAX_ATTEMPTS_KEY, SettingContext.tenant()));
        } catch (RuntimeException e) {
            log.warn("Could not read {}; using app.llm.retry.max-attempts: {}", MAX_ATTEMPTS_KEY, e.toString());
            return platform;
        }
    }

    /** As {@link #maxAttempts()} for the tenant {@code scope} carries, whatever thread this is called on. */
    public int maxAttempts(TenantScope scope) {
        return scope == null ? maxAttempts() : scope.get(this::maxAttempts);
    }

    /** Whether a streaming turn retries a failure that came before its first token. */
    public boolean retriesStreamsBeforeFirstToken() {
        return props.isStreamPreFirstToken() && maxAttempts() > 1;
    }

    /** As {@link #retriesStreamsBeforeFirstToken()} for the tenant {@code scope} carries. */
    public boolean retriesStreamsBeforeFirstToken(TenantScope scope) {
        return props.isStreamPreFirstToken() && maxAttempts(scope) > 1;
    }

    /** True when {@code failure} is worth another attempt; see the class javadoc. */
    public boolean isRetryable(Throwable failure) {
        if (failure == null) {
            return false;
        }
        boolean transientSeen = false;
        int depth = 0;
        for (Throwable t = failure; t != null && depth++ < MAX_CAUSE_DEPTH; t = t.getCause() == t ? null : t.getCause()) {
            if (isReadTimeout(t) || t instanceof LlmHttpClientFactory.ModelServerUrlNotAllowedException) {
                return false;
            }
            if (t instanceof TransientAiException
                    || t instanceof ResourceAccessException
                    || t instanceof WebClientRequestException
                    || t instanceof ServerException) {
                transientSeen = true;
            }
        }
        if (props.isHonorRetryAfter()) {
            Optional<Duration> asked = RetryAfter.of(failure);
            if (asked.isPresent() && asked.get().compareTo(props.getMaxRetryAfter()) > 0) {
                return false;
            }
        }
        int status = statusOf(failure);
        if (status == 429) {
            return props.isRetryRateLimited();
        }
        return transientSeen || status >= 500;
    }

    /**
     * How long to wait before retry number {@code retryNumber} (1 for the first retry) of a call that
     * failed with {@code failure}.
     */
    public Duration backoff(int retryNumber, Throwable failure) {
        long initial = Math.max(1, props.getInitialBackoff().toMillis());
        long max = Math.max(initial, props.getMaxBackoff().toMillis());
        int doublings = Math.min(Math.max(0, retryNumber - 1), 30);
        long exponential = Math.min(max, initial * (1L << doublings));
        double kept = 1.0 - props.getJitter() * Math.min(1.0, Math.max(0.0, random.getAsDouble()));
        long millis = Math.max(1, (long) (exponential * kept));
        if (props.isHonorRetryAfter()) {
            Optional<Duration> asked = RetryAfter.of(failure);
            if (asked.isPresent()) {
                millis = Math.max(millis, asked.get().toMillis());
            }
        }
        return Duration.ofMillis(millis);
    }

    /** The upstream HTTP status found in {@code failure}'s cause chain, or {@code 0} when there is none. */
    public static int statusOf(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth++ < MAX_CAUSE_DEPTH; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof WebClientResponseException w) {
                return w.getStatusCode().value();
            }
            if (t instanceof RestClientResponseException r) {
                return r.getStatusCode().value();
            }
            if (t instanceof ApiException api) {
                return api.code();
            }
            if (t instanceof UpstreamProviderException upstream) {
                return upstream.getUpstreamStatus();
            }
        }
        ProviderErrorCauses.StatusAndBody structured = ProviderErrorCauses.findStructuredStatus(failure);
        return structured == null ? 0 : structured.status();
    }

    private static boolean isReadTimeout(Throwable t) {
        if (t instanceof HttpConnectTimeoutException) {
            return false;
        }
        if (t instanceof HttpTimeoutException) {
            return true; // JDK HttpClient's per-request (response) timeout
        }
        if (t instanceof SocketTimeoutException) {
            // Apache HttpClient 5's read timeout. Its connect timeout is a ConnectTimeoutException - not a
            // SocketTimeoutException - and stays retryable; a plain-socket "Connect timed out" is excluded.
            String message = t.getMessage();
            return message == null || !message.toLowerCase(Locale.ROOT).contains("connect");
        }
        return false;
    }
}
