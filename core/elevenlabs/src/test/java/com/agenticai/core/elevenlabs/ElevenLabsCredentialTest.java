package com.agenticai.core.elevenlabs;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.elevenlabs.dto.ElevenLabsCredentialActivationRequest;
import com.agenticai.core.elevenlabs.dto.ElevenLabsCredentialResponse;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ElevenLabsCredential}'s single-row lifecycle (mirroring {@code SarvamCredential}):
 * activation starts ACTIVE, {@code reactivate} rotates the ciphertext in place, {@code deactivate}
 * is a status flip. Also pins the response DTO's no-key shape and the activation request's
 * copy-paste whitespace trim.
 */
class ElevenLabsCredentialTest {

    @Test
    void aNewCredentialStartsActiveWithTimestamps() {
        ElevenLabsCredential c = new ElevenLabsCredential("encrypted-key-1");

        assertThat(c.getEncryptedApiKey()).isEqualTo("encrypted-key-1");
        assertThat(c.getStatus()).isEqualTo(ElevenLabsCredentialStatus.ACTIVE);
        assertThat(c.getCreatedAt()).isNotNull();
        assertThat(c.getUpdatedAt()).isEqualTo(c.getCreatedAt());
    }

    @Test
    void reactivateRotatesTheKeyOnTheSameRow() {
        ElevenLabsCredential c = new ElevenLabsCredential("encrypted-key-1");
        c.deactivate();

        c.reactivate("encrypted-key-2");

        assertThat(c.getEncryptedApiKey()).isEqualTo("encrypted-key-2");
        assertThat(c.getStatus()).isEqualTo(ElevenLabsCredentialStatus.ACTIVE);
    }

    @Test
    void deactivateMarksTheRowInactive() {
        ElevenLabsCredential c = new ElevenLabsCredential("encrypted-key-1");

        c.deactivate();

        assertThat(c.getStatus()).isEqualTo(ElevenLabsCredentialStatus.INACTIVE);
        assertThat(c.getEncryptedApiKey()).isEqualTo("encrypted-key-1");
    }

    @Test
    void credentialResponseMapsStatusAndTimestampsNeverTheKey() {
        ElevenLabsCredential c = new ElevenLabsCredential("encrypted-key-1");

        ElevenLabsCredentialResponse r = ElevenLabsCredentialResponse.from(c);

        assertThat(r.id()).isEqualTo(c.getId());
        assertThat(r.status()).isEqualTo(ElevenLabsCredentialStatus.ACTIVE);
        assertThat(r.createdAt()).isEqualTo(c.getCreatedAt());
        assertThat(r.updatedAt()).isEqualTo(c.getUpdatedAt());
        assertThat(r.getClass().getDeclaredFields())
                .noneMatch(f -> f.getName().toLowerCase().contains("key"));
    }

    @Test
    void credentialStatusHasExactlyTheTwoStates() {
        assertThat(ElevenLabsCredentialStatus.values())
                .containsExactly(ElevenLabsCredentialStatus.ACTIVE, ElevenLabsCredentialStatus.INACTIVE);
    }

    @Test
    void activationRequestTrimsTheKeyBeforeValidation() {
        // Pasted keys routinely carry a trailing newline; it must not reach the encryptor.
        assertThat(new ElevenLabsCredentialActivationRequest("  sk-abc123 \n").apiKey())
                .isEqualTo("sk-abc123");
        assertThat(new ElevenLabsCredentialActivationRequest(null).apiKey()).isNull();
    }
}
