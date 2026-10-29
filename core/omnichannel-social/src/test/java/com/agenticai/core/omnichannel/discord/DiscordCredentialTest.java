package com.agenticai.core.omnichannel.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The Discord credential lifecycle: reactivation only accepts the same application id, the
 * username shown on the Channels card is refreshed on every activation, and disconnect/revoke
 * keep the row so the audit trail survives.
 */
class DiscordCredentialTest {

    private static DiscordCredential credential() {
        DiscordCredential credential = new DiscordCredential();
        credential.setStatus(DiscordCredentialStatus.ACTIVE);
        return credential;
    }

    @Test
    void defaultsToDisconnected() {
        assertThat(new DiscordCredential().getStatus())
                .isEqualTo(DiscordCredentialStatus.DISCONNECTED);
    }

    @Test
    void activateForTheSameApplicationRefreshesTokenAndUsername() {
        DiscordCredential credential = credential();
        credential.setApplicationId("app-1");
        credential.setBotUsername("old-name");
        credential.setBotTokenEnc("old-token");
        credential.setVersion(2L);

        credential.activate("app-1", "new-name", "new-token", "conn-3", "alice");

        assertThat(credential.getStatus()).isEqualTo(DiscordCredentialStatus.ACTIVE);
        assertThat(credential.getBotUsername()).isEqualTo("new-name");
        assertThat(credential.getBotTokenEnc()).isEqualTo("new-token");
        assertThat(credential.getConnectedByConnectionId()).isEqualTo("conn-3");
        assertThat(credential.getVersion()).isEqualTo(3L);
        assertThat(credential.getActivatedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void reactivatingWithADifferentApplicationIsRejected() {
        DiscordCredential credential = credential();
        credential.setApplicationId("app-1");

        assertThatThrownBy(() -> credential.activate("app-2", "n", "t", "c", "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing credential");
    }

    @Test
    void disconnectAndRevokeRecordActorAndBumpVersion() throws Exception {
        DiscordCredential credential = credential();
        credential.setActivatedAt(Instant.now());
        credential.setVersion(1L);

        Thread.sleep(2);
        credential.disconnect("bob");

        assertThat(credential.getStatus()).isEqualTo(DiscordCredentialStatus.DISCONNECTED);
        assertThat(credential.getDisconnectedByUserId()).isEqualTo("bob");
        assertThat(credential.getDisconnectedAt()).isNotEqualTo(credential.getActivatedAt());
        assertThat(credential.getVersion()).isEqualTo(2L);

        credential.setStatus(DiscordCredentialStatus.ACTIVE);
        credential.revoke("carol");
        assertThat(credential.getStatus()).isEqualTo(DiscordCredentialStatus.REVOKED);
        assertThat(credential.getVersion()).isEqualTo(3L);
    }

    @Test
    void rotateUpdatesTheTokenAndVersion() throws Exception {
        DiscordCredential credential = credential();
        credential.setBotTokenEnc("token-1");
        credential.setVersion(4L);
        credential.setRotatedAt(Instant.now().minus(Duration.ofDays(90)));

        Thread.sleep(2);
        credential.rotate("token-2");

        assertThat(credential.getBotTokenEnc()).isEqualTo("token-2");
        assertThat(credential.getRotatedAt()).isAfter(Instant.now().minusSeconds(5));
        assertThat(credential.getVersion()).isEqualTo(5L);
    }
}
