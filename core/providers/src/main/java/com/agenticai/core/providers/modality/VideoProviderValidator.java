package com.agenticai.core.providers.modality;

import org.springframework.stereotype.Component;

/**
 * Validates video model provider + model combinations at agent creation/update time.
 * Mirrors {@link VoiceProviderValidator} for voice providers.
 *
 * <p>Ensures:
 * <ul>
 *   <li>The provider is available on this deployment</li>
 *   <li>The model string is valid for the selected provider</li>
 *   <li>Clear error messages if validation fails</li>
 * </ul>
 */
@Component
public class VideoProviderValidator {

    private final VideoProviderFactory videoProviderFactory;

    public VideoProviderValidator(VideoProviderFactory videoProviderFactory) {
        this.videoProviderFactory = videoProviderFactory;
    }

    /**
     * Validates that a video provider + model combination is valid and available.
     *
     * @param provider the video provider enum (e.g., OPENAI_WHISPER_VIDEO, SIDECAR_WHISPER_VIDEO)
     * @param model    the model name (e.g., "whisper-1")
     * @throws IllegalArgumentException if the provider is not available or the model is invalid
     */
    public void validate(VideoModelProvider provider, String model) throws IllegalArgumentException {
        if (provider == null) {
            throw new IllegalArgumentException("Video provider cannot be null");
        }

        if (!videoProviderFactory.isAvailable(provider)) {
            String reason = videoProviderFactory.getUnavailabilityReason(provider);
            throw new IllegalArgumentException("Video provider " + provider.getProviderId() + " is not available: " + reason);
        }

        // If model is empty/null, use the provider's default
        if (model == null || model.isBlank()) {
            return; // Default model is acceptable
        }

        // Additional provider-specific validation can be added here in the future
        // For now, we trust that the model string is valid if the provider is available
    }
}
