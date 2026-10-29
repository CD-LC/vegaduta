package com.agenticai.core.sarvam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link SarvamCredentialAvailabilityCheckerImpl}'s probe: configured iff the active key
 * resolves; {@link SarvamNotConfiguredException} is the expected "no key" answer, and any other
 * failure also fails closed to "not configured".
 */
class SarvamCredentialAvailabilityCheckerImplTest {

    private final SarvamCredentialService credentialService = mock(SarvamCredentialService.class);
    private final SarvamCredentialAvailabilityCheckerImpl checker =
            new SarvamCredentialAvailabilityCheckerImpl(credentialService);

    @Test
    void resolvableKeyMeansConfigured() {
        when(credentialService.resolveActiveApiKey()).thenReturn("key");

        assertThat(checker.isSarvamConfigured()).isTrue();
    }

    @Test
    void notConfiguredExceptionMeansNotConfigured() {
        when(credentialService.resolveActiveApiKey()).thenThrow(new SarvamNotConfiguredException());

        assertThat(checker.isSarvamConfigured()).isFalse();
    }

    @Test
    void anyOtherFailureAlsoFailsClosed() {
        when(credentialService.resolveActiveApiKey())
                .thenThrow(new RuntimeException("database unreachable"));

        assertThat(checker.isSarvamConfigured()).isFalse();
    }
}
