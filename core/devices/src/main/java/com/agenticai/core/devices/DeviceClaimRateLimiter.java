package com.agenticai.core.devices;

import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * SAST audit fix (2026-07-27) - {@code POST /api/devices/claim} is
 * {@code permitAll()} (no Keycloak JWT possible - see {@link
 * DeviceGatewayController}'s javadoc) and authenticates solely via a 6-digit
 * {@link DevicePairingCode}, a keyspace of one million values. Before this
 * class existed there was NO rate limit anywhere on this endpoint - unlike
 * every other unauthenticated, abuse-relevant endpoint in this codebase
 * (compare {@code SignupRateLimiter}, {@code DemoSessionRateLimiter}, {@code
 * DemoChannelRateLimiter}), so an attacker could brute force the full
 * six-digit space well within the code's 10-minute TTL (plain HTTP, no
 * captcha, no delay) and claim WHOEVER's pairing code happened to be live at
 * that moment - not even a targeted attack, just "spray guesses, eventually
 * hit somebody's active pairing window." A claimed device registers as a
 * trusted {@link MobileDevice} inside the victim tenant's own schema and can
 * then push fabricated screen-state/notification/app-usage/SMS data into
 * that tenant's pipeline and read any command queued for the real device.
 *
 * <p>Same fixed-window shape as {@code DemoSessionRateLimiter}/{@code
 * DemoChannelRateLimiter} (Redis {@code INCR} + conditional {@code EXPIRE},
 * limits re-tunable live via {@link PlatformSettingsService}), fails OPEN on
 * a missing/unreachable Redis for the identical reason those two document:
 * this module has no Redis hard dependency of its own today, and a narrow
 * test context may have none configured. Bucketed per-IP AND per-code
 * (see {@link #tryAcquire}) so a distributed attacker spraying many codes
 * from many addresses is still slowed on the code itself, not just the
 * source IP.
 */
@Component
public class DeviceClaimRateLimiter {

    private static final String IP_KEY_PREFIX = "agentic:device-claim-rate:ip:";
    private static final String CODE_KEY_PREFIX = "agentic:device-claim-rate:code:";
    private static final String GLOBAL_KEY = "agentic:device-claim-rate:global";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final PlatformSettingsService platformSettingsService;
    private final int defaultMaxAttemptsPerIp;
    private final long defaultIpWindowMinutes;
    private final int defaultMaxAttemptsPerCode;
    private final long defaultCodeWindowMinutes;

    public DeviceClaimRateLimiter(
        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
        PlatformSettingsService platformSettingsService,
        @Value("${app.devices.claim.rate-limit.max-attempts-per-ip:10}") int defaultMaxAttemptsPerIp,
        @Value("${app.devices.claim.rate-limit.ip-window-minutes:10}") long defaultIpWindowMinutes,
        @Value("${app.devices.claim.rate-limit.max-attempts-per-code:5}") int defaultMaxAttemptsPerCode,
        @Value("${app.devices.claim.rate-limit.code-window-minutes:10}") long defaultCodeWindowMinutes
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.platformSettingsService = platformSettingsService;
        this.defaultMaxAttemptsPerIp = defaultMaxAttemptsPerIp;
        this.defaultIpWindowMinutes = defaultIpWindowMinutes;
        this.defaultMaxAttemptsPerCode = defaultMaxAttemptsPerCode;
        this.defaultCodeWindowMinutes = defaultCodeWindowMinutes;
    }

    /**
     * @param clientIp the caller's real address (see {@code
     *     DeviceGatewayController#clientIp} - never trust {@code
     *     X-Forwarded-For} directly at this layer).
     * @param submittedCode the raw code the caller submitted, verbatim. Rate
     *     limiting this too (not just the IP) means retrying the SAME guess
     *     from many source addresses still only spends one code-bucket, and
     *     a six-digit code that is genuinely being brute-forced gets locked
     *     out even if the attempts are spread across addresses.
     * @return true when this call is within both the per-IP and per-code
     *     allowance for the current window (and has been counted against
     *     both); false if either is already exhausted.
     */
    public boolean tryAcquire(String clientIp, String submittedCode) {
        boolean ipOk = tryAcquire(
            IP_KEY_PREFIX + clientIp,
            platformSettingsService.getInt("devices.claim.rate-limit.max-attempts-per-ip", defaultMaxAttemptsPerIp),
            platformSettingsService.getInt("devices.claim.rate-limit.ip-window-minutes", (int) defaultIpWindowMinutes)
        );
        boolean codeOk = tryAcquire(
            CODE_KEY_PREFIX + normalizeCode(submittedCode),
            platformSettingsService.getInt("devices.claim.rate-limit.max-attempts-per-code", defaultMaxAttemptsPerCode),
            platformSettingsService.getInt("devices.claim.rate-limit.code-window-minutes", (int) defaultCodeWindowMinutes)
        );
        // Platform-wide ceiling (security audit 2026-09-18). The two buckets
        // above do not bound a DISTRIBUTED guess of the 6-digit code space: the
        // per-IP bucket is per address, and the per-code bucket is keyed on the
        // code submitted, so trying a different code every time never touches
        // it. Codes are looked up across all tenants, so the only number that
        // bounds that attack is total claims per window. Real pairing volume
        // is a handful an hour; the default leaves two orders of magnitude.
        boolean globalOk = tryAcquire(
            GLOBAL_KEY,
            platformSettingsService.getInt("devices.claim.rate-limit.max-attempts-global", 300),
            platformSettingsService.getInt("devices.claim.rate-limit.global-window-minutes", 10)
        );
        return ipOk && codeOk && globalOk;
    }

    private static String normalizeCode(String submittedCode) {
        // Bucketed on the raw submitted string, not a validated/parsed code -
        // this must never throw or reject before the increment happens, or a
        // malformed submission would dodge the counter entirely. A blank
        // code still gets its own (shared, harmless) bucket.
        return submittedCode == null ? "" : submittedCode.trim();
    }

    private boolean tryAcquire(String key, int maxAttempts, int windowMinutes) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return true; // see class javadoc - fail open, no other control stands behind this one today
        }
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return LocalRateLimitFallback.tryAcquire(key, maxAttempts, Duration.ofMinutes(windowMinutes));
            }
            if (count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(windowMinutes));
            }
            return count <= maxAttempts;
        } catch (RuntimeException e) {
            // A Redis error must never block a legitimate pairing attempt - but
            // it must not unthrottle guessing a 6-digit code either, and this
            // limiter is the only control in front of that (class javadoc).
            // Keep counting in-process; see LocalRateLimitFallback.
            return LocalRateLimitFallback.tryAcquire(key, maxAttempts, Duration.ofMinutes(windowMinutes));
        }
    }
}
