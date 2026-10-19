package com.agenticai.core.imagegen;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Tiered caps for authenticated static image generation - FREE gets a small
 * daily/monthly allowance and (for the default {@code gpt-image-1} model) a
 * forced low-quality render, PREMIUM gets a generous allowance at whatever
 * quality was requested, and UNLIMITED is not capped at all. Same
 * "tunable without a redeploy" shape {@link VideoGenerationQuotaService}
 * already uses, every value read live from {@link PlatformSettingsService}.
 *
 * <p>Billing audit finding (2026-07-27): static image generation had zero
 * quota service - every tenant, FREE included, got unlimited generations.
 * This mirrors {@link VideoGenerationQuotaService}'s shape for images. Image
 * generation is billed per call, not per second of output like video, so it
 * tolerates a much larger allowance for the same plan - these defaults sit
 * at roughly 10x the video caps (see that class's {@code free-daily-cap}/
 * {@code paid-daily-cap} etc. for the numbers this is scaled from).
 */
@Service
public class ImageGenerationQuotaService {

    private final GeneratedImageRepository imageRepository;
    private final PlatformSettingsService platformSettings;
    private final boolean defaultEnabled;
    private final int defaultFreeDailyCap;
    private final int defaultFreeMonthlyCap;
    private final int defaultPaidDailyCap;
    private final int defaultPaidMonthlyCap;
    private final String defaultFreeQuality;

    public ImageGenerationQuotaService(
        GeneratedImageRepository imageRepository,
        PlatformSettingsService platformSettings,
        @Value("${app.image.generation.enabled:true}") boolean defaultEnabled,
        @Value("${app.image.generation.free-daily-cap:5}") int defaultFreeDailyCap,
        @Value("${app.image.generation.free-monthly-cap:50}") int defaultFreeMonthlyCap,
        @Value("${app.image.generation.paid-daily-cap:50}") int defaultPaidDailyCap,
        @Value("${app.image.generation.paid-monthly-cap:500}") int defaultPaidMonthlyCap,
        @Value("${app.image.generation.free-quality:low}") String defaultFreeQuality
    ) {
        this.imageRepository = imageRepository;
        this.platformSettings = platformSettings;
        this.defaultEnabled = defaultEnabled;
        this.defaultFreeDailyCap = defaultFreeDailyCap;
        this.defaultFreeMonthlyCap = defaultFreeMonthlyCap;
        this.defaultPaidDailyCap = defaultPaidDailyCap;
        this.defaultPaidMonthlyCap = defaultPaidMonthlyCap;
        this.defaultFreeQuality = defaultFreeQuality;
    }

    public boolean isEnabled() {
        return platformSettings.getBoolean("image.generation.enabled", defaultEnabled);
    }

    /**
     * @return the {@code quality} value a FREE-plan request should be forced
     * to (only meaningful for {@code gpt-image-1}, the default model - see
     * {@link OpenAiImageGenerationClient}), or {@code null} for PREMIUM/
     * UNLIMITED, which get whatever quality they requested.
     */
    public String forcedQualityFor(TenantPlan plan) {
        return plan == TenantPlan.FREE
            ? platformSettings.getString("image.generation.free-quality", defaultFreeQuality)
            : null;
    }

    /**
     * @throws ImageGenerationQuotaExceededException with a visitor-facing
     * reason if this request would exceed the tenant's tier. UNLIMITED-plan
     * tenants skip the daily/monthly cap check entirely (still subject to
     * the platform-wide {@link #isEnabled()} switch).
     */
    public void checkAllowed(TenantPlan plan) {
        if (!isEnabled()) {
            throw new ImageGenerationQuotaExceededException("Image generation is turned off on this platform right now.");
        }
        if (plan == TenantPlan.UNLIMITED) {
            return;
        }
        int dailyCap = plan == TenantPlan.FREE
            ? platformSettings.getInt("image.generation.free-daily-cap", defaultFreeDailyCap)
            : platformSettings.getInt("image.generation.paid-daily-cap", defaultPaidDailyCap);
        int monthlyCap = plan == TenantPlan.FREE
            ? platformSettings.getInt("image.generation.free-monthly-cap", defaultFreeMonthlyCap)
            : platformSettings.getInt("image.generation.paid-monthly-cap", defaultPaidMonthlyCap);

        Instant now = Instant.now();
        long usedToday = imageRepository.countByCreatedAtAfter(now.minus(Duration.ofDays(1)));
        if (usedToday >= dailyCap) {
            throw new ImageGenerationQuotaExceededException(
                "You've reached today's image generation limit (" + dailyCap + "). Try again tomorrow, or upgrade for a higher limit.");
        }
        long usedThisMonth = imageRepository.countByCreatedAtAfter(now.minus(Duration.ofDays(30)));
        if (usedThisMonth >= monthlyCap) {
            throw new ImageGenerationQuotaExceededException(
                "You've reached this month's image generation limit (" + monthlyCap + "). Upgrade for a higher limit.");
        }
    }
}
