package com.agenticai.core.imagegen;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Entitlement + caps for authenticated, server-side video generation.
 *
 * <p><b>Server-side video generation is PAID-ONLY - never free</b> (product
 * entitlement rule). A FREE tenant is rejected outright by {@link
 * #checkAllowed}, before and regardless of the {@code
 * app.video.generation.enabled} flag, so flipping that flag on can never
 * silently gift FREE tenants pool-billed Veo time. The FREE tier's video
 * story is exclusively the client-side V0/V1 ladder (on-device Ken Burns /
 * keyframe clips, priced through image quota), and the rejection message
 * says exactly that. This class previously carried dormant FREE allowances
 * ({@code free-daily-cap 1}, {@code free-monthly-cap 5}, {@code
 * free-max-duration-seconds 30}) - dormant only because {@code enabled}
 * defaults to {@code false}; they are removed rather than zeroed. No seed
 * migration or yml ever wrote those settings rows (verified 2026-08-04:
 * zero {@code video.generation.free-*} references outside this class), so
 * removing the reads breaks no {@link PlatformSettingsService} seeding, and
 * a leftover admin-written row for one of those keys is simply never
 * consulted again.
 *
 * <p>PREMIUM/UNLIMITED keep tunable ceilings and daily/monthly allowances -
 * every value reads live from {@link PlatformSettingsService}, same
 * "tunable without a redeploy" shape {@code DemoImageService} already uses.
 *
 * <p>The anonymous {@code /api/demo/video} sandbox path deliberately does
 * NOT go through this service - {@code DemoVideoController} has its own
 * per-session cap, per-IP rate limit and platform-wide daily budget
 * ({@code DemoVideoService.isGlobalBudgetAvailable()}), and per the same
 * product decision it stays that way: a funnel cost, not a free-tier
 * entitlement.
 *
 * <p>Video is billed per second of output, not per call - a much sharper
 * cost curve than image generation - so the paid defaults are deliberately
 * conservative and meant to be tightened further once real usage data
 * exists (see the approved plan's note on this).
 */
@Service
public class VideoGenerationQuotaService {

    /**
     * Package-private so the tool-callback/controller tests can assert the
     * exact copy that reaches a FREE user - written for a person, passed
     * through verbatim by {@code GenerateVideoToolCallback#consumerReason}.
     */
    static final String FREE_PLAN_REJECTION_MESSAGE =
        "Cinematic AI video is a paid feature — upgrade to render on VegaDūta's servers, "
            + "or use the free on-device Clip Studio.";

    private final VideoGenerationJobRepository jobRepository;
    private final PlatformSettingsService platformSettings;
    private final int defaultPaidMaxDurationSeconds;
    private final int defaultPaidDailyCap;
    private final int defaultPaidMonthlyCap;
    private final boolean defaultEnabled;

    public VideoGenerationQuotaService(
        VideoGenerationJobRepository jobRepository,
        PlatformSettingsService platformSettings,
        @Value("${app.video.generation.enabled:false}") boolean defaultEnabled,
        @Value("${app.video.generation.paid-max-duration-seconds:60}") int defaultPaidMaxDurationSeconds,
        @Value("${app.video.generation.paid-daily-cap:10}") int defaultPaidDailyCap,
        @Value("${app.video.generation.paid-monthly-cap:100}") int defaultPaidMonthlyCap
    ) {
        this.jobRepository = jobRepository;
        this.platformSettings = platformSettings;
        this.defaultEnabled = defaultEnabled;
        this.defaultPaidMaxDurationSeconds = defaultPaidMaxDurationSeconds;
        this.defaultPaidDailyCap = defaultPaidDailyCap;
        this.defaultPaidMonthlyCap = defaultPaidMonthlyCap;
    }

    public boolean isEnabled() {
        return platformSettings.getBoolean("video.generation.enabled", defaultEnabled);
    }

    /**
     * The paid-tier clip-length ceiling. FREE never reaches a duration
     * decision at all - {@link #checkAllowed} rejects the plan first - so
     * this returns {@code 0} for FREE ("no server-side seconds at any
     * length") rather than a number that could be mistaken for an allowance.
     */
    public int maxDurationSecondsFor(TenantPlan plan) {
        if (plan == TenantPlan.FREE) {
            return 0;
        }
        return platformSettings.getInt("video.generation.paid-max-duration-seconds", defaultPaidMaxDurationSeconds);
    }

    /** @throws VideoGenerationQuotaExceededException with a visitor-facing reason if this request would exceed the tenant's tier. */
    public void checkAllowed(TenantPlan plan, int requestedDurationSeconds) {
        // PAID-ONLY comes first, before the enabled flag, on purpose: a FREE
        // tenant must get the honest "this is a paid feature" answer in every
        // platform state, and enabling video generation platform-wide must
        // never widen what FREE can do. See the class javadoc.
        if (plan == TenantPlan.FREE) {
            throw new VideoGenerationQuotaExceededException(FREE_PLAN_REJECTION_MESSAGE);
        }
        if (!isEnabled()) {
            throw new VideoGenerationQuotaExceededException("Video generation is turned off on this platform right now.");
        }
        int maxDuration = maxDurationSecondsFor(plan);
        if (requestedDurationSeconds > maxDuration) {
            throw new VideoGenerationQuotaExceededException(
                "Your plan supports up to " + maxDuration + "s clips.");
        }
        int dailyCap = platformSettings.getInt("video.generation.paid-daily-cap", defaultPaidDailyCap);
        int monthlyCap = platformSettings.getInt("video.generation.paid-monthly-cap", defaultPaidMonthlyCap);

        Instant now = Instant.now();
        long usedToday = jobRepository.countByCreatedAtAfterAndStatusNot(now.minus(Duration.ofDays(1)), VideoGenerationJobStatus.FAILED);
        if (usedToday >= dailyCap) {
            throw new VideoGenerationQuotaExceededException(
                "You've reached today's video generation limit (" + dailyCap + "). Try again tomorrow.");
        }
        long usedThisMonth = jobRepository.countByCreatedAtAfterAndStatusNot(now.minus(Duration.ofDays(30)), VideoGenerationJobStatus.FAILED);
        if (usedThisMonth >= monthlyCap) {
            throw new VideoGenerationQuotaExceededException(
                "You've reached this month's video generation limit (" + monthlyCap + ").");
        }
    }
}
