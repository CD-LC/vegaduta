package com.agenticai.core.providers.modality;

import java.util.List;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that registers all {@link VideoToTextProvider}
 * implementations with the {@link VideoProviderFactory} at startup - the
 * video-side counterpart of {@link SpeechToTextProviderConfiguration}.
 * Without this, the factory's provider map stays empty and every
 * {@link VideoProviderFactory#isAvailable} check reports false, so no video
 * provider is ever selectable regardless of what the individual providers'
 * own {@code isAvailable()} say (gap caught in the 2026-07-29 audit).
 *
 * <p>Unlike the speech configuration's per-bean constructor parameters, this
 * injects the whole {@code List<VideoToTextProvider>}: {@link
 * OpenAIWhisperVideoProvider} is {@code @ConditionalOnProperty} on
 * {@code openai.api-key} and simply doesn't exist as a bean on deployments
 * without a key, which a mandatory constructor parameter would turn into a
 * startup failure. List injection registers whatever subset actually exists.
 */
@Configuration
public class VideoProviderConfiguration {

    public VideoProviderConfiguration(VideoProviderFactory factory, List<VideoToTextProvider> providers) {
        providers.forEach(factory::register);
    }
}
