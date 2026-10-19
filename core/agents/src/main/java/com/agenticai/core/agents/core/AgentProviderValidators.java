package com.agenticai.core.agents.core;

import com.agenticai.core.providers.modality.TextModelProviderValidator;
import com.agenticai.core.providers.modality.TextToSpeechProviderValidator;
import com.agenticai.core.providers.modality.VideoProviderValidator;
import com.agenticai.core.providers.modality.VoiceProviderValidator;
import org.springframework.stereotype.Component;

/**
 * The four per-modality provider validators (voice, video, text, TTS) an agent's model selection is
 * checked against, bundled so the services that validate a selection ({@link
 * AgentCreationService}, {@link AgentModelSelectionService}) take one dependency instead of four.
 */
@Component
public class AgentProviderValidators {

    private final VoiceProviderValidator voice;
    private final VideoProviderValidator video;
    private final TextModelProviderValidator text;
    private final TextToSpeechProviderValidator tts;

    public AgentProviderValidators(
            VoiceProviderValidator voice,
            VideoProviderValidator video,
            TextModelProviderValidator text,
            TextToSpeechProviderValidator tts) {
        this.voice = voice;
        this.video = video;
        this.text = text;
        this.tts = tts;
    }

    public VoiceProviderValidator voice() {
        return voice;
    }

    public VideoProviderValidator video() {
        return video;
    }

    public TextModelProviderValidator text() {
        return text;
    }

    public TextToSpeechProviderValidator tts() {
        return tts;
    }
}
