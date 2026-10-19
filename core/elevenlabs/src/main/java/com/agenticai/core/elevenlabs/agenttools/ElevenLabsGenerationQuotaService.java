package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.agentspi.AudioGenerationQuotaExceededException;
import com.agenticai.core.elevenlabs.ElevenLabsGeneratedAudioService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Per-tenant daily/monthly caps for PLATFORM-SUBSIDISED ElevenLabs voice generation only (the
 * {@code PlatformElevenLabsSpeakBackend} path, where VegaDūta's own operator-configured ElevenLabs
 * key pays) - mirrors {@code sarvam.agenttools.AudioGenerationQuotaService} (the equivalent for
 * platform-Sarvam) exactly, as a SEPARATE service/settings-key family rather than a shared one: the
 * two providers have very different per-request cost (ElevenLabs bills by character count, Sarvam
 * by request), so sharing one cap would let one provider silently starve the other's budget.
 *
 * <p>Deliberately NOT applied to a tenant's own BYOK ElevenLabs key ({@link
 * com.agenticai.core.elevenlabs.VoiceQuotaService} already caps that separately) - {@code
 * resolveAudioTools} only wires this in on the platform-key fallback.
 */
@Service
public class ElevenLabsGenerationQuotaService {

    private final ElevenLabsGeneratedAudioService audioService;
    private final PlatformSettingsService platformSettings;
    private final TenantLimits tenantLimits;
    private final boolean defaultEnabled;
    private final int defaultFreeDailyCap;
    private final int defaultFreeMonthlyCap;
    private final int defaultPaidDailyCap;
    private final int defaultPaidMonthlyCap;

    public ElevenLabsGenerationQuotaService(
            ElevenLabsGeneratedAudioService audioService,
            PlatformSettingsService platformSettings,
            TenantLimits tenantLimits,
            @Value("${app.audio.generation.elevenlabs.enabled:true}") boolean defaultEnabled,
            @Value("${app.audio.generation.elevenlabs.free-daily-cap:10}") int defaultFreeDailyCap,
            @Value("${app.audio.generation.elevenlabs.free-monthly-cap:100}")
                    int defaultFreeMonthlyCap,
            @Value("${app.audio.generation.elevenlabs.paid-daily-cap:100}") int defaultPaidDailyCap,
            @Value("${app.audio.generation.elevenlabs.paid-monthly-cap:1500}")
                    int defaultPaidMonthlyCap) {
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
        return platformSettings.getBoolean("audio.generation.elevenlabs.enabled", defaultEnabled);
    }

    /**
     * @throws AudioGenerationQuotaExceededException (person-facing message) if the platform
     *     ElevenLabs feature is off, or this tenant has hit today's/this month's cap for its tier.
     */
    public void checkAllowed() {
        if (!isEnabled()) {
            throw new AudioGenerationQuotaExceededException(
                    "Platform voice generation is turned off right now.");
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;

        int dailyCap =
                plan == TenantPlan.FREE
                        ? platformSettings.getInt(
                                "audio.generation.elevenlabs.free-daily-cap", defaultFreeDailyCap)
                        : platformSettings.getInt(
                                "audio.generation.elevenlabs.paid-daily-cap", defaultPaidDailyCap);
        int monthlyCap =
                plan == TenantPlan.FREE
                        ? platformSettings.getInt(
                                "audio.generation.elevenlabs.free-monthly-cap",
                                defaultFreeMonthlyCap)
                        : platformSettings.getInt(
                                "audio.generation.elevenlabs.paid-monthly-cap",
                                defaultPaidMonthlyCap);

        Instant now = Instant.now();
        long usedToday = audioService.countSince(now.minus(Duration.ofDays(1)));
        if (usedToday >= dailyCap) {
            throw new AudioGenerationQuotaExceededException(
                    "You've reached today's platform voice generation limit ("
                            + dailyCap
                            + "). Try again tomorrow, or upgrade for more.");
        }
        long usedThisMonth = audioService.countSince(now.minus(Duration.ofDays(30)));
        if (usedThisMonth >= monthlyCap) {
            throw new AudioGenerationQuotaExceededException(
                    "You've reached this month's platform voice generation limit ("
                            + monthlyCap
                            + "). Upgrade for more.");
        }
    }
}
