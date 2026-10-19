package com.agenticai.core.elevenlabs;

import com.agenticai.core.speech.call.CallSpeechQuotaExceededException;
import com.agenticai.core.speech.call.CallSpeechSynthesizer;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * ElevenLabs for live-call reply audio (the primary tier): the tenant's newest cloned voice, or the
 * public "Rachel" premade voice if none is cloned yet. MP3 output. {@link
 * ElevenLabsClient#textToSpeech} enforces the tenant's voice quota itself; its {@link
 * VoiceQuotaExceededException} becomes the kernel's {@link CallSpeechQuotaExceededException} with
 * the same message.
 */
@Component
public class ElevenLabsCallSpeechSynthesizer implements CallSpeechSynthesizer {

    /**
     * ElevenLabs' public "Rachel" premade voice - every ElevenLabs account can use it, so a tenant
     * that hasn't cloned a voice yet still gets a working TTS leg instead of a hard failure.
     */
    static final String DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM";

    private final ElevenLabsClient client;
    private final ElevenLabsVoiceService voiceService;
    private final ElevenLabsGeneratedAudioService audioService;

    public ElevenLabsCallSpeechSynthesizer(
            ElevenLabsClient client,
            ElevenLabsVoiceService voiceService,
            ElevenLabsGeneratedAudioService audioService) {
        this.client = client;
        this.voiceService = voiceService;
        this.audioService = audioService;
    }

    @Override
    public String method() {
        return "elevenlabs";
    }

    @Override
    public String defaultVoice() {
        return voiceService.hasAnyVoice()
                ? voiceService.mostRecent().getElevenLabsVoiceId()
                : DEFAULT_VOICE_ID;
    }

    @Override
    public byte[] synthesize(String text, String voice) {
        try {
            return client.textToSpeech(voice, text, null);
        } catch (VoiceQuotaExceededException e) {
            throw new CallSpeechQuotaExceededException(e.getMessage(), e);
        }
    }

    @Override
    public void store(byte[] audio, String voice) {
        audioService.store(audio, voice);
    }

    /** MP3 is frame-based, so plain concatenation of complete clips is safe. */
    @Override
    public byte[] concatenate(List<byte[]> clips) {
        int total = clips.stream().mapToInt(c -> c.length).sum();
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] clip : clips) {
            System.arraycopy(clip, 0, out, pos, clip.length);
            pos += clip.length;
        }
        return out;
    }
}
