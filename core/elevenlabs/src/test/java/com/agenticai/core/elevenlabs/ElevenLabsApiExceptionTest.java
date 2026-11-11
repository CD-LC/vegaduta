package com.agenticai.core.elevenlabs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the HTTP mapping of this module's exception family, same split as {@code
 * SarvamApiException}'s: upstream call failures surface as 502 and log at WARN; client-actionable
 * setup/validation problems surface as 400 and do not.
 */
class ElevenLabsApiExceptionTest {

    @Test
    void apiExceptionCarriesTheUpstreamStatusAndMapsTo502Warn() {
        ElevenLabsApiException e = new ElevenLabsApiException(429, "rate_limit_exceeded");

        assertThat(e.getUpstreamStatus()).isEqualTo(429);
        assertThat(e.getMessage()).isEqualTo("rate_limit_exceeded");
        assertThat(e.httpStatus()).isEqualTo(502);
        assertThat(e.logAtWarn()).isTrue();
    }

    @Test
    void notConfiguredIsA400WithTheActivationHint() {
        ElevenLabsNotConfiguredException e = new ElevenLabsNotConfiguredException();

        assertThat(e.getMessage()).contains("/api/elevenlabs/credential");
        assertThat(e.httpStatus()).isEqualTo(400);
        assertThat(e.logAtWarn()).isFalse();
    }

    @Test
    void notConfiguredAcceptsACustomMessageForSiblingUseCases() {
        ElevenLabsNotConfiguredException e =
                new ElevenLabsNotConfiguredException("clone a voice first");

        assertThat(e.getMessage()).isEqualTo("clone a voice first");
        assertThat(e.httpStatus()).isEqualTo(400);
    }

    @Test
    void validationFailuresAreA400() {
        ElevenLabsCredentialValidationException e =
                new ElevenLabsCredentialValidationException("key rejected");
        ElevenLabsCredentialValidationException withCause =
                new ElevenLabsCredentialValidationException(
                        "key rejected", new RuntimeException("io"));

        assertThat(e.getMessage()).isEqualTo("key rejected");
        assertThat(e.httpStatus()).isEqualTo(400);
        assertThat(e.logAtWarn()).isFalse();
        assertThat(withCause.getCause()).isNotNull();
        assertThat(withCause.httpStatus()).isEqualTo(400);
    }
}
