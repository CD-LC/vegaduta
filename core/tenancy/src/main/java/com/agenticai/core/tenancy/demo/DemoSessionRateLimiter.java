package com.agenticai.core.tenancy.demo;

import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window, per-IP throttle on demo-sandbox creation - a direct copy of
 * {@code SignupRateLimiter}'s shape (Redis {@code INCR} + conditional
 * {@code EXPIRE}, limits read live from {@link PlatformSettingsService} so a
 * platform admin can retune them without a redeploy), with its own key
 * prefix and its own, much tighter defaults.
 *
 * <p><b>Why tighter than signup:</b> signup costs a Keycloak user and a
 * schema, but it is also a real conversion event a human deliberately
 * completed. Demo creation is one anonymous click, and each one costs a
 * whole Postgres schema with ~90 tenant migrations run against it. Left at
 * signup's 5-per-hour that is a trivially cheap way for one host to
 * manufacture 120 schemas a day. Defaults here are 3 per 24 hours per IP.
 *
 * <p><b>This is one of two controls, not the control.</b> A rate limit is
 * per-IP and therefore defeated by anything with a pool of addresses; it is
 * paired with a standing per-IP live-sandbox cap
 * ({@code DemoSessionRepository#countLiveForIp}) and a per-session message
 * cap, both enforced in {@link DemoSessionService}. Stated plainly: none of
 * these stop a determined, distributed abuser. What actually bounds the
 * blast radius is that a demo tenant is FREE-plan, so every LLM call it
 * makes is already subject to the platform's existing FREE-tier daily
 * message cap and per-user dollar budget.
 *
 * <p>{@code StringRedisTemplate} is injected through an {@link
 * ObjectProvider} rather than as a hard dependency (unlike {@code
 * SignupRateLimiter}, which is {@code @Profile("security")}-gated and so can
 * assume the full stack): this component is deliberately NOT profile-gated,
 * because the demo endpoints have to work both with the {@code security}
 * profile on and off, and a narrow test context may have no Redis
 * autoconfiguration at all. Missing Redis fails OPEN, same as {@code
 * SignupRateLimiter}'s null-count branch - the standing per-IP cap and the
 * FREE-tier limits are the controls that still hold in that case.
 */
@Component
public class DemoSessionRateLimiter {

    private static final String KEY_PREFIX = "agentic:demo-session-rate:";

    /**
     * Separate namespace from sandbox creation, deliberately. The two are
     * counted independently because they bound different things: creation
     * costs a Postgres schema, image generation costs money at a provider on
     * the PLATFORM's key. Sharing one counter would let a visitor who
     * legitimately created a sandbox find their images already rate-limited,
     * and - worse - would let image spam consume the creation budget.
     */
    private static final String IMAGE_KEY_PREFIX = "agentic:demo-image-rate:";

    /**
     * Own namespace again, tighter still than {@link #IMAGE_KEY_PREFIX} -
     * Veo-class video is billed per second of output, not per call, so the
     * default here is much stingier than the image default (2/day vs 6/day).
     */
    private static final String VIDEO_KEY_PREFIX = "agentic:demo-video-rate:";

    /**
     * Own namespace for {@code POST /api/demo/music}. Music is billed per
     * generated minute on the platform's ElevenLabs key; a sandbox clip is
     * seconds long, so the default sits between image (6/day) and video
     * (2/day).
     */
    private static final String MUSIC_KEY_PREFIX = "agentic:demo-music-rate:";
    private static final int DEFAULT_MUSIC_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_MUSIC_WINDOW_MINUTES = 1440;

    /**
     * Third independent namespace, for {@code POST /api/demo/transcript-email}
     * - same do-not-share-counters reasoning as the image prefix above. What
     * this one bounds is outbound MAIL from an unauthenticated endpoint to a
     * visitor-chosen address: without a per-IP lid, that endpoint is a small
     * spam cannon wearing the platform's SMTP reputation. The per-session
     * send cap (2, V130's {@code transcript_emails_sent}) is the product
     * limit; this is the abuse limit over sessions.
     */
    private static final String TRANSCRIPT_EMAIL_KEY_PREFIX = "agentic:demo-transcript-email-rate:";

    /**
     * Fourth independent namespace: a VIEWER's messages to a shared sandbox
     * agent ({@code POST /api/demo/share/{shareId}/chat}). Its own counter for
     * the standing do-not-share-counters reason - and because share-chat is
     * the one demo surface where the caller is not even the sandbox's owner,
     * so its abuse profile (one hot link passed around a group) is different
     * in kind from everything above.
     */
    private static final String SHARE_CHAT_KEY_PREFIX = "agentic:demo-share-chat-rate:";

    /**
     * Fifth namespace, for {@code POST /api/demo/share/{shareId}/report}.
     * Reports auto-revoke a share at a small threshold, so without a per-IP
     * lid one host could single-handedly revoke any share it dislikes by
     * posting the threshold in one loop. Counted per attempt.
     */
    private static final String SHARE_REPORT_KEY_PREFIX = "agentic:demo-share-report-rate:";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final PlatformSettingsService platformSettingsService;
    private final int defaultMaxAttempts;
    private final long defaultWindowMinutes;
    private final int defaultImageMaxAttempts;
    private final long defaultImageWindowMinutes;
    private final int defaultVideoMaxAttempts;
    private final long defaultVideoWindowMinutes;
    private final int defaultTranscriptEmailMaxAttempts;
    private final long defaultTranscriptEmailWindowMinutes;
    private final int defaultShareChatMaxAttempts;
    private final long defaultShareChatWindowMinutes;
    private final int defaultShareReportMaxAttempts;
    private final long defaultShareReportWindowMinutes;

    public DemoSessionRateLimiter(
        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
        PlatformSettingsService platformSettingsService,
        @Value("${app.demo.rate-limit.max-attempts:3}") int defaultMaxAttempts,
        @Value("${app.demo.rate-limit.window-minutes:1440}") long defaultWindowMinutes,
        @Value("${app.demo.image.rate-limit.max-attempts:6}") int defaultImageMaxAttempts,
        @Value("${app.demo.image.rate-limit.window-minutes:1440}") long defaultImageWindowMinutes,
        @Value("${app.demo.video.rate-limit.max-attempts:2}") int defaultVideoMaxAttempts,
        @Value("${app.demo.video.rate-limit.window-minutes:1440}") long defaultVideoWindowMinutes,
        @Value("${app.demo.transcript-email.rate-limit.max-attempts:6}") int defaultTranscriptEmailMaxAttempts,
        @Value("${app.demo.transcript-email.rate-limit.window-minutes:1440}") long defaultTranscriptEmailWindowMinutes,
        @Value("${app.demo.share.chat.rate-limit.max-attempts:20}") int defaultShareChatMaxAttempts,
        @Value("${app.demo.share.chat.rate-limit.window-minutes:60}") long defaultShareChatWindowMinutes,
        @Value("${app.demo.share.report.rate-limit.max-attempts:5}") int defaultShareReportMaxAttempts,
        @Value("${app.demo.share.report.rate-limit.window-minutes:1440}") long defaultShareReportWindowMinutes
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.platformSettingsService = platformSettingsService;
        this.defaultMaxAttempts = defaultMaxAttempts;
        this.defaultWindowMinutes = defaultWindowMinutes;
        this.defaultImageMaxAttempts = defaultImageMaxAttempts;
        this.defaultImageWindowMinutes = defaultImageWindowMinutes;
        this.defaultVideoMaxAttempts = defaultVideoMaxAttempts;
        this.defaultVideoWindowMinutes = defaultVideoWindowMinutes;
        this.defaultTranscriptEmailMaxAttempts = defaultTranscriptEmailMaxAttempts;
        this.defaultTranscriptEmailWindowMinutes = defaultTranscriptEmailWindowMinutes;
        this.defaultShareChatMaxAttempts = defaultShareChatMaxAttempts;
        this.defaultShareChatWindowMinutes = defaultShareChatWindowMinutes;
        this.defaultShareReportMaxAttempts = defaultShareReportMaxAttempts;
        this.defaultShareReportWindowMinutes = defaultShareReportWindowMinutes;
    }

    /**
     * @return true if this call is within the limit (and has been counted
     *     against it); false if this IP has already exhausted its demo
     *     sandboxes for the current window.
     */
    public boolean tryAcquire(String clientIp) {
        // Exempt-IP allowlist (2026-07-28, direct request: "exclude my ip...
        // give a control in platform maintenance"). Checked BEFORE touching
        // Redis - an exempt address should never even spend a counter slot,
        // so it also never trips as "used up" for anyone genuinely sharing
        // that address later (irrelevant for a single operator IP, but keeps
        // the semantics honest). See DemoRateLimitExemptions' own javadoc for
        // why this is a plain allowlist, not CIDR ranges.
        if (DemoRateLimitExemptions.isExempt(platformSettingsService, clientIp)) {
            return true;
        }
        return tryAcquire(
            KEY_PREFIX + clientIp,
            platformSettingsService.getInt("demo.rate-limit.max-attempts", defaultMaxAttempts),
            platformSettingsService.getInt("demo.rate-limit.window-minutes", (int) defaultWindowMinutes)
        );
    }

    /**
     * The same fixed-window shape applied to {@code POST /api/demo/image},
     * against its own counter and its own settings ({@code
     * demo.image.rate-limit.max-attempts}, default 6 per 24h).
     *
     * <p><b>Why a per-IP limit on top of the 2-per-sandbox cap:</b> because
     * the per-sandbox cap is per SANDBOX, and sandboxes are free. The
     * standing per-IP live-sandbox cap (2) means one host can hold two
     * sandboxes at once, so the session cap alone permits 4 images - and once
     * a sandbox expires and the visitor starts another, more. This counter is
     * what actually bounds spend per address over time. It is deliberately
     * NOT a multiple of the session cap: 6 leaves normal exploration
     * untouched (nobody legitimately generates six images in a "try it"
     * session) while flattening the replay-a-new-sandbox loop.
     *
     * <p>Same honest caveat as the creation limiter: per-IP, therefore
     * defeated by an address pool. Behind it sit the per-sandbox cap, the
     * {@code demo.image.enabled} kill switch, and the fact that images are a
     * platform-key cost line an operator can watch directly.
     */
    public boolean tryAcquireImage(String clientIp) {
        return tryAcquire(
            IMAGE_KEY_PREFIX + clientIp,
            platformSettingsService.getInt("demo.image.rate-limit.max-attempts", defaultImageMaxAttempts),
            platformSettingsService.getInt("demo.image.rate-limit.window-minutes", (int) defaultImageWindowMinutes)
        );
    }

    /**
     * Per-IP lid on {@code POST /api/demo/music} attempts - see {@link
     * #MUSIC_KEY_PREFIX}. Same fixed-window mechanism and the same live
     * {@code demo.music.rate-limit.*} override shape as image/video; the
     * defaults are constants rather than two more {@code @Value} constructor
     * parameters only to leave this class's constructor untouched.
     */
    public boolean tryAcquireMusic(String clientIp) {
        return tryAcquire(
            MUSIC_KEY_PREFIX + clientIp,
            platformSettingsService.getInt("demo.music.rate-limit.max-attempts", DEFAULT_MUSIC_MAX_ATTEMPTS),
            platformSettingsService.getInt("demo.music.rate-limit.window-minutes", DEFAULT_MUSIC_WINDOW_MINUTES)
        );
    }

    /** Per-IP lid on {@code POST /api/demo/video} attempts - see {@link #VIDEO_KEY_PREFIX}. */
    public boolean tryAcquireVideo(String clientIp) {
        return tryAcquire(
            VIDEO_KEY_PREFIX + clientIp,
            platformSettingsService.getInt("demo.video.rate-limit.max-attempts", defaultVideoMaxAttempts),
            platformSettingsService.getInt("demo.video.rate-limit.window-minutes", (int) defaultVideoWindowMinutes)
        );
    }

    /**
     * Per-IP lid on {@code POST /api/demo/transcript-email} attempts - see
     * {@link #TRANSCRIPT_EMAIL_KEY_PREFIX}. Counted per ATTEMPT, not per
     * successful send, deliberately: probing with addresses that fail
     * validation or hitting the per-session cap must spend from this window
     * too, or retry-until-something-sends is free.
     */
    public boolean tryAcquireTranscriptEmail(String clientIp) {
        return tryAcquire(
            TRANSCRIPT_EMAIL_KEY_PREFIX + clientIp,
            platformSettingsService.getInt("demo.transcript-email.rate-limit.max-attempts", defaultTranscriptEmailMaxAttempts),
            platformSettingsService.getInt("demo.transcript-email.rate-limit.window-minutes", (int) defaultTranscriptEmailWindowMinutes)
        );
    }

    /**
     * Per-IP lid on a VIEWER's share-chat turns (default 20 per hour, tunable
     * via {@code demo.share.chat.rate-limit.*}). Deliberately looser than the
     * per-session viewer cap and message cap that sit behind it - this bounds
     * the RATE of one address hammering the endpoint; the caps bound totals.
     */
    public boolean tryAcquireShareChat(String clientIp) {
        return tryAcquire(
            SHARE_CHAT_KEY_PREFIX + clientIp,
            platformSettingsService.getInt("demo.share.chat.rate-limit.max-attempts", defaultShareChatMaxAttempts),
            platformSettingsService.getInt("demo.share.chat.rate-limit.window-minutes", (int) defaultShareChatWindowMinutes)
        );
    }

    /**
     * Per-IP lid on share abuse reports (default 5 per 24h, tunable via
     * {@code demo.share.report.rate-limit.*}) - see
     * {@link #SHARE_REPORT_KEY_PREFIX} for why this one is load-bearing.
     * Counted per attempt, same reasoning as the transcript-email limiter.
     */
    public boolean tryAcquireShareReport(String clientIp) {
        return tryAcquire(
            SHARE_REPORT_KEY_PREFIX + clientIp,
            platformSettingsService.getInt("demo.share.report.rate-limit.max-attempts", defaultShareReportMaxAttempts),
            platformSettingsService.getInt("demo.share.report.rate-limit.window-minutes", (int) defaultShareReportWindowMinutes)
        );
    }

    private boolean tryAcquire(String key, int maxAttempts, int windowMinutes) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return true; // see class javadoc - fail open, other caps still apply
        }
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
        } catch (RuntimeException e) {
            // Uncaught until 2026-09-18: a Redis outage made every demo call a 500.
            count = null;
        }
        if (count == null) {
            // Redis is down. The demo spends platform LLM budget for anonymous
            // visitors, so it keeps being limited in-process instead of
            // failing open - see LocalRateLimitFallback.
            return LocalRateLimitFallback.tryAcquire(key, maxAttempts, Duration.ofMinutes(windowMinutes));
        }
        if (count == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(windowMinutes));
        }
        return count <= maxAttempts;
    }
}
