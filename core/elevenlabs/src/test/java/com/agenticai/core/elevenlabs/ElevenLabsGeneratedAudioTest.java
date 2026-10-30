package com.agenticai.core.elevenlabs;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.elevenlabs.dto.ElevenLabsGeneratedAudioResponse;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ElevenLabsGeneratedAudio}'s stored-clip shape and {@link
 * ElevenLabsGeneratedAudioResponse#from}'s listing mapping - the {@code downloadUrl} points back at
 * this app's own authenticated endpoint, never at object storage.
 */
class ElevenLabsGeneratedAudioTest {

    @Test
    void createCarriesEveryColumn() {
        ElevenLabsGeneratedAudio a =
                ElevenLabsGeneratedAudio.create(
                        "greeting.mp3", "audio/mpeg", "elevenlabs-audio/abc.mp3", "voice-42");

        assertThat(a.getFilename()).isEqualTo("greeting.mp3");
        assertThat(a.getContentType()).isEqualTo("audio/mpeg");
        assertThat(a.getObjectKey()).isEqualTo("elevenlabs-audio/abc.mp3");
        assertThat(a.getVoiceId()).isEqualTo("voice-42");
        assertThat(a.getCreatedAt()).isNotNull();
        assertThat(a.getId()).isNull(); // assigned on persist
    }

    @Test
    void responseFromBuildsTheAppDownloadUrl() {
        ElevenLabsGeneratedAudio a =
                ElevenLabsGeneratedAudio.create("g.mp3", "audio/mpeg", "k", "voice-1");

        ElevenLabsGeneratedAudioResponse r = ElevenLabsGeneratedAudioResponse.from(a);

        assertThat(r.filename()).isEqualTo("g.mp3");
        assertThat(r.contentType()).isEqualTo("audio/mpeg");
        assertThat(r.voiceId()).isEqualTo("voice-1");
        assertThat(r.downloadUrl()).isEqualTo("/api/elevenlabs/audio/" + a.getId());
    }
}
