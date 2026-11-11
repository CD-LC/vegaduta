package com.agenticai.core.sarvam;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.sarvam.dto.SarvamCredentialResponse;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SarvamCredential}'s single-row lifecycle: activation starts ACTIVE, {@code
 * reactivate} rotates the ciphertext in place (same row, history kept), {@code deactivate} flips
 * the status and touches {@code updatedAt}. Also pins {@link SarvamCredentialResponse#from}'s
 * no-key-in-the-DTO mapping.
 */
class SarvamCredentialTest {

    @Test
    void aNewCredentialStartsActiveWithTimestamps() {
        SarvamCredential c = new SarvamCredential("encrypted-key-1");

        assertThat(c.getEncryptedApiKey()).isEqualTo("encrypted-key-1");
        assertThat(c.getStatus()).isEqualTo(SarvamCredentialStatus.ACTIVE);
        assertThat(c.getCreatedAt()).isNotNull();
        assertThat(c.getUpdatedAt()).isEqualTo(c.getCreatedAt());
    }

    @Test
    void reactivateRotatesTheKeyOnTheSameRow() {
        SarvamCredential c = new SarvamCredential("encrypted-key-1");
        c.deactivate();

        c.reactivate("encrypted-key-2");

        assertThat(c.getEncryptedApiKey()).isEqualTo("encrypted-key-2");
        assertThat(c.getStatus()).isEqualTo(SarvamCredentialStatus.ACTIVE);
        assertThat(c.getUpdatedAt()).isNotNull();
        assertThat(c.getCreatedAt()).isNotNull();
    }

    @Test
    void deactivateMarksTheRowInactiveWithoutRemovingIt() {
        SarvamCredential c = new SarvamCredential("encrypted-key-1");

        c.deactivate();

        assertThat(c.getStatus()).isEqualTo(SarvamCredentialStatus.INACTIVE);
        // The ciphertext stays - deactivation is a status flip, not a delete.
        assertThat(c.getEncryptedApiKey()).isEqualTo("encrypted-key-1");
    }

    @Test
    void credentialResponseMapsStatusAndTimestampsNeverTheKey() {
        SarvamCredential c = new SarvamCredential("encrypted-key-1");

        SarvamCredentialResponse r = SarvamCredentialResponse.from(c);

        assertThat(r.id()).isEqualTo(c.getId());
        assertThat(r.status()).isEqualTo(SarvamCredentialStatus.ACTIVE);
        assertThat(r.createdAt()).isEqualTo(c.getCreatedAt());
        assertThat(r.updatedAt()).isEqualTo(c.getUpdatedAt());
        assertThat(r.getClass().getDeclaredFields())
                .noneMatch(f -> f.getName().toLowerCase().contains("key"));
    }

    @Test
    void credentialStatusHasExactlyTheTwoStates() {
        assertThat(SarvamCredentialStatus.values())
                .containsExactly(SarvamCredentialStatus.ACTIVE, SarvamCredentialStatus.INACTIVE);
    }
}
