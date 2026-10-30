package com.agenticai.core.elevenlabs;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.elevenlabs.dto.GeneratedMusicResponse;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GeneratedMusic}'s stored-track shape (which tenant the bill lands on via {@code
 * billedTo}, every field the compose tool persists) and {@link GeneratedMusicResponse#from}'s
 * listing mapping.
 */
class GeneratedMusicTest {

    private static GeneratedMusic track() {
        return GeneratedMusic.create(
                "jingle.mp3",
                "audio/mpeg",
                "generated-music/abc.mp3",
                "upbeat intro jingle",
                "music_v1",
                "elevenlabs",
                GeneratedMusic.BILLED_TO_TENANT,
                12);
    }

    @Test
    void createCarriesEveryColumn() {
        GeneratedMusic m = track();

        assertThat(m.getFilename()).isEqualTo("jingle.mp3");
        assertThat(m.getContentType()).isEqualTo("audio/mpeg");
        assertThat(m.getObjectKey()).isEqualTo("generated-music/abc.mp3");
        assertThat(m.getPrompt()).isEqualTo("upbeat intro jingle");
        assertThat(m.getModel()).isEqualTo("music_v1");
        assertThat(m.getProvider()).isEqualTo("elevenlabs");
        assertThat(m.getBilledTo()).isEqualTo(GeneratedMusic.BILLED_TO_TENANT);
        assertThat(m.getDurationSeconds()).isEqualTo(12);
        assertThat(m.getCreatedAt()).isNotNull();
        assertThat(m.getId()).isNull(); // assigned on persist
    }

    @Test
    void billedToConstantsAreTheTwoLedgers() {
        // TENANT rows feed nothing quota-wise; PLATFORM rows ARE the platform-spend quota ledger.
        assertThat(GeneratedMusic.BILLED_TO_TENANT).isEqualTo("TENANT");
        assertThat(GeneratedMusic.BILLED_TO_PLATFORM).isEqualTo("PLATFORM");
    }

    @Test
    void responseFromMapsTheListingFields() {
        GeneratedMusicResponse r = GeneratedMusicResponse.from(track());

        assertThat(r.filename()).isEqualTo("jingle.mp3");
        assertThat(r.contentType()).isEqualTo("audio/mpeg");
        assertThat(r.prompt()).isEqualTo("upbeat intro jingle");
        assertThat(r.model()).isEqualTo("music_v1");
        assertThat(r.durationSeconds()).isEqualTo(12);
        assertThat(r.createdAt()).isNotNull();
    }
}
