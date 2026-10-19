package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.elevenlabs.ElevenLabsApiException;
import com.agenticai.core.elevenlabs.ElevenLabsClient;
import com.agenticai.core.elevenlabs.ElevenLabsCredentialService;
import com.agenticai.core.elevenlabs.GeneratedMusic;
import com.agenticai.core.elevenlabs.GeneratedMusicService;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.providers.modelcost.MediaModality;
import com.agenticai.core.providers.modelcost.MediaModelCostService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The music counterpart of {@code ImageGenerationOrchestratorService}: picks who pays and which
 * model composes, so {@link GenerateMusicToolCallback} knows neither.
 *
 * <h2>Who pays</h2>
 *
 * The tenant's own ElevenLabs key when one is active (their money, no count cap), otherwise
 * VegaDuta's platform ElevenLabs key - the same one the platform voice uses ({@link
 * PlatformToolKey#ELEVENLABS}) - capped per tenant by {@link MusicGenerationQuotaService} BEFORE
 * the billable call, never after. Unlike the platform voice this needs no voice id, so a platform
 * key alone is enough.
 *
 * <h2>Which model</h2>
 *
 * {@link MediaModelCostService#cheapestFirst} for {@link MediaModality#MUSIC} - cheapest listed
 * model first, quality rank breaking ties. The list is also the fallback chain: a 4xx that is not
 * an auth/plan/quota problem means "this model id or this prompt shape was rejected", so the next
 * listed model gets one try. 401/402/403/429 and transport failures stop immediately - retrying
 * those only burns the visitor's patience.
 *
 * <p>ElevenLabs is the only music provider because it is the only one the platform holds a key for.
 * OpenRouter serves no music (or video) models, so there is nothing to pick there.
 */
@Service
public class MusicGenerationService {

    private static final Logger log = LoggerFactory.getLogger(MusicGenerationService.class);
    static final String PROVIDER = "ELEVENLABS";
    static final int DEFAULT_DURATION_SECONDS = 20;
    private static final int MIN_DURATION_SECONDS = 3;

    /** What {@link GenerateMusicToolCallback} needs to build its result. */
    public record GeneratedTrack(
            UUID id, String model, int durationSeconds, boolean platformBilled) {}

    private final ElevenLabsClient client;
    private final ElevenLabsCredentialService credentialService;
    private final PlatformToolCredentialService platformCredentials;
    private final GeneratedMusicService musicService;
    private final MusicGenerationQuotaService quotaService;
    private final MediaModelCostService modelCosts;

    public MusicGenerationService(
            ElevenLabsClient client,
            ElevenLabsCredentialService credentialService,
            PlatformToolCredentialService platformCredentials,
            GeneratedMusicService musicService,
            MusicGenerationQuotaService quotaService,
            MediaModelCostService modelCosts) {
        this.client = client;
        this.credentialService = credentialService;
        this.platformCredentials = platformCredentials;
        this.musicService = musicService;
        this.quotaService = quotaService;
        this.modelCosts = modelCosts;
    }

    /** Cheap, non-throwing offer-time gate for {@code AgentToolProvider#resolveMusicGenTools}. */
    public boolean isAvailable() {
        return credentialService.isActive()
                || platformCredentials.resolveActiveApiKey(PlatformToolKey.ELEVENLABS).isPresent();
    }

    /**
     * @param requestedSeconds {@code null}/non-positive means {@link #DEFAULT_DURATION_SECONDS};
     *     always clamped to the tenant tier's maximum.
     * @throws MusicGenerationQuotaExceededException person-facing, before any billable call
     * @throws MusicGenNotConfiguredException if no key can serve the request
     * @throws ElevenLabsApiException if every candidate model failed
     */
    public GeneratedTrack generate(String prompt, Integer requestedSeconds, boolean instrumental) {
        quotaService.checkEnabled();
        boolean byok = credentialService.isActive();
        String apiKey;
        if (byok) {
            apiKey = credentialService.resolveActiveApiKey();
        } else {
            apiKey =
                    platformCredentials
                            .resolveActiveApiKey(PlatformToolKey.ELEVENLABS)
                            .orElseThrow(
                                    () ->
                                            new MusicGenNotConfiguredException(
                                                    "No ElevenLabs key is available for music generation."));
            quotaService.checkAllowed();
        }
        int seconds = clampSeconds(requestedSeconds, quotaService.maxDurationSeconds());

        List<MediaModelCostService.ModelChoice> candidates =
                modelCosts.cheapestFirst(MediaModality.MUSIC, PROVIDER);
        ElevenLabsApiException lastFailure = null;
        for (MediaModelCostService.ModelChoice candidate : candidates) {
            try {
                byte[] mp3 =
                        client.composeMusic(
                                prompt, seconds * 1000, candidate.modelId(), instrumental, apiKey);
                UUID id =
                        musicService.store(
                                mp3,
                                prompt,
                                candidate.modelId(),
                                PROVIDER,
                                byok
                                        ? GeneratedMusic.BILLED_TO_TENANT
                                        : GeneratedMusic.BILLED_TO_PLATFORM,
                                seconds);
                return new GeneratedTrack(id, candidate.modelId(), seconds, !byok);
            } catch (ElevenLabsApiException e) {
                lastFailure = e;
                if (!isWorthTryingNextModel(e.getUpstreamStatus())) {
                    throw e;
                }
                log.info(
                        "Music model {} rejected the request (HTTP {}) - trying the next listed model",
                        candidate.modelId(),
                        e.getUpstreamStatus());
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new MusicGenNotConfiguredException("No music model is listed in the cost table.");
    }

    static int clampSeconds(Integer requested, int maxSeconds) {
        int wanted = requested == null || requested <= 0 ? DEFAULT_DURATION_SECONDS : requested;
        return Math.max(MIN_DURATION_SECONDS, Math.min(maxSeconds, wanted));
    }

    /**
     * A model- or request-shaped 4xx; never auth (401/403), payment (402) or rate limiting (429).
     */
    static boolean isWorthTryingNextModel(int status) {
        return status == 400 || status == 404 || status == 422;
    }
}
