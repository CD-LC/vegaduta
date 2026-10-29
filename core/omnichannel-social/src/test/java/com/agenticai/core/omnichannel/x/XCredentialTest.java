package com.agenticai.core.omnichannel.x;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The X credential lifecycle: a registered Developer App before OAuth consent is
 * APP_REGISTERED, completing OAuth records the account handle and rotates the refresh token,
 * disconnect wipes the grant but keeps the app registration, and grant/refresh failures mark
 * DISCONNECTED/REVOKED respectively.
 */
class XCredentialTest {

    private static XCredential active() {
        XCredential credential = new XCredential();
        credential.setStatus(XCredentialStatus.ACTIVE);
        credential.setXUserId("user-1");
        return credential;
    }

    @Test
    void defaultsToAppRegistered() {
        assertThat(new XCredential().getStatus()).isEqualTo(XCredentialStatus.APP_REGISTERED);
    }

    @Test
    void markAppRegisteredStoresTheClientCredentials() {
        XCredential credential = new XCredential();

        credential.markAppRegistered("client-id", "client-secret-enc");

        assertThat(credential.getStatus()).isEqualTo(XCredentialStatus.APP_REGISTERED);
        assertThat(credential.getXClientId()).isEqualTo("client-id");
        assertThat(credential.getXClientSecretEnc()).isEqualTo("client-secret-enc");
        assertThat(credential.getRegisteredAt()).isAfter(Instant.now().minusSeconds(5));
        assertThat(credential.getConnectedByConnectionId()).isNull();
    }

    @Test
    void activateForTheSameXUserStoresTheGrant() {
        XCredential credential = active();
        credential.setVersion(1L);
        UUID pkce = UUID.randomUUID();

        credential.activate("user-1", "acme", "access-enc", "refresh-enc", pkce, "alice");

        assertThat(credential.getStatus()).isEqualTo(XCredentialStatus.ACTIVE);
        assertThat(credential.getXUsername()).isEqualTo("acme");
        assertThat(credential.getXAccessTokenEnc()).isEqualTo("access-enc");
        assertThat(credential.getXRefreshTokenEnc()).isEqualTo("refresh-enc");
        assertThat(credential.getConnectedByConnectionId()).isEqualTo(pkce);
        assertThat(credential.getVersion()).isEqualTo(2L);
        assertThat(credential.getActivatedAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void reactivatingForADifferentXUserIsRejected() {
        XCredential credential = active();

        assertThatThrownBy(() -> credential.activate("user-2", "other", "a", "r", null, "u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing credential");
    }

    @Test
    void disconnectAndRevokeWipeTheGrantButKeepTheApp() {
        XCredential credential = active();
        credential.setXClientId("client-id");
        credential.setXUsername("acme");
        credential.setXAccessTokenEnc("a");
        credential.setXRefreshTokenEnc("r");
        credential.setVersion(3L);

        credential.disconnect("bob");

        assertThat(credential.getStatus()).isEqualTo(XCredentialStatus.APP_REGISTERED);
        assertThat(credential.getXClientId()).isEqualTo("client-id");
        assertThat(credential.getXUserId()).isNull();
        assertThat(credential.getXUsername()).isNull();
        assertThat(credential.getXAccessTokenEnc()).isNull();
        assertThat(credential.getXRefreshTokenEnc()).isNull();
        assertThat(credential.getDisconnectedByUserId()).isEqualTo("bob");
        assertThat(credential.getDisconnectedAt()).isNotNull();
        assertThat(credential.getVersion()).isEqualTo(4L);

        credential.setStatus(XCredentialStatus.ACTIVE);
        credential.revoke("carol");
        assertThat(credential.getStatus()).isEqualTo(XCredentialStatus.REVOKED);
    }

    @Test
    void grantFailuresDisconnectWhileRefreshFailuresRevoke() {
        XCredential grant = active();
        grant.markGrantFailure();
        assertThat(grant.getStatus()).isEqualTo(XCredentialStatus.DISCONNECTED);

        XCredential refresh = active();
        refresh.markRefreshFailure();
        assertThat(refresh.getStatus()).isEqualTo(XCredentialStatus.REVOKED);
    }

    @Test
    void markRefreshedRotatesTheRefreshTokenAndTimestamp() {
        XCredential credential = active();
        credential.setXAccessTokenEnc("old-a");
        credential.setXRefreshTokenEnc("old-r");
        credential.setVersion(1L);

        credential.markRefreshed("new-a", "new-r");

        assertThat(credential.getXAccessTokenEnc()).isEqualTo("new-a");
        assertThat(credential.getXRefreshTokenEnc()).isEqualTo("new-r");
        assertThat(credential.getRotatedAt()).isAfter(Instant.now().minusSeconds(5));
        assertThat(credential.getVersion()).isEqualTo(2L);
    }
}
