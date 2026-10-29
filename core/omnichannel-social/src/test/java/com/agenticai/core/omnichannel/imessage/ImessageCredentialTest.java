package com.agenticai.core.omnichannel.imessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The iMessage (BlueBubbles) credential lifecycle: reactivation only accepts the same server
 * URL (pointing an existing row at a different bridge would silently move the channel), and
 * disconnect/revoke keep the row for audit.
 */
class ImessageCredentialTest {

    private static ImessageCredential credential() {
        ImessageCredential credential = new ImessageCredential();
        credential.setStatus(ImessageCredentialStatus.ACTIVE);
        return credential;
    }

    @Test
    void defaultsToDisconnected() {
        assertThat(new ImessageCredential().getStatus())
                .isEqualTo(ImessageCredentialStatus.DISCONNECTED);
    }

    @Test
    void activateForTheSameServerUrlRefreshesThePasswordAndActor() {
        ImessageCredential credential = credential();
        credential.setServerUrl("https://bubbles.example.com");
        credential.setPasswordEnc("pw-1");
        credential.setVersion(1L);

        credential.activate("https://bubbles.example.com", "pw-2", "conn-4", "alice");

        assertThat(credential.getStatus()).isEqualTo(ImessageCredentialStatus.ACTIVE);
        assertThat(credential.getPasswordEnc()).isEqualTo("pw-2");
        assertThat(credential.getConnectedByConnectionId()).isEqualTo("conn-4");
        assertThat(credential.getConnectedByUserId()).isEqualTo("alice");
        assertThat(credential.getVersion()).isEqualTo(2L);
        assertThat(credential.getActivatedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void reactivatingWithADifferentServerUrlIsRejected() {
        ImessageCredential credential = credential();
        credential.setServerUrl("https://bubbles.example.com");

        assertThatThrownBy(
                        () ->
                                credential.activate(
                                        "https://other.example.com", "pw", "c", "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing credential");
    }

    @Test
    void disconnectAndRevokeKeepTheRowWithActorAndVersion() throws Exception {
        ImessageCredential credential = credential();
        credential.setActivatedAt(Instant.now());
        credential.setVersion(1L);

        Thread.sleep(2);
        credential.disconnect("bob");

        assertThat(credential.getStatus()).isEqualTo(ImessageCredentialStatus.DISCONNECTED);
        assertThat(credential.getDisconnectedByUserId()).isEqualTo("bob");
        assertThat(credential.getDisconnectedAt()).isNotEqualTo(credential.getActivatedAt());
        assertThat(credential.getVersion()).isEqualTo(2L);

        credential.setStatus(ImessageCredentialStatus.ACTIVE);
        credential.revoke("carol");
        assertThat(credential.getStatus()).isEqualTo(ImessageCredentialStatus.REVOKED);
        assertThat(credential.getVersion()).isEqualTo(3L);
    }
}
