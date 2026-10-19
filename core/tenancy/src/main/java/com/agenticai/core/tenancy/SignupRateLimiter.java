package com.agenticai.core.tenancy;

import com.agenticai.core.security.ratelimit.RedisFixedWindowCounter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter for {@code POST /api/signup}, keyed by client
 * IP. Reuses the existing {@link StringRedisTemplate} (already a dependency
 * for {@link com.agenticai.core.agents.memory.SessionMemoryService}) with a
 * plain {@code INCR} + conditional {@code EXPIRE} rather than pulling in a
 * dedicated rate-limiting library (Bucket4j etc.) - one counter key per
 * IP-per-window is enough for an unauthenticated signup endpoint at Phase 0
 * scale, and Redis-backed means it is correct across multiple {@code core}
 * instances, not just single-process (unlike an in-memory
 * {@code ConcurrentHashMap} counter would be).
 *
 * <p><b>Known limitation, stated honestly:</b> {@code INCR} then a
 * conditional {@code EXPIRE} is two round-trips, not atomic - under a tight
 * race at the very first request of a new window, two concurrent callers
 * could each see {@code count == 1} and both set the TTL, which is harmless
 * (same TTL value either way), or in a rarer interleaving the window could
 * end up very slightly longer than configured. Acceptable for an abuse
 * throttle (approximate is fine); a Lua script would make it atomic if this
 * ever needs to be exact.
 *
 * <p><b>{@code maxAttempts}/{@code window}, changed after direct feedback
 * ("too many signup attempts" while repeatedly testing signup, plus "I want
 * a central control in admin platform maintenance to set all these values
 * in UI"):</b> these are now read from {@link PlatformSettingsService}
 * (backed by {@code control_plane.platform_setting}, admin-editable via
 * {@code PlatformController}) on every call, not fixed at JVM startup via
 * {@code @Value}. The {@code @Value}-annotated constructor params below are
 * kept ONLY as the fallback {@code defaultIfMissing} passed to {@link
 * PlatformSettingsService#getInt}, so behavior is unchanged for anyone who
 * hasn't touched the new admin UI (same defaults as before: 5 attempts per
 * 60-minute window) and a database outage degrades to those same fixed
 * defaults rather than failing every signup.
 */
@Component
@Profile("security")
public class SignupRateLimiter {

    private static final String KEY_PREFIX = "agentic:signup-rate:";

    private final StringRedisTemplate redisTemplate;
    private final PlatformSettingsService platformSettingsService;
    private final int defaultMaxAttempts;
    private final long defaultWindowMinutes;

    public SignupRateLimiter(
        StringRedisTemplate redisTemplate,
        PlatformSettingsService platformSettingsService,
        @Value("${app.signup.rate-limit.max-attempts:5}") int defaultMaxAttempts,
        @Value("${app.signup.rate-limit.window-minutes:60}") long defaultWindowMinutes
    ) {
        this.redisTemplate = redisTemplate;
        this.platformSettingsService = platformSettingsService;
        this.defaultMaxAttempts = defaultMaxAttempts;
        this.defaultWindowMinutes = defaultWindowMinutes;
    }

    /**
     * @return true if this call is within the limit (and has been counted
     *     against it); false if the caller has already exhausted their
     *     attempts for the current window.
     */
    public boolean tryAcquire(String clientIp) {
        int maxAttempts = platformSettingsService.getInt("signup.rate-limit.max-attempts", defaultMaxAttempts);
        Duration window = Duration.ofMinutes(
            platformSettingsService.getInt("signup.rate-limit.window-minutes", (int) defaultWindowMinutes)
        );

        // Atomic INCR+EXPIRE (a lost EXPIRE used to lock an IP out of signup
        // forever); falls back to an in-process counter when Redis is down -
        // signup must neither 500 nor become unthrottled on a Redis outage.
        return RedisFixedWindowCounter.tryAcquire(redisTemplate, KEY_PREFIX + clientIp, maxAttempts, window);
    }
}
