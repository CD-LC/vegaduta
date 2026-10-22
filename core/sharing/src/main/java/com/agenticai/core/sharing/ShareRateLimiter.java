package com.agenticai.core.sharing;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.logging.LogSafe;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-caller fixed-window rate limiter for the share-grant surface - the abuse control {@code
 * ShareController#grant}'s javadoc and this module's README previously flagged as "not
 * implemented": repeated grant calls with guessed emails are a user-enumeration oracle (via {@code
 * KeycloakAdminClient#findByEmail}), and flooding one resource with grants is a separate grant-spam
 * shape.
 *
 * <p>This is the platform's established Redis limiter contract - plain {@code INCR} + conditional
 * {@code EXPIRE}, fail-open on any Redis error - the exact same shape {@code SignupRateLimiter},
 * {@code LlmGatewayRateLimiterService}, {@code DeviceClaimRateLimiter} and {@code
 * AgentShareService}'s {@code tryAcquire*} family already use; deliberately NOT a new algorithm or
 * a rate-limiting library. Redis-backed means the cap is correct across multiple {@code core}
 * replicas, and fail-open means a Redis hiccup can never be the reason a legitimate share operation
 * is rejected (an abuse throttle degrading open is the right trade-off - same posture as every
 * sibling limiter).
 *
 * <p>Keys are per {@code tenantId:subject} (the authenticated caller), not per IP - every caller of
 * this surface is a signed-in tenant member, so the principal is a far better bucket than an
 * address behind shared NAT. Two independent windows: a tight one on grant <em>creation</em> (the
 * enumeration-oracle write path) and a looser one on grant <em>resolution</em> (the "who has
 * access" / "shared with me" reads).
 */
@Component
public class ShareRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ShareRateLimiter.class);
    private static final String KEY_PREFIX = "agentic:share-rate:";

    private final StringRedisTemplate redisTemplate;
    private final long maxGrantWritesPerMinute;
    private final long maxGrantReadsPerMinute;
    private final Duration window = Duration.ofMinutes(1);

    public ShareRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.sharing.rate-limit.max-grant-writes-per-minute:20}")
                    long maxGrantWritesPerMinute,
            @Value("${app.sharing.rate-limit.max-grant-reads-per-minute:120}")
                    long maxGrantReadsPerMinute) {
        this.redisTemplate = redisTemplate;
        this.maxGrantWritesPerMinute = maxGrantWritesPerMinute;
        this.maxGrantReadsPerMinute = maxGrantReadsPerMinute;
    }

    /**
     * Gate on minting/updating a grant ({@code POST .../grants}) - called BEFORE the Keycloak email
     * lookup, so an over-limit caller never exercises the enumeration oracle at all.
     *
     * @throws ShareRateLimitExceededException (429) once the caller exceeds the per-minute write
     *     cap.
     */
    public void assertGrantWriteAllowed() {
        assertWithinLimit("write", maxGrantWritesPerMinute);
    }

    /**
     * Gate on resolving grants ({@code GET .../grants}, {@code GET /api/shared-with-me}).
     *
     * @throws ShareRateLimitExceededException (429) once the caller exceeds the per-minute read
     *     cap.
     */
    public void assertGrantReadAllowed() {
        assertWithinLimit("read", maxGrantReadsPerMinute);
    }

    private void assertWithinLimit(String op, long maxPerWindow) {
        String principal =
                TenantContext.current()
                        .map(
                                ctx ->
                                        ctx.getTenantId()
                                                + ":"
                                                + (ctx.getSubject() == null
                                                        ? "*"
                                                        : ctx.getSubject()))
                        .orElse(null);
        if (principal == null) {
            // No tenant context - authentication itself is SecurityConfig's
            // job, not this throttle's; don't share one global bucket across
            // every context-less caller.
            return;
        }
        String key = KEY_PREFIX + op + ":" + principal;
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (RuntimeException e) {
            // Redis unreachable - degrade to the in-process limiter rather than
            // failing open (security audit 2026-09-18, see LocalRateLimitFallback).
            log.warn(
                    "Share rate limiter could not reach Redis for {} - limiting in-process",
                    LogSafe.clean(principal),
                    e);
            count = null;
        }
        if (count == null) {
            if (!LocalRateLimitFallback.tryAcquire(key, maxPerWindow, window)) {
                throw new ShareRateLimitExceededException();
            }
            return;
        }
        if (count == 1L) {
            redisTemplate.expire(key, window);
        }
        if (count > maxPerWindow) {
            throw new ShareRateLimitExceededException();
        }
    }
}
