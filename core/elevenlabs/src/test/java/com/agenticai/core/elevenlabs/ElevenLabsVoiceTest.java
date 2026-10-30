package com.agenticai.core.elevenlabs;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.elevenlabs.dto.ElevenLabsVoiceResponse;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ElevenLabsVoice}'s cloned-voice row (the provider-side {@code voice_id} is the only
 * thing TTS needs - the sample audio is never stored) and {@link ElevenLabsVoiceResponse#from}'s
 * listing mapping.
 */
class ElevenLabsVoiceTest {

    @Test
    void createCarriesTheProviderVoiceIdAndName() {
        ElevenLabsVoice v = ElevenLabsVoice.create("elv-voice-99", "Priya clone");

        assertThat(v.getElevenLabsVoiceId()).isEqualTo("elv-voice-99");
        assertThat(v.getName()).isEqualTo("Priya clone");
        assertThat(v.getCreatedAt()).isNotNull();
        assertThat(v.getId()).isNull(); // assigned on persist
    }

    @Test
    void responseFromMapsEveryField() {
        ElevenLabsVoice v = ElevenLabsVoice.create("elv-voice-99", "Priya clone");

        ElevenLabsVoiceResponse r = ElevenLabsVoiceResponse.from(v);

        assertThat(r.id()).isEqualTo(v.getId());
        assertThat(r.elevenLabsVoiceId()).isEqualTo("elv-voice-99");
        assertThat(r.name()).isEqualTo("Priya clone");
        assertThat(r.createdAt()).isEqualTo(v.getCreatedAt());
    }
}
