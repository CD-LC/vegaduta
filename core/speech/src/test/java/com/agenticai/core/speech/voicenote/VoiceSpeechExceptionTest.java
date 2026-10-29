package com.agenticai.core.speech.voicenote;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Contract coverage for {@link VoiceSpeechException} (the typed failure the omnichannel voice-note
 * caller switches on) and {@link VoiceSpeechGateway.Transcript} (the record STT results are handed
 * back as).
 */
class VoiceSpeechExceptionTest {

    @Test
    void failureIsCarriedThroughBothConstructors() {
        VoiceSpeechException notConfigured =
                new VoiceSpeechException(VoiceSpeechException.Failure.NOT_CONFIGURED, "no key");
        assertThat(notConfigured.getFailure())
                .isEqualTo(VoiceSpeechException.Failure.NOT_CONFIGURED);
        assertThat(notConfigured.getMessage()).isEqualTo("no key");
        assertThat(notConfigured.getCause()).isNull();

        RuntimeException upstream = new RuntimeException("boom");
        VoiceSpeechException failed =
                new VoiceSpeechException(
                        VoiceSpeechException.Failure.UPSTREAM_FAILED, "bad call", upstream);
        assertThat(failed.getFailure()).isEqualTo(VoiceSpeechException.Failure.UPSTREAM_FAILED);
        assertThat(failed.getMessage()).isEqualTo("bad call");
        assertThat(failed.getCause()).isSameAs(upstream);
    }

    @Test
    void failureEnumHasExactlyTheTwoMeaningsCallersActOn() {
        assertThat(VoiceSpeechException.Failure.values())
                .containsExactly(
                        VoiceSpeechException.Failure.NOT_CONFIGURED,
                        VoiceSpeechException.Failure.UPSTREAM_FAILED);
    }

    @Test
    void transcriptRecordCarriesTextAndDetectedLanguage() {
        VoiceSpeechGateway.Transcript t = new VoiceSpeechGateway.Transcript("namaste", "hi-IN");
        assertThat(t.text()).isEqualTo("namaste");
        assertThat(t.languageCode()).isEqualTo("hi-IN");
        assertThat(t).isEqualTo(new VoiceSpeechGateway.Transcript("namaste", "hi-IN"));
        assertThat(t).isNotEqualTo(new VoiceSpeechGateway.Transcript("namaste", "mr-IN"));
    }
}
