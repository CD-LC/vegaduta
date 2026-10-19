package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.elevenlabs.GeneratedMusicService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-tenant caps for {@code generate_music} - the music counterpart of {@code
 * ElevenLabsGenerationQuotaService}, as its own settings-key family because the cost shape differs
 * again: Eleven Music bills per generated MINUTE ($0.15/min list, 2026-09-20), so there are two
 * levers here, not one - how many tracks, and how long each may be.
 *
 * <ul>
 *   <li><b>Count caps</b> ({@link #checkAllowed}) apply only when the PLATFORM key pays; the ledger
 *       is {@link GeneratedMusicService#countPlatformBilledSince}. A tenant on their own ElevenLabs
 *       key is spending their own money and is not count-capped.
 *   <li><b>Duration cap</b> ({@link #maxDurationSeconds}) applies to both paths: a model asking for
 *       a 10-minute track by accident is a bad surprise on anyone's bill.
 * </ul>
 *
 * Worst case at the defaults, platform-paid: FREE 10 tracks x 30s = 5 min = $0.75/tenant/month;
 * paid 200 x 120s = 400 min = $60/tenant/month.
 */
@Service
public class MusicGenerationQuotaService {

    private final GeneratedMusicService musicService;
    private final PlatformSettingsService platformSettings;
    private final TenantLimits tenantLimits;
    private final boolean defaultEnabled;
    private final int defaultFreeDailyCap;
    private final int defaultFreeMonthlyCap;
    private final int defaultPaidDailyCap;
    private final int defaultPaidMonthlyCap;
    private final int defaultFreeMaxSeconds;
    private final int defaultPaidMaxSeconds;

    public MusicGenerationQuotaService(
            GeneratedMusicService musicService,
            PlatformSettingsService platformSettings,
            TenantLimits tenantLimits,
            @Value("${app.music.generation.enabled:true}") boolean defaultEnabled,
            @Value("${app.music.generation.free-daily-cap:2}") int defaultFreeDailyCap,
            @Value("${app.music.generation.free-monthly-cap:10}") int defaultFreeMonthlyCap,
            @Value("${app.music.generation.paid-daily-cap:20}") int defaultPaidDailyCap,
            @Value("${app.music.generation.paid-monthly-cap:200}") int defaultPaidMonthlyCap,
            @Value("${app.music.generation.free-max-seconds:30}") int defaultFreeMaxSeconds,
            @Value("${app.music.generation.paid-max-seconds:120}") int defaultPaidMaxSeconds) {
        this.musicService = musicService;
        this.platformSettings = platformSettings;
        this.tenantLimits = tenantLimits;
        this.defaultEnabled = defaultEnabled;
        this.defaultFreeDailyCap = defaultFreeDailyCap;
        this.defaultFreeMonthlyCap = defaultFreeMonthlyCap;
        this.defaultPaidDailyCap = defaultPaidDailyCap;
        this.defaultPaidMonthlyCap = defaultPaidMonthlyCap;
        this.defaultFreeMaxSeconds = defaultFreeMaxSeconds;
        this.defaultPaidMaxSeconds = defaultPaidMaxSeconds;
    }

    public boolean isEnabled() {
        return platformSettings.getBoolean("music.generation.enabled", defaultEnabled);
    }

    /**
     * The longest track this tenant's tier may request, in seconds (never below the API's 3s
     * floor).
     */
    public int maxDurationSeconds() {
        int configured =
                currentPlan() == TenantPlan.FREE
                        ? platformSettings.getInt(
                                "music.generation.free-max-seconds", defaultFreeMaxSeconds)
                        : platformSettings.getInt(
                                "music.generation.paid-max-seconds", defaultPaidMaxSeconds);
        return Math.max(3, configured);
    }

    /**
     * @throws MusicGenerationQuotaExceededException (person-facing message) if music is switched
     *     off - applies to BYOK and platform alike.
     */
    public void checkEnabled() {
        if (!isEnabled()) {
            throw new MusicGenerationQuotaExceededException(
                    "Music generation is turned off right now.");
        }
    }

    /**
     * Platform-paid path only. @throws MusicGenerationQuotaExceededException if this tenant has hit
     * today's/this month's cap for its tier.
     */
    public void checkAllowed() {
        checkEnabled();
        TenantPlan plan = currentPlan();
        if (plan == TenantPlan.UNLIMITED) {
            return;
        }
        int dailyCap =
                plan == TenantPlan.FREE
                        ? platformSettings.getInt(
                                "music.generation.free-daily-cap", defaultFreeDailyCap)
                        : platformSettings.getInt(
                                "music.generation.paid-daily-cap", defaultPaidDailyCap);
        int monthlyCap =
                plan == TenantPlan.FREE
                        ? platformSettings.getInt(
                                "music.generation.free-monthly-cap", defaultFreeMonthlyCap)
                        : platformSettings.getInt(
                                "music.generation.paid-monthly-cap", defaultPaidMonthlyCap);

        Instant now = Instant.now();
        long usedToday = musicService.countPlatformBilledSince(now.minus(Duration.ofDays(1)));
        if (usedToday >= dailyCap) {
            throw new MusicGenerationQuotaExceededException(
                    "You've reached today's music generation limit ("
                            + dailyCap
                            + "). Try again tomorrow, upgrade for more, "
                            + "or add your own ElevenLabs key for uncapped music.");
        }
        long usedThisMonth = musicService.countPlatformBilledSince(now.minus(Duration.ofDays(30)));
        if (usedThisMonth >= monthlyCap) {
            throw new MusicGenerationQuotaExceededException(
                    "You've reached this month's music generation limit ("
                            + monthlyCap
                            + "). Upgrade for more, "
                            + "or add your own ElevenLabs key for uncapped music.");
        }
    }

    private TenantPlan currentPlan() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        return tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
    }
}
