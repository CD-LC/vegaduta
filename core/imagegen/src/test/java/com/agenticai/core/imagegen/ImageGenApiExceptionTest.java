package com.agenticai.core.imagegen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the module's two exception types: {@link ImageGenApiException} is the upstream-failure 502
 * logged at WARN; {@link ImageGenNotConfiguredException} is the client-actionable 400 that is not.
 */
class ImageGenApiExceptionTest {

    @Test
    void apiExceptionMapsTo502AndWarns() {
        ImageGenApiException e = new ImageGenApiException("provider error");

        assertThat(e.getMessage()).isEqualTo("provider error");
        assertThat(e.httpStatus()).isEqualTo(502);
        assertThat(e.logAtWarn()).isTrue();
    }

    @Test
    void apiExceptionCarriesACause() {
        RuntimeException cause = new RuntimeException("io");
        ImageGenApiException e = new ImageGenApiException("provider error", cause);

        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void notConfiguredMapsTo400WithoutAWarn() {
        ImageGenNotConfiguredException e =
                new ImageGenNotConfiguredException("no OPENAI credential");

        assertThat(e.getMessage()).isEqualTo("no OPENAI credential");
        assertThat(e.httpStatus()).isEqualTo(400);
        assertThat(e.logAtWarn()).isFalse();
    }
}
