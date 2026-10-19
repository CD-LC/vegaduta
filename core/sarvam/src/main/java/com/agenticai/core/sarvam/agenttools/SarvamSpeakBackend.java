package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.sarvam.SarvamGeneratedAudioService;
import com.agenticai.core.sarvam.SarvamTextToSpeechClient;
import java.util.UUID;

/**
 * Speaks in an Indian language (or English) via Sarvam's stock voices - {@code languageCode} is a
 * BCP-47 code (e.g. {@code "hi-IN"}), defaulting to {@link #DEFAULT_LANGUAGE} when the model omits
 * it (the unified {@code speak} tool no longer requires a language the way the previous standalone
 * {@code sarvam_speak} tool did, since a non-Sarvam-aware caller has no reason to know Sarvam needs
 * one). {@code voiceHint} is an optional speaker name for the default model. Behavior otherwise
 * unchanged from the previous standalone tool - only the wrapping changed, see {@code
 * tools.audio.SpeakToolCallback}.
 */
public class SarvamSpeakBackend implements SpeakBackend {

    private static final String DEFAULT_LANGUAGE = "en-IN";

    private final SarvamTextToSpeechClient client;
    private final SarvamGeneratedAudioService audioService;

    public SarvamSpeakBackend(
            SarvamTextToSpeechClient client, SarvamGeneratedAudioService audioService) {
        this.client = client;
        this.audioService = audioService;
    }

    @Override
    public String providerLabel() {
        return "SARVAM";
    }

    @Override
    public SpokenAudio speak(String text, String languageCode, String voiceHint) {
        String target =
                (languageCode == null || languageCode.isBlank()) ? DEFAULT_LANGUAGE : languageCode;
        SarvamTextToSpeechClient.SynthesisResult result =
                client.synthesize(text, target, null, voiceHint);
        UUID audioId = audioService.store(result.wavBytes(), "speech.wav");
        return new SpokenAudio("/api/sarvam/audio/" + audioId, target);
    }
}
