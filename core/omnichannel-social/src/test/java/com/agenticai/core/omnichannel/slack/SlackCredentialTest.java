package com.agenticai.core.omnichannel.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The Slack credential lifecycle: exactly one ACTIVE row is allowed per tenant, DISCONNECTED
 * rows stay on file so disconnect remains auditable, and reactivation only accepts the same
 * workspace/team id so a token cannot be pointed at a different workspace silently.
 */
class SlackCredentialTest {

    private static SlackCredential credential() {
        SlackCredential credential = new SlackCredential();
        credential.setStatus(SlackCredentialStatus.ACTIVE);
        return credential;
    }

    @Test
    void defaultsToDisconnected() {
        assertThat(new SlackCredential().getStatus())
                .isEqualTo(SlackCredentialStatus.DISCONNECTED);
    }

    @Test
    void activateReusesTheSameWorkspaceAndBumpsVersionAndTimestamp() throws Exception {
        SlackCredential credential = credential();
        credential.setTeamId("T123");
        credential.setTeamName("Acme");
        credential.setBotUserId("UOLD");
        credential.setBotTokenEnc("old-token");
        credential.setSigningSecretEnc("old-secret");
        credential.setVersion(3L);
        credential.setActivatedAt(Instant.now().minus(Duration.ofDays(30)));

        Thread.sleep(2);
        credential.activate("T123", "Acme", "UNEW", "new-token", "new-secret", "conn-9", "alice");

        assertThat(credential.getTeamId()).isEqualTo("T123");
        assertThat(credential.getBotUserId()).isEqualTo("UNEW");
        assertThat(credential.getBotTokenEnc()).isEqualTo("new-token");
        assertThat(credential.getSigningSecretEnc()).isEqualTo("new-secret");
        assertThat(credential.getConnectedByConnectionId()).isEqualTo("conn-9");
        assertThat(credential.getConnectedByUserId()).isEqualTo("alice");
        assertThat(credential.getVersion()).isEqualTo(4L);
        assertThat(credential.getActivatedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void reactivatingWithADifferentTeamIdIsRejected() {
        SlackCredential credential = credential();
        credential.setTeamId("T123");

        assertThatThrownBy(
                        () -> credential.activate("T999", "Other", "U", "t", "s", "c", "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing credential");
    }

    @Test
    void activateWorksWhenNoTeamIdIsOnFileYet() {
        SlackCredential credential = new SlackCredential();
        credential.activate("T123", "Acme", "UBOT", "t", "s", "c", "u");
        assertThat(credential.getStatus()).isEqualTo(SlackCredentialStatus.ACTIVE);
    }

    @Test
    void disconnectKeepsTheRowWithNewSecretAndActor() throws Exception {
        SlackCredential credential = credential();
        credential.setActivatedAt(Instant.now());
        credential.setVersion(7L);

        Thread.sleep(2);
        credential.disconnect("sec-2", "bob");

        assertThat(credential.getStatus()).isEqualTo(SlackCredentialStatus.DISCONNECTED);
        assertThat(credential.getSigningSecretEnc()).isEqualTo("sec-2");
        assertThat(credential.getDisconnectedByUserId()).isEqualTo("bob");
        assertThat(credential.getDisconnectedAt())
                .isAfter(Instant.now().minusSeconds(5))
                .isNotEqualTo(credential.getActivatedAt());
        assertThat(credential.getVersion()).isEqualTo(8L);
    }

    @Test
    void revokeDisconnectsWithAMoreSpecificStatus() {
        SlackCredential credential = credential();
        credential.setVersion(1L);

        credential.revoke("sec-3", "carol");

        assertThat(credential.getStatus()).isEqualTo(SlackCredentialStatus.REVOKED);
        assertThat(credential.getVersion()).isEqualTo(2L);
    }

    @Test
    void rotateUpdatesTheBotTokenAndVersion() throws Exception {
        SlackCredential credential = credential();
        credential.setBotTokenEnc("token-1");
        credential.setVersion(2L);
        credential.setRotatedAt(Instant.now().minus(Duration.ofDays(90)));

        Thread.sleep(2);
        credential.rotate("token-2");

        assertThat(credential.getBotTokenEnc()).isEqualTo("token-2");
        assertThat(credential.getRotatedAt()).isAfter(Instant.now().minusSeconds(5));
        assertThat(credential.getVersion()).isEqualTo(3L);
    }
}
