package com.agenticai.core.sarvam;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the HTTP mapping of this module's exception family: upstream call failures surface as 502
 * and log at WARN; client-actionable setup/validation problems surface as 400 and do not.
 */
class SarvamApiExceptionTest {

    @Test
    void apiExceptionCarriesTheUpstreamStatusAndMapsTo502Warn() {
        // Sarvam reports auth failures as 403 (not 401 like other providers) - the status is
        // preserved verbatim, not translated.
        SarvamApiException e = new SarvamApiException(403, "invalid_api_key_error");

        assertThat(e.getUpstreamStatus()).isEqualTo(403);
        assertThat(e.getMessage()).isEqualTo("invalid_api_key_error");
        assertThat(e.httpStatus()).isEqualTo(502);
        assertThat(e.logAtWarn()).isTrue();
    }

    @Test
    void notConfiguredIsA400WithTheActivationHint() {
        SarvamNotConfiguredException e = new SarvamNotConfiguredException();

        assertThat(e.getMessage()).contains("/api/sarvam/credential");
        assertThat(e.httpStatus()).isEqualTo(400);
        assertThat(e.logAtWarn()).isFalse();
    }

    @Test
    void validationFailuresAreA400() {
        SarvamCredentialValidationException e = new SarvamCredentialValidationException("bad key");
        SarvamCredentialValidationException withCause =
                new SarvamCredentialValidationException("bad key", new RuntimeException("io"));

        assertThat(e.getMessage()).isEqualTo("bad key");
        assertThat(e.httpStatus()).isEqualTo(400);
        assertThat(e.logAtWarn()).isFalse();
        assertThat(withCause.getCause()).isNotNull();
        assertThat(withCause.httpStatus()).isEqualTo(400);
    }
}
