package com.agenticai.core.tenancy.demo;

import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-chat throttle for the demo messenger bridge - same fixed-window
 * {@code INCR} + conditional {@code EXPIRE} shape as
 * {@link DemoSessionRateLimiter} and {@code SignupRateLimiter}, with its own
 * key space.
 *
 * <h2>Why this exists when the message cap already bounds the damage</h2>
 * It does not exist to bound COST - the per-sandbox message cap does that,
 * and it is the control that actually holds. This bounds RATE, and the thing
 * it protects is the Telegram bot itself: the demo bot is shared by every
 * visitor on the platform, and Telegram will rate-limit or throttle a bot
 * that fans out replies too fast. One chat spamming the bot must not degrade
 * the bridge for everybody else's sandbox. Secondarily it stops a paired
 * chat burning its whole 20-turn allowance in three seconds by accident
 * (autocorrect loops, a held-down send button), which is a worse first
 * impression than being asked to slow down.
 *
 * <h2>Fail-open, deliberately</h2>
 * No Redis (a narrow test context, or a genuine outage) means every call is
 * allowed. That is the same call {@link DemoSessionRateLimiter} makes, and it
 * is defensible here for a specific reason: unlike sandbox creation, nothing
 * downstream of this limiter is unbounded. A paired chat that gets through
 * still spends {@code demo_session.message_count} per turn, still stops dead
 * at the cap, and is still a FREE-plan tenant subject to the platform's daily
 * message and dollar budgets.
 */
@Component
public class DemoChannelRateLimiter {

    private static final String KEY_PREFIX = "agentic:demo-channel-rate:";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final PlatformSettingsService platformSettingsService;
    private final int defaultMaxMessages;
    private final int defaultWindowSeconds;

    public DemoChannelRateLimiter(
        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
        PlatformSettingsService platformSettingsService,
        @Value("${app.demo.channels.rate-limit.max-messages:8}") int defaultMaxMessages,
        @Value("${app.demo.channels.rate-limit.window-seconds:60}") int defaultWindowSeconds
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.platformSettingsService = platformSettingsService;
        this.defaultMaxMessages = defaultMaxMessages;
        this.defaultWindowSeconds = defaultWindowSeconds;
    }

    /**
     * @param bucket an opaque, already-scoped identity - a Telegram chat id
     *     for the inbound bridge, a demo session id for the browser-facing
     *     endpoints. Never logged by this class.
     * @return true when the call is within the window's allowance.
     */
    public boolean tryAcquire(String bucket) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null || bucket == null || bucket.isBlank()) {
            return true; // see class javadoc - fail open, the message cap still holds
        }
        int maxMessages = platformSettingsService.getInt("demo.channels.rate-limit.max-messages", defaultMaxMessages);
        Duration window = Duration.ofSeconds(
            platformSettingsService.getInt("demo.channels.rate-limit.window-seconds", defaultWindowSeconds)
        );
        String key = KEY_PREFIX + bucket;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return LocalRateLimitFallback.tryAcquire(key, maxMessages, window);
            }
            if (count == 1L) {
                redisTemplate.expire(key, window);
            }
            return count <= maxMessages;
        } catch (RuntimeException e) {
            // Redis is down: keep limiting in-process rather than waving every
            // demo message through (security audit 2026-09-18).
            return LocalRateLimitFallback.tryAcquire(key, maxMessages, window);
        }
    }
}
