package com.agenticai.core.sarvam;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link SarvamGeneratedAudio}'s two storage shapes: the legacy base64-in-column constructor
 * (pre-migration rows) and {@link #forObjectStorage} (every row written since) - exactly one of
 * {@code audioDataBase64}/{@code objectKey} is populated per row.
 */
class SarvamGeneratedAudioTest {

    @Test
    void legacyConstructorStoresBase64AndLeavesObjectKeyNull() {
        SarvamGeneratedAudio a = new SarvamGeneratedAudio("clip.wav", "audio/wav", "QUJD");

        assertThat(a.getFilename()).isEqualTo("clip.wav");
        assertThat(a.getContentType()).isEqualTo("audio/wav");
        assertThat(a.getAudioDataBase64()).isEqualTo("QUJD");
        assertThat(a.getObjectKey()).isNull();
        assertThat(a.getCreatedAt()).isNotNull();
    }

    @Test
    void objectStorageRowsCarryTheKeyAndNoBase64() {
        SarvamGeneratedAudio a =
                SarvamGeneratedAudio.forObjectStorage(
                        "clip.wav", "audio/wav", "sarvam-audio/abc.wav");

        assertThat(a.getFilename()).isEqualTo("clip.wav");
        assertThat(a.getContentType()).isEqualTo("audio/wav");
        assertThat(a.getObjectKey()).isEqualTo("sarvam-audio/abc.wav");
        assertThat(a.getAudioDataBase64()).isNull();
        assertThat(a.getCreatedAt()).isNotNull();
    }

    @Test
    void idIsNullUntilPersisted() {
        // GenerationType.UUID assigns on insert - a detached row has none.
        assertThat(SarvamGeneratedAudio.forObjectStorage("f", "audio/wav", "k").getId()).isNull();
    }
}
