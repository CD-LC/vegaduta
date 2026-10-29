package com.agenticai.core.speech.call;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Contract coverage for the {@code call} package's two exception types: {@link
 * CallSpeechQuotaExceededException} (the shared, never-swallowed voice-budget signal) and {@link
 * CallSpeechTranscriber.NotConfiguredException} (the distinct "no credential" transcription
 * outcome).
 */
class CallSpeechQuotaExceededExceptionTest {

    @Test
    void quotaExceptionCarriesThePersonFacingMessageAndCause() {
        RuntimeException cause = new RuntimeException("upstream");
        CallSpeechQuotaExceededException e =
                new CallSpeechQuotaExceededException("Daily voice limit reached. Upgrade for more.", cause);

        assertThat(e.getMessage()).isEqualTo("Daily voice limit reached. Upgrade for more.");
        assertThat(e.getCause()).isSameAs(cause);
        assertThat(e).isInstanceOf(RuntimeException.class);
    }

    @Test
    void notConfiguredExceptionIsADistinctRuntimeType() {
        RuntimeException cause = new RuntimeException("no row");
        CallSpeechTranscriber.NotConfiguredException e =
                new CallSpeechTranscriber.NotConfiguredException("No Sarvam credential", cause);

        assertThat(e.getMessage()).isEqualTo("No Sarvam credential");
        assertThat(e.getCause()).isSameAs(cause);
        // Deliberately NOT a CallSpeechQuotaExceededException - callers must tell setup gaps
        // apart from budget exhaustion.
        assertThat(e).isNotInstanceOf(CallSpeechQuotaExceededException.class);
    }
}
