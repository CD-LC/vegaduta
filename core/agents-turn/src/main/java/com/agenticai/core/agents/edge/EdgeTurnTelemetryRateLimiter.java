package com.agenticai.core.agents.edge;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Pentest L7 fix (2026-08-27) - {@code POST /api/edge/turns} is
 * {@code permitAll()} (the anonymous {@code /try} surface has no JWT to
 * present - see {@link EdgeTurnTelemetryController}'s javadoc) and, unlike
 * every other unauthenticated abuse-relevant endpoint in this codebase
 * (compare {@code LandingEventController}'s inlined hourly lid,
 * {@code DeviceClaimRateLimiter}, {@code SignupRateLimiter},
 * {@code DemoSessionRateLimiter}), it had NO rate limit at all: one host
 * could hammer it in a loop, and every hit produced an INFO log line
 * carrying caller-controlled strings - an unbounded log-volume amplifier
 * into Loki, plus free DB/RL write attempts for authenticated callers.
 *
 * <p>Same fixed-window shape as those limiters (Redis {@code INCR} +
 * conditional {@code EXPIRE}, limits re-tunable live via
 * {@link PlatformSettingsService}), fails OPEN on a missing/unreachable
 * Redis for the identical documented reason: this is telemetry, and a Redis
 * outage must never turn a fire-and-forget beacon into a caller-visible
 * error - nor does anything here need to be watertight; the lid only has to
 * keep one bored script from being free.
 *
 * <p>The default (300 events per IP per hour) is deliberately generous:
 * edge turns are human-paced (one report per completed chat turn), so even
 * a busy NAT'd office stays far under it, while a flood loop hits the lid
 * within its first second. Per-IP and therefore walked by an address pool -
 * the same honest caveat every per-IP control in this codebase documents;
 * what bounds THAT is that anonymous reports only ever produce log lines.
 */
@Component
public class EdgeTurnTelemetryRateLimiter {

    private static final String KEY_PREFIX = "agentic:edge-turn-rate:ip:";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final PlatformSettingsService platformSettingsService;
    private final int defaultMaxEventsPerIp;
    private final long defaultWindowMinutes;

    public EdgeTurnTelemetryRateLimiter(
        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
        PlatformSettingsService platformSettingsService,
        @Value("${app.edge.turns.rate-limit.max-events-per-ip:300}") int defaultMaxEventsPerIp,
        @Value("${app.edge.turns.rate-limit.window-minutes:60}") long defaultWindowMinutes
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.platformSettingsService = platformSettingsService;
        this.defaultMaxEventsPerIp = defaultMaxEventsPerIp;
        this.defaultWindowMinutes = defaultWindowMinutes;
    }

    /**
     * @param clientIp the caller's real address ({@code getRemoteAddr()} -
     *     never trust {@code X-Forwarded-For} directly at this layer, same
     *     posture as {@code LandingEventController#clientIp}).
     * @return true if this event is within the per-IP allowance for the
     *     current window (and has been counted against it); false when the
     *     window is exhausted and the report should be dropped.
     */
    public boolean tryAcquire(String clientIp) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return true; // see class javadoc - fail open, telemetry only
        }
        try {
            String key = KEY_PREFIX + clientIp;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return true;
            }
            if (count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(
                    platformSettingsService.getInt("edge.turns.rate-limit.window-minutes", (int) defaultWindowMinutes)));
            }
            return count <= platformSettingsService.getInt(
                "edge.turns.rate-limit.max-events-per-ip", defaultMaxEventsPerIp);
        } catch (RuntimeException e) {
            return true; // a Redis error must never surface through a fire-and-forget beacon
        }
    }
}
