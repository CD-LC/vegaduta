package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link ModalityClassifier} is the sole source of the per-modality axis in
 * every Phase P4 spend report - a misclassification silently mis-buckets a
 * model's whole spend, so the recognized families and the conservative
 * text/other fallback are pinned here.
 */
class ModalityClassifierTest {

    @Test
    void chatModelsAreText() {
        assertEquals(ModalityClassifier.TEXT, ModalityClassifier.classify("claude-opus-4-8"));
        assertEquals(ModalityClassifier.TEXT, ModalityClassifier.classify("gemini-2.0-flash-lite"));
        assertEquals(ModalityClassifier.TEXT, ModalityClassifier.classify("gpt-4o-mini"));
        assertEquals(ModalityClassifier.TEXT, ModalityClassifier.classify("llama-3.1-8b-instant"));
    }

    @Test
    void imageModelsAreImage() {
        assertEquals(ModalityClassifier.IMAGE, ModalityClassifier.classify("dall-e-3"));
        assertEquals(ModalityClassifier.IMAGE, ModalityClassifier.classify("imagen-3.0-generate"));
        assertEquals(ModalityClassifier.IMAGE, ModalityClassifier.classify("gpt-image-1"));
    }

    @Test
    void audioModelsAreAudio() {
        assertEquals(ModalityClassifier.AUDIO, ModalityClassifier.classify("whisper-1"));
        assertEquals(ModalityClassifier.AUDIO, ModalityClassifier.classify("gpt-4o-mini-tts"));
        assertEquals(ModalityClassifier.AUDIO, ModalityClassifier.classify("sarvam-saarika-v2"));
        assertEquals(ModalityClassifier.AUDIO, ModalityClassifier.classify("eleven-multilingual-v2"));
    }

    @Test
    void nullOrBlankIsOther() {
        assertEquals(ModalityClassifier.OTHER, ModalityClassifier.classify(null));
        assertEquals(ModalityClassifier.OTHER, ModalityClassifier.classify("   "));
    }

    @Test
    void caseInsensitive() {
        assertEquals(ModalityClassifier.IMAGE, ModalityClassifier.classify("DALL-E-3"));
        assertEquals(ModalityClassifier.AUDIO, ModalityClassifier.classify("WHISPER-LARGE"));
    }
}
