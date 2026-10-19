package com.agenticai.core.providers.modality;

import java.util.List;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that registers every {@link TextToSpeechProvider} bean
 * with the {@link TextToSpeechProviderFactory} at startup - mirrors {@link
 * SpeechToTextProviderConfiguration} exactly.
 *
 * <p>ADR-0011 phase 2: providers are discovered by type ({@code
 * List<TextToSpeechProvider>}) instead of being named here. The vendor
 * adapters live in their vendor modules and implement this module's SPI
 * ({@code elevenlabs.ElevenLabsTextToSpeechProvider}, {@code
 * sarvam.SarvamTextToSpeechProvider}), so {@code providers} no longer depends
 * on any vendor module. The full distribution ({@code app}) has both on its
 * classpath, so the registered set - and each provider's {@link
 * TtsModelProvider} key - is unchanged. A distribution without a vendor module
 * simply has no provider for that key, which {@link
 * TextToSpeechProviderFactory#getUnavailabilityReason} already reports as "not
 * available on this deployment".
 */
@Configuration
public class TextToSpeechProviderConfiguration {

    public TextToSpeechProviderConfiguration(
        TextToSpeechProviderFactory factory,
        List<TextToSpeechProvider> providers
    ) {
        ModalityProviderRegistration.registerAll(
            "TextToSpeechProvider", providers, TextToSpeechProvider::getProvider, factory::register);
    }
}
