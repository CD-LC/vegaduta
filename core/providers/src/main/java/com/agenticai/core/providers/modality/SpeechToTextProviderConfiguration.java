package com.agenticai.core.providers.modality;

import java.util.List;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that registers every {@link SpeechToTextProvider} bean
 * with the {@link SpeechToTextProviderFactory} at startup.
 *
 * <p>ADR-0011 phase 2: providers are discovered by type ({@code
 * List<SpeechToTextProvider>}) instead of being named here, so vendor adapters
 * (e.g. {@code sarvam.SarvamSpeechToTextProvider}) live in their vendor module
 * and {@code providers} has no vendor dependency. In the full distribution the
 * registered set is unchanged: {@link SidecarWhisperProvider}
 * ({@link VoiceModelProvider#SIDECAR_WHISPER}) and Sarvam
 * ({@link VoiceModelProvider#SARVAM}).
 */
@Configuration
public class SpeechToTextProviderConfiguration {

    public SpeechToTextProviderConfiguration(
        SpeechToTextProviderFactory factory,
        List<SpeechToTextProvider> providers
    ) {
        ModalityProviderRegistration.registerAll(
            "SpeechToTextProvider", providers, SpeechToTextProvider::getProvider, factory::register);
    }
}
