package com.agenticai.core.omnichannel.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The Teams credential lifecycle: reactivation only accepts the same Entra app + tenant pair
 * (otherwise the channel's token endpoint would change under an existing row), disconnect keeps
 * the row for audit, and version/rotation timestamps feed the optimistic-lock + 90-day-rotation
 * flows.
 */
class TeamsCredentialTest {

    private static TeamsCredential credential() {
        TeamsCredential credential = new TeamsCredential();
        credential.setStatus(TeamsCredentialStatus.ACTIVE);
        return credential;
    }

    @Test
    void defaultsToDisconnected() {
        assertThat(new TeamsCredential().getStatus())
                .isEqualTo(TeamsCredentialStatus.DISCONNECTED);
    }

    @Test
    void activateSetsFieldsBumpsVersionAndTimestamps() {
        TeamsCredential credential = credential();
        credential.setEntraAppId("app-1");
        credential.setEntraTenantId("tenant-1");
        credential.setVersion(3L);

        credential.activate("app-1", "secret-enc", "tenant-1", "conn-2", "alice");

        assertThat(credential.getStatus()).isEqualTo(TeamsCredentialStatus.ACTIVE);
        assertThat(credential.getEntraAppPasswordEnc()).isEqualTo("secret-enc");
        assertThat(credential.getConnectedByConnectionId()).isEqualTo("conn-2");
        assertThat(credential.getConnectedByUserId()).isEqualTo("alice");
        assertThat(credential.getVersion()).isEqualTo(4L);
        assertThat(credential.getActivatedAt()).isAfter(Instant.now().minusSeconds(5));
        assertThat(credential.getRotatedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void reactivatingWithADifferentAppOrTenantIsRejected() {
        TeamsCredential credential = credential();
        credential.setEntraAppId("app-1");
        credential.setEntraTenantId("tenant-1");

        assertThatThrownBy(() -> credential.activate("app-2", "s", "tenant-1", "c", "u"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credential.activate("app-1", "s", "tenant-2", "c", "u"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activateAcceptsNullAppAndTenantOnFile() {
        TeamsCredential credential = new TeamsCredential();
        credential.activate("app-1", "s", "tenant-1", "c", "u");
        assertThat(credential.getStatus()).isEqualTo(TeamsCredentialStatus.ACTIVE);
    }

    @Test
    void disconnectAndRevokeKeepTheRowWithActorAndVersion() throws Exception {
        TeamsCredential credential = credential();
        credential.setActivatedAt(Instant.now());
        credential.setVersion(1L);

        Thread.sleep(2);
        credential.disconnect("bob");

        assertThat(credential.getStatus()).isEqualTo(TeamsCredentialStatus.DISCONNECTED);
        assertThat(credential.getDisconnectedByUserId()).isEqualTo("bob");
        assertThat(credential.getDisconnectedAt()).isNotEqualTo(credential.getActivatedAt());
        assertThat(credential.getVersion()).isEqualTo(2L);

        credential.setStatus(TeamsCredentialStatus.ACTIVE);
        credential.revoke("carol");
        assertThat(credential.getStatus()).isEqualTo(TeamsCredentialStatus.REVOKED);
        assertThat(credential.getVersion()).isEqualTo(3L);
    }

    @Test
    void rotateBumpsOnlyTheVersionAndTimestamp() throws Exception {
        TeamsCredential credential = credential();
        credential.setVersion(5L);
        credential.setRotatedAt(Instant.now().minus(Duration.ofDays(90)));

        Thread.sleep(2);
        credential.rotate();

        assertThat(credential.getRotatedAt()).isAfter(Instant.now().minusSeconds(5));
        assertThat(credential.getVersion()).isEqualTo(6L);
        assertThat(credential.getStatus()).isEqualTo(TeamsCredentialStatus.ACTIVE);
    }
}
