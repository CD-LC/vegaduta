package com.agenticai.core.sarvam;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.speech.call.CallSpeechTranscriber;
import org.springframework.stereotype.Component;

/**
 * Sarvam speech-to-text as the live-call transcription fallback; no tenant credential is "not
 * configured".
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamCallSpeechTranscriber implements CallSpeechTranscriber {

    private final SarvamSpeechToTextClient client;

    public SarvamCallSpeechTranscriber(SarvamSpeechToTextClient client) {
        this.client = client;
    }

    @Override
    public String transcribe(byte[] audio, String filename) {
        try {
            return client.transcribe(audio, filename).transcript();
        } catch (SarvamNotConfiguredException e) {
            throw new NotConfiguredException(e.getMessage(), e);
        }
    }
}
