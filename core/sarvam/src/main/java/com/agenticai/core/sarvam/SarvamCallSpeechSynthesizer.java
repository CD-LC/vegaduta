package com.agenticai.core.sarvam;

import com.agenticai.core.speech.WavConcatenator;
import com.agenticai.core.speech.call.CallSpeechQuota;
import com.agenticai.core.speech.call.CallSpeechSynthesizer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sarvam for live-call reply audio (the second tier, after ElevenLabs): the tenant's OWN Sarvam
 * credential (never a platform-subsidised key), {@code en-IN}, WAV output. Quota-gated against the
 * shared voice budget before every synthesis (2026-08-08 audit fix #2) - a {@code
 * CallSpeechQuotaExceededException} propagates; any other failure, including "no Sarvam credential
 * configured", returns {@code null} so callers fall through to text-only.
 */
@Component
public class SarvamCallSpeechSynthesizer implements CallSpeechSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(SarvamCallSpeechSynthesizer.class);

    private final SarvamTextToSpeechClient client;
    private final SarvamGeneratedAudioService audioService;
    private final CallSpeechQuota quota;

    public SarvamCallSpeechSynthesizer(
            SarvamTextToSpeechClient client,
            SarvamGeneratedAudioService audioService,
            CallSpeechQuota quota) {
        this.client = client;
        this.audioService = audioService;
        this.quota = quota;
    }

    @Override
    public String method() {
        return "sarvam";
    }

    /** Sarvam speaks a stock voice; the "voice" recorded with each clip is its file name. */
    @Override
    public String defaultVoice() {
        return "speech.wav";
    }

    @Override
    public byte[] synthesize(String text, String voice) {
        quota.checkAllowed(text, audioService::countSince);
        try {
            SarvamTextToSpeechClient.SynthesisResult result =
                    client.synthesize(text, "en-IN", null, null);
            byte[] wav = result.wavBytes();
            return (wav != null && wav.length > 0) ? wav : null;
        } catch (Exception e) {
            // WARN, not DEBUG (2026-08-08 audit fix #6): a production "no voice reply at all"
            // incident needs
            // this reason visible without DEBUG logging turned on.
            log.warn("Sarvam TTS synthesis failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void store(byte[] audio, String voice) {
        audioService.store(audio, "speech.wav");
    }

    /** Each WAV clip carries its own RIFF header, so clips are merged, not byte-concatenated. */
    @Override
    public byte[] concatenate(List<byte[]> clips) {
        return WavConcatenator.concatenate(clips);
    }
}
