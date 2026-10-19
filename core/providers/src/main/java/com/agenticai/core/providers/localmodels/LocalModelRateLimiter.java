package com.agenticai.core.providers.localmodels;

import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-caller, per-minute caps on the local-models API. The probe sends a chat request to a model
 * (seconds of CPU or GPU) and a pull starts a multi-gigabyte download, so those are tight; the
 * read endpoints are loose enough for a page that polls.
 *
 * <p>In-process fixed windows ({@link LocalRateLimitFallback}), not Redis: this surface is meant to
 * work on a laptop install with no Redis, and the caller is a tenant admin of a single replica in
 * that setup. With several core replicas the effective cap is per replica, still a hard bound.
 */
@Component
public class LocalModelRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final long readsPerMinute;
    private final long probesPerMinute;
    private final long pullsPerMinute;

    public LocalModelRateLimiter(
            @Value("${app.local-models.rate-limit.reads-per-minute:120}") long readsPerMinute,
            @Value("${app.local-models.rate-limit.probes-per-minute:6}") long probesPerMinute,
            @Value("${app.local-models.rate-limit.pulls-per-minute:6}") long pullsPerMinute) {
        this.readsPerMinute = readsPerMinute;
        this.probesPerMinute = probesPerMinute;
        this.pullsPerMinute = pullsPerMinute;
    }

    /** Recommendation and pull-status reads. */
    public void checkRead(String caller) {
        check("read", caller, readsPerMinute);
    }

    /** The tool-calling probe. */
    public void checkProbe(String caller) {
        check("probe", caller, probesPerMinute);
    }

    /** Starting a download. */
    public void checkPull(String caller) {
        check("pull", caller, pullsPerMinute);
    }

    private static void check(String kind, String caller, long max) {
        if (!LocalRateLimitFallback.tryAcquire("local-models:" + kind + ":" + caller, max, WINDOW)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please slow down and retry shortly.");
        }
    }
}
