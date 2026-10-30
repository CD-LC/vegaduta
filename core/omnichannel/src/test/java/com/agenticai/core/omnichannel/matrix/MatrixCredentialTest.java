package com.agenticai.core.omnichannel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** {@link MatrixCredential}: lifecycle of one tenant's bot connection row. */
class MatrixCredentialTest {

    private MatrixCredential credential() {
        return new MatrixCredential(
                "https://matrix.example.org",
                "@bot:example.org",
                "device-1",
                "enc-token",
                MatrixJoinPolicy.ALLOWLIST,
                MatrixSenderMode.PAIRING,
                "@alice:example.org");
    }

    @Test
    void constructorStartsActiveAndStampsTimestamps() {
        MatrixCredential c = credential();
        assertNull(c.getId());
        assertEquals("https://matrix.example.org", c.getHomeserverUrl());
        assertEquals("@bot:example.org", c.getBotUserId());
        assertEquals("device-1", c.getDeviceId());
        assertEquals("enc-token", c.getEncryptedAccessToken());
        assertEquals(MatrixCredentialStatus.ACTIVE, c.getStatus());
        assertEquals(MatrixJoinPolicy.ALLOWLIST, c.getJoinPolicy());
        assertEquals(MatrixSenderMode.PAIRING, c.getSenderMode());
        assertEquals("@alice:example.org", c.getAllowedSenders());
        assertNull(c.getSinceToken());
        assertNotNull(c.getCreatedAt());
        assertNotNull(c.getUpdatedAt());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void reconnectDropsTheSyncCursorAndMarksTheRowActive() {
        MatrixCredential c = credential();
        c.updateSinceToken("s123_4");
        c.markInvalid();

        c.reconnect(
                "https://hs2.example.org",
                "@bot2:example.org",
                "device-2",
                "enc-2",
                MatrixJoinPolicy.OFF,
                MatrixSenderMode.ALLOWLIST,
                null);

        assertEquals("https://hs2.example.org", c.getHomeserverUrl());
        assertEquals("@bot2:example.org", c.getBotUserId());
        assertEquals(MatrixCredentialStatus.ACTIVE, c.getStatus());
        // The since token belongs to the old account and must never carry over.
        assertNull(c.getSinceToken());
        // Null allow-list is normalized to the empty string, never persisted as null.
        assertEquals("", c.getAllowedSenders());
    }

    @Test
    void updatePolicyReplacesRoutingFieldsWithoutTouchingTheConnection() {
        MatrixCredential c = credential();

        c.updatePolicy(MatrixJoinPolicy.OFF, MatrixSenderMode.ALLOWLIST, null);

        assertEquals(MatrixJoinPolicy.OFF, c.getJoinPolicy());
        assertEquals(MatrixSenderMode.ALLOWLIST, c.getSenderMode());
        assertEquals("", c.getAllowedSenders());
        assertEquals("https://matrix.example.org", c.getHomeserverUrl());
    }

    @Test
    void updateSinceTokenStoresTheLatestSyncCursor() {
        MatrixCredential c = credential();
        c.updateSinceToken("batch_42");
        assertEquals("batch_42", c.getSinceToken());
    }

    @Test
    void markInvalidStopsUseUntilReconnect() {
        MatrixCredential c = credential();
        c.markInvalid();
        assertEquals(MatrixCredentialStatus.INVALID, c.getStatus());
    }

    @Test
    void disconnectKeepsTheRowButStopsUse() {
        MatrixCredential c = credential();
        c.disconnect();
        assertEquals(MatrixCredentialStatus.DISCONNECTED, c.getStatus());
    }
}
