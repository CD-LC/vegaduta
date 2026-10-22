package com.agenticai.core.elevenlabs;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.function.ToLongFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Tiered caps for ElevenLabs text-to-speech generation - FREE gets a short per-request character
 * ceiling and a small daily/monthly call allowance, PREMIUM gets a much longer ceiling and a
 * generous allowance, UNLIMITED is uncapped entirely. Same shape as {@code
 * VideoGenerationQuotaService} in {@code core.imagegen}: every value reads live from {@link
 * PlatformSettingsService} so it's tunable without a redeploy, and the tenant's own usage rows
 * double as the ledger rather than a separate counter table.
 *
 * <p>Unlike video generation, this module is BYOK-only (every synthesis call spends the tenant's
 * own ElevenLabs API key, never a platform-pooled one - see {@code ElevenLabsClient}'s javadoc) -
 * so this cap isn't about platform spend, it's about keeping the FREE tier's feature surface
 * consistent with every other plan-gated capability in this codebase (agents/workflows/KB
 * collections caps, video generation, etc. - see {@link TenantPlan}'s javadoc on the "universal
 * tier, enforced everywhere" product decision).
 *
 * <p>Usage is counted from {@link ElevenLabsGeneratedAudioRepository}'s {@code created_at} column.
 * Both text-to-speech surfaces persist a row here and so both count toward the daily/monthly
 * ledger: the {@code speak_with_cloned_voice} agent tool path ({@code
 * ElevenLabsGeneratedAudioService#store}) and {@code core.voice}'s real-time {@code
 * VoiceCallController#turn}, which stores each turn's reply audio via the same {@code store} call
 * right after generating it.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class VoiceQuotaService {

    private final ElevenLabsGeneratedAudioRepository audioRepository;
    private final PlatformSettingsService platformSettings;
    private final boolean defaultQuotaEnabled;
    private final int defaultFreeMaxCharactersPerRequest;
    private final int defaultPremiumMaxCharactersPerRequest;
    private final int defaultFreeDailyCap;
    private final int defaultFreeMonthlyCap;
    private final int defaultPremiumDailyCap;
    private final int defaultPremiumMonthlyCap;

    public VoiceQuotaService(
            ElevenLabsGeneratedAudioRepository audioRepository,
            PlatformSettingsService platformSettings,
            @Value("${app.elevenlabs.voice.quota-enabled:true}") boolean defaultQuotaEnabled,
            @Value("${app.elevenlabs.voice.free-max-characters-per-request:300}")
                    int defaultFreeMaxCharactersPerRequest,
            @Value("${app.elevenlabs.voice.premium-max-characters-per-request:5000}")
                    int defaultPremiumMaxCharactersPerRequest,
            @Value("${app.elevenlabs.voice.free-daily-cap:5}") int defaultFreeDailyCap,
            @Value("${app.elevenlabs.voice.free-monthly-cap:50}") int defaultFreeMonthlyCap,
            @Value("${app.elevenlabs.voice.premium-daily-cap:200}") int defaultPremiumDailyCap,
            @Value("${app.elevenlabs.voice.premium-monthly-cap:3000}")
                    int defaultPremiumMonthlyCap) {
        this.audioRepository = audioRepository;
        this.platformSettings = platformSettings;
        this.defaultQuotaEnabled = defaultQuotaEnabled;
        this.defaultFreeMaxCharactersPerRequest = defaultFreeMaxCharactersPerRequest;
        this.defaultPremiumMaxCharactersPerRequest = defaultPremiumMaxCharactersPerRequest;
        this.defaultFreeDailyCap = defaultFreeDailyCap;
        this.defaultFreeMonthlyCap = defaultFreeMonthlyCap;
        this.defaultPremiumDailyCap = defaultPremiumDailyCap;
        this.defaultPremiumMonthlyCap = defaultPremiumMonthlyCap;
    }

    public boolean isEnabled() {
        return platformSettings.getBoolean("elevenlabs.voice.quota-enabled", defaultQuotaEnabled);
    }

    public int maxCharactersPerRequestFor(TenantPlan plan) {
        return plan == TenantPlan.FREE
                ? platformSettings.getInt(
                        "elevenlabs.voice.free-max-characters-per-request",
                        defaultFreeMaxCharactersPerRequest)
                : platformSettings.getInt(
                        "elevenlabs.voice.premium-max-characters-per-request",
                        defaultPremiumMaxCharactersPerRequest);
    }

    /**
     * @throws VoiceQuotaExceededException with a visitor-facing reason if this request would exceed
     *     the tenant's tier, or if voice quota enforcement is turned off platform-wide. {@code
     *     UNLIMITED} tenants still skip the daily/monthly/per-request caps below, but do NOT skip
     *     the {@link #isEnabled()} check - same fail-closed shape as {@code
     *     ImageGenerationQuotaService#checkAllowed} and {@code
     *     VideoGenerationQuotaService#checkAllowed}. Historically this method returned (i.e.
     *     allowed, uncapped) when the admin toggle was off, which is backwards: this is the only
     *     gate in front of a paid, per-character external API call ({@link ElevenLabsClient}), so
     *     "disabled" must mean "feature unavailable," not "unlimited spend."
     */
    public void checkAllowed(TenantPlan plan, String text) {
        checkAllowed(plan, text, since -> 0L);
    }

    /**
     * Cross-provider variant of {@link #checkAllowed(TenantPlan, String)} - same
     * per-request/daily/monthly gating, but the daily/monthly usage total also folds in {@code
     * otherProviderUsageSince}, a caller-supplied count of a DIFFERENT provider's own
     * generated-audio rows created after a given instant (e.g. {@code
     * SarvamGeneratedAudioService::countSince}).
     *
     * <p>Added for the 2026-08-08 audit finding that Sarvam TTS (the fallback tier in {@code
     * VoiceCallController#synthesizeSarvamOnly}) had zero plan enforcement of its own: this
     * service's ledger only ever counted {@link ElevenLabsGeneratedAudioRepository} rows, so a FREE
     * tenant with a Sarvam BYOK credential could bypass the ElevenLabs cap entirely just by falling
     * through to the other provider. Rather than duplicate a parallel quota system in {@code
     * core.sarvam} (a module this one has no reason to depend on), the caller passes its own
     * ledger's counting function in and this service folds it into the same cap check - one shared
     * daily/monthly budget across both TTS providers, still keyed off this service's own day/month
     * window boundaries so those stay defined in exactly one place.
     */
    public void checkAllowed(
            TenantPlan plan, String text, ToLongFunction<Instant> otherProviderUsageSince) {
        if (!isEnabled()) {
            throw new VoiceQuotaExceededException(
                    "Voice generation is turned off on this platform right now.");
        }
        if (plan == TenantPlan.UNLIMITED) {
            return;
        }

        int requestedCharacters = text == null ? 0 : text.length();
        int maxCharacters = maxCharactersPerRequestFor(plan);
        if (requestedCharacters > maxCharacters) {
            throw new VoiceQuotaExceededException(
                    "Your plan supports up to "
                            + maxCharacters
                            + " characters per voice generation. Upgrade for longer clips.");
        }

        int dailyCap =
                plan == TenantPlan.FREE
                        ? platformSettings.getInt(
                                "elevenlabs.voice.free-daily-cap", defaultFreeDailyCap)
                        : platformSettings.getInt(
                                "elevenlabs.voice.premium-daily-cap", defaultPremiumDailyCap);
        int monthlyCap =
                plan == TenantPlan.FREE
                        ? platformSettings.getInt(
                                "elevenlabs.voice.free-monthly-cap", defaultFreeMonthlyCap)
                        : platformSettings.getInt(
                                "elevenlabs.voice.premium-monthly-cap", defaultPremiumMonthlyCap);

        Instant now = Instant.now();
        Instant dayAgo = now.minus(Duration.ofDays(1));
        Instant monthAgo = now.minus(Duration.ofDays(30));
        long usedToday =
                audioRepository.countByCreatedAtAfter(dayAgo)
                        + otherProviderUsageSince.applyAsLong(dayAgo);
        if (usedToday >= dailyCap) {
            throw new VoiceQuotaExceededException(
                    "You've reached today's voice generation limit ("
                            + dailyCap
                            + "). Try again tomorrow, or upgrade for a higher limit.");
        }
        long usedThisMonth =
                audioRepository.countByCreatedAtAfter(monthAgo)
                        + otherProviderUsageSince.applyAsLong(monthAgo);
        if (usedThisMonth >= monthlyCap) {
            throw new VoiceQuotaExceededException(
                    "You've reached this month's voice generation limit ("
                            + monthlyCap
                            + "). Upgrade for a higher limit.");
        }
    }
}
