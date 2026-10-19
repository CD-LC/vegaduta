package com.agenticai.core.providers.modality;

import com.agenticai.core.providers.dto.ModalityCatalogResponse;
import com.agenticai.core.providers.dto.ModalityProviderOption;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Server-side source of truth behind {@code GET /api/providers/modalities} -
 * the catalog the workbench's STT/TTS/video selectors render instead of
 * their old hardcoded provider arrays + bespoke credential/health probes
 * ({@code getSarvamCredential}, {@code getElevenLabsCredential}, {@code
 * openaiActive}, sidecar health).
 *
 * <p>Every enum constant becomes one catalog row; availability is delegated
 * to the same factories {@code AgentService} validates selections against,
 * so the dropdown can never offer an option the save path would reject.
 * {@code implemented=false} marks reserved constants with no registered
 * provider bean (e.g. {@code SARVAM_VIDEO}) - the old frontend hardcoded
 * those as {@code disabled: true, disabledReason: "Coming soon"}.
 *
 * <p>Adding a provider: implement the modality's provider interface,
 * register the bean, add the enum constant (which now carries its own
 * label/description) - it appears in this catalog with correct availability
 * and no further wiring.
 */
@Service
public class ModalityCatalogService {

    private final SpeechToTextProviderFactory speechToTextFactory;
    private final TextToSpeechProviderFactory textToSpeechFactory;
    private final VideoProviderFactory videoFactory;

    public ModalityCatalogService(
        SpeechToTextProviderFactory speechToTextFactory,
        TextToSpeechProviderFactory textToSpeechFactory,
        VideoProviderFactory videoFactory
    ) {
        this.speechToTextFactory = speechToTextFactory;
        this.textToSpeechFactory = textToSpeechFactory;
        this.videoFactory = videoFactory;
    }

    public ModalityCatalogResponse catalog() {
        return new ModalityCatalogResponse(
            speechToTextOptions(),
            textToSpeechOptions(),
            videoToTextOptions()
        );
    }

    private List<ModalityProviderOption> speechToTextOptions() {
        return Arrays.stream(VoiceModelProvider.values())
            .filter(VoiceModelProvider::isSelectable)
            .map(provider -> new ModalityProviderOption(
                provider.name(),
                provider.getProviderId(),
                provider.getDisplayName(),
                provider.getDescription(),
                provider.getDefaultModel(),
                speechToTextFactory.getProvider(provider).isPresent(),
                speechToTextFactory.isAvailable(provider),
                speechToTextFactory.getUnavailabilityReason(provider),
                provider.isPremium(),
                provider.isBeta()
            ))
            .toList();
    }

    private List<ModalityProviderOption> textToSpeechOptions() {
        return Arrays.stream(TtsModelProvider.values())
            .map(provider -> new ModalityProviderOption(
                provider.name(),
                provider.getProviderId(),
                provider.getDisplayName(),
                provider.getDescription(),
                provider.getDefaultModel(),
                textToSpeechFactory.getProvider(provider).isPresent(),
                textToSpeechFactory.isAvailable(provider),
                textToSpeechFactory.getUnavailabilityReason(provider),
                provider.isPremium(),
                false
            ))
            .toList();
    }

    private List<ModalityProviderOption> videoToTextOptions() {
        return Arrays.stream(VideoModelProvider.values())
            .map(provider -> new ModalityProviderOption(
                provider.name(),
                provider.getProviderId(),
                provider.getDisplayName(),
                provider.getDescription(),
                provider.getDefaultModel(),
                videoFactory.getProvider(provider).isPresent(),
                videoFactory.isAvailable(provider),
                videoFactory.getUnavailabilityReason(provider),
                provider.isPremium(),
                provider.isBeta()
            ))
            .toList();
    }
}
