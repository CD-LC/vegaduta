package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.elevenlabs.ElevenLabsClient;
import com.agenticai.core.elevenlabs.ElevenLabsGeneratedAudioService;
import com.agenticai.core.elevenlabs.ElevenLabsVoice;
import com.agenticai.core.elevenlabs.ElevenLabsVoiceService;
import java.util.UUID;

/**
 * Speaks in this tenant's own cloned ElevenLabs voice - {@code languageCode} is ignored (the voice
 * itself, not a parameter, determines what it sounds like; ElevenLabs' models are multilingual per
 * voice), {@code voiceHint} is the internal id of a specific cloned voice, defaulting to the most
 * recently cloned one. Behavior unchanged from the previous standalone {@code
 * speak_with_cloned_voice} tool - only the wrapping changed, see {@code
 * tools.audio.SpeakToolCallback}.
 */
public class ElevenLabsSpeakBackend implements SpeakBackend {

    private final ElevenLabsClient client;
    private final ElevenLabsGeneratedAudioService audioService;
    private final ElevenLabsVoiceService voiceService;

    public ElevenLabsSpeakBackend(
            ElevenLabsClient client,
            ElevenLabsGeneratedAudioService audioService,
            ElevenLabsVoiceService voiceService) {
        this.client = client;
        this.audioService = audioService;
        this.voiceService = voiceService;
    }

    @Override
    public String providerLabel() {
        return "ELEVENLABS";
    }

    @Override
    public SpokenAudio speak(String text, String languageCode, String voiceHint) {
        ElevenLabsVoice voice = resolveVoice(voiceHint);
        byte[] mp3Bytes = client.textToSpeech(voice.getElevenLabsVoiceId(), text, null);
        UUID audioId = audioService.store(mp3Bytes, voice.getElevenLabsVoiceId());
        return new SpokenAudio("/api/elevenlabs/audio/" + audioId, voice.getName());
    }

    /**
     * {@code AgentToolProvider#resolveAudioTools}'s gate already guarantees at least one voice
     * exists by the time this backend is ever constructed, so {@code mostRecent()} throwing is not
     * a real code path here in practice - kept as a defensive fallback (e.g. a voice deleted
     * mid-conversation) rather than assumed impossible.
     */
    private ElevenLabsVoice resolveVoice(String voiceIdArg) {
        if (voiceIdArg == null || voiceIdArg.isBlank()) {
            return voiceService.mostRecent();
        }
        try {
            return voiceService
                    .findById(UUID.fromString(voiceIdArg))
                    .orElseGet(voiceService::mostRecent);
        } catch (IllegalArgumentException notAUuid) {
            return voiceService.mostRecent();
        }
    }
}
