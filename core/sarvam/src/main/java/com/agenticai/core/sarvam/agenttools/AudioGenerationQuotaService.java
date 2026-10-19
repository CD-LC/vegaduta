package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.agentspi.AudioGenerationQuotaExceededException;
import com.agenticai.core.sarvam.SarvamGeneratedAudioService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-tenant daily/monthly caps for PLATFORM-SUBSIDISED voice generation only (the {@code
 * PlatformSarvamSpeakBackend} path, where VegaDūta's own Sarvam key pays). Mirrors {@code
 * ImageGenerationQuotaService}/{@code VideoGenerationQuotaService} - every value reads live from
 * {@link PlatformSettingsService} so it is tunable without a redeploy.
 *
 * <p>Deliberately NOT applied to a tenant's own BYOK Sarvam key (the tenant pays there, so it is
 * uncapped) - {@code resolveAudioTools} only wires this in on the platform-key fallback. Because
 * that fallback only runs when the tenant has no BYOK Sarvam credential, every Sarvam audio row in
 * that tenant's schema IS a platform row, so counting the schema's rows counts platform usage
 * exactly (no BYOK contamination).
 *
 * <p>Sarvam TTS is billed per request (cheap, not per-second like Veo video), so the defaults are
 * generous relative to image/video but still bounded - the whole point is that "free audio for
 * everyone" can never become an unbounded bill.
 */
@Service
public class AudioGenerationQuotaService {

    private final SarvamGeneratedAudioService audioService;
    private final PlatformSettingsService platformSettings;
    private final TenantLimits tenantLimits;
    private final boolean defaultEnabled;
    private final int defaultFreeDailyCap;
    private final int defaultFreeMonthlyCap;
    private final int defaultPaidDailyCap;
    private final int defaultPaidMonthlyCap;

    public AudioGenerationQuotaService(
            SarvamGeneratedAudioService audioService,
            PlatformSettingsService platformSettings,
            TenantLimits tenantLimits,
            @Value("${app.audio.generation.enabled:true}") boolean defaultEnabled,
            @Value("${app.audio.generation.free-daily-cap:20}") int defaultFreeDailyCap,
            @Value("${app.audio.generation.free-monthly-cap:200}") int defaultFreeMonthlyCap,
            @Value("${app.audio.generation.paid-daily-cap:200}") int defaultPaidDailyCap,
            @Value("${app.audio.generation.paid-monthly-cap:3000}") int defaultPaidMonthlyCap) {
        this.audioService = audioService;
        this.platformSettings = platformSettings;
        this.tenantLimits = tenantLimits;
        this.defaultEnabled = defaultEnabled;
        this.defaultFreeDailyCap = defaultFreeDailyCap;
        this.defaultFreeMonthlyCap = defaultFreeMonthlyCap;
        this.defaultPaidDailyCap = defaultPaidDailyCap;
        this.defaultPaidMonthlyCap = defaultPaidMonthlyCap;
    }

    public boolean isEnabled() {
        return platformSettings.getBoolean("audio.generation.enabled", defaultEnabled);
    }

    /**
     * Resolves the current tenant's plan and enforces the platform-audio caps.
     *
     * @throws AudioGenerationQuotaExceededException (person-facing message) if the platform audio
     *     feature is off, or this tenant has hit today's / this month's cap for its tier.
     */
    public void checkAllowed() {
        if (!isEnabled()) {
            throw new AudioGenerationQuotaExceededException(
                    "Voice generation is turned off on this platform right now.");
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;

        int dailyCap =
                plan == TenantPlan.FREE
                        ? platformSettings.getInt(
                                "audio.generation.free-daily-cap", defaultFreeDailyCap)
                        : platformSettings.getInt(
                                "audio.generation.paid-daily-cap", defaultPaidDailyCap);
        int monthlyCap =
                plan == TenantPlan.FREE
                        ? platformSettings.getInt(
                                "audio.generation.free-monthly-cap", defaultFreeMonthlyCap)
                        : platformSettings.getInt(
                                "audio.generation.paid-monthly-cap", defaultPaidMonthlyCap);

        Instant now = Instant.now();
        long usedToday = audioService.countSince(now.minus(Duration.ofDays(1)));
        if (usedToday >= dailyCap) {
            throw new AudioGenerationQuotaExceededException(
                    "You've reached today's voice generation limit ("
                            + dailyCap
                            + "). Try again tomorrow, or upgrade for more.");
        }
        long usedThisMonth = audioService.countSince(now.minus(Duration.ofDays(30)));
        if (usedThisMonth >= monthlyCap) {
            throw new AudioGenerationQuotaExceededException(
                    "You've reached this month's voice generation limit ("
                            + monthlyCap
                            + "). Upgrade for more.");
        }
    }
}
